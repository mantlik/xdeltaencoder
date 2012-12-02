/*
 * Copyright (C) 2012 fm
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package com.nothome.delta;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.TreeMap;

/**
 *
 * @author fm
 */
public class MultiBufferSeekableSource implements SeekableSource {

    private class Buffer {

        long position;
        ByteBuffer buffer;

        public Buffer(long position, ByteBuffer buffer) {
            this.position = position;
            this.buffer = buffer;
        }
    }
    private RandomAccessFile source;
    private int bufferSize;
    private int noOfBuffers;
    private int totalBuffers = 0;
    private Buffer currentBuf = null;
    private long currentPos = 0;
    private TreeMap<Long, Buffer> buffers = new TreeMap<Long, Buffer>();
    private ArrayList<Buffer> bufUsage = new ArrayList<Buffer>();
    public final OutputStream outputStream = new SelfOutputStream();
    public final InputStream inputStream = new SelfInputStream();
    private long isPos = 0;
    private ByteBuffer isbuf;
    private ByteBuffer osbuf;

    public MultiBufferSeekableSource(RandomAccessFile source, int bufferSize, int noOfBuffers) {
        this.source = source;
        this.bufferSize = bufferSize;
        this.noOfBuffers = noOfBuffers;
        isbuf = ByteBuffer.allocate(bufferSize);
        isbuf.clear();
        isbuf.limit(0);
        osbuf = ByteBuffer.allocate(bufferSize);
        osbuf.clear();
    }

    @Override
    public void seek(long pos) throws IOException {
        clearos();
        Long p = null;
        if (!buffers.isEmpty()) {
            p = buffers.firstKey();
        }
        Long lp = p;
        while ((p != null) && (p < pos)) {
            lp = p;
            p = buffers.higherKey(p);
        }
        if ((lp == null) || (lp > pos)) {
            if (totalBuffers >= noOfBuffers) {
                currentBuf = bufUsage.get(0);
                buffers.remove(currentBuf.position);
            } else {
                currentBuf = new Buffer(pos, ByteBuffer.allocate(bufferSize));
                totalBuffers ++;
            }
            currentBuf.buffer.clear();
            currentBuf.buffer.limit(0);
            currentBuf.position = pos;
            currentPos = pos;
            buffers.put(currentBuf.position, currentBuf);
            bufUsage.remove(currentBuf);
            bufUsage.add(currentBuf);
            return;
        }
        currentBuf = buffers.get(lp);
        currentPos = pos;
        if ((pos - lp) < currentBuf.buffer.limit()) {
            currentBuf.buffer.position((int) (pos - lp));
        } else {
            buffers.remove(lp);
            currentBuf.buffer.clear();
            currentBuf.buffer.limit(0);
            currentBuf.position = pos;
            buffers.put(currentBuf.position, currentBuf);
            bufUsage.remove(currentBuf);
            bufUsage.add(currentBuf);
        }
    }

    @Override
    public int read(ByteBuffer bb) throws IOException {
        clearos();
        if (currentBuf == null) {
            return -1;
        }
        int c = 0;
        while (bb.hasRemaining()) {
            if (!currentBuf.buffer.hasRemaining()) {
                currentBuf.buffer.clear();
                source.seek(currentPos);
                int i = source.read(currentBuf.buffer.array(), currentBuf.buffer.position(),
                        currentBuf.buffer.limit());
                if (i < 0) {
                    currentBuf.buffer.limit(0);
                    if (c == 0) {
                        return -1;
                    } else {
                        return c;
                    }
                }
                currentBuf.buffer.position(i);
                currentBuf.buffer.flip();
                buffers.remove(currentBuf.position);
                currentBuf.position = currentPos;
                buffers.put(currentBuf.position, currentBuf);
                bufUsage.remove(currentBuf);
                bufUsage.add(currentBuf);
            }
            bb.put(currentBuf.buffer.get());
            c++;
            currentPos++;
        }
        return c;
    }

    @Override
    public void close() throws IOException {
        clearos();
        currentBuf = null;
        buffers.clear();
        //source.close();
    }

    public void close(boolean closeSource) throws IOException {
        close();
        if (closeSource) {
            source.close();
        }
    }
    // Input stream simulation

    public void resetStream() {
        isPos = 0;
        isbuf.clear();
        isbuf.limit(0);
    }

    public class SelfInputStream extends InputStream {

        @Override
        public int read() throws IOException {
            if (!isbuf.hasRemaining()) {
                isbuf.clear();
                source.seek(isPos);
                int len = source.read(isbuf.array());
                if (len <= 0) {
                    isbuf.limit(0);
                    return -1;
                }
                isPos += len;
                isbuf.position(0);
                isbuf.limit(len);
            }
            return 0x00ff & isbuf.get();
        }

        @Override
        public void close() throws IOException {
            resetStream();
        }
        
    }
    
    private void clearos() throws IOException {
        if (osbuf.position() > 0) {
            source.seek(source.length());
            source.write(osbuf.array(), 0, osbuf.position());
        }
        osbuf.clear();
    }

    public class SelfOutputStream extends OutputStream {

        @Override
        public void write(int b) throws IOException {
            if (! osbuf.hasRemaining()) {
                clearos();
            }
            osbuf.put((byte)(b & 0x00ff));
        }
    }
}
