/*
 * #%L
 * XDeltaEncoder
 * %%
 * Copyright (C) 2011 - 2012 Frantisek Mantlik <frantisek at mantlik.cz>
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the 
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public 
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */
package org.mantlik.xdeltaencoder;

import com.nothome.delta.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.sql.SQLException;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 *
 * @author fm
 */
public class XDeltaEncoder {

    static final int CHUNKSIZE = 5;
    static final int BLOCKSIZE = 33554432 * 4;
    static final int MAXTICKS = 6;
    static final int PREPARATION_CHUNK_FACTOR = 20;
    static final int PREPARATION_BLOCK_FACTOR = 2;
    private static File source = null;
    private static boolean randomDataSource = false; // test data
    private static long sourceLength = 0; // test data length
    private static long randomDataSeed = (new Random()).nextLong();
    private static File target = null;
    private static File delta = null;
    private static SeekableSource sStream = null;
    private static InputStream tStream = null;
    private static GDiffWriter dStream = null;
    private static Delta processor = new Delta();
    private static GDiffPatcher patcher = new GDiffPatcher();
    private static XDiffPatcher xpatcher = new XDiffPatcher();
    private static DecimalFormat df = new DecimalFormat("0.00");
    private static DecimalFormat df2 = new DecimalFormat("#0");
    private static SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
    private static long targetpos = 0;
    private static Runtime runtime = Runtime.getRuntime();
    private static ByteBuffer targetbuf;
    private static long sumpass = 0;
    private static int maxticks = MAXTICKS;
    private static boolean preparation_pass = false;
    private static boolean do_preparation_pass = false;
    private static boolean differential = false;
    private static boolean sourceInMemory = true;
    private static boolean multiFileDecode = false;
    private static boolean splitOutput = false;
    private static boolean mergeOutput = false;
    private static boolean splittedDelta = false;
    private static boolean nonGzippedDelta = false;
    private static boolean verify = false;
    private static boolean randomDataVerify = false;
    private static long verifyDataLength = 0;
    private static long verifyDataSeed = (new Random()).nextLong();
    private static int chunksize = CHUNKSIZE;
    private static long chunkFactor = 10;
    private static boolean xdiff = false;
    private static boolean useReverseDelta = false;
    private static File reverseDelta = null;
    private static boolean reverseDeltaOnly = false;
    private static boolean upgradeReverseDelta = false;
    private static boolean multiBuffer = false;
    private static File oldDeltaReference = null;
    private static CompareOutputStream compareStream;

    private static void encode(int blocksize) throws FileNotFoundException, IOException, SQLException, ClassNotFoundException {
        ByteBuffer sourcebuf = ByteBuffer.wrap(new byte[2 * blocksize]);
        targetbuf = ByteBuffer.wrap(new byte[blocksize]);
        long sourcepos = 0;
        long deltasize = 0;
        long totalsize = target.length();
        maxticks = MAXTICKS;

        sStream = new RandomAccessFileSeekableSource(new RandomAccessFile(source, "r"));
        tStream = new BufferedInputStream(new FileInputStream(target));
        dStream = new GDiffWriter(new DataOutputStream(new GZIPOutputStream(new FileOutputStream(delta))));
        targetbuf.rewind();
        int targetsize = tStream.read(targetbuf.array(), 0, blocksize);
        targetpos += targetsize;
        while (targetsize > 0) {
            long newSourcePos = find_source_pos(sourcepos);
            sourcepos = newSourcePos;
            sStream.seek(sourcepos);
            sourcebuf.rewind();
            int sourcesize = sStream.read(sourcebuf);
            byte[] ss = new byte[sourcesize];
            sourcebuf.rewind();
            sourcebuf.get(ss, 0, sourcesize);
            byte[] tt = new byte[targetsize];
            targetbuf.rewind();
            targetbuf.get(tt, 0, targetsize);
            long outputlen = -dStream.written();
            processor.compute(new ByteBufferSeekableSource(ss),
                    new ByteArrayInputStream(tt),
                    dStream, sourcepos, false);
            outputlen += dStream.written();
            sourcepos += targetsize;
            deltasize = deltasize + outputlen;
            System.out.println(df.format(100.00 * targetpos / totalsize) + "% block: "
                    + df.format(100.00 * (outputlen) / targetsize) + "% total: " + df.format(100.00 * deltasize / targetpos) + "% "
                    + "free mem: " + runtime.freeMemory());
            System.gc();
            if ((1.0d * (outputlen) / targetsize) > 0.95d) {
                maxticks += 1;
            } else {
                maxticks = Math.max(maxticks - 1, MAXTICKS);
            }
            targetbuf.rewind();
            targetsize = tStream.read(targetbuf.array(), 0, blocksize);
            targetpos += targetsize;
        }
        sStream.close();
        tStream.close();
        dStream.close();
    }

    /*
     * Simple encoder for the full file with the use of SQL database to store
     * hash table
     */
    private static void encode(boolean autoChunk) throws IOException {
        GDiffWriter dd = new GDiffWriter(new DataOutputStream(new GZIPOutputStream(new FileOutputStream(delta))));
        boolean oldProgress = processor.progress;
        processor.progress = true;
        if (!autoChunk) {
            processor.compute(source, target, dd);
        } else {
            boolean computed = false;
            while (!computed) {
                try {
                    processor.compute(source, target, dd);
                    computed = true;
                } catch (OutOfMemoryError ex) {
                    chunksize = (int) ((1.1d * source.length() / processor.getCheksumPos()) * chunksize);
                    System.out.println("Not enough memory. Chunk size changed to " + chunksize + ".");
                    processor.setChunkSize(chunksize);
                }
            }
        }
        processor.progress = oldProgress;
    }

