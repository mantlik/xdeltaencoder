/*
 * DeltaDiffPatchTestSuite.java
 * JUnit based test
 *
 * Created on May 26, 2006, 9:06 PM
 * Copyright (c) 2006 Heiko Klein
 *
 * Permission is hereby granted, free of charge, to any person obtaining a
 * copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in 
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
 * THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS
 * IN THE SOFTWARE.
 */

package com.nothome.delta;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.junit.Test;

/**
 * Tests {@link Checksum}.
 */
public class ChecksumTest {

    String s = "abcdefghijklmnopqrstuvwyxz012345679";
    
    @Test
    public void testCheck() throws IOException {
       testCheck(16);
       testCheck(4); 
       testCheck(10); 
    }
    
    public void testCheck(int chunk) throws IOException {
        byte[] bytes = s.getBytes("ASCII");
        ByteBufferSeekableSource source = new ByteBufferSeekableSource(bytes);
        Checksum checksum = new Checksum(source, chunk);
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        long hash = Checksum.queryChecksum(bb, chunk);
        assertEquals(0, checksum.findChecksumIndex(hash));
        bb.position(chunk);
        long hash2 = Checksum.queryChecksum(bb, chunk);
        assertEquals(1, checksum.findChecksumIndex(hash2));
        for (int i = 0; i < chunk; i++)
            hash = Checksum.incrementChecksum(hash, bb.get(bb.position() - chunk), bb.get(), chunk);
        assertEquals(hash2, hash);
    }
}
