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
import java.io.OutputStream;

import org.junit.Test;

/**
 * Testing the boundaries.
 * 
 * @author Heiko Klein
 * @author Stefan Liebig
 */
public class DeltaDiffPatchBoundariesTest {

    @Test
    public void testCase1() throws Exception {
        run("0123456789abcdef", "0123456789abcdef");
    }

    @Test
    public void testCase2() throws Exception {
        run("0123456789abcdef", "0123456789abcdef+");
    }

    @Test
    public void testCase3() throws Exception {
        run("0123456789abcdef0", "0123456789abcdef0+");
    }

    @Test
    public void testCase4() throws Exception {
        run("0123456789abcdef0123456789abcdef",
                "0123456789abcdef0123456789abcdef+");
    }

    @Test
    public void testCase4b() throws Exception {
        String a = "aaaaaaaaaaaaaaaa";
        String x = "xxxxxxxxxxxxxxxx";
        run(a + x, x + a);
    }

    @Test
    public void testCase5() throws Exception {
        run("0123456789abcdef0123456789abcdef", "0123456789abcdef");
    }

    @Test
    public void testCase6() throws Exception {
        run(
                "Seite reserviert. Hier soll demnächst etwas über mich stehen.",
                "Seite reserviert. Hier soll demnächst etwas über mich stehen. (Test der Umlaute)");
        // / 0123456789123456789
    }

    @Test
    public void testShort() throws Exception {
        run("0123456789abcdef", "0123456789");
        run("0123456789", "0123456789abcdef");
    }

    private void run(String string1, String string2) throws Exception {
        File test1File = new File("test1.txt");
        File test2File = new File("test2.txt");

        OutputStream os = new FileOutputStream(test1File);
        os.write(string1.getBytes());
        os.close();
        os = new FileOutputStream(test2File);
        os.write(string2.getBytes());
        os.close();

        File patchedFile = new File("patchedFile.txt");
        File deltaFile = new File("delta");

        try {
            DiffWriter output = new GDiffWriter(new DataOutputStream(
                    new BufferedOutputStream(new FileOutputStream(deltaFile))));
            Delta d = new Delta();
            d.compute(test1File, test2File, output);
            output.close();

            assertTrue(deltaFile.exists());

            System.out.println(fmt(DeltaPatchTest.read(deltaFile)));

            GDiffPatcher diffPatcher = new GDiffPatcher();
            diffPatcher.patch(test1File, deltaFile, patchedFile);
            assertTrue(patchedFile.exists());

            assertEquals((long) string2.getBytes().length, patchedFile.length());
            byte[] buf = new byte[string2.getBytes().length];
            FileInputStream is = new FileInputStream(patchedFile);
            is.read(buf);
            is.close();

            String got = new String(buf);
            assertEquals(string2, got);
        } finally {
            test1File.delete();
            test2File.delete();
            deltaFile.delete();
            patchedFile.delete();
        }
    }

    private String fmt(ByteArrayOutputStream read) {
        byte[] b = read.toByteArray();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            int b1 = b[i] & 0xFF;
            if (b1 < 32 || b1 > 127)
                sb.append("|" + b1 + "|");
            else
                sb.append((char) b1);
        }
        return sb.toString();
    }

}