    /*
     * Multi-pass encoder using virtual writer temporary file
     */
    private static void encodeVirtual(int blocksize) throws FileNotFoundException, IOException {
        File tempFile1 = new File("temp1.diff");
        File tempFile2 = new File("temp2.diff");
        File tempFile = new File("temp.data");
        /*
         * if (preparation_pass) { blocksize = blocksize *
         * PREPARATION_BLOCK_FACTOR;
         * processor.setChunkSize(processor.getChunkSize() *
         * PREPARATION_CHUNK_FACTOR); }
         */
        ByteBuffer sourcebuf = ByteBuffer.wrap(new byte[blocksize]);
        targetbuf = ByteBuffer.wrap(new byte[blocksize]);
        long sourcepos = 0;
        sStream = new RandomAccessFileSeekableSource(new RandomAccessFile(source, "r"));
        sourcebuf.rewind();
        int sourcesize = sStream.read(sourcebuf);
        sourcebuf.rewind();
        byte[] ss = new byte[sourcesize];
        sourcebuf.get(ss, 0, sourcesize);
        tStream = new BufferedInputStream(new FileInputStream(target));
        DiffWriter ddStream;
        if (source.length() < blocksize && (!preparation_pass)) {
            ddStream = new GDiffWriter(new DataOutputStream(new GZIPOutputStream(new FileOutputStream(delta))), false, differential);
        } else {
            ddStream = new VirtualWriter(new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tempFile1))));
        }
        // first pass - making virtual file
        System.out.println(" [" + sdf.format(new Date(System.currentTimeMillis())) + "]: Initial pass. This can take several minutes.");
        processor.progress = true;
        boolean computed = false;
        while (!computed) {
            try {
                processor.compute(new ByteBufferSeekableSource(ss), tStream, ddStream, 0, true);
                computed = true;
            } catch (OutOfMemoryError ex) {
                chunksize = (int) ((1.1d * ss.length / processor.getCheksumPos()) * chunksize);
                System.out.println("Not enough memory. Chunk size changed to " + chunksize + ".");
                processor.setChunkSize(chunksize);
            }
        }
        long totalLength;
        if (ddStream.getClass().equals(VirtualWriter.class)) {
            totalLength = ((VirtualWriter) ddStream).totalLength;
        } else {
            totalLength = ((GDiffWriter) ddStream).totalLength;
        }
        if (totalLength != target.length()) {
            System.out.println("Target length mismatch.");
            System.out.println("Total output length = " + totalLength + " target length = " + target.length());
            return;
        }
        tStream.close();
        processor.progress = false;
        sourcepos += sourcesize;
        // processing passes
        int pass = 1;
        long fits = 0;
        long missed = 0;
        long totmem = Runtime.getRuntime().maxMemory();
        long curmem;
        long availmem;
        int freemem;
        processor.setKeepSource(true);
        while (preparation_pass || sourcepos < source.length()) {
            if (preparation_pass) {
                System.out.print("Preparation ");
            }
            availmem = Runtime.getRuntime().freeMemory();
            curmem = Runtime.getRuntime().totalMemory();
            freemem = (int) (100d * (availmem + totmem - curmem) / totmem);
            System.out.println("Pass " + pass + " [" + sdf.format(new Date(System.currentTimeMillis())) + "]: " + df.format(100.0d * sourcepos / source.length())
                    + " % done, found " + (fits + processor.found) / 1024 / 1024 + " mb " + freemem + " % free mem.");
            System.gc();
            processor.clearSource();
            pass++;
            fits = 0;
            processor.found = 0;
            sourcebuf.rewind();
            sourcesize = sStream.read(sourcebuf);
            sourcebuf.rewind();
            tStream = new BufferedInputStream(new FileInputStream(target));
            ss = new byte[sourcesize];
            sourcebuf.get(ss);
            SeekableSource sourceData = new ByteBufferSeekableSource(ss);
            if ((!preparation_pass) && (sourcepos + sourcesize) >= source.length()) {
                ddStream = new GDiffWriter(new DataOutputStream(new GZIPOutputStream(new FileOutputStream(delta))), false, differential);
            } else {
                ddStream = new VirtualWriter(new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tempFile2))));
            }
            DataInputStream vinp = new DataInputStream(new BufferedInputStream(new FileInputStream(tempFile1)));
            byte op = vinp.readByte();
            int j = 0;
            int nextj = 1000 + j;
            long done = 0;
            while (op != 3) {
                if (op == 1) {  // copy pass through
                    long offs = vinp.readLong();
                    int length = vinp.readInt();
                    //System.out.println("Copy " + length + " bytes from " + offs);
                    ddStream.addCopy(offs, length);
                    int skipped = 0;
                    while (skipped < length) {
                        skipped += tStream.skip(length - skipped);
                    }
                    fits += length;
                    done += length;
                } else if (op == 2) {
                    int len = vinp.readInt();
                    done += len;
                    if ((len < processor.getChunkSize())
                            || /*
                             * (preparation_pass && ((100.00d * processor.found
                             * / blocksize > 90) || (1.0d * done > missed + 1.0d
                             * * (pass + 1) * blocksize * target.length() /
                             * source.length())))
                             */ (preparation_pass && (len < (10 * processor.getChunkSize())))) {
                        //System.out.println("Passthrough " + len + " bytes");
                        for (int i = 0; i < len; i++) {
                            ddStream.addData((byte) (tStream.read()));
                        }
                    } else {
                        if (len > blocksize) { // copy to temp file
                            //System.out.println("Analyze " + len + " bytes (tempfile)");
                            OutputStream os = new BufferedOutputStream(new FileOutputStream(tempFile));
                            int remaining = len;
                            int datalen = 0;
                            while (remaining > 0 && datalen >= 0) {
                                targetbuf.rewind();
                                datalen = tStream.read(targetbuf.array(), 0, Math.min(targetbuf.capacity(), remaining));
                                if (datalen > 0) {
                                    targetbuf.rewind();
                                    byte[] tt = new byte[datalen];
                                    targetbuf.get(tt);
                                    os.write(tt);
                                    remaining -= datalen;
                                }
                            }
                            os.close();
                            processor.compute(sourceData, new BufferedInputStream(new FileInputStream(tempFile)),
                                    ddStream, sourcepos, false);
                            j = nextj;
                        } else {  // byte buffer
                            //System.out.println("Analyze " + len + " bytes (buffer)");
                            targetbuf.rewind();
                            int datalen = tStream.read(targetbuf.array(), 0, Math.min(targetbuf.capacity(), len));
                            targetbuf.rewind();
                            byte[] tt = new byte[datalen];
                            targetbuf.get(tt);
                            processor.compute(sourceData, new ByteArrayInputStream(tt), ddStream, sourcepos, false);
                        }
                    }
                }
                op = vinp.readByte();
                j++;
                if (j >= nextj) {
                    nextj += 1000;
                    if (preparation_pass) {
                        /*
                         * if ((100.00d * processor.found / blocksize > 90) ||
                         * (1.0d * done > missed + 1.0d * (pass + 1) * blocksize
                         * * target.length() / source.length())) {
                         * System.out.print("Preparation*"); } else {
                         */
                        System.out.print("Preparation ");
                        //}
                    }
                    System.out.print("Pass " + pass + " progress: " + df.format(100.00 * done / target.length())
                            + " %, so far fitted " + processor.found / 1024 / 1024 + " m\b\r");
                }
            }
            vinp.close();
            ddStream.close();
            if (ddStream.getClass().equals(VirtualWriter.class)) {
                totalLength = ((VirtualWriter) ddStream).totalLength;
            } else {
                totalLength = ((GDiffWriter) ddStream).totalLength;
            }
            if (totalLength != target.length()) {
                System.out.println("Target length mismatch.");
                System.out.println("Total output length = " + totalLength + " target length = " + target.length());
                return;
            }
            sourcepos += sourcesize;
            File file = tempFile1;
            tempFile1 = tempFile2;
            tempFile2 = file;
            if (preparation_pass && (sourcepos >= source.length())) {
                // switch to normal pass
                preparation_pass = false;
                sourcepos = 0;
                sStream.seek(0);
                //blocksize = blocksize / PREPARATION_BLOCK_FACTOR;
                //processor.setChunkSize(processor.getChunkSize() / PREPARATION_CHUNK_FACTOR);
                //sourcebuf = ByteBuffer.wrap(new byte[blocksize]);
                //targetbuf = ByteBuffer.wrap(new byte[blocksize]);
            }
        }
        System.out.println("Pass " + pass + " [" + sdf.format(new Date(System.currentTimeMillis())) + "]: " + df.format(100.0d * sourcepos / source.length())
                + " % done, found " + (fits + processor.found) / 1024 / 1024 + " mb.");
        System.out.println("Offsets; long = " + ((GDiffWriter) ddStream).lo
                + " medium = " + ((GDiffWriter) ddStream).io + " short = " + ((GDiffWriter) ddStream).so + " byte = " + ((GDiffWriter) ddStream).bo);
        System.out.println("Final compression ratio: " + df.format(100.00d * delta.length() / target.length()) + " %");
        missed = Math.max(blocksize * pass - fits - processor.found, 0);
        if (tempFile1.exists()) {
            tempFile1.delete();
        }
        if (tempFile2.exists()) {
            tempFile2.delete();
        }
        if (tempFile.exists()) {
            tempFile.delete();
        }
    }

    /*
     * Multi-pass encoder using virtual writer temporary file and blocks in
     * files
     */
    private static void encodeVirtualFile(long blocksize) throws FileNotFoundException, IOException {
        File tempFile1 = File.createTempFile("temp1-", ".vdiff", new File("."));
        File tempFile2 = File.createTempFile("temp2-", ".vdiff", new File("."));
        File tempFile3 = null;
        if (do_preparation_pass) {
            tempFile3 = File.createTempFile("temp3-", ".vdiff", new File("."));
            tempFile3.deleteOnExit();
        }
        tempFile1.deleteOnExit();
        tempFile2.deleteOnExit();
        ByteBuffer bb = null;
        preparation_pass = do_preparation_pass && (sourceLength > blocksize);
        boolean origSourceInMemory = sourceInMemory;
        long origBlocksize = blocksize;
        if (preparation_pass) {
            blocksize = sourceLength;
            sourceInMemory = false;
            processor.firstMatch = true;
        }
        //System.out.println("Blocksize = "+blocksize);
        processor.clearSource();
        processor.setKeepSource(false);
        //processor.setChunkSize(chunksize);        
        processor.found = 0;
        long sourcepos = 0;
        SeekableSource asource = null;
        SeekableSource bsource = null;
        long filteredData;

        DiffWriter ddStream;
        // first pass - making virtual file
        System.out.println(" [" + sdf.format(new Date(System.currentTimeMillis())) + "]: Initial pass. This can take several minutes.");
        processor.progress = true;
        boolean computed = false;
        long sourcesize = 0;
        if (!sourceInMemory) {
            if (randomDataSource) {
                asource = new RandomDataSeekableSource(randomDataSeed, sourceLength);
            } else if (preparation_pass || multiBuffer) {
                asource = new MultiBufferSeekableSource(new RandomAccessFile(source, "r"), 100 * 1024, 500);
            } else {
                asource = new RandomAccessFileSeekableSource(new RandomAccessFile(source, "r"), 0, blocksize);
            }
            sourcesize = sourceLength;
        }
        if (sourceInMemory) {
            while (bb == null) {
                try {
                    if (bb == null) {
                        bb = ByteBuffer.wrap(new byte[(int) blocksize]);
                    }
                    bb.clear();
                    if (randomDataSource) {
                        SeekableSource ss = new RandomDataSeekableSource(randomDataSeed, sourceLength);
                        ss.seek(0);
                        sourcesize = ss.read(bb);
                    } else {
                        RandomAccessFile raf = new RandomAccessFile(source, "r");
                        raf.seek(0);
                        sourcesize = 0;
                        int i = 0;
                        while ((i >= 0) && (sourcesize < blocksize)) {
                            i = raf.read(bb.array(), (int) sourcesize, (int) (blocksize - sourcesize));
                            if (i > 0) {
                                sourcesize += i;
                            }
                            //System.out.println("Reading source " + (sourcesize / 1024 / 1024) + " mb     \r");
                        }
                        raf.close();
                    }
                    bb.limit((int) sourcesize);
                    bb.rewind();
                    bsource = new ByteBufferSeekableSource(bb);
                } catch (OutOfMemoryError e) {
                    bb = null;
                    System.gc();
                    blocksize -= blocksize / 4;
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ex) {
                    }
                    System.out.println("Not enough memory. Block size changed to " + blocksize + ".");
                }
            }
        }
        if ((!preparation_pass) && (source.length() <= blocksize)) {
            if (xdiff) {
                ddStream = new XDiffWriter(new DataOutputStream(new GZIPOutputStream(new FileOutputStream(delta))));
            } else {
                ddStream = new GDiffWriter(new DataOutputStream(new GZIPOutputStream(new FileOutputStream(delta))), false, differential);
            }
        } else {
            ddStream = new VirtualWriter(new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tempFile1), 1024 * 1024)));
            //if (preparation_pass) {
            //    ((VirtualWriter) ddStream).filterFactor = 10 * chunksize;
            //}
        }
        Runtime.getRuntime().gc();
        long freeMemory = Runtime.getRuntime().maxMemory() - Runtime.getRuntime().freeMemory()
                + Runtime.getRuntime().totalMemory();
        chunksize = Math.max((int) (blocksize * chunkFactor / freeMemory), CHUNKSIZE);

        if (preparation_pass) {
            chunksize = 5 * chunksize + 3000;
            processor.acceptHash = true;
            processor.duplicateChecksum = true;
        }

        processor.setChunkSize(chunksize);
        System.out.println("Chunk size changed to " + chunksize + ".");
        InputStream is = new BufferedInputStream(new FileInputStream(target), 1024 * 1024 * 32);
        processor.targetsize = target.length();
        while (!computed) {
            try {
                if (sourceInMemory) {
                    processor.compute(bsource, is, ddStream, 0, true);
                } else {
                    processor.compute(asource, is, ddStream, 0, true);
                }
                computed = true;
            } catch (OutOfMemoryError ex) {
                chunksize = 1 + (int) ((1.2d * sourcesize / processor.getCheksumPos()) * chunksize);
                chunkFactor = (int) (1.2d * chunkFactor * sourcesize / processor.getCheksumPos());
                System.out.println("Not enough memory. Chunk size changed to " + chunksize + ".");
                processor.setChunkSize(chunksize);
            }
        }
        long totalLength;
        if (ddStream.getClass().equals(VirtualWriter.class)) {
            totalLength = ((VirtualWriter) ddStream).totalLength;
        } else {
            totalLength = ((GDiffWriter) ddStream).totalLength;
        }
        if (totalLength != target.length()) {
            System.out.println("Target length mismatch.");
            System.out.println("Total output length = " + totalLength + " target length = " + target.length());
            return;
        }
        processor.progress = false;
        sourcepos += sourcesize;
        // processing passes
        int pass = 1;
        long fits = 0;
        long missed = 0;
        long totmem = Runtime.getRuntime().maxMemory();
        long curmem;
        long availmem;
        int freemem;
        boolean interrupted = false;
        processor.setKeepSource(true);
        long lastdisptime = System.currentTimeMillis();
        while (preparation_pass || sourcepos < source.length()) {
            filteredData = 0;
            if (preparation_pass) {
                if (!interrupted) {
                    System.out.print("Preparation ");
                }
                filteredData = ((VirtualWriter) ddStream).filteredData;
            }
            LimitInputStream ttStream = new LimitInputStream(new BufferedInputStream(new FileInputStream(target)));
            availmem = Runtime.getRuntime().freeMemory();
            curmem = Runtime.getRuntime().totalMemory();
            freemem = (int) (100d * (availmem + totmem - curmem) / totmem);
            if (!interrupted) {
                System.out.println("Pass " + pass + " [" + sdf.format(new Date(System.currentTimeMillis())) + "]: " + df.format(100.0d * sourcepos / source.length())
                        + " % done, found " + df.format((fits + processor.found - filteredData) / 1024d / 1024d) + " mb " + freemem + " % free mem.");
            }
            System.gc();
            if (preparation_pass && (sourcepos >= source.length())) {
                // switch to normal pass
                preparation_pass = false;
                processor.acceptHash = false;
                processor.duplicateChecksum = false;

                sourcepos = 0;
                if (sourceInMemory) {
                    bsource.seek(0);
                } else {
                    asource.seek(0);
                }
                sourceInMemory = origSourceInMemory;
                blocksize = origBlocksize;
                bsource = null;
                asource = null;
                bb = null;
                System.gc();
                chunkFactor = 10;
                chunksize = Math.max((int) (blocksize * chunkFactor / freeMemory), CHUNKSIZE);
                processor.setChunkSize(chunksize);
                System.out.println("Chunk size changed to " + chunksize + ".                                ");
            }
            processor.clearSource();
            pass++;
            fits = 0;
            if (!sourceInMemory) {
                if (randomDataSource) {
                    asource = new RandomDataSeekableSource(randomDataSeed, sourceLength);
                } else {
                    asource = new RandomAccessFileSeekableSource(new RandomAccessFile(source, "r"), 0, blocksize);
                }
                sourcesize = sourceLength;
            }
            boolean read = false;
            if (sourceInMemory) {
                while (!read) {
                    try {
                        if (bb == null) {
                            bb = ByteBuffer.wrap(new byte[(int) blocksize]);
                        }
                        bb.clear();
                        if (randomDataSource) {
                            SeekableSource ss = new RandomDataSeekableSource(randomDataSeed, sourceLength);
                            ss.seek(sourcepos);
                            sourcesize = ss.read(bb);
                        } else {
                            RandomAccessFile raf = new RandomAccessFile(source, "r");
                            raf.seek(sourcepos);
                            sourcesize = 0;
                            int i = 0;
                            while ((i >= 0) && (sourcesize < blocksize)) {
                                i = raf.read(bb.array(), (int) sourcesize, (int) (blocksize - sourcesize));
                                if (i > 0) {
                                    sourcesize += i;
                                }
                                //System.out.println("Reading source " + (sourcesize / 1024 / 1024) + " mb     \r");
                            }
                            raf.close();
                        }
                        bb.limit((int) sourcesize);
                        bb.rewind();
                        bsource = new ByteBufferSeekableSource(bb);

                        read = true;
                    } catch (OutOfMemoryError e) {
                        bb = null;
                        System.gc();
                        blocksize -= blocksize / 4;
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ex) {
                        }
                        System.out.println("Not enough memory. Block size changed to " + blocksize + ".");
                    }
                }
            }
            processor.found = 0;
            if ((!preparation_pass) && (sourcepos + sourcesize) >= source.length()) {
                if (xdiff) {
                    ddStream = new XDiffWriter(new DataOutputStream(new GZIPOutputStream(new FileOutputStream(delta))));
                } else {
                    ddStream = new GDiffWriter(new DataOutputStream(new GZIPOutputStream(new FileOutputStream(delta))), false, differential);
                }
            } else {
                ddStream = new VirtualWriter(new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tempFile2))));
                //if (preparation_pass) {
                //    ((VirtualWriter) ddStream).filterFactor = 2 * chunksize;
                //}
            }
            DataInputStream vinp = new DataInputStream(new BufferedInputStream(new FileInputStream(tempFile1)));
            int length = 0;
            long offs = 0;
            int chs = processor.getChunkSize();
            if (do_preparation_pass) {
                // disable found hashes for current block
                System.out.print("Preprocessing block delta...                               \r");
                DiffWriter ddStream2 = new VirtualWriter(new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tempFile3))));
                byte op = vinp.readByte();
                while (op != 3) {
                    if (op == 1) {  // copy pass through
                        offs = vinp.readLong();
                        length = vinp.readInt();
                        if (do_preparation_pass && (offs >= sourcepos && offs < (sourcepos + sourcesize))) {
                            op = 4;
                        } else {
                            ddStream2.addCopy(offs, length);
                        }
                    }
                    if ((op == 2) || (op == 4)) {
                        if (op == 2) {
                            length = vinp.readInt();
                        }
                        //System.out.println("Passthrough " + len + " bytes");
                        for (int i = 0; i < length; i++) {
                            ddStream2.addData((byte) (0));  // real data are not important for virtual writer
                        }

                    }
                    op = vinp.readByte();
                }
                ddStream2.close();
                vinp.close();
                vinp = new DataInputStream(new BufferedInputStream(new FileInputStream(tempFile3)));
            }
            byte op = vinp.readByte();
            long done = 0;
            interrupted = false;
            length = 0;
            offs = 0;
            while ((op != 3) && !interrupted) {
                if (op == 1) {  // copy pass through
                    offs = vinp.readLong();
                    length = vinp.readInt();
                    //System.out.println("Copy " + length + " bytes from " + offs);
                    ddStream.addCopy(offs, length);
                    int skipped = 0;
                    while (skipped < length) {
                        skipped += ttStream.skip(length - skipped);
                    }
                    fits += length;
                    done += length;
                }
                if ((op == 2)) {
                    length = vinp.readInt();
                    done += length;
                    if (length <= chs) {
                        //System.out.println("Passthrough " + len + " bytes");
                        for (int i = 0; i < length; i++) {
                            ddStream.addData((byte) (ttStream.read()));
                        }
                    } else {
                        ttStream.setLimit(length);
                        computed = false;
                        try {
                            if (sourceInMemory) {
                                processor.compute(bsource, ttStream, ddStream, sourcepos, false);
                            } else {
                                processor.compute(asource, ttStream, ddStream, sourcepos, false);
                            }
                            computed = true;
                        } catch (OutOfMemoryError ex) {
                            chunksize = 1 + (int) ((1.2d * sourcesize / processor.getCheksumPos()) * chunksize);
                            chunkFactor = (int) (1.2d * chunkFactor * sourcesize / processor.getCheksumPos());
                            System.out.println("Not enough memory. Chunk size changed to " + chunksize + ".");
                            processor.setChunkSize(chunksize);
                            interrupted = true;
                            pass--;
                        }
                        ttStream.setLimit(-1);
                    }
                }
                if (interrupted) {
                    continue;
                }
                if (ddStream.getClass().equals(VirtualWriter.class)) {
                    totalLength = ((VirtualWriter) ddStream).totalLength;
                } else {
                    totalLength = ((GDiffWriter) ddStream).totalLength;
                }
                if (done != totalLength) {
                    System.out.println("Target length mismatch expected = " + done + " current = " + totalLength);
                    System.out.print("Last operation: " + op + " length = " + length);
                    if (op == 1) {
                        System.out.println(" offset = " + offs);
                    } else {
                        System.out.println();
                    }
                    return;
                }
                op = vinp.readByte();
                if ((System.currentTimeMillis() - lastdisptime) > 1000) {
                    lastdisptime = System.currentTimeMillis();
                    filteredData = 0;
                    if (preparation_pass) {
                        System.out.print("Preparation ");
                        filteredData = ((VirtualWriter) ddStream).filteredData;
                    }
                    System.out.print("Pass " + pass + " progress: " + df.format(100.00 * done / target.length())
                            + " %, so far fitted " + df.format((processor.found - filteredData) / 1024d / 1024d) + " mb\b\r");
                }
            }

            vinp.close();

            ddStream.close();

            ttStream.close();
            if (!interrupted) {
                if (ddStream.getClass().equals(VirtualWriter.class)) {
                    totalLength = ((VirtualWriter) ddStream).totalLength;
                } else {
                    totalLength = ((GDiffWriter) ddStream).totalLength;
                }

                if (totalLength != target.length()) {
                    System.out.println("Target length mismatch.");
                    System.out.println("Total output length = " + totalLength + " target length = " + target.length());
                    return;
                }
                sourcepos += sourcesize;
                File file = tempFile1;
                tempFile1 = tempFile2;
                tempFile2 = file;
            }
        }
        System.out.println(
                "Pass " + pass + " [" + sdf.format(new Date(System.currentTimeMillis())) + "]: " + df.format(100.0d * sourcepos / source.length())
                + " % done, found " + df.format((fits + processor.found) / 1024d / 1024d) + " mb.");
        /*
         * if (xdiff) { long addrlen = 0; for (int i = 0; i < ((XDiffWriter)
         * ddStream).addrlen.length; i++) { addrlen += ((XDiffWriter)
         * ddStream).addrlen[i]; } addrlen = addrlen / 1024 / 1024;
         * System.out.println("Instructions = " + ((XDiffWriter)
         * ddStream).instrLen / 1024 / 1024 + " mb data = " + ((XDiffWriter)
         * ddStream).dataLen / 1024 / 1024 + " mb addresses = " + addrlen + "
         * mb"); } else { System.out.println("Offsets; long = " + ((GDiffWriter)
         * ddStream).lo + " medium = " + ((GDiffWriter) ddStream).io + " short =
         * " + ((GDiffWriter) ddStream).so + " byte = " + ((GDiffWriter)
         * ddStream).bo); }
         */

        System.out.print("Delta file size: " + delta.length());
        System.out.println(
                "   Final compression ratio: " + df.format(100.00d * delta.length() / target.length()) + " %");
        missed = Math.max(blocksize * pass - fits - processor.found, 0);
        //tStream.close();

        if (tempFile1.exists()) {
            tempFile1.delete();
        }

        if (tempFile2.exists()) {
            tempFile2.delete();
        }

        if (do_preparation_pass && tempFile3.exists()) {
            tempFile3.delete();
        }
    }

    /*
     * Test for optimal block size on sample of target file
     */
    private static long testBlockSize() throws FileNotFoundException, IOException {
        final int MIN_CHANGE = 40 * 1024 * 1024;
        final int MIN_BLOCKSIZE = 128 * 1024 * 1024;
        final int MAX_BLOCKSIZE = 1024 * 1024 * 1024;
        long blocksize;
        long bs[] = new long[3];
        long fs[] = new long[3];
        long lastblocksize;
        File origtarget = target;
        target = new File("testTarget.data");
        HashMap<Long, Long> map = new HashMap<Long, Long>();

        // make sample file
        RandomAccessFile in = new RandomAccessFile(origtarget, "r");
        OutputStream out = new BufferedOutputStream(new FileOutputStream(target));
        long n;
        int b = 0;
        long sourcesize = sourceLength;
        double factor = 0.005;
        double skipfactor = 0.05 * sourceLength / 1024 / 1024 / 1024;
        long processed = 0;
        while (b >= 0) {
            n = (long) (Math.random() * factor * origtarget.length());
            while (n > 0) {
                b = in.read();
                processed++;
                if (b < 0) {
                    break;
                }
                out.write(b);
                n--;
            }
            n = (long) (Math.random() * skipfactor * origtarget.length());
            in.seek(in.getFilePointer() + n);
            processed += n;
            System.out.print("Preparing test data " + 100 * processed / sourcesize + " %\b\r");
        }
        in.close();
        out.close();

        // compute starting guesses for 400m, 600m and 800m
        chunksize = CHUNKSIZE;
        blocksize = 384 * 1024 * 1024;
        encodeVirtualFile(blocksize);
        bs[0] = blocksize;
        fs[0] = delta.length();
        chunksize = CHUNKSIZE;
        blocksize = 512 * 1024 * 1024;
        encodeVirtualFile(blocksize);
        if (delta.length() < fs[0]) {
            bs[1] = blocksize;
            fs[1] = delta.length();
            lastblocksize = blocksize;
            chunksize = CHUNKSIZE;
            blocksize = 640 * 1024 * 1024;
            encodeVirtualFile(blocksize);
            bs[2] = blocksize;
            fs[2] = delta.length();
        } else {
            bs[1] = bs[0];
            fs[1] = fs[0];
            bs[2] = blocksize;
            fs[2] = delta.length();
            lastblocksize = blocksize;
            chunksize = CHUNKSIZE;
            blocksize = 128 * 1024 * 1024;
            encodeVirtualFile(blocksize);
            bs[0] = blocksize;
            fs[0] = delta.length();
        }
        int newindex;
        int pass = 3;
        long deltalen;

        while (true) {
            if (fs[0] < fs[1] && fs[0] < fs[2] && bs[0] > MIN_BLOCKSIZE) {
                blocksize = 2 * bs[0] - bs[1];
                newindex = 0;
            } else if (fs[2] < fs[1] && fs[2] < fs[0] && bs[2] < MAX_BLOCKSIZE) {
                blocksize = 2 * bs[2] - bs[1];
                newindex = 3;
            } else if (fs[0] < fs[2]) {
                blocksize = (bs[0] + bs[1]) / 2;
                newindex = 1;
            } else {
                blocksize = (bs[1] + bs[2]) / 2;
                newindex = 2;
            }
            if (blocksize <= MIN_BLOCKSIZE || blocksize >= MAX_BLOCKSIZE) {
                blocksize = Math.min(Math.max(blocksize, MIN_BLOCKSIZE), MAX_BLOCKSIZE);
            }
            System.out.println("Test pass " + pass + " results: " + (int) (bs[0] / 1024 / 1024) + " mb => " + fs[0] + " "
                    + (int) (bs[1] / 1024 / 1024) + " mb => " + fs[1] + " "
                    + (int) (bs[2] / 1024 / 1024) + " mb => " + fs[2]);
            if (Math.abs(lastblocksize - blocksize) < MIN_CHANGE) {
                break;
            }
            pass++;
            System.out.println("New blocksize: " + (int) (blocksize / 1024 / 1024) + " mb");
            chunksize = CHUNKSIZE;
            lastblocksize = blocksize;
            if (map.containsKey(blocksize)) {
                deltalen = map.get(blocksize);
                System.err.println("Blocksize " + (int) (blocksize / 1024 / 1024) + " mb computed in previous iterations.");
            } else {
                encodeVirtualFile(blocksize);
                deltalen = delta.length();
                map.put(blocksize, deltalen);
            }
            if (newindex == 0) {
                bs[2] = bs[1];
                fs[2] = fs[1];
                bs[1] = bs[0];
                fs[1] = fs[0];
                bs[0] = blocksize;
                fs[0] = deltalen;
            } else if (newindex == 1) {
                bs[2] = bs[1];
                fs[2] = fs[1];
                bs[1] = blocksize;
                fs[1] = deltalen;
            } else if (newindex == 2) {
                bs[0] = bs[1];
                fs[0] = fs[1];
                bs[1] = blocksize;
                fs[1] = deltalen;
            } else {
                bs[0] = bs[1];
                fs[0] = fs[1];
                bs[1] = bs[2];
                fs[1] = fs[2];
                bs[2] = blocksize;
                fs[2] = deltalen;
            }
        }
        if (fs[0] < fs[1] && fs[0] < fs[2]) {
            blocksize = bs[0];
        } else if (fs[2] < fs[0] && fs[2] < fs[1]) {
            blocksize = bs[2];
        } else {
            blocksize = bs[1];
        }
        System.out.println("Test finished. Best blocksize: " + (int) (blocksize / 1024 / 1024) + " mb");
        if (target.exists()) {
            target.delete();
            target.deleteOnExit();
        }
        target = origtarget;
        chunksize = CHUNKSIZE;
        return blocksize;
    }

    private static void createReverseDelta(long blocksize) throws IOException {
        // unpack reference
        System.out.println("Unpacking reference delta " + target);
        InputStream in = new BufferedInputStream(new GZIPInputStream(new TargetInputStream(target, 1024 * 1024, null)));
        File referenceFile = File.createTempFile("reference-", ".delta", new File("."));
        referenceFile.deleteOnExit();
        OutputStream out = new BufferedOutputStream(new FileOutputStream(referenceFile));
        int b = 0;
        byte[] buffer = new byte[10000];
        while (b >= 0) {
            b = in.read(buffer);
            if (b > 0) {
                out.write(buffer, 0, b);
            }
        }
        in.close();
        out.close();
        // unpack source
        if (upgradeReverseDelta) {
            System.out.println("\rCreating old delta from " + source + " and reference " + oldDeltaReference);
            in = makeReverseDelta(oldDeltaReference, source);
        } else {
            System.out.println("\rUnpacking first delta " + source);
            in = new BufferedInputStream(new GZIPInputStream(new TargetInputStream(source, 1024 * 1024, null)));
        }
        File sourceFile = File.createTempFile("first-", ".delta", new File("."));
        sourceFile.deleteOnExit();
        out = new BufferedOutputStream(new FileOutputStream(sourceFile));
        b = 0;
        while (b >= 0) {
            b = in.read(buffer);
            if (b > 0) {
                out.write(buffer, 0, b);
            }
        }
        in.close();
        out.close();
        // merge source+reference to reverse
        System.out.println("\rMerging first + reference to full delta");
        File reverseFile = File.createTempFile("reverse-", ".delta", new File("."));
        reverseFile.deleteOnExit();
        try {
            SeekableSource s;
            if (multiBuffer) {
                s = new MultiBufferSeekableSource(new RandomAccessFile(sourceFile, "r"), 100 * 1024, 500);
            } else {
                s = new RandomAccessFileSeekableSource(new RandomAccessFile(sourceFile, "r"));
            }
            InputStream d = new BufferedInputStream(new TargetInputStream(referenceFile, 1024 * 1024, null), 100000);
            DiffWriter tt = new GDiffWriter(new DataOutputStream(new BufferedOutputStream(new FileOutputStream(reverseFile))));
            new GDiffMerger(tt).patch(s, d, null);
            s.close();
            d.close();
            tt.close();
        } catch (IOException ex) {
            Logger.getLogger(XDeltaEncoder.class.getName()).log(Level.SEVERE, null, ex);
        }
        // create reverse delta from reference file and reverse file
        System.out.println("\rComputing reverse delta " + reverseDelta);
        source = referenceFile;
        target = reverseFile;
        delta = reverseDelta;
        encodeVirtualFile(blocksize);
    }

    private static void decode() throws IOException {
        SeekableSource ss = null;
        if (!reverseDeltaOnly) {
            if (multiFileDecode) {
                File dir = source.getParentFile();
                String prefix = source.getName();
                ss = new MultifileSeekableSource(dir, prefix);
            } else if (randomDataSource) {
                ss = new RandomDataSeekableSource(randomDataSeed, sourceLength);
            } else if (multiBuffer) {
                ss = new MultiBufferSeekableSource(new RandomAccessFile(source, "r"), 100 * 1024, 500);
            } else {
                ss = new RandomAccessFileSeekableSource(new RandomAccessFile(source, "r"));
            }
        }
        InputStream dd;
        if (splittedDelta) {
            dd = new SplitInputStream(delta.getParentFile(), delta.getName(), 1024 * 1024, patcher);
        } else if (useReverseDelta) {
            dd = makeReverseDelta(delta, reverseDelta);
        } else {
            dd = new BufferedInputStream(new TargetInputStream(delta, 1024 * 1024, patcher), 100000);
        }
        if (!(nonGzippedDelta || useReverseDelta)) {
            dd = new GZIPInputStream(dd);
        }
        OutputStream tt;
        if (splitOutput) {
            tt = new SplitOutputStream(target, 1000000000, mergeOutput);
        } else if (verify) {
            if (randomDataVerify) {
                compareStream = new CompareOutputStream(new RandomDataInputStream(verifyDataSeed, verifyDataLength));
            } else {
                compareStream = new CompareOutputStream(target);
            }
            tt = new BufferedOutputStream(compareStream, 100000);
        } else {
            tt = new BufferedOutputStream(new FileOutputStream(target), 100000);
        }
        if (useReverseDelta && reverseDeltaOnly) {
            OutputStream os = new GZIPOutputStream(tt);
            int b = 0;
            byte[] buffer = new byte[10000];
            while (b >= 0) {
                b = dd.read(buffer);
                if (b > 0) {
                    os.write(buffer, 0, b);
                }
            }
            dd.close();
            os.close();
        } else {
            try {
                patcher.patch(ss, dd, tt);
            } catch (PatchException ex) {
                dd.close();
                dd = new GZIPInputStream(new BufferedInputStream(new TargetInputStream(delta, 1024 * 1024, null)), 100000);
                xpatcher.patch(ss, dd, tt);
            }
        }
        System.out.println("\rProcessing finished successfully. Decoded " + patcher.totalLength + " bytes.");
    }

    private static InputStream makeReverseDelta(final File reference, final File reverseDelta) throws IOException {
        // unpack reference to temp
        InputStream in = new BufferedInputStream(new GZIPInputStream(new TargetInputStream(reference, 1024 * 1024, null)));
        final File tempFile = File.createTempFile("reverse-", ".delta", new File("."));
        tempFile.deleteOnExit();
        OutputStream out = new BufferedOutputStream(new FileOutputStream(tempFile));
        int b = 0;
        byte[] buffer = new byte[10000];
        while (b >= 0) {
            b = in.read(buffer);
            if (b > 0) {
                out.write(buffer, 0, b);
            }
        }
        in.close();
        out.close();
        final PipedOutputStream reverseDeltaInput = new PipedOutputStream();
        final InputStream reverseDeltaOutput = new PipedInputStream(reverseDeltaInput);
        new Thread(new Runnable() {

            @Override
            public void run() {
                try {
                    SeekableSource s;
                    if (multiBuffer) {
                        s = new MultiBufferSeekableSource(new RandomAccessFile(tempFile, "r"), 100 * 1024, 500);
                    } else {
                        s = new RandomAccessFileSeekableSource(new RandomAccessFile(tempFile, "r"));
                    }
                    GDiffPatcher pp = new GDiffPatcher();
                    InputStream d = new GZIPInputStream(new BufferedInputStream(new TargetInputStream(reverseDelta, 1024 * 1024, pp), 100000));
                    pp.patch(s, d, reverseDeltaInput);
                    s.close();
                    d.close();
                    reverseDeltaInput.close();
                } catch (IOException ex) {
                    Logger.getLogger(XDeltaEncoder.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        }).start();
        return reverseDeltaOutput;
    }

    private static void convert() throws FileNotFoundException, IOException {
        if (randomDataSource) {
            System.out.println("Test source not supported for convert.\n");
            return;
        }
        SeekableSource ss;
        if (multiBuffer) {
            ss = new MultiBufferSeekableSource(new RandomAccessFile(source, "r"), 1024 * 100, 500);
        } else {
            ss = new RandomAccessFileSeekableSource(new RandomAccessFile(source, "r"));
        }
        InputStream dd = new GZIPInputStream(new BufferedInputStream(new TargetInputStream(target, 1024 * 1024, null)));
        DiffWriter tt = new XDiffWriter(new DataOutputStream(new BufferedOutputStream(new FileOutputStream(delta))));
        GDiffConverter converter = new GDiffConverter(tt);
        converter.patch(ss, dd, null);
    }

    private static void merge() throws IOException {
        if (randomDataSource) {
            System.out.println("Test source not supported for merge.\n");
            return;
        }
        File diffTemp = new File("diff.tmp");
        InputStream sis = new GZIPInputStream(new BufferedInputStream(new FileInputStream(source)));
        OutputStream sos = new BufferedOutputStream(new FileOutputStream(diffTemp));
        byte[] buf = new byte[10000];
        int n = 1;
        while (n > 0) {
            n = sis.read(buf);
            if (n > 0) {
                sos.write(buf, 0, n);
            }
        }
        sis.close();
        sos.flush();
        sos.close();
        SeekableSource ss;
        if (multiBuffer) {
            ss = new MultiBufferSeekableSource(new RandomAccessFile(diffTemp, "r"), 1024 * 100, 500);
        } else {
            ss = new RandomAccessFileSeekableSource(new RandomAccessFile(diffTemp, "r"));
        }
        InputStream dd = new GZIPInputStream(new BufferedInputStream(new TargetInputStream(target, 1024 * 1024, null)));
        DiffWriter tt = new GDiffWriter(new DataOutputStream(new GZIPOutputStream(new BufferedOutputStream(new FileOutputStream(delta)))));
        GDiffMerger merger = new GDiffMerger(tt);
        merger.patch(ss, dd, null);
        ss.close();
        if (!diffTemp.delete()) {
            diffTemp.deleteOnExit();
        }
        System.out.println("\rProcessing finished successfully.");
    }

    /*
     * Find optimal source pos
     */
    private static long find_source_pos(long sourcepos) throws IOException, SQLException, ClassNotFoundException {
        int pass = 0;
        int bestpass = 0;
        long bestpos = sourcepos;
        int bestsize = find_pass(sourcepos);
        long pos;
        //System.out.print(pass + " " + df.format(100.00 * bestsize / BLOCKSIZE * 8) + " %");
        while (pass < maxticks && (8.0d * bestsize / BLOCKSIZE) > 0.001) {
            pass++;
            pos = sourcepos - BLOCKSIZE / 8 * pass;
            if (pos >= 0) {
                int size = find_pass(pos);
                if (size < bestsize) {
                    bestsize = size;
                    bestpos = pos;
                    bestpass = -pass;
                    //System.out.print(" -" + pass + " " + df.format(100.00 * bestsize / BLOCKSIZE * 8) + " %");
                }
            }
            pos = sourcepos + BLOCKSIZE / 8 * pass;
            if (pos < sourceLength) {
                int size = find_pass(pos);
                if (size < bestsize) {
                    bestsize = size;
                    bestpos = pos;
                    bestpass = pass;
                    //System.out.print(" +" + pass + " " + df.format(100.00 * bestsize / BLOCKSIZE * 8) + " %");
                }
            }
        }
        sumpass += bestpass;
        System.out.print("(" + df2.format(sumpass * BLOCKSIZE / 8 / 1024 / 1024) + " mb [" + bestpass + "/" + maxticks + "] "
                + df.format(100.00 * bestsize / BLOCKSIZE * 8) + "%) ");
        return bestpos;
    }

    private static int find_pass(long sourcepos) throws IOException, SQLException, ClassNotFoundException {
        int targetsize = BLOCKSIZE / 8;
        ByteBuffer sourcebuf = ByteBuffer.wrap(new byte[BLOCKSIZE / 4]);
        sStream.seek(sourcepos);
        sourcebuf.rewind();
        int sourcesize = sStream.read(sourcebuf);
        byte[] ss = new byte[sourcesize];
        sourcebuf.rewind();
        sourcebuf.get(ss, 0, sourcesize);
        byte[] tt = new byte[targetsize];
        targetbuf.rewind();
        targetbuf.get(tt, 0, targetsize);
        byte[] dd = processor.compute(ss, tt);
        return dd.length;
    }

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        if (args.length < 3) {
            System.out.println("XDeltaEncoder version "
                    + Package.getPackage("org.mantlik.xdeltaencoder").getImplementationVersion()
                    + " (C) RNDr. Frantisek Mantlik, 2011-2012\n"
                    + "Usage:\njava -Xmx2048m -jar XDeltaEncoder.jar [options] source target delta\n"
                    + "java -jar XDeltaEncoder.jar -d source delta target\n"
                    + "java -jar XDeltaEncoder.jar -v source delta target\n"
                    + "java -jar XDeltaEncoder.jar -m first second merged\n"
                    + "java -jar XDeltaEncoder.jar -gx source delta xdelta\n"
                    + "Options: -c chunksize     chunk size in bytes - default 32\n"
                    + "         -b blocksize     block size processed in 1 pass in bytes - default 128m\n"
                    + "         -r name          create reverse delta or decode using reverse delta\n"
                    + "                              name - reverse delta file name\n"
                    + "                              Encoding: source - old delta\n"
                    + "                                        target - new delta, i.e. reverse reference\n"
                    + "                                        delta - not used\n"
                    + "             -u old_reference_file upgrade reverse delta from source and old_reference_file\n"
                    + "                              Decoding: source - source file\n"
                    + "                                        delta - reverse reference delta\n"
                    + "                                        target - target file\n"
                    + "             -ro                   write reverse delta to target, do not decode source\n"
                    + "         -v               verify patch against target\n"
                    + "             -mb          multi-buffer source - can be faster but needs more memory\n"
                    + "         -t               test the best block size (deprecated)\n"
                    + "         -p               preprocess using full file size (can be slow)\n"
                    + "         -f               read source block from file in memory\n"
                    + "                          slower but needs less memory\n"
                    + "         -s               single pass encoding (deprecated)\n"
                    //+ "         -x               extended output format (non-standard)\n"
                    //+ "         -gx              convert from standard to extended format\n"
                    + "         -m               merge two consecutive patches\n"
                    + "                             (does not check patch consistence)\n"
                    + "         -d               decode using delta patch\n"
                    + "             -so              split output - useful when JVM can't handle big files\n"
                    + "             -mo              merge splitted output when finished (Linux only)\n"
                    + "             -jd              join delta from splitted parts - delta means delta prefix\n"
                    + "             -ng              delta is not gzipped\n"
                    + "             -g               join source from splitted parts - source means source prefix");
            System.exit(99);
        }
        int arcbase = 0;
        int decoder = 0;
        int convert = 0;
        int merge = 0;
        long blocksize = BLOCKSIZE;
        boolean ignoreWarnings = false;
        boolean singlePass = false;
        boolean testBlockSize = false;
        while (args[arcbase].startsWith("-")) {
            if (args[arcbase].equalsIgnoreCase("-d") || args[arcbase].equalsIgnoreCase("-v")) {
                decoder = 1;
                if (args[arcbase].equalsIgnoreCase("-v")) {
                    verify = true;
                }
            } else if (args[arcbase].equalsIgnoreCase("-gx")) {
                convert = 1;
            } else if (args[arcbase].equalsIgnoreCase("-m")) {
                merge = 1;
            } else if (args[arcbase].equalsIgnoreCase("-p")) {
                do_preparation_pass = true;
            } else if (args[arcbase].equalsIgnoreCase("-d")) {
                differential = true;
            } else if (args[arcbase].equalsIgnoreCase("-f")) {
                sourceInMemory = false;
            } else if (args[arcbase].equalsIgnoreCase("-g")) {
                multiFileDecode = true;
            } else if (args[arcbase].equalsIgnoreCase("-so")) {
                splitOutput = true;
            } else if (args[arcbase].equalsIgnoreCase("-mo")) {
                mergeOutput = true;
            } else if (args[arcbase].equalsIgnoreCase("-ro")) {
                reverseDeltaOnly = true;
            } else if (args[arcbase].equalsIgnoreCase("-u")) {
                upgradeReverseDelta = true;
                arcbase++;
                oldDeltaReference = new File(args[arcbase]);
            } else if (args[arcbase].equalsIgnoreCase("-ng")) {
                nonGzippedDelta = true;
            } else if (args[arcbase].equalsIgnoreCase("-jd")) {
                splittedDelta = true;
            } else if (args[arcbase].equalsIgnoreCase("-s")) {
                singlePass = true;
            } else if (args[arcbase].equalsIgnoreCase("-t")) {
                testBlockSize = true;
            } else if (args[arcbase].equalsIgnoreCase("-i")) {
                ignoreWarnings = true;
            } else if (args[arcbase].equalsIgnoreCase("-x")) {
                xdiff = true;
            } else if (args[arcbase].equalsIgnoreCase("-c")) {
                arcbase++;
                chunksize = Integer.decode(args[arcbase]);
                if (chunksize < 3) {
                    chunksize = CHUNKSIZE;
                    System.out.println("Invalid chunk size. Used default value " + CHUNKSIZE);
                }
            } else if (args[arcbase].equalsIgnoreCase("-b")) {
                arcbase++;
                String ch = args[arcbase];
                int factor = 1;
                if (ch.endsWith("m")) {
                    factor = 1024 * 1024;
                    ch = ch.replace("m", "");
                }
                blocksize = Integer.decode(ch) * factor;
                if (blocksize <= chunksize) {
                    blocksize = BLOCKSIZE;
                    System.out.println("Invalid block size. Used default value " + BLOCKSIZE);
                }
            } else if (args[arcbase].equalsIgnoreCase("-r")) {
                useReverseDelta = true;
                arcbase++;
                reverseDelta = new File(args[arcbase]);
            }
            arcbase++;
        }
        String sourceString = args[arcbase];
        if (sourceString.startsWith("test:")) {
            randomDataSource = true;
            String[] parms = sourceString.split(":");
            sourceLength = Long.parseLong(parms[1]);
            if (parms.length > 2) {
                randomDataSeed = Long.parseLong(parms[2]);
            }
            //System.out.println("Random generated source: seed=" + randomDataSeed + " length=" + sourceLength + "\n");
        } else {
            source = new File(sourceString);
        }
        String targetString = args[arcbase + 1 + decoder];
        if (verify && targetString.startsWith("test:")) {
            randomDataVerify = true;
            String[] parms = targetString.split(":");
            verifyDataLength = Long.parseLong(parms[1]);
            if (parms.length > 2) {
                verifyDataSeed = Long.parseLong(parms[2]);
            }
            //System.out.println("Random generated target: seed=" + verifyDataSeed + " length=" + verifyDataLength + "\n");
        } else {
            target = new File(targetString);
        }
        if (!(useReverseDelta && decoder == 0)) {
            delta = new File(args[arcbase + 2 - decoder]);
        }
        if (!(randomDataSource || source.exists() || ignoreWarnings)) {
            System.out.println("Source file " + source.getPath() + " does not exist.");
            System.exit(88);
        }
        if (!randomDataSource) {
            sourceLength = source.length();
        }
        if (decoder == 0) {
            if (!(target.exists() || ignoreWarnings)) {
                System.out.println("Target file " + target.getPath() + " does not exist.");
                System.exit(87);
            }
            if ((!useReverseDelta) && delta.exists()) {
                delta.delete();
            }
            if (useReverseDelta && reverseDelta.exists()) {
                reverseDelta.delete();
            }
        } else {
            if (!(delta.exists() || ignoreWarnings)) {
                System.out.println("Delta file " + delta.getPath() + " does not exist.");
                System.exit(87);
            }
            if (useReverseDelta && (!(reverseDelta.exists() || ignoreWarnings))) {
                System.out.println("Reverse delta file " + reverseDelta.getPath() + " does not exist.");
                System.exit(87);
            }
            if ((!verify) && target.exists()) {
                target.delete();
            }
        }
        boolean encoded = false;
        if ((decoder == 0) && (convert == 0) && (merge == 0)) {
            if (testBlockSize) {
                System.out.println("Running blocksize test.");
            } else {
                System.out.println("Chunk size: " + chunksize + ", block size: " + blocksize);
            }
        }
        try {
            processor.setChunkSize(chunksize);
            if (decoder == 0) {
                if (convert == 1) {
                    convert();
                } else if (merge == 1) {
                    merge();
                } else if (useReverseDelta) {
                    createReverseDelta(blocksize);
                } else {
                    if (testBlockSize) {
                        blocksize = (int) testBlockSize();
                    }
                    if (singlePass) {
                        blocksize = source.length();
                        sourceInMemory = false;
                    }
                    encodeVirtualFile(blocksize);
                    encoded = true;
                }
            } else {
                decode();
                if (verify) {
                    System.out.println("Verify OK.");
                }
            }
            /*
             * } catch (SQLException ex) {
             * Logger.getLogger(XDeltaEncoder.class.getName()).log(Level.SEVERE,
             * null, ex); } catch (ClassNotFoundException ex) {
             * Logger.getLogger(XDeltaEncoder.class.getName()).log(Level.SEVERE,
             * null, ex);
             */
        } catch (FileNotFoundException ex) {
            Logger.getLogger(XDeltaEncoder.class.getName()).log(Level.SEVERE, null, ex);
        } catch (IOException ex) {
            if (!verify) {
                Logger.getLogger(XDeltaEncoder.class.getName()).log(Level.SEVERE, null, ex);
            } else {
                System.out.println(ex.getMessage());
                System.out.println("Verify error.");
                System.exit(2);
            }
        }
    }
}
