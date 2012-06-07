/*
 * #%L
 * XDeltaEncoder
 * %%
 * Copyright (C) 2011 - 2012 Frantisek Mantlik <frantisek at mantlik.cz>
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the 
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public 
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */
/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.mantlik.xdeltaencoder;

import com.nothome.delta.SeekableSource;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.TreeMap;

/**
 *
 * @author fm
 */
public class MultifileSeekableSource implements SeekableSource {

    TreeMap<Long, RandomAccessFile> filemap = new TreeMap<Long, RandomAccessFile>();
    long position = 0;
    RandomAccessFile currentFile = null;
    long currentStart = 0;
    RandomAccessFile source;

    MultifileSeekableSource(File dir, String prefix) throws FileNotFoundException {
        File[] files = dir.listFiles();
        TreeMap<String, File> namemap = new TreeMap<String, File>();
        for (File f : files) {
            if (f.getName().equals(prefix)) {
                continue;
            }
            if (f.getName().startsWith(prefix)) {
                namemap.put(f.getName(), f);
            }
        }
        String name = namemap.firstKey();
        long index = 0;
        while (name != null) {
            File f = namemap.get(name);
            long l = f.length();
            filemap.put(index, new RandomAccessFile(f, "r"));
            index += l;
            name = namemap.higherKey(name);
        }
    }

    @Override
    public void seek(long pos) throws IOException {
        position = pos;
        currentFile = findFile(position);
        if (currentFile == null) {
            source = null;
            return;
        }
        source = currentFile;
        source.seek(pos - currentStart);
    }

    @Override
    public int read(ByteBuffer bb) throws IOException {
        if (source == null) {
            return -1;
        }
        long remaining = bb.remaining();
        long rr = remaining;
        while (remaining > 0) {
            long r = source.read(bb.array(), bb.position(), bb.remaining());
            if (r == -1) {
                seek(position);
                if (source == null) {
                    return (int) (rr - remaining);
                }
                continue;
            }
            bb.position((int) (bb.position() + r));
            remaining -= r;
            position += r;
        }
        return (int) (rr - remaining);
    }

    @Override
    public void close() throws IOException {
        if (filemap.isEmpty()) {
            return;
        }
        Long key = filemap.firstKey();
        while (key != null) {
            filemap.get(key).close();
            key = filemap.higherKey(key);
        }
    }

    private RandomAccessFile findFile(long position) throws IOException {
        Long key = filemap.floorKey(position);
        RandomAccessFile file = null;
        if (key != null) {
            file = filemap.get(key);
        }
        if (key == null || ((key + file.length()) <= position)) {
            return null;
        }
        currentStart = key;
        return file;
    }
}
