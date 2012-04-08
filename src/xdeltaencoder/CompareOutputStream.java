/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package xdeltaencoder;

import java.io.*;

/**
 *
 * @author fm
 */
class CompareOutputStream  extends OutputStream {

    InputStream target;
    long count = 0;
    public boolean compareOK = true;
    
    public CompareOutputStream(File target) throws FileNotFoundException {
        this.target = new BufferedInputStream(new FileInputStream(target), 100000);
    }
    
    public CompareOutputStream(InputStream target) {
        this.target = target;
    }

    @Override
    public void write(int b) throws IOException {
        int c = target.read();
        if ((b & 0xff) != c) {
            compareOK = false;
            throw new IOException("Difference "+ (b & 0xff) +"!=" + c + " at position " + count);
        }
        count++;
    }

    @Override
    public void close() throws IOException {
        target.close();
    }
    
}
