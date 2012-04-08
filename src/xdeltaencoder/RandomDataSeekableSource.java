/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package xdeltaencoder;

import com.nothome.delta.SeekableSource;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Random;

/**
 * Generated data random access file of given length files with the same seed
 * have the same virtual content
 *
 * @author fm
 */
public class RandomDataSeekableSource implements SeekableSource {

    long length;
    long seed;
    long position = 0;
    byte[] data = new byte[10000];

    public RandomDataSeekableSource(long seed, long length) {
        this.seed = seed;
        this.length = length;
        Random random = new Random(seed);
        random.nextBytes(data);
    }

    @Override
    public void seek(long pos) throws IOException {
        if ((pos > length) || (pos < 0)) {
            throw new IOException("pos " + pos + " cannot seek " + length);
        }
        position = pos;
    }

    @Override
    public int read(ByteBuffer dest) throws IOException {
        if (position >= length) {
            return -1;
        }
        int c = 0;
        while ((position < length) && dest.hasRemaining()) {
            dest.put(computeData());
            c++;
            position ++;
        }
        return c;
    }

    @Override
    public void close() throws IOException {
        position = length;
    }
    
    byte computeData() {
        byte b = (byte) (position / data.length + data[(int)(position % data.length)]);
        return b;
    }
    
}
