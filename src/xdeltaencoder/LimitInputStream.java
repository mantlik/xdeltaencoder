/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package xdeltaencoder;

import java.io.IOException;
import java.io.InputStream;

/**
 *
 * @author fm
 */
public class LimitInputStream extends InputStream {

    private InputStream is;
    private long limit = -1;

    LimitInputStream(InputStream is) {
        this.is = is;
    }

    @Override
    public int read() throws IOException {
        if (limit < 0) {
            return is.read();
        }
        if (limit > 0) {
            limit--;
            return is.read();
        }
        return -1;
    }

    @Override
    public int available() throws IOException {
        if (limit < 0) {
            return super.available();
        }
        return Math.min(super.available(), (int) Math.min(limit, Integer.MAX_VALUE));
    }

    @Override
    public long skip(long n) throws IOException {
        if (limit < 0) {
            return super.skip(n);
        }
        long skip = super.skip(Math.min(limit,n));
        limit -= skip;
        return skip;
    }
    
    public void setLimit(long limit) {
        this.limit = limit;
    }
}
