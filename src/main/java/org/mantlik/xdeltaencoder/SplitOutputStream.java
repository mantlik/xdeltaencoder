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
