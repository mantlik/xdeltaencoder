/*
 * Copyright (c) 2003, 2007 s IT Solutions AT Spardat GmbH.
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
 *
 */
package at.spardat.xma.xdelta.test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import at.spardat.xma.xdelta.JarDelta;
import at.spardat.xma.xdelta.JarPatcher;

/**
 * This class tests JarDelta and JarPatcher with randomly generated zip files.
 *
 * @author S3460
 */
public class JarDeltaJarPatcherTest {

    private SecureRandom random;

    private int byteMaxLength = 1000;

    private int entryMaxSize = 10;

    private File sourceFile; //das originale File

    private File targetFile; //die neue Generation

    private File patchFile; //die Unterschiede

    private File resultFile; //das erechnete Result



    public JarDeltaJarPatcherTest() {
        try {
            random = SecureRandom.getInstance("SHA1PRNG");
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            throw new RuntimeException(e.getMessage());
        }

    }

    @Before
    public void setUp() throws Exception {
        sourceFile = File.createTempFile("JarDeltaJarPatcherTest_Source", ".zip");
        sourceFile.deleteOnExit();

        targetFile = File.createTempFile("JarDeltaJarPatcherTest_Target", ".zip");
        targetFile.deleteOnExit();

        patchFile = File.createTempFile("JarDeltaJarPatcherTest_Patch", ".zip");
        patchFile.deleteOnExit();

        resultFile = File.createTempFile("JarDeltaJarPatcherTest_Result", ".zip");
        resultFile.deleteOnExit();
    }

    @After
    public void tearDown() throws Exception {
        cleanUp();
    }

    /**
     *
     * @since version_number
     * @author S3460
     */
    private void cleanUp() {
        if (sourceFile != null) {
            sourceFile.delete();
        }
        if (targetFile != null) {
            targetFile.delete();
        }
        if (patchFile != null) {
            patchFile.delete();
        }
        if (resultFile != null) {
            resultFile.delete();
        }
    }

    /**
     * Creates a zip file with random content.
     * @author S3460
     */
    private ZipFile makeSourceZipFile(File source) throws Exception {

        ZipOutputStream out = new ZipOutputStream(new FileOutputStream(source));
        int size = randomSize(entryMaxSize);
        for (int i = 0; i < size; i++) {
            out.putNextEntry(new ZipEntry("zipentry" + i));
            int anz = randomSize(10);
            for (int j = 0; j < anz; j++) {
                byte[] bytes = getRandomBytes();
                out.write(bytes, 0, bytes.length);
            }

            out.closeEntry();
        }

        //add leeres Entry
        out.putNextEntry(new ZipEntry("zipentry" + size));
        out.closeEntry();

        out.close();

        return new ZipFile(source);
    }

    /**
     * Writes a modified version of zip_Source into target.
     * @author S3460
     */
    private ZipFile makeTargetZipFile(ZipFile zipSource, File target) throws Exception {

        ZipOutputStream out = new ZipOutputStream(new FileOutputStream(target));

        for (Enumeration enumer = zipSource.entries(); enumer.hasMoreElements();) {
            ZipEntry sourceEntry = (ZipEntry) enumer.nextElement();
            out.putNextEntry(new ZipEntry(sourceEntry.getName()));

            byte[] oldBytes = toBytes(zipSource, sourceEntry);
            byte[] newBytes = getRandomBytes();
            byte[] mixedBytes = mixBytes(oldBytes, newBytes);
            out.write(mixedBytes, 0, mixedBytes.length);
            out.closeEntry();
        }

        //zusatzlichen Entry schreiben
        out.putNextEntry(new ZipEntry("zipentry" + entryMaxSize+1));
        byte[] bytes = getRandomBytes();
        out.write(bytes, 0, bytes.length);

//      zusatzlichen leeres Entry schreiben
        out.putNextEntry(new ZipEntry("zipentry" + (entryMaxSize+2)));
        out.closeEntry();

        out.close();

        return new ZipFile(targetFile);
    }

    /**
     * Copies a modified version of oldBytes into newBytes by mixing some bytes.
     */
    private byte[] mixBytes(byte[] oldBytes, byte[] newBytes) {
        byte[] bytes = new byte[oldBytes.length + newBytes.length];

        if(oldBytes.length == 0){
            return newBytes;
        }
        if(newBytes.length == 0){
            return oldBytes;
        }

        System.arraycopy(oldBytes, 0, bytes, 0, oldBytes.length / 2);
        System.arraycopy(newBytes, 0, bytes, oldBytes.length / 2 - 1, newBytes.length / 2);
        System.arraycopy(oldBytes, oldBytes.length / 2, bytes, oldBytes.length / 2
                + newBytes.length / 2 - 1, oldBytes.length / 2);
        System.arraycopy(newBytes, newBytes.length / 2, bytes, oldBytes.length + newBytes.length
                / 2 - 1, newBytes.length / 2);

        //      System.arraycopy(oldBytes,0,bytes,0,oldBytes.length);
        //      System.arraycopy(newBytes,0,bytes,oldBytes.length-1,newBytes.length);

        //        int chunkSize = randomSize(10);
        //        for (int i = chunkSize; i > 0; i--) {
        //            System.arraycopy(oldBytes,oldBytes.length/i,bytes,0,oldBytes.length/chunkSize);
        //            System.arraycopy(newBytes,newBytes.length/i,bytes,oldBytes.length/chunkSize-1,newBytes.length/chunkSize);
        //        }

        return bytes;
    }

