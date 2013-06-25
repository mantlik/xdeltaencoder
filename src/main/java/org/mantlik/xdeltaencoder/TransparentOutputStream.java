/*
 * #%L
 * XDeltaEncoder
 * %%
 * Copyright (C) 2011 - 2013 Frantisek Mantlik <frantisek at mantlik.cz>
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
 * Copyright (C) 2013 fm
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.mantlik.xdeltaencoder;

import java.io.IOException;
import java.io.OutputStream;

/**
 *
 * @author fm
 */
public class TransparentOutputStream extends OutputStream {

    private static final int BUFFERSIZE = 1024;
    private final OutputStream stream;
    private int bufferpos = 0;
    private final byte[] buffer = new byte[BUFFERSIZE];

    public TransparentOutputStream(OutputStream stream) {
        this.stream = stream;
    }

    @Override
    public void write(int b) throws IOException {
        buffer[bufferpos] = (byte) b;
        bufferpos++;
        if (bufferpos == BUFFERSIZE) {
            stream.write(buffer);
            bufferpos = 0;
        }
    }

    @Override
    public void flush() throws IOException {
        super.flush();
        if (bufferpos > 0) {
            stream.write(buffer, 0, bufferpos);
        }
        bufferpos = 0;
        stream.flush();
    }

    @Override
    public void close() throws IOException {
        // do not close
    }

    public void closeStream() throws IOException {
        flush();
        stream.close();
        super.close();
    }

}
