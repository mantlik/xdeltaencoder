package com.nothome.delta.text;

import static org.junit.Assert.assertEquals;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;

import org.junit.Test;

public class DeltaTest {

    static Reader forFile(String name) throws FileNotFoundException {
        InputStreamReader isr = new InputStreamReader(DeltaTest.class.getResourceAsStream(name));
        return new BufferedReader(isr);
    }
    
    @Test
    public void testVer() throws IOException {
        test("/ver1.txt", "/ver2.txt");
        test("/ver2.txt", "/ver1.txt");
    }
    
    @Test
    public void testLorem() throws IOException {
        test("/lorem.txt", "/lorem2.txt");
        test("/lorem2.txt", "/lorem.txt");
    }
    
    @Test
    public void testLoremVer() throws IOException {
        test("/ver1.txt", "/lorem2.txt");
    }
    
    public void test(String v1, String v2) throws IOException {
        CharSequence string = Delta.toString(forFile(v1));
        CharSequence string2 = Delta.toString(forFile(v2));
        Delta d = new Delta();
        String delta = d.compute(string, string2);
        // System.err.println(delta);
        // System.err.println("----");
        String string3 = new TextPatcher(string).patch(delta);
        // System.out.println(string3);
        assertEquals(string2.toString(), string3);
    }
    
}
