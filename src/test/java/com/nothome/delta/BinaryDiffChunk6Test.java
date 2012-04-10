package com.nothome.delta;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.RandomAccessFile;

import org.junit.Test;

/**
 * From issue:
 * https://sourceforge.net/tracker/?func=detail&aid=2787868&group_id=54817&atid=474943 
 * 
 * "GDiffPatcher produces files different than the original,
 * using Delta with chunkSize of 6. The process is as follows (also check
 * attached testcase): files obj1.bin and obj2.bin (binary data). patch
 * created in memory and used to patch obj1.bin to create obj2.bin_patched.
 * comparing obj2.bin_patched with obj2.bin should be identical but it isnt...
 * 
 * looking for :obj1.bin
 * looking for :obj2.bin
 * b1 = 67903
 * b2 = 68050
 * patch = 17731
 * difference detected in position 66943
 * 
 * Seems that the GDiffPatcher (probably) chokes on some arbitrary combination
 * of data. See attachment for test case (to add in src/test/) and resources
 * that reproduce the problem. 
 */
public class BinaryDiffChunk6Test {

    public byte[] readFully(String fileName) throws Exception {
        System.out.println("looking for: " + fileName);
        RandomAccessFile raf = new RandomAccessFile(new File("target/test-classes/"
                + fileName),
                "r");
        byte[] data = new byte[(int) raf.length()];
        raf.readFully(data);
        raf.close();
        return data;
    }

    @Test
    public void testIt() throws Exception {
        String o1 = "obj1.bin";
        String o2 = "obj2.bin";

        byte[] b1 = readFully(o1);
        byte[] b2 = readFully(o2);
        System.out.println(" b1 = " + b1.length);
        System.out.println(" b2 = " + b2.length);
        Delta delta = new Delta();
        delta.setChunkSize(6);
        byte[] patch = delta.compute(b1, b2);
        System.out.println(" patch = " + patch.length);
        assertTrue(patch.length > 1);

        GDiffPatcher patcher = new GDiffPatcher();
        byte[] patched_b1 = patcher.patch(b1, patch);
        assertTrue("files should have same length", b2.length == patched_b1.length);
        assertTrue("patched_b1 content should be identical to content b2 ",
                compareOriginals(b2, patched_b1));
    }

    /**
     * @return true if identical
     */
    private boolean compareOriginals(byte[] b2, byte[] patched_b1) {
        for (int i = 0; i < b2.length; i++) {
            if (b2[i] != patched_b1[i]) {
                System.out.println("difference detected in position " + i);
                return false;
            }
        }
        return true;
    }

}
