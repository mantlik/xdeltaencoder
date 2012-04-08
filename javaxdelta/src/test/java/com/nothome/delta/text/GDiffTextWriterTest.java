package com.nothome.delta.text;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.io.StringWriter;

import org.junit.Test;

public class GDiffTextWriterTest {

    @Test
    public void testVer() throws IOException {
        StringWriter sw = new StringWriter();
        GDiffTextWriter tw = new GDiffTextWriter(sw);
        tw.addCopy(0x1f4, 0xa0);
        String s = "abcdefg";
        for (int i = 0; i < s.length(); i++)
            tw.addData(s.charAt(i));
        tw.close();
        assertEquals(
                GDiffTextWriter.GDT + "\n" +
                GDiffTextWriter.COPY + "1f4,a0\n" +
                GDiffTextWriter.DATA + "" + s.length() + "\n" + 
                s + "\n", sw.toString());
    }
    
}
