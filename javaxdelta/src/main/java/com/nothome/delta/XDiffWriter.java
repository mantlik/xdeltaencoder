/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.nothome.delta;

import com.nothome.delta.DiffWriter;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;

/**
 *
 * @author fm
 */
public class XDiffWriter implements DiffWriter {

    private static final int ADDRPOOLS = 4;
    private static final int MAXDATA = 0x7fff;   // maximum amount to pass in one instruction
    private static final int MAXCOPY = 0x7fff;

    private class Copyop {

        long offset;
        int length;

        Copyop(long offset, int length) {
            this.offset = offset;
            this.length = length;
        }
    }
    private DataOutputStream output;
    private DataOutputStream instr, data;
    private DataOutputStream[] addr = new DataOutputStream[ADDRPOOLS];
    private long[] addrPos = new long[ADDRPOOLS];
    private long newdata = 0;
    private ArrayList<Copyop> copyops = new ArrayList<Copyop>();
    public long instrLen = 0;
    public long dataLen = 0;
    public long[] addrlen = new long[ADDRPOOLS];

    public XDiffWriter(DataOutputStream os) throws FileNotFoundException {
        output = os;
        instr = new DataOutputStream(new BufferedOutputStream(new FileOutputStream("instr.tmp")));
        data = new DataOutputStream(new BufferedOutputStream(new FileOutputStream("data.tmp")));
        for (int i = 0; i < addr.length; i++) {
            addr[i] = new DataOutputStream(new BufferedOutputStream(new FileOutputStream("addr" + i + ".tmp")));
        }
    }

    public void addCopy(long offset, int length) throws IOException {
        if (newdata > 0) {    // write data
            write_dataops();
        }
        if (copyops.size() >= MAXCOPY) {
            write_copyops();
            instr.write(0);  // next instr is copy again
        }
        copyops.add(new Copyop(offset, length));
    }

    public void addData(byte b) throws IOException {
        if (copyops.size() > 0) {     // write copy ops
            write_copyops();
        }
        data.write(b);
        newdata++;
    }

    private void write_copyops() throws IOException {
        if (copyops.isEmpty()) {
            return;
        }
        // write no. of instructions to copy
        if (copyops.size() < 0x80) {
            instr.write(copyops.size());
        } else {
            int ss = copyops.size() | 0x8000;
            instr.writeShort(ss);
        }
        // write operations
        Iterator<Copyop> iterator = copyops.iterator();
        while (iterator.hasNext()) {
            Copyop op = iterator.next();
            int pool = -1;
            long diff = Long.MAX_VALUE;
            for (int i = 0; i < addrPos.length; i++) {
                long d = op.offset - addrPos[i];
                if (Math.abs(d) < Math.abs(diff)) {
                    diff = d;
                    pool = i;
                }
            }
            // write pool & legth
            if (op.length < 0x20) {    // 1 byte op
                int b = op.length | (pool << 6);
                instr.write(b);
            } else if (op.length < 0x1000) { // 2 byte op
                int s = op.length | 0x2000 | (pool << 14);
                instr.writeShort(s);
            } else {    // int+1 op
                byte b = (byte) (0x30 | (pool << 6));
                instr.write(b);
                instr.writeInt(op.length);
            }
            // write offset
            long abs = Math.abs(diff);
            boolean negative = diff < 0;
            long dd = abs;
            if (abs < 0x40) { // byte diff
                if (negative) {
                    dd = dd | 0x40;
                }
                addr[pool].write((int)dd);
            } else if (abs < 0x2000) { // short diff
                if (negative) {
                    dd = dd | 0x2000;
                }
                addr[pool].writeShort((int)(dd | 0x8000));
            } else if (abs < 0x10000000) { // int diff
                if (negative) {
                    dd = dd | 0x10000000;
                }
                addr[pool].writeInt((int)(dd | 0xc0000000));
            } else {
                addr[pool].write(0xe0);
                addr[pool].writeLong(diff);
            }
            addrPos[pool] = op.offset;
        }
        copyops.clear();
    }

    private void write_dataops() throws IOException {
        if (instr.size() == 0) {
            instr.write(0);     // first instruction is data
        }
        while (newdata > 0) {
            if (newdata < 0x80) {
                instr.write((int) newdata);
                newdata = 0;
                break;
            }
            long cc = newdata;
            if (cc > MAXDATA) {
                cc = MAXDATA;
            }
            int dd = (((int) cc) | 0x8000);
            instr.writeShort(dd);
            newdata -= cc;
            if (newdata > 0) {
                instr.write(0); // next instruction is data again
            }
        }
    }

    public void flush() throws IOException {
        if (copyops.size() > 0) {
            write_copyops();
        }
        if (newdata > 0) {
            write_dataops();
        }
        instr.flush();
        data.flush();
        for (int i = 0; i < addr.length; i++) {
            addr[i].flush();
        }
        return;
    }

    public void close() throws IOException {
        flush();
        instrLen = instr.size();
        instr.close();
        dataLen = data.size();
        data.close();
        for (int i = 0; i < addr.length; i++) {
            addrlen[i] = addr[i].size();
            addr[i].close();
        }
        output.writeByte(0xd1); //write magic string
        output.writeByte(0xff);
        output.writeByte(0xd1);
        output.writeByte(0xff);
        output.writeByte(0x06); // extension for XDiffWriter

        // write block lengths
        output.writeLong(instrLen);
        output.writeLong(dataLen);
        output.writeByte(ADDRPOOLS);        // no. of address pools
        for (int i = 0; i < addr.length; i++) {
            output.writeLong(addrlen[i]);
        }
        // write instructions
        InputStream is = new BufferedInputStream(new FileInputStream("instr.tmp"));
        copydata(is);
        new File("instr.tmp").delete();
        // write data
        is = new BufferedInputStream(new FileInputStream("data.tmp"));
        copydata(is);
        new File("data.tmp").delete();
        // write addr pools
        for (int i = 0; i < addr.length; i++) {
            is = new BufferedInputStream(new FileInputStream("addr" + i + ".tmp"));
            copydata(is);
            new File("addr" + i + ".tmp").delete();
        }
        output.close();
    }

    private void copydata(InputStream is) throws IOException {
        int b = 0;
        while (b >= 0) {
            b = is.read();
            if (b >= 0) {
                output.writeByte(b);
            }
        }
        is.close();
    }
}
