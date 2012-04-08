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
package at.spardat.xma.xdelta;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import com.nothome.delta.GDiffPatcher;
import com.nothome.delta.PatchException;

/**
 * This class applys a zip file containing deltas created with {@link JarDelta} using
 * {@link com.nothome.delta.GDiffPatcher} on the files contained in the jar file.
 * The result of this operation is not binary equal to the original target zip file.
 * Timestamps of files and directories are not reconstructed. But the contents of all
 * files in the reconstructed target zip file are complely equal to their originals.
 *
 * @author s2877
 */
public class JarPatcher {
    /**
     * Applies the differences in patch to source to create the target file. All binary difference files
     * are applied to their corresponding file in source using {@link com.nothome.delta.GDiffPatcher}.
     * All other files listed in <code>META-INF/file.list</code> are copied from patch to output.
     *
     * @param source the original zip file, where the patches have to be applied
     * @param patch a zip file created by {@link JarDelta#computeDelta(ZipFile, ZipFile, ZipOutputStream)}
     *        containing the patches to apply
     * @param output the patched zip file to create
     * @throws IOException if an error occures reading or writing any entry in a zip file
     */
    public void applyDelta(ZipFile source, ZipFile patch, ZipOutputStream output) throws IOException {
        try {
            ZipEntry listEntry = patch.getEntry("META-INF/file.list");
            if(listEntry==null) {
                throw new FileNotFoundException("META-INF/file.list");
            }
            BufferedReader list = new BufferedReader(new InputStreamReader(patch.getInputStream(listEntry)));
            for(String fileName=list.readLine();fileName!=null;fileName=list.readLine()) {
                if("META-INF/file.list".equalsIgnoreCase(fileName)) continue;
                ZipEntry patchEntry = patch.getEntry(fileName);
                if(patchEntry!=null) { // new Entry
                    if(patchEntry.isDirectory()) {
                        ZipEntry outputEntry = new ZipEntry(patchEntry);
                        output.putNextEntry(outputEntry);
                        continue;
                    } else {
                        byte[] patchBytes = new byte[(int)patchEntry.getSize()];
                        InputStream patchStream = patch.getInputStream(patchEntry);
                        for(int erg=patchStream.read(patchBytes);erg<patchBytes.length;erg+=patchStream.read(patchBytes,erg,patchBytes.length-erg));
                        patchStream.close();
                        ZipEntry outputEntry = new ZipEntry(patchEntry);
                        output.putNextEntry(outputEntry);
                        output.write(patchBytes);
                    }
                } else {
                    ZipEntry sourceEntry = source.getEntry(fileName);
                    if(sourceEntry == null) {
                        throw new FileNotFoundException(fileName+" not found in "+source.getName()+" or "+patch.getName());
                    }
                    if(sourceEntry.isDirectory()) {
                        ZipEntry outputEntry = new ZipEntry(sourceEntry);
                        output.putNextEntry(outputEntry);
                        continue;
                    }
                    byte[] sourceBytes = new byte[(int)sourceEntry.getSize()];
                    InputStream sourceStream = source.getInputStream(sourceEntry);
                    for(int erg=sourceStream.read(sourceBytes);erg<sourceBytes.length;erg+=sourceStream.read(sourceBytes,erg,sourceBytes.length-erg));
                    sourceStream.close();

                    patchEntry = patch.getEntry(fileName+".gdiff");
                    if(patchEntry!=null) { // changed Entry
                        ZipEntry outputEntry = new ZipEntry(sourceEntry.getName());
                        outputEntry.setTime(patchEntry.getTime());
                        output.putNextEntry(outputEntry);
                        
                        InputStream patchStream = patch.getInputStream(patchEntry);
                        GDiffPatcher diffPatcher = new GDiffPatcher();
                        diffPatcher.patch(sourceBytes,patchStream,output);
                        patchStream.close();

                    } else { // unchanged Entry
                        ZipEntry outputEntry = new ZipEntry(sourceEntry);
                        output.putNextEntry(outputEntry);
                        output.write(sourceBytes);
                    }
                }
                output.closeEntry(); //nach jedem: output.putNextEntry() + output.write()
            }
            list.close();
        } catch (PatchException pe) {
            IOException ioe = new IOException();
            ioe.initCause(pe);
            throw ioe;
        } finally {
            source.close();
            patch.close();
            output.close();
        }
    }

    /**
     * Main method to make {@link #applyDelta(ZipFile, ZipFile, ZipOutputStream)} available at
     * the command line.<br>
     * usage JarPatcher source patch output
     */
    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            System.err.println("usage JarPatcher source patch output");
            return;
        }
        new JarPatcher().applyDelta(new ZipFile(args[0]),new ZipFile(args[1]),new ZipOutputStream(new FileOutputStream(args[2])));
    }
}
