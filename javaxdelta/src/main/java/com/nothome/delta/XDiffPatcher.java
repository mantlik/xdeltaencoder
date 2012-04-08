/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.nothome.delta;

import com.nothome.delta.PatchException;
import com.nothome.delta.SeekableSource;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 *
 * @author fm
 */
public class XDiffPatcher {

    private ByteBuffer buf = ByteBuffer.allocate(1024);
    private byte buf2[] = buf.array();

    /**
     * Patches to an output stream.
     */
    public void patch(SeekableSource source, InputStream patch, OutputStream out) throws IOException {

        DataOutputStream outOS = new DataOutputStream(out);
        DataInputStream patchIS = new DataInputStream(patch);

        // the magic string is 'd1 ff d1 ff' + the version number
        if (patchIS.readUnsignedByte() != 0xd1
                || patchIS.readUnsignedByte() != 0xff
                || patchIS.readUnsignedByte() != 0xd1
                || patchIS.readUnsignedByte() != 0xff
                || patchIS.readUnsignedByte() != 0x06) {

            throw new PatchException("magic string not found, aborting!");
        }
        long instrLen = patchIS.readLong();
        long dataLen = patchIS.readLong();
        int addrSize = patchIS.readByte();
        long[] addrLen = new long[addrSize];
        for (int i = 0; i < addrSize; i++) {
            addrLen[i] = patchIS.readLong();
        }
        File instrFile = new File("instr.tmp");
        copyToFile(patchIS, instrLen, instrFile);
        DataInputStream instr = new DataInputStream(new BufferedInputStream(new FileInputStream(instrFile)));
        File dataFile = new File("data.tmp");
        copyToFile(patchIS, dataLen, dataFile);
        DataInputStream data = new DataInputStream(new BufferedInputStream(new FileInputStream(dataFile)));
        File[] addrFile = new File[addrSize];
        DataInputStream[] addr = new DataInputStream[addrSize];
        long[] addrPos = new long[addrSize];
        for (int i = 0; i < addrSize; i++) {
            addrFile[i] = new File("addr" + i + ".tmp");
            copyToFile(patchIS, addrLen[i], addrFile[i]);
            addr[i] = new DataInputStream(new BufferedInputStream(new FileInputStream(addrFile[i])));
        }
        patchIS.close();
        boolean copying = true;
        int ii = 0;
        while (ii >= 0) {
            ii = instr.read();
            if (ii < 0) {
                break;
            }
            if (ii == 0) {  // invert state
                copying = !copying;
                continue;
            }
            if (copying) { // copy instructions
                int instructions = ii;
                if (ii >= 0x80) {
                    instructions = (ii - 0x80) * 256 + instr.read();
                }
                while (instructions > 0) {
                    // process instruction
                    int jj = instr.read();
                    int pool = (jj & 0xc0) >> 6;
                    jj = jj & 0x3f;
                    int len = 0;
                    if ((jj & 0x20) == 0) {
                        len = jj;
                    } else if ((jj & 0x10) == 0) {
                        len = (jj & 0x0f) * 256 + instr.read();
                    } else {
                        len = instr.readInt();
                    }
                    //process address
                    jj = addr[pool].read();
                    long offset = 0;
                    if (jj < 0x80) { // byte
                        int sign = 1;
                        if ((jj & 0x40) != 0) {
                            sign = -1;
                        }
                        offset = (jj & 0x3f) * sign;
                    } else {
                        jj = jj & 0x7f;
                        if ((jj & 0x40) == 0) {  // short
                            int sign = 1;
                            if ((jj & 0x20) != 0) {
                                sign = -1;
                            }
                            offset = sign * ((jj & 0x1f) * 256 + addr[pool].read());
                        } else if ((jj & 0x20) == 0) { // integer
                            long sign = 1;
                            if ((jj & 0x10) != 0) {
                                sign = -1;
                            }
                            long d = jj & 0x0f;
                            d = ((d * 256) + addr[pool].read()) * 256; 
                            d = (d + addr[pool].read()) * 256;
                            offset = (d + addr[pool].read()) * sign;
                        } else { // long
                            offset = addr[pool].readLong();
                        }
                    }
                    addrPos[pool] += offset;
                    copy(addrPos[pool], len, source, out);
                    instructions--;
                }
            } else {  // data instruction
                if (ii < 0x80) {
                    append(ii, data, out);
                } else {
                    int len = (ii - 0x80) * 256 + instr.read();
                    append(len, data, out);
                }
            }
            copying = !copying;
        }
        instr.close();
        instrFile.delete();
        data.close();
        dataFile.delete();
        for (int i = 0; i < addrSize; i++) {
            addr[i].close();
            addrFile[i].delete();
        }
        source.close();
        out.close();
    }

    private void copy(long offset, int length, SeekableSource source, OutputStream output)
            throws IOException {
        source.seek(offset);
        while (length > 0) {
            int len = Math.min(buf.capacity(), length);
            buf.clear().limit(len);
            int res = source.read(buf);
            if (res == -1) {
                throw new EOFException("in copy " + offset + " " + length);
            }
            output.write(buf.array(), 0, res);
            length -= res;
        }
    }

    private void append(int length, InputStream patch, OutputStream output) throws IOException {
        while (length > 0) {
            int len = Math.min(buf2.length, length);
            int res = patch.read(buf2, 0, len);
            if (res == -1) {
                throw new EOFException("cannot read " + length);
            }
            output.write(buf2, 0, res);
            length -= res;
        }
    }

    void copyToFile(InputStream is, long len, File file) throws FileNotFoundException, IOException {
        OutputStream os = new BufferedOutputStream(new FileOutputStream(file));
        for (long l = 0; l < len; l++) {
            int b = is.read();
            if (b < 0) {
                break;
            }
            os.write(b);
        }
        os.close();
    }
}
