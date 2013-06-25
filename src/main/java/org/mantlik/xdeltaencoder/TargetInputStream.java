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

import com.nothome.delta.GDiffPatcher;
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

    private static final long REPORT_INTERVAL = 1000;
    long interval;
    long read;
    long totalread;
    long filesize;
    long reportTime;
    GDiffPatcher patcher = null;
    private final static DecimalFormat df = new DecimalFormat("0.00");
    private final static DecimalFormat df0 = new DecimalFormat("0");

    public TargetInputStream(File file, long interval, GDiffPatcher patcher) throws FileNotFoundException {
        super(file);
        this.interval = interval;
        this.patcher = patcher;
        read = 0;
        totalread = 0;
        filesize = file.length();
    }

    private void readBytes(long bytes) {
        read += bytes;
        totalread += bytes;
        if (read >= interval) {
            double perc = 100d * totalread / filesize;
            if (patcher != null) {
                System.out.print("\rProcessed " + df0.format(totalread / 1024d / 1024d) + " mb " + df.format(perc)
                        + " % written " + df0.format(patcher.totalLength / 1024d / 1024d) + " mb      ");
            } else {
                System.out.print("\rProcessed " + df0.format(totalread / 1024d / 1024d) + " mb " + df.format(perc) + " %          ");
            }
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
