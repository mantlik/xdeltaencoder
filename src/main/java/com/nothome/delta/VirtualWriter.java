/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.nothome.delta;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 *
 * @author fm simple instructions writer Instructions: byte 1=copy_sourcedata
 * long sourceoffset int sourcelength byte 2=add_targetdata int targetlength
 * byte 3=end
 */
public class VirtualWriter implements DiffWriter {

    private DataOutputStream output = null;
    private long dataLength;
    public int filterFactor = 0;
    public long filteredData = 0;
    public long totalLength = 0;
    private SeekableSource source;
    private InputStream target;
    private boolean debugMode = false;
    private ByteBuffer buffer;
    private long position = 0;

    public VirtualWriter(DataOutputStream os) {
        this.output = os;
        dataLength = 0;
    }

    public VirtualWriter(DataOutputStream os, SeekableSource source, InputStream target) {
        this(os);
        debugMode = true;
        buffer = ByteBuffer.allocate(1024 * 1024);
        this.source = source;
        this.target = target;
    }

    @Override
    public void addCopy(long offset, int length) throws IOException {
        if (length == 0) {
            return;
        }
        if (debugMode) {
            source.seek(offset);
            int len = length;
            while (len > 0) {
                buffer.clear();
                if (len < buffer.capacity()) {
                    buffer.limit(len);
                }
                int r = source.read(buffer);
                if (r < 0) {
                    throw new IOException("Cannot read " + length + " bytes at offset " + offset);
                }
                len -= r;
                buffer.flip();
                while (buffer.hasRemaining()) {
                    position++;
                    byte s1 = buffer.get();
                    byte s2 = (byte)target.read();
                    if (s1 != s2) {
                        throw new IOException("Copy mismatch " + s1 + " != " + s2 + " at position " + position);
                    }
                }
            }
        }
        totalLength += length;
        if (length <= filterFactor) {
            dataLength += length;
            filteredData += length;
            return;
        }
        writeData();
        output.writeByte(1);
        output.writeLong(offset);
        output.writeInt(length);
    }

    @Override
    public void addData(byte b) throws IOException {
        if (debugMode) {
            position ++;
            byte s2 = (byte) target.read();
            if (b != s2) {
                throw new IOException("Add Mismatch " + b + " != " + s2 + " at position " + position);
            }
        }
        dataLength++;
        totalLength++;
    }

    @Override
    public void flush() throws IOException {
        writeData();
        output.flush();
    }

    @Override
    public void close() throws IOException {
        writeData();
        output.writeByte(3);
        output.flush();
        output.close();
        if (debugMode) {
            target.close();
        }
    }

    private void writeData() throws IOException {
        while (dataLength >= Integer.MAX_VALUE) {
            int length = Integer.MAX_VALUE;
            output.writeByte(2);
            output.writeInt(length);
            dataLength -= length;
        }
        if (dataLength > 0) {
            output.writeByte(2);
            output.writeInt((int) dataLength);
            dataLength = 0;
        }
    }

}
