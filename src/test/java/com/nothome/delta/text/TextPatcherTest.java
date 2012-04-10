package com.nothome.delta.text;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.io.StringWriter;

import org.junit.Test;

public class TextPatcherTest {

    @Test
    public void testPatching() throws IOException {
        //          0123456
        String s = "abcdefg";
        StringWriter sw = new StringWriter();
        GDiffTextWriter w = new GDiffTextWriter(sw);
        w.addCopy(2, 2);
        w.addData('x');
        w.addData('y');
        w.addData('z');
        w.addData('z');
        w.addCopy(6, 1);
        w.addData('!');
        w.flush();
        TextPatcher patcher = new TextPatcher(s);
        String patch = patcher.patch(sw.toString());
        assertEquals("cdxyzzg!", patch);
    }
    
}
