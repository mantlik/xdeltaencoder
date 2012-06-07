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
import java.util.TreeMap;

/**
 *
 * @author fm
 */
public class SplitInputStream extends InputStream {

    private final static DecimalFormat df = new DecimalFormat("0.00");
    private final static DecimalFormat df0 = new DecimalFormat("0");
    
    private TreeMap<String, File> namemap = new TreeMap<String, File>();
    private InputStream is = null;
    private String currentFile = null;
    private long interval = 1024*1024;
    private long totalread = 0;
    private long read = 0;
    private long filesize = 0;
    
    
    SplitInputStream (File dir, String prefix, long interval) {
        File[] files = dir.listFiles();
        for (File f : files) {
            if (f.getName().equals(prefix)) {
                continue;
            }
            if (f.getName().startsWith(prefix)) {
                namemap.put(f.getName(), f);
                filesize += f.length();
            }
        }
        currentFile = namemap.firstKey();
        this.interval = interval;
    }

    @Override
    public int read() throws IOException {
        if (currentFile==null) {
            return -1;
        }
        if (is==null) {
            is = new BufferedInputStream(new FileInputStream(namemap.get(currentFile)), 10000);
        }
        int i = is.read();
        if (i < 0) {
            if (is != null) {
                is.close();
                is = null;
            }
            currentFile = namemap.higherKey(currentFile);
            if (currentFile!=null) {
                is = new BufferedInputStream(new FileInputStream(namemap.get(currentFile)), 10000);
                i = is.read();
            }
        }
        if (i >=0) {
            read ++;
            totalread ++;
        }
        if (read >= interval) {
            double perc = 100d * totalread / filesize;
            System.out.print("\rProcessed " + df0.format(totalread / 1024d / 1024d) + " mb " + df.format(perc) + " %");
            read = 0;
        }
        return i;
    }

    @Override
    public void close() throws IOException {
        if (is != null) {
            currentFile = null;
            is.close();
        }
    }
    
}
