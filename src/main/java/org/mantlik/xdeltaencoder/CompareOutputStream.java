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
