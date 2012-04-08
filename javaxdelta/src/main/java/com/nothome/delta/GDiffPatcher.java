/*
 *
 * Copyright (c) 2001 Torgeir Veimo
 * Copyright (c) 2006 Heiko Klein
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

import static com.nothome.delta.GDiffWriter.*;
import java.io.*;
import java.nio.ByteBuffer;

/**
 * This class patches an input file with a GDIFF patch fil�e.
 *
 * The patch file follows the GDIFF file specification available at
 * {@link http://www.w3.org/TR/NOTE-gdiff-19970901.html}.
 */
public class GDiffPatcher {

    private ByteBuffer buf = ByteBuffer.allocate(1024);
    private byte buf2[] = buf.array();
    private boolean differential = false;
    private long currentOffset = 0l;
    private int data_max = DATA_MAX;
    public long totalLength = 0;

    /**
     * Constructs a new GDiffPatcher.
     */
    public GDiffPatcher() {
    }

    /**
     * Patches to an output file.
     */
    public void patch(File sourceFile, File patchFile, File outputFile)
            throws IOException {
        RandomAccessFileSeekableSource source = new RandomAccessFileSeekableSource(new RandomAccessFile(sourceFile, "r"));
        InputStream patch = new FileInputStream(patchFile);
        OutputStream output = new FileOutputStream(outputFile);
        try {
            patch(source, patch, output);
        } catch (IOException e) {
            throw e;
        } finally {
            source.close();
            patch.close();
            output.close();
        }
    }

    /**
     * Patches to an output stream.
     */
    public void patch(byte[] source, InputStream patch, OutputStream output) throws IOException {
        patch(new ByteBufferSeekableSource(source), patch, output);
    }

    /**
     * Patches in memory, returning the patch result.
     */
    public byte[] patch(byte[] source, byte[] patch) throws IOException {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        patch(source, new ByteArrayInputStream(patch), os);
        return os.toByteArray();
    }

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
                || patchIS.readUnsignedByte() != 0xff) {

            throw new PatchException("magic string not found, aborting!");
        }
        int flag = patchIS.readUnsignedByte();
        if (flag == 0x05) {
            differential = true;
            data_max = DATA_MAX - 3;
        } else if (flag != 0x04) {
            throw new PatchException("magic string not found, aborting!");
        }
        totalLength = 0;

        int length;
        long offset;
        while (true) {
            int command = patchIS.readUnsignedByte();
            if (command == EOF) {
                break;
            }

            if (command <= data_max) {
                append(command, patchIS, outOS);
                totalLength += command;
                continue;
            }

            switch (command) {
                case DATA_USHORT: // ushort, n bytes following; append
                    length = patchIS.readUnsignedShort();
                    append(length, patchIS, outOS);
                    totalLength += length;
                    break;
                case DATA_INT: // int, n bytes following; append
                    length = patchIS.readInt();
                    append(length, patchIS, outOS);
                    totalLength += length;
                    break;
                case COPY_USHORT_UBYTE:
                    if (differential) {
                        offset = patchIS.readShort();
                        offset = offset + currentOffset;
                        currentOffset = offset;
                    } else {
                        offset = patchIS.readUnsignedShort();
                    }
                    length = patchIS.readUnsignedByte();
                    copy(offset, length, source, outOS);
                    totalLength += length;
                    break;
                case COPY_USHORT_USHORT:
                    if (differential) {
                        offset = patchIS.readShort();
                        offset = offset + currentOffset;
                        currentOffset = offset;
                    } else {
                        offset = patchIS.readUnsignedShort();
                    }
                    length = patchIS.readUnsignedShort();
                    copy(offset, length, source, outOS);
                    totalLength += length;
                    break;
                case COPY_USHORT_INT:
                    if (differential) {
                        offset = patchIS.readShort();
                        offset = offset + currentOffset;
                        currentOffset = offset;
                    } else {
                        offset = patchIS.readUnsignedShort();
                    }
                    length = patchIS.readInt();
                    copy(offset, length, source, outOS);
                    totalLength += length;
                    break;
                case COPY_UBYTE_UBYTE:
                    if (differential) {
                        offset = patchIS.readByte();
                        offset = offset + currentOffset;
                        currentOffset = offset;
                    } else {
                        offset = patchIS.readUnsignedByte();
                    }
                    length = patchIS.readUnsignedByte();
                    copy(offset, length, source, outOS);
                    totalLength += length;
                    break;
                case COPY_UBYTE_USHORT:
                    if (differential) {
                        offset = patchIS.readByte();
                        offset = offset + currentOffset;
                        currentOffset = offset;
                    } else {
                        offset = patchIS.readUnsignedByte();
                    }
                    length = patchIS.readUnsignedShort();
                    copy(offset, length, source, outOS);
                    totalLength += length;
                    break;
                case COPY_UBYTE_INT:
                    if (differential) {
                        offset = patchIS.readByte();
                        offset = offset + currentOffset;
                        currentOffset = offset;
                    } else {
                        offset = patchIS.readUnsignedByte();
                    }
                    length = patchIS.readInt();
                    copy(offset, length, source, outOS);
                    totalLength += length;
                    break;
                case COPY_INT_UBYTE:
                    offset = patchIS.readInt();
                    if (differential) {
                        offset = offset + currentOffset;
                        currentOffset = offset;
                    }
                    length = patchIS.readUnsignedByte();
                    copy(offset, length, source, outOS);
                    totalLength += length;
                    break;
                case COPY_INT_USHORT:
                    offset = patchIS.readInt();
                    if (differential) {
                        offset = offset + currentOffset;
                        currentOffset = offset;
                    }
                    length = patchIS.readUnsignedShort();
                    copy(offset, length, source, outOS);
                    totalLength += length;
                    break;
                case COPY_INT_INT:
                    offset = patchIS.readInt();
                    if (differential) {
                        offset = offset + currentOffset;
                        currentOffset = offset;
                    }
                    length = patchIS.readInt();
                    copy(offset, length, source, outOS);
                    totalLength += length;
                    break;
                case COPY_LONG_INT:
                    offset = patchIS.readLong();
                    if (differential) {
                        offset = offset + currentOffset;
                        currentOffset = offset;
                    }
                    length = patchIS.readInt();
                    copy(offset, length, source, outOS);
                    totalLength += length;
                    break;
                default:
                    throw new IllegalStateException("command " + command);
            }
        }
        flush(outOS);
        return;
    }

    void copy(long offset, int length, SeekableSource source, OutputStream output)
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

    void append(int length, InputStream patch, OutputStream output) throws IOException {
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

    void flush(OutputStream os) throws IOException {
        os.flush();
    }

    /**
     * Simple command line tool to patch a file.
     */
    public static void main(String argv[]) {

        if (argv.length != 3) {
            System.err.println("usage GDiffPatch source patch output");
            System.err.println("aborting..");
            return;
        }
        try {
            File sourceFile = new File(argv[0]);
            File patchFile = new File(argv[1]);
            File outputFile = new File(argv[2]);

            if (sourceFile.length() > Integer.MAX_VALUE
                    || patchFile.length() > Integer.MAX_VALUE) {
                System.err.println("source or patch is too large, max length is " + Integer.MAX_VALUE);
                System.err.println("aborting..");
                return;
            }
            GDiffPatcher patcher = new GDiffPatcher();
            patcher.patch(sourceFile, patchFile, outputFile);

            System.out.println("finished patching file");

        } catch (Exception ioe) {                                   //gls031504a
            System.err.println("error while patching: " + ioe);
        }
    }
}