    /**
     * Converts the given zip entry to a byte array.
     * @author S3460
     */
    private byte[] toBytes(ZipFile zipfile, ZipEntry entry) throws Exception {
        int entrySize = (int) entry.getSize();
        byte[] bytes = new byte[entrySize];
        InputStream entryStream = zipfile.getInputStream(entry);
        for (int erg = entryStream.read(bytes); erg < bytes.length; erg += entryStream.read(bytes,
                erg, bytes.length - erg))
            ;

        return bytes;
    }

    /**
     * Returns a byte array of random length filled with random bytes.
     * @author S3460
     */
    private byte[] getRandomBytes() {
        int lengt = randomSize(byteMaxLength);
        byte[] bytes = new byte[lengt];
        random.nextBytes(bytes);
        return bytes;
    }

    /**
     * Returns a random integer <= maxSize
     * @author S3460
     */
    private int randomSize(int maxSize) {
        return ((int) (maxSize * random.nextFloat())) + 1;
    }

    /**
     * Compares the content of two zip files. The zip files are considered equal, if
     * the content of all zip entries is equal to the content of its corresponding entry
     * in the other zip file.
     * @author S3460
     */
    private void compareFiles(ZipFile zipSource, ZipFile resultZip) throws Exception {
        boolean rc = false;

        try {
            for (Enumeration enumer = zipSource.entries(); enumer.hasMoreElements();) {
                ZipEntry sourceEntry = (ZipEntry) enumer.nextElement();
                ZipEntry resultEntry = resultZip.getEntry(sourceEntry.getName());
                assertNotNull("Entry nicht generiert: " + sourceEntry.getName(),
                        resultEntry);
    
                byte[] oldBytes = toBytes(zipSource, sourceEntry);
                byte[] newBytes = toBytes(resultZip, resultEntry);
    
                rc = new JarDelta().equal(oldBytes, newBytes);
                assertTrue("bytes the same " + sourceEntry, rc);
            }
        } finally {
            zipSource.close();
            resultZip.close();
        }
    }

    /**
     * Uses JarDelta to create a patch file and tests if JarPatcher correctly reconstructs
     * newZip using this patch file.
     * @author S3460
     */
    private void runJarPatcher(ZipFile orginalZip, ZipFile newZip) throws Exception {

        new JarDelta().computeDelta(orginalZip, newZip, new ZipOutputStream(new FileOutputStream(
                patchFile)));

        new JarPatcher().applyDelta(new ZipFile(sourceFile), new ZipFile(patchFile),
                new ZipOutputStream(new FileOutputStream(resultFile)));

        compareFiles(new ZipFile(targetFile), new ZipFile(resultFile));
    }

    private void runJarPatcherDerivedFile() throws Exception {
        ZipFile orginalZip = makeSourceZipFile(sourceFile);
        ZipFile derivedZip = makeTargetZipFile(orginalZip, targetFile);

        runJarPatcher(orginalZip, derivedZip);
    }

    private void runJarPatcherCompleteDifferntFile() throws Exception {
        ZipFile orginalZip = makeSourceZipFile(sourceFile);
        ZipFile derivedZip = makeSourceZipFile(targetFile);
        runJarPatcher(orginalZip, derivedZip);
    }

    @Test
    public void testJarPatcherDerivedFile() throws Exception {
        //byteMaxLength = 10000;
        //entrySize = 10;
        runJarPatcherDerivedFile();
    }
    
    @Test
    public void testJarPatcherDerivedFileBig() throws Exception {
        byteMaxLength = 100000;
        entryMaxSize = 100;
        runJarPatcherDerivedFile();
    }

    @Ignore
    public void noTestJarPatcherDerivedFileVeryBig() throws Exception {
        byteMaxLength = 100000;
        entryMaxSize = 100;
        runJarPatcherDerivedFile();
        for (int i = 0; i < 100; i++) {
            //runJarPatcherDerivedFile();
        }
    }

    @Ignore
    public void noTestJarPatcherDerivedFileStressed() throws Exception {
        byteMaxLength = 100000;
        entryMaxSize = 1000;
        runJarPatcherDerivedFile();
    }

    @Test
    public void testJarPatcherCompleteDifferntFile() throws Exception {
        runJarPatcherCompleteDifferntFile();
    }

    @Test
    public void testJarPatcherCompleteDifferntFileBig() throws Exception {
        byteMaxLength = 10000;
        entryMaxSize = 100;
        runJarPatcherCompleteDifferntFile();
    }

    @Ignore
    public void noTestJarPatcherCompleteDifferntStressed() throws Exception {
        byteMaxLength = 100000;
        entryMaxSize = 1000;
        runJarPatcherCompleteDifferntFile();
    }


    /**
     * Tests JarDelta and JarPatcher on two identical files
     */
    @Test
    public void testJarPatcherIdentFile() throws Exception {

        ZipFile orginalZip = makeSourceZipFile(sourceFile);

        new JarDelta().computeDelta(orginalZip, orginalZip, new ZipOutputStream(new FileOutputStream(
                patchFile)));
        new JarPatcher().applyDelta(new ZipFile(sourceFile), new ZipFile(patchFile),
                new ZipOutputStream(new FileOutputStream(resultFile)));

        compareFiles(new ZipFile(sourceFile), new ZipFile(resultFile));
    }

    /**
     * Tests JarDelta and JarPatcher on two big identical files
     */
    @Ignore
    public void noTestJarPatcherIdentFileBig() throws Exception {
        byteMaxLength = 100000;
        entryMaxSize = 1000;
        testJarPatcherIdentFile();
    }

}
