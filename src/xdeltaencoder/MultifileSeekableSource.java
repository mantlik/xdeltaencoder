/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package xdeltaencoder;

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
