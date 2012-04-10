package com.nothome.delta;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.junit.Test;

/**
 * Tests {@link GDiffWriter}.
 */
public class GDiffWriterTest {

    @Test
    public void testVer() throws IOException {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        GDiffWriter tw = new GDiffWriter(os);
        byte b[] = "abc".getBytes();
        tw.addData(b[0]);
        tw.addData(b[1]);
        tw.addCopy(0x1f4, 0xa0);
        tw.addData(b[0]);
        tw.addData(b[1]);
        tw.addData(b[2]);
        tw.close();
        byte[] ba = os.toByteArray();
        assertEquals((byte)0, ba[ba.length - 1]);
        assertEquals(5 + /*D*/1 + 2 + /*C*/4 + /*D*/1 + 3 + /*EOF*/1, os.toByteArray().length);
    }
    
}
