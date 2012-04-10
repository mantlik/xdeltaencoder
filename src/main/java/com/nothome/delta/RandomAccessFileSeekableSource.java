/*
 * RandomAccessFileSeekableSource.java
 *
 * Created on May 17, 2006, 1:45 PM
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
 *
 */
package com.nothome.delta;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;

/**
 * Wraps a random access file.
 */
public class RandomAccessFileSeekableSource implements SeekableSource {

    private RandomAccessFile raf;
    private long offset = 0;
    private long length = 0;
    private long position = 0;

    /**
     * Constructs a new RandomAccessFileSeekableSource.
     *
     * @param raf
     */
    public RandomAccessFileSeekableSource(RandomAccessFile raf) throws IOException {
        if (raf == null) {
            throw new NullPointerException("raf");
        }
        this.raf = raf;
        length = raf.length();
        offset = 0;
        position = 0;
    }

    public RandomAccessFileSeekableSource(RandomAccessFile raf, long offset, long length) throws IOException {
        if (raf == null) {
            throw new NullPointerException("raf");
        }
        this.raf = raf;
        this.offset = offset;
        this.length = Math.min(Math.max(raf.length() - offset, 0), length);
        this.position = 0;
    }

    @Override
    public void seek(long pos) throws IOException {
        raf.seek(pos + offset);
        position = Math.min(length, pos);
    }

    public int read(byte[] b, int off, int len) throws IOException {
        long ll = Math.min(len, length - position);
        if (ll <= 0) {
            return -1;
        }
        if (ll > Integer.MAX_VALUE) {
            throw new IndexOutOfBoundsException("Ilegal state.");
        }
        int i = raf.read(b, off, (int) ll);
        if (i < 0) {
            return -1;
        }
        position += i;
        return i;
    }

    public long length() throws IOException {
        return length;
    }

    @Override
    public void close() throws IOException {
        raf.close();
    }

    @Override
    public int read(ByteBuffer bb) throws IOException {
        long ll = Math.min(bb.remaining(), length - position);
        if (ll <= 0) {
            return -1;
        }
        if (ll > Integer.MAX_VALUE) {
            throw new IndexOutOfBoundsException("Ilegal state.");
        }
        int c = raf.read(bb.array(), bb.position(), (int) ll);
        if (c == -1) {
            return -1;
        }
        bb.position(bb.position() + c);
        position += c;
        return c;
    }
}
