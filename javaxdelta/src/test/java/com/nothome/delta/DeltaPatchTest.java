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
import static org.junit.Assert.assertTrue;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests {@link Delta} and {@link GDiffPatcher}.
 */
public class DeltaPatchTest {

    private File test1File;
    private File test2File;
    private int chunkSize;
    
    static ByteArrayOutputStream read(File f) throws IOException {
        FileInputStream fis = new FileInputStream(f);
        try {
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            while (true) {
                int r = fis.read();
                if (r == -1) break;
                os.write(r);
            }
            return os;
        } finally {
            fis.close();
        }
    }

    @Before
    public void setUp() throws Exception {
        chunkSize = Delta.DEFAULT_CHUNK_SIZE;
    }

    @After
    public void tearDown() throws Exception {
        (new File("delta")).delete();
    }

    @Test
    public void testLorem() throws IOException {
        use("lorem.txt", "lorem2.txt");
        doTest();
    }
    
    @Test
    public void testLorem2() throws IOException {
        use("lorem2.txt", "lorem.txt");
        doTest();
    }
        
    @Test
    public void testLorem22() throws IOException {
        use("lorem2.txt", "lorem2.txt");
        doTest();
    }
        
    @Test
    public void testLoremLong() throws IOException {
        use("lorem-long.txt", "lorem-long2.txt");
        // doTest();
        chunkSize = 8;
        doTest();
    }
    
    @Test
    public void testLoremLong2() throws IOException {
        use("lorem-long2.txt", "lorem-long.txt");
        doTest();
    }
    
    @Test
    public void testLoremLong3() throws IOException {
        use("lorem-long.txt", "lorem-long3.txt");
        doTest();
        chunkSize = 14;
        doTest();
    }
    
    @Test
    public void testVer() throws IOException {
        use("ver1.txt", "ver2.txt");
        doTest();
    }
        
    @Test
    public void testVer34() throws IOException {
        use("ver3.txt", "ver4.txt");
        doTest();
    }
        
    @Test
    public void testVer21() throws IOException {
        use("ver2.txt", "ver1.txt");
        doTest();
    }
        
    @Test
    public void testMinBug() throws IOException {
        use("min1.bin", "min2.bin");
        doTest();
        chunkSize = 14;
        doTest();
    }
    
    @Test
    public void testObj12() throws IOException {
        use("obj1.bin", "obj2.bin");
        doTest();
        chunkSize = 14;
        doTest();
    }
    
    private void doTest() throws IOException {
        File patchedFile = new File("patchedFile.txt");
        File delta = new File("delta");
        DiffWriter output = new GDiffWriter(new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(delta))));
        Delta d = new Delta();
        d.setChunkSize(chunkSize);
        d.compute(test1File, test2File, output);
        output.close();

        assertTrue(delta.exists());

        System.out.println("delta length " + delta.length() + " for " + test1File + " " + test2File);
        System.out.println(toString(read(delta).toByteArray()));
        System.out.println("end patch");

        GDiffPatcher diffPatcher = new GDiffPatcher();
        diffPatcher.patch(test1File, delta, patchedFile);
        assertTrue(patchedFile.exists());

        assertEquals("file length", test2File.length(), patchedFile.length());
        byte[] buf = new byte[(int) test2File.length()];
        FileInputStream is = new FileInputStream(patchedFile);
        is.read(buf);
        is.close();
        patchedFile.delete();

        assertEquals(new String(buf), read(test2File).toString());
    }

    private void use(String f1, String f2) {
        URL l1 = getClass().getClassLoader().getResource(f1);
        URL l2 = getClass().getClassLoader().getResource(f2);
        test1File = new File(l1.getPath());
        test2File = new File(l2.getPath());
    }

    private static void append(StringBuffer sb, int value) {
        char b1 = (char)((value >> 4) & 0x0F);
        char b2 = (char)((value) & 0x0F);
        sb.append( Character.forDigit(b1, 16) );
        sb.append( Character.forDigit(b2, 16) );
    }

    /**
     * Return the data as a series of hex values.
     */
    public String toString(byte buffer[])
    {
        int length = buffer.length;
        StringBuffer sb = new StringBuffer(length * 2);
        for (int i=0; i<length; i++) {
            append(sb, buffer[i]);
        }
        return sb.toString();
    }


}
