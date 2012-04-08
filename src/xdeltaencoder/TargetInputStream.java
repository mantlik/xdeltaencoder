/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package xdeltaencoder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.text.DecimalFormat;

/**
 *
 * @author fm
 */
public class TargetInputStream extends FileInputStream {

    long interval;
    long read;
    long totalread;
    long filesize;
    private final static DecimalFormat df = new DecimalFormat("0.00");
    private final static DecimalFormat df0 = new DecimalFormat("0");

    public TargetInputStream(File file, long interval) throws FileNotFoundException {
        super(file);
        this.interval = interval;
        read = 0;
        totalread = 0;
        filesize = file.length();
    }

    private void readBytes(long bytes) {
        read += bytes;
        totalread += bytes;
        if (read >= interval) {
            double perc = 100d * totalread / filesize;
            System.out.print("\rProcessed " + df0.format(totalread / 1024d / 1024d) + " mb " + df.format(perc) + " %          ");
            read = 0;
        }
    }

    @Override
    public int read() throws IOException {
        readBytes(1);
        return super.read();
    }

    @Override
    public int read(byte[] b) throws IOException {
        int i = super.read(b);
        readBytes(i);
        return i;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int i = super.read(b, off, len);
        readBytes(i);
        return i;
    }

    @Override
    public long skip(long n) throws IOException {
        long i = super.skip(n);
        readBytes(i);
        return i;
    }
}
