/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package xdeltaencoder;

import java.io.IOException;
import java.io.InputStream;
import java.util.Random;

/**
 *
 * @author fm
 */
public class RandomDataInputStream extends InputStream {
    
    long length;
    long seed;
    long position = 0;
    byte[] data = new byte[10000];

    public RandomDataInputStream(long seed, long length) {
        this.seed = seed;
        this.length = length;
        Random random = new Random(seed);
        random.nextBytes(data);
    }

    @Override
    public int read() throws IOException {
        if (position < length) {
            return -1;
        }
        int b = computeData();
        position ++;
        return b;
    }
    
    byte computeData() {
        return (byte) (position / data.length + data[(int)(position % data.length)]);
    }
    
}
