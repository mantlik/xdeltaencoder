/* 
 *
 * Copyright (c) 2001 Torgeir Veimo
 *
 * Permission is hereby granted, free of charge, to any person obtaining a
 * copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
 * THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS
 * IN THE SOFTWARE.
 *
 */
package com.nothome.delta;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Outputs a diff following the GDIFF file specification available at
 * http://www.w3.org/TR/NOTE-gdiff-19970901.html.
 */
public class GDiffWriter implements DiffWriter {

    /**
     * Max length of a chunk.
     */
    public static final int CHUNK_SIZE = Short.MAX_VALUE;
    public static final byte EOF = 0;
    /**
     * Max length for single length data encode.
     */
    public static final int DATA_MAX = 246;  //0xf6
    public static final int DATA_USHORT = 247;  //0xf7
    public static final int DATA_INT = 248;  //0xf8
    public static final int COPY_UBYTE_UBYTE = 244;  //0xf3
    public static final int COPY_UBYTE_USHORT = 245;  //0xf4
    public static final int COPY_UBYTE_INT = 246;  //0xf5
    public static final int COPY_USHORT_UBYTE = 249;  //0xf9
    public static final int COPY_USHORT_USHORT = 250;  //0xfa
    public static final int COPY_USHORT_INT = 251;  //0xfb
    public static final int COPY_INT_UBYTE = 252;  //0xfc
    public static final int COPY_INT_USHORT = 253; //0xfd
    public static final int COPY_INT_INT = 254;  //0xfe
    public static final int COPY_LONG_INT = 255; //0xff
    private ByteArrayOutputStream buf = new ByteArrayOutputStream();
    private boolean debug = false;
    private boolean skipHeaders = false;
    private boolean differential = false;
    private boolean zeroAdditions = false;
    private long currentOffset = 0l;
    private long written = 0;
    private int data_max = DATA_MAX;
    private DataOutputStream output = null;
    public long lo = 0;
    public long io = 0;
    public long so = 0;
    public long bo = 0;
    public long totalLength = 0;

    /**
     * Constructs a new GDiffWriter.
     * @param os
     * @throws IOException  
     */
    public GDiffWriter(DataOutputStream os) throws IOException {
        this(os, false, false, false);
    }

    public GDiffWriter(DataOutputStream os, boolean skipHeaders) throws IOException {
        this(os, skipHeaders, false, false);
    }

    public GDiffWriter(DataOutputStream os, boolean skipHeaders, boolean differential, boolean zeroAdditions) throws IOException {
        this.differential = differential;
        this.output = os;
        this.skipHeaders = skipHeaders;
        this.zeroAdditions = zeroAdditions;
        // write magic string "d1 ff d1 ff 04"
        if (!skipHeaders) {
            output.writeByte(0xd1);
            output.writeByte(0xff);
            output.writeByte(0xd1);
            output.writeByte(0xff);
            if (differential) {
                output.writeByte(0x05);     // magic string extension
                data_max = 243;
            } else {
                output.writeByte(0x04);
            }
            written = 5;
        }
    }

    /**
     * Constructs a new GDiffWriter.
     */
    public GDiffWriter(OutputStream output) throws IOException {
        this(new DataOutputStream(output));
    }
    
    public long written() {
        return written;
    }

    @Override
    public void addCopy(long offset, int length) throws IOException {
        writeBuf();
        //output debug data        
        if (debug) {
            System.err.println("COPY off: " + offset + ", len: " + length);
        }
        if (differential) {
            long newoffset = offset;
            offset = offset-currentOffset;
            currentOffset = newoffset;
        }
        long aoffset = Math.abs(offset);
        // output real data
        if (aoffset > Integer.MAX_VALUE) {
            lo++;
            // Actually, we don't support longer files than int.MAX_VALUE at the moment..
            output.writeByte(COPY_LONG_INT);
            output.writeLong(offset);
            output.writeInt(length);
            written += 13;
        } else if (differential && aoffset < 128) {
            bo++;
            if (length < 256) {
                output.writeByte(COPY_UBYTE_UBYTE);
                output.writeByte((byte) offset);
                output.writeByte(length);
                written += 3;
            } else if (length > 32767) {
                output.writeByte(COPY_UBYTE_INT);
                output.writeByte((byte) offset);
                output.writeInt(length);
                written += 6;
            } else {
                output.writeByte(COPY_UBYTE_USHORT);
                output.writeByte((byte) offset);
                output.writeShort(length);
                written += 4;
            }
        } else if (aoffset < 32768) {
            so++;
            if (length < 256) {
                output.writeByte(COPY_USHORT_UBYTE);
                output.writeShort((int) offset);
                output.writeByte(length);
                written += 4;
            } else if (length > 32767) {
                output.writeByte(COPY_USHORT_INT);
                output.writeShort((int) offset);
                output.writeInt(length);
                written += 7;
            } else {
                output.writeByte(COPY_USHORT_USHORT);
                output.writeShort((int) offset);
                output.writeShort(length);
                written += 5;
            }
        } else {
            io++;
            if (length < 256) {
                output.writeByte(COPY_INT_UBYTE);
                output.writeInt((int) offset);
                output.writeByte(length);
                written += 6;
            } else if (length > 32767) {
                output.writeByte(COPY_INT_INT);
                output.writeInt((int) offset);
                output.writeInt(length);
                written += 9;
            } else {
                output.writeByte(COPY_INT_USHORT);
                output.writeInt((int) offset);
                output.writeShort(length);
                written += 7;
            }
        }
        totalLength += length;
    }

    /**
     * Adds a data byte.
     */
    @Override
    public void addData(byte b) throws IOException {
        if (zeroAdditions) {
            buf.write(0);
        } else {
            buf.write(b);
        }
        if (buf.size() >= CHUNK_SIZE) {
            writeBuf();
        }
        totalLength ++;
    }

    private void writeBuf() throws IOException {
        if (buf.size() > 0) {
            if (buf.size() <= data_max) {
                output.writeByte(buf.size());
                written += buf.size() + 1;
            } else if (buf.size() <= 32767) {
                output.writeByte(DATA_USHORT);
                output.writeShort(buf.size());
                written += buf.size() + 3;
            } else {
                output.writeByte(DATA_INT);
                output.writeInt(buf.size());
                written += buf.size() + 5;
            }
            buf.writeTo(output);
            buf.reset();
        }
    }

    /**
     * Flushes accumulated data bytes, if any.
     */
    public void flush() throws IOException {
        writeBuf();
        output.flush();
    }

    /**
     * Writes the final EOF byte, closes the underlying stream.
     */
    public void close() throws IOException {
        this.flush();
        if (!skipHeaders) {
            output.write((byte) EOF);
            written ++;
        }
        output.close();
    }
}
