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
