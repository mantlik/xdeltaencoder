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
    private Buffer currentBuf = null;
    private long currentPos = 0;
    private TreeMap<Long, Buffer> buffers = new TreeMap<Long, Buffer>();
    private ArrayList<Buffer> bufUsage = new ArrayList<Buffer>();

    public MultiBufferSeekableSource(RandomAccessFile source, int bufferSize, int noOfBuffers) {
        this.source = source;
        this.bufferSize = bufferSize;
        this.noOfBuffers = noOfBuffers;
    }

    @Override
    public void seek(long pos) throws IOException {
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
            if (buffers.size() >= noOfBuffers) {
                currentBuf = bufUsage.get(0);
                buffers.remove(currentBuf.position);
            } else {
                currentBuf = new Buffer(pos, ByteBuffer.allocate(bufferSize));
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
        currentBuf = null;
        buffers.clear();
        source.close();
    }
}
