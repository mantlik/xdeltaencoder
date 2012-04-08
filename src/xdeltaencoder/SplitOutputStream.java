/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package xdeltaencoder;

import java.io.*;
import java.text.DecimalFormat;

/**
 *
 * @author fm
 */
public class SplitOutputStream extends OutputStream {

    private static final DecimalFormat df = new DecimalFormat("0000");
    private OutputStream os;
    private File dir;
    private String prefix;
    private long blocksize = 1000000000;
    private long written = 0;
    private boolean join = false;
    private int fileno = 0;

    SplitOutputStream(File prefix, long blocksize, boolean join) {
        this.dir = prefix.getParentFile();
        this.prefix = prefix.getName();
        this.join = join;
        this.blocksize = blocksize;
    }

    @Override
    public void write(int b) throws IOException {
        if (os == null) {
            os = new BufferedOutputStream(new FileOutputStream(new File(dir, prefix + "." + df.format(fileno))), 100000);
        }
        os.write(b);
        written++;
        if (written >= blocksize) {
            if (join) {
                new File(dir, prefix + "." + df.format(fileno)).deleteOnExit();
            }
            fileno++;
            os.close();
            os = null;
            written = 0;
        }
    }

    @Override
    public void close() throws IOException {
        if (os != null) {
            os.close();
        }
        if (join) {
            String command = "cat";
            for (int i=0; i<=fileno; i++) {
                
                command += " " + (new File(dir, prefix + "." + df.format(fileno))).getAbsolutePath();
            }
            command += " > " + dir.getAbsolutePath() + prefix;
            Runtime.getRuntime().exec(command);
        }
    }

    @Override
    public void flush() throws IOException {
        os.flush();
    }
}
