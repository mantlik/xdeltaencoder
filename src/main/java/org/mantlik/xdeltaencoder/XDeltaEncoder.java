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
import java.beans.XMLDecoder;
import java.beans.XMLEncoder;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
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
    private static final Delta preprocessor = new Delta();
    private static final Delta mainprocessor = new Delta();
    private static final GDiffPatcher patcher = new GDiffPatcher();
    private static final XDiffPatcher xpatcher = new XDiffPatcher();
    private static final DecimalFormat df = new DecimalFormat("0.00");
    private static final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
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
    private static int chunksize;
    private static int min_chunksize = 5;
    private static long chunkFactor = 10;
    private static boolean xdiff = false;
    private static boolean useReverseDelta = false;
    private static File reverseDelta = null;
    private static boolean reverseDeltaOnly = false;
    private static boolean upgradeReverseDelta = false;
    private static File oldDeltaReference = null;
    private static CompareOutputStream compareStream;
    private static boolean zeroAdditions = false;
    private static int zeroMinBlock = -1;
    private static double zeroRatio = GDiffWriter.DEFAULT_ZERO_RATIO;
    private static boolean autocode = false;
    private static MultiBufferSeekableSource targetFile = null;
    private static int targetBlockSize = 0;
    private static FileChannel targetChannel;
    private static ByteBuffer targetBuffer;
    private static long totalfounds = 0;
    private static int block_threshold = 0;
    private static boolean debugMode = false;
    private static SeekableSource debugSource = null;

    ;

    /*
     * Multi-pass encoder using virtual writer temporary file and blocks in
     * files
     */
    private static void encodeVirtualFile(long blksize) throws FileNotFoundException, IOException, ClassNotFoundException {
        if (autocode) {
            targetFile = new MultiBufferSeekableSource(new RandomAccessFile(target, "r"), 100 * 1024, 500);
        }
        Status status = new Status();
        status.read();
        if (status.pass == 0) {
            status.tempFile1 = File.createTempFile("temp1-", ".vdiff", new File("."));
            status.tempFile2 = File.createTempFile("temp2-", ".vdiff", new File("."));
            status.tempFile3 = File.createTempFile("temp3-", ".vdiff", new File("."));
            status.blocksize = blksize;
            status.sourcepos = 0;
            status.targetblocksize = targetBlockSize;
            status.preparation_pass = do_preparation_pass && (sourceLength > blksize);
        }
        if (status.targetblocksize > 0) {
            targetBuffer = ByteBuffer.allocateDirect(targetBlockSize);
            targetChannel = new FileInputStream(target).getChannel();
        }
        ByteBuffer bb = null;
        boolean origSourceInMemory = sourceInMemory;
        if (status.preparation_pass) {
            status.blocksize = sourceLength;
            sourceInMemory = false;
            preprocessor.firstMatch = true;
        }
        mainprocessor.setAutocode(autocode);
        mainprocessor.clearSource();
        mainprocessor.setKeepSource(false);
        mainprocessor.found = 0;
        preprocessor.setAutocode(autocode);
        preprocessor.clearSource();
        preprocessor.setKeepSource(false);
        preprocessor.found = 0;
        SeekableSource asource = null;
        SeekableSource bsource = null;

        boolean started = false;
        DiffWriter ddStream;

        boolean interrupted = false;
        // first pass - making virtual file
        if (status.pass == 0) {
            System.out.println(" [" + sdf.format(new Date(System.currentTimeMillis())) + "]: Initial pass. This can take several minutes.");
        } else {
            if (status.targetblocksize > 0) {
                System.out.println(" [" + sdf.format(new Date(System.currentTimeMillis()))
                        + "]: Restarting processing from pass " + status.targetpass + ".");
            } else {
                System.out.println(" [" + sdf.format(new Date(System.currentTimeMillis())) + "]: Restarting processing from pass " + (status.pass + 1) + ".");
                interrupted = true;
            }
        }
        TransparentOutputStream output = new TransparentOutputStream(new GZIPOutputStream(
                new FileOutputStream(delta)));
        int currentTargetPass = 0;
        while (currentTargetPass < status.targetpass) {
            targetBuffer.clear();
            while (targetChannel.read(targetBuffer) > 0) {
                if (!targetBuffer.hasRemaining()) {
                    break;
                }
            }
            targetBuffer.flip();
            writePassResults(status, new File(delta.getAbsolutePath() + "." + currentTargetPass), output, currentTargetPass);
            currentTargetPass++;
            status.pass = 0;
            status.sourcepos = 0;
        }
        while ((!started) || ((status.targetblocksize > 0) && (status.targetpos < target.length()))) {
            started = true;
            if (status.targetblocksize > 0) {
                targetBuffer.clear();
                int r;
                while ((r = targetChannel.read(targetBuffer)) > 0) {
                    if (targetBuffer.remaining() == 0) {
                        break;
                    }
                }
                targetBuffer.flip();
            }
            preprocessor.progress = true;
            boolean computed = false;
            if (status.pass == 0) {
                if (asource == null && bsource == null) {
                    status.sourcesize = 0;

                    if (!sourceInMemory) {
                        if (randomDataSource) {
                            asource = new RandomDataSeekableSource(randomDataSeed, sourceLength);
                        } else if (autocode) {
                            asource = targetFile;
                        } else if (status.preparation_pass) {
                            asource = new MultiBufferSeekableSource(new RandomAccessFile(source, "r"), 100 * 1024, 500);
                        } else {
                            asource = new RandomAccessFileSeekableSource(new RandomAccessFile(source, "r"), 0, status.blocksize);
                        }
                        status.sourcesize = sourceLength;
                    } else {
                        while (bb == null) {
                            try {
                                if (bb == null) {
                                    bb = ByteBuffer.wrap(new byte[(int) status.blocksize]);
                                }
                                bb.clear();
                                if (randomDataSource) {
                                    SeekableSource ss = new RandomDataSeekableSource(randomDataSeed, sourceLength);
                                    ss.seek(0);
                                    status.sourcesize = ss.read(bb);
                                } else if (autocode) {
                                    targetFile.seek(0);
                                    status.sourcesize = targetFile.read(bb);
                                } else {
                                    RandomAccessFile raf = new RandomAccessFile(source, "r");
                                    raf.seek(0);
                                    status.sourcesize = 0;
                                    int i = 0;
                                    while ((i >= 0) && (status.sourcesize < status.blocksize)) {
                                        i = raf.read(bb.array(), (int) status.sourcesize, (int) (status.blocksize - status.sourcesize));
                                        if (i > 0) {
                                            status.sourcesize += i;
                                        }
                                        //System.out.println("Reading source " + (sourcesize / 1024 / 1024) + " mb     \r");
                                    }
                                    raf.close();
                                }
                                bb.limit((int) status.sourcesize);
                                bb.rewind();
                                bsource = new ByteBufferSeekableSource(bb);
                            } catch (OutOfMemoryError e) {
                                bb = null;
                                System.gc();
                                status.blocksize -= status.blocksize / 4;
                                try {
                                    Thread.sleep(1000);
                                } catch (InterruptedException ex) {
                                }
                                System.out.println("Not enough memory. Block size changed to " + status.blocksize + ".");
                            }
                        }
                    }
                    Runtime.getRuntime().gc();
                    long freeMemory = Runtime.getRuntime().maxMemory() - Runtime.getRuntime().freeMemory()
                            + Runtime.getRuntime().totalMemory();
                    chunksize = Math.max((int) (status.blocksize * chunkFactor / freeMemory), min_chunksize);

                    if (status.preparation_pass) {
                        chunksize = 5 * chunksize + 3000;
                        preprocessor.acceptHash = true;
                        preprocessor.setDuplicateChecksum(true);
                    }

                    preprocessor.setChunkSize(chunksize);
                    System.out.println("Chunk size changed to " + chunksize + ".");
                } else {
                    chunksize = preprocessor.getChunkSize();
                }
                InputStream is = null;
                if (autocode) {
                    targetFile.resetStream();
                    is = targetFile.inputStream;
                } else if (status.targetblocksize > 0) {
                    is = new ByteBufferBackedInputStream(targetBuffer);
                } else {
                    is = new BufferedInputStream(new FileInputStream(target), 1024 * 1024 * 32);
                }
                if (debugMode && !autocode && !(status.targetblocksize > 0)) {
                    System.out.println("Debug check mode started.");
                    if (debugSource == null) {
                        debugSource = new RandomAccessFileSeekableSource(new RandomAccessFile(source, "r"), 0, source.length());
                    }
                    InputStream tt = new BufferedInputStream(new FileInputStream(target), 1024 * 1024 * 32);
                    ddStream = new VirtualWriter(new DataOutputStream(new BufferedOutputStream(
                            new GZIPOutputStream(new FileOutputStream(status.tempFile1), 1024 * 1024))),
                            debugSource, tt);
                } else {
                    ddStream = new VirtualWriter(new DataOutputStream(new BufferedOutputStream(
                            new GZIPOutputStream(new FileOutputStream(status.tempFile1), 1024 * 1024))));
                }
                preprocessor.targetsize = target.length();
                while (!computed) {
                    try {
                        if (sourceInMemory) {
                            preprocessor.compute(bsource, is, ddStream, 0, 0, true);
                        } else {
                            preprocessor.compute(asource, is, ddStream, 0, 0, true);
                        }
                        preprocessor.setKeepSource(true);
                        computed = true;
                    } catch (OutOfMemoryError ex) {
                        chunksize = 1 + (int) ((1.2d * status.sourcesize / preprocessor.getCheksumPos()) * chunksize);
                        chunkFactor = (int) (1.2d * chunkFactor * status.sourcesize / preprocessor.getCheksumPos());
                        System.out.println("Not enough memory. Chunk size changed to " + chunksize + ".");
                        preprocessor.setChunkSize(chunksize);
                    }
                }
                long totalLength;
                if (ddStream.getClass().equals(VirtualWriter.class)) {
                    totalLength = ((VirtualWriter) ddStream).totalLength;
                } else {
                    totalLength = ((GDiffWriter) ddStream).totalLength;
                }
                long targetlength = target.length();
                if (status.targetblocksize > 0) {
                    targetBuffer.rewind();
                    targetlength = targetBuffer.remaining();
                }
                if (totalLength != targetlength) {
                    System.out.println("Target length mismatch.");
                    System.out.println("Total output length = " + totalLength + " target length = " + targetlength);
                    return;
                }
                status.pass++;
                status.sourcepos += status.sourcesize;
                status.write();
            }
            bb = null;
            mainprocessor.progress = false;
            long sourcesize = status.sourcesize;
            long sourcelen = sourceLength;
            process_passes(status, interrupted, blksize, origSourceInMemory);
            // write result
            writePassResults(status, status.tempFile1, output, status.targetpass);
            status.tempFile1.renameTo(new File(delta.getAbsolutePath() + "." + status.targetpass));
            status.pass = 0;
            status.sourcepos = 0;
            status.sourcesize = sourcesize;
            sourceLength = sourcelen;
            status.targetpos += status.targetblocksize;
            status.targetpass++;
            status.preparation_pass = do_preparation_pass && (sourceLength > blksize);
        }
        output.closeStream();
        if (status.targetblocksize > 0) {
            targetChannel.close();
        }
        for (int i = 0; i < status.targetpass; i++) {
            File f = new File(delta.getAbsolutePath() + "." + i);
            if (f.exists()) {
                f.delete();
            }
        }
        System.out.print("Delta file size: " + delta.length());
        System.out.println(
                "   Final compression ratio: " + df.format(100.00d * delta.length() / target.length()) + " %");
    }

    private static void process_passes(Status status, boolean interrupted, long origBlocksize, boolean origSourceInMemory)
            throws FileNotFoundException, IOException {
        long fits = 0;
        long preparation_data;
        long totmem = Runtime.getRuntime().maxMemory();
        long curmem;
        long availmem;
        int freemem;
        long filteredData, totalLength, found;
        boolean under_threshold = false;
        ByteBuffer bb = null;
        SeekableSource asource = null, bsource = null;
        DiffWriter ddStream = null;
        mainprocessor.setKeepSource(true);
        long lastdisptime = System.currentTimeMillis();
        while (status.preparation_pass || status.sourcepos < sourceLength) {
            filteredData = 0;
            if (status.preparation_pass) {
                if (!interrupted) {
                    System.out.print("Preparation ");
                }
                found = preprocessor.found - totalfounds;
                if (ddStream != null) {
                    filteredData = ((VirtualWriter) ddStream).filteredData;
                }
            } else {
                found = mainprocessor.found;
            }
            LimitInputStream ttStream = null;
            if (autocode) {
                targetFile.resetStream();
                ttStream = new LimitInputStream(targetFile.inputStream);
            } else if (status.targetblocksize > 0) {
                targetBuffer.rewind();
                ttStream = new LimitInputStream(new ByteBufferBackedInputStream(targetBuffer));
            } else {
                ttStream = new LimitInputStream(new BufferedInputStream(new FileInputStream(target)));
            }
            availmem = Runtime.getRuntime().freeMemory();
            curmem = Runtime.getRuntime().totalMemory();
            freemem = (int) (100d * (availmem + totmem - curmem) / totmem);
            if (!interrupted) {
                if (status.targetblocksize > 0) {
                    if (!under_threshold) {
                        System.out.println(
                                "Pass " + status.targetpass + "." + status.pass + " [" + sdf.format(new Date(System.currentTimeMillis())) + "]: "
                                + df.format(100.0d * ((1d * status.targetpos) / target.length()
                                + (1d * status.sourcepos) / sourceLength * targetBuffer.limit() / target.length()))
                                + " % done, found " + df.format((totalfounds + fits + found) / 1024d / 1024d) + " mb.");
                    }
                } else {
                    System.out.println("Pass " + status.pass + " [" + sdf.format(new Date(System.currentTimeMillis())) + "]: "
                            + df.format(100.0d * status.sourcepos / sourceLength)
                            + " % done, found " + df.format((totalfounds + fits + found - filteredData) / 1024d / 1024d) + " mb " + freemem + " % free mem.");
                }
            }
            under_threshold = false;
            System.gc();
            if (status.preparation_pass && (status.sourcepos >= sourceLength)) {
                // switch to normal pass
                status.preparation_pass = false;
                mainprocessor.acceptHash = false;
                mainprocessor.setDuplicateChecksum(false);

                status.sourcepos = 0;
                sourceInMemory = origSourceInMemory;
                status.blocksize = origBlocksize;
                bsource = null;
                asource = null;
                bb = null;
                System.gc();
                if (status.targetpass == 0) {
                    chunkFactor = 10;
                    long freeMemory = Runtime.getRuntime().maxMemory() - Runtime.getRuntime().freeMemory()
                            + Runtime.getRuntime().totalMemory();
                    chunksize = Math.max((int) (status.blocksize * chunkFactor / freeMemory), min_chunksize);
                    mainprocessor.setChunkSize(chunksize);
                    System.out.println("Chunk size changed to " + chunksize + ".                                ");
                } else {
                    chunksize = mainprocessor.getChunkSize();
                }
            }
            status.pass++;
            fits = 0;
            mainprocessor.found = 0;
            preparation_data = 0;
            DataInputStream vinp = new DataInputStream(new GZIPInputStream(
                    new BufferedInputStream(new FileInputStream(status.tempFile1))));
            int length = 0;
            long offs = 0;
            int chs = mainprocessor.getChunkSize();
            if (do_preparation_pass) {
                // disable found hashes for current block
                status.sourcesize = status.blocksize;
                if (status.targetblocksize > 0) {
                    System.out.print("Pass " + status.targetpass + "." + status.pass + " Preprocessing block delta...                     \r");
                } else {
                    System.out.print("Pass " + status.pass + " Preprocessing block delta...                     \r");
                }
                DiffWriter ddStream2 = new VirtualWriter(new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(status.tempFile3)))));
                byte op = vinp.readByte();
                while (op != 3) {
                    if (op == 1) {  // copy pass through
                        offs = vinp.readLong();
                        length = vinp.readInt();
                        fits += length;
                        if (do_preparation_pass && (offs >= status.sourcepos && offs < (status.sourcepos + status.sourcesize))) {
                            op = 4;
                        } else {
                            ddStream2.addCopy(offs, length);
                        }
                    }
                    if ((op == 2) || (op == 4)) {
                        if (op == 2) {
                            length = vinp.readInt();
                        } else {
                            preparation_data += length;
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
                // Skip blocks with low ratio
                if (preparation_data < block_threshold) {
                    ttStream.close();
                    status.sourcepos += status.sourcesize;
                    under_threshold = true;
                    continue;
                }
                vinp = new DataInputStream(new GZIPInputStream(new BufferedInputStream(new FileInputStream(status.tempFile3))));
            }
            mainprocessor.clearSource();
            if (!sourceInMemory) {
                if (randomDataSource) {
                    asource = new RandomDataSeekableSource(randomDataSeed, sourceLength);
                } else if (autocode) {
                    asource = targetFile;
                } else {
                    asource = new RandomAccessFileSeekableSource(new RandomAccessFile(source, "r"), 0, status.blocksize);
                }
                status.sourcesize = sourceLength;
            }
            boolean read = false;
            if (sourceInMemory) {
                while (!read) {
                    try {
                        if (bb == null) {
                            bb = ByteBuffer.wrap(new byte[(int) status.blocksize]);
                        }
                        bb.clear();
                        if (randomDataSource) {
                            SeekableSource ss = new RandomDataSeekableSource(randomDataSeed, sourceLength);
                            ss.seek(status.sourcepos);
                            status.sourcesize = ss.read(bb);
                        } else if (autocode) {
                            targetFile.seek(status.sourcepos);
                            status.sourcesize = targetFile.read(bb);
                        } else {
                            RandomAccessFile raf = new RandomAccessFile(source, "r");
                            raf.seek(status.sourcepos);
                            status.sourcesize = 0;
                            int i = 0;
                            while ((i >= 0) && (status.sourcesize < status.blocksize)) {
                                i = raf.read(bb.array(), (int) status.sourcesize, (int) (status.blocksize - status.sourcesize));
                                if (i > 0) {
                                    status.sourcesize += i;
                                }
                                //System.out.println("Reading source " + (sourcesize / 1024 / 1024) + " mb     \r");
                            }
                            raf.close();
                        }
                        bb.limit((int) status.sourcesize);
                        bb.rewind();
                        bsource = new ByteBufferSeekableSource(bb);

                        read = true;
                    } catch (OutOfMemoryError e) {
                        bb = null;
                        System.gc();
                        status.blocksize -= status.blocksize / 4;
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ex) {
                        }
                        System.out.println("Not enough memory. Block size changed to " + status.blocksize + ".");
                    }
                }
            }
            if (debugMode && (!autocode) && !(status.targetblocksize > 0)) {
                System.out.println("Debug check mode started.");
                if (debugSource == null) {
                    debugSource = new RandomAccessFileSeekableSource(new RandomAccessFile(source, "r"), 0, source.length());
                }
                InputStream tt = new BufferedInputStream(new FileInputStream(target), 1024 * 1024 * 32);
                ddStream = new VirtualWriter(new DataOutputStream(new BufferedOutputStream(
                        new GZIPOutputStream(new FileOutputStream(status.tempFile2), 1024 * 1024))),
                        debugSource, tt);
            } else {
                ddStream = new VirtualWriter(new DataOutputStream(new BufferedOutputStream(
                        new GZIPOutputStream(new FileOutputStream(status.tempFile2), 1024 * 1024))));
            }
            byte op = vinp.readByte();
            long done = 0;
            interrupted = false;
            length = 0;
            offs = 0;
            fits = 0;
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
                    if ((length <= chs) || (autocode && (done < status.sourcepos))) {
                        //System.out.println("Passthrough " + len + " bytes");
                        for (int i = 0; i < length; i++) {
                            ddStream.addData((byte) (ttStream.read()));
                        }
                    } else {
                        ttStream.setLimit(length);
                        try {
                            if (sourceInMemory) {
                                mainprocessor.compute(bsource, ttStream, ddStream, status.sourcepos, done - length, false);
                            } else {
                                mainprocessor.compute(asource, ttStream, ddStream, status.sourcepos, done - length, false);
                            }
                        } catch (OutOfMemoryError ex) {
                            chunksize = 1 + (int) ((1.2d * status.sourcesize / mainprocessor.getCheksumPos()) * chunksize);
                            chunkFactor = (int) (1.2d * chunkFactor * status.sourcesize / mainprocessor.getCheksumPos());
                            System.out.println("Not enough memory. Chunk size changed to " + chunksize + ".");
                            mainprocessor.setChunkSize(chunksize);
                            interrupted = true;
                            status.pass--;
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
                    if (status.preparation_pass) {
                        System.out.print("Preparation ");
                        filteredData = ((VirtualWriter) ddStream).filteredData;
                    }
                    if (status.targetblocksize > 0) {
                        System.out.print("Pass " + status.targetpass + "." + status.pass + " progress: "
                                + df.format(100.00 * done / (targetBuffer.position() + targetBuffer.remaining()))
                                + " %, so far fitted " + df.format((mainprocessor.found - preparation_data - filteredData)
                                / 1024d / 1024d) + " mb      \b\r");
                    } else {
                        System.out.print("Pass " + status.pass + " progress: " + df.format(100.00 * done / target.length())
                                + " %, so far fitted " + df.format((mainprocessor.found - preparation_data - filteredData)
                                / 1024d / 1024d) + " mb      \b\r");
                    }
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
                long targetlength = target.length();
                if (status.targetblocksize > 0) {
                    targetBuffer.rewind();
                    targetlength = targetBuffer.remaining();
                }
                if (totalLength != targetlength) {
                    System.out.println("Target length mismatch.");
                    System.out.println("Total output length = " + totalLength + " target length = " + targetlength);
                    return;
                }
                status.sourcepos += status.sourcesize;
                File file = status.tempFile1;
                status.tempFile1 = status.tempFile2;
                status.tempFile2 = file;
            }
            status.write();
        }
        if (status.targetblocksize > 0) {
            if (!under_threshold) {
                System.out.println(
                        "Pass " + status.targetpass + "." + status.pass + " [" + sdf.format(new Date(System.currentTimeMillis())) + "]: "
                        + df.format(100.0d * (1d * status.targetpos / target.length()
                        + (1d * status.sourcepos) / sourceLength * targetBuffer.limit() / target.length()))
                        + " % done, found " + df.format((totalfounds + fits + mainprocessor.found) / 1024d / 1024d) + " mb.");
            }
        } else {
            System.out.println(
                    "Pass " + status.pass + " [" + sdf.format(new Date(System.currentTimeMillis())) + "]: " + df.format(100.0d * status.sourcepos / sourceLength)
                    + " % done, found " + df.format((totalfounds + fits + mainprocessor.found) / 1024d / 1024d) + " mb.");
        }
        totalfounds += fits + mainprocessor.found;
        if (status.tempFile2.exists()) {
            status.tempFile2.delete();
        }

        if (status.tempFile3.exists()) {
            status.tempFile3.delete();
        }
        File statusFile = new File(status.statusFileName);
        if (statusFile.exists()) {
            statusFile.delete();
        }
        if (autocode) {
            targetFile.close(true);
        }
        mainprocessor.clearSource();
        System.gc();
    }

    private static void writePassResults(Status status, File vdiff, OutputStream output, int pass) throws IOException {
        DiffWriter ddStream;
        if (xdiff) {
            ddStream = new XDiffWriter(new DataOutputStream(new GZIPOutputStream(new FileOutputStream(delta))));
        } else {
            int skipheaders = 0;
            if (status.targetblocksize > 0) {
                if (pass > 0) {
                    skipheaders += GDiffWriter.SKIP_HEADER;
                }
                if ((pass + 1) * status.targetblocksize < target.length()) {
                    skipheaders += GDiffWriter.SKIP_EOF;
                }
            }
            if (debugMode && !autocode && !(status.targetblocksize > 0)) {
                System.out.println("Debug check mode started.");
                if (debugSource == null) {
                    debugSource = new RandomAccessFileSeekableSource(new RandomAccessFile(source, "r"), 0, source.length());
                }
                InputStream tt = new BufferedInputStream(new FileInputStream(target), 1024 * 1024 * 32);
                ddStream = new VirtualWriter(new DataOutputStream(output), debugSource, tt);
            } else {
                ddStream = new GDiffWriter(new DataOutputStream(output),
                        skipheaders, differential, zeroAdditions, zeroMinBlock, zeroRatio);
            }
        }
        InputStream is = null;
        if (autocode) {
            targetFile.resetStream();
            is = targetFile.inputStream;
        } else if (status.targetblocksize > 0) {
            is = new ByteBufferBackedInputStream(targetBuffer);
        } else {
            is = new BufferedInputStream(new FileInputStream(target), 1024 * 1024 * 32);
        }
        DataInputStream vinp = new DataInputStream(new GZIPInputStream(new BufferedInputStream(
                new FileInputStream(vdiff))));
        if (status.targetblocksize > 0) {
            System.out.print("Writing delta file for pass " + pass + "...                        \r");
        } else {
            System.out.print("Writing delta file...                                 \r");
        }
        long offs;
        int length = 0;
        byte op = vinp.readByte();
        while (op != 3) {
            if (op == 1) {  // copy
                offs = vinp.readLong();
                length = vinp.readInt();
                int l = length;
                while (l > 0) {
                    l -= is.skip(l);
                }
                ddStream.addCopy(offs, length);
            } else if (op == 2) {
                length = vinp.readInt();
                for (int i = 0; i < length; i++) {
                    ddStream.addData((byte) is.read());
                }
            }
            op = vinp.readByte();
        }
        vinp.close();
        is.close();
        ddStream.close();
    }
    /*
     * Test for optimal block size on sample of target file
     */

    private static long testBlockSize() throws FileNotFoundException, IOException, ClassNotFoundException {
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
        chunksize = min_chunksize;
        blocksize = 384 * 1024 * 1024;
        encodeVirtualFile(blocksize);
        bs[0] = blocksize;
        fs[0] = delta.length();
        chunksize = min_chunksize;
        blocksize = 512 * 1024 * 1024;
        encodeVirtualFile(blocksize);
        if (delta.length() < fs[0]) {
            bs[1] = blocksize;
            fs[1] = delta.length();
            lastblocksize = blocksize;
            chunksize = min_chunksize;
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
            chunksize = min_chunksize;
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
            chunksize = min_chunksize;
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
        chunksize = min_chunksize;
        return blocksize;
    }

    private static void createReverseDelta(long blocksize) throws IOException, FileNotFoundException, ClassNotFoundException {
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
        System.out.println("\rMerging first + reference to full delta     ");
        File reverseFile = File.createTempFile("reverse-", ".delta", new File("."));
        reverseFile.deleteOnExit();
        try {
            SeekableSource s = new RandomAccessFileSeekableSource(new RandomAccessFile(sourceFile, "r"));
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
        System.out.println("\rComputing reverse delta      " + reverseDelta);
        source = referenceFile;
        target = reverseFile;
        delta = reverseDelta;
        encodeVirtualFile(blocksize);
    }

    private static void decode() throws IOException {
        SeekableSource ss = null;
        if (autocode) {
            if (verify) {
                targetFile = new MultiBufferSeekableSource(new RandomAccessFile(target, "r"), 100 * 1024, 500);
            } else {
                targetFile = new MultiBufferSeekableSource(new RandomAccessFile(target, "rw"), 100 * 1024, 500);
            }
        }
        if (!reverseDeltaOnly) {
            if (multiFileDecode) {
                File dir = source.getParentFile();
                String prefix = source.getName();
                ss = new MultifileSeekableSource(dir, prefix);
            } else if (randomDataSource) {
                ss = new RandomDataSeekableSource(randomDataSeed, sourceLength);
            } else if (autocode) {
                ss = targetFile;
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
        if (autocode) {
            if (verify) {
                compareStream = new CompareOutputStream(targetFile.inputStream);
                tt = compareStream;
                targetFile.resetStream();
            } else {
                tt = targetFile.outputStream;
            }
        } else if (splitOutput) {
            tt = new SplitOutputStream(target, 1000000000, mergeOutput);
        } else if (verify) {
            if (randomDataVerify) {
                compareStream = new CompareOutputStream(new RandomDataInputStream(verifyDataSeed, verifyDataLength));
            } else {
                compareStream = new CompareOutputStream(target);
            }
            tt = compareStream;
        } else {
            tt = new FileOutputStream(target);
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
                tt = new BufferedOutputStream(tt, 100000);
                patcher.patch(ss, dd, tt);
            } catch (PatchException ex) {
                dd.close();
                dd = new GZIPInputStream(new BufferedInputStream(new TargetInputStream(delta, 1024 * 1024, null)), 100000);
                xpatcher.patch(ss, dd, tt);
            }
        }
        if (autocode) {
            targetFile.close(true);
        }
        if (verify) {
            if (patcher.totalLength == target.length()) {
                System.out.println("\rProcessing finished successfully. Verified " + patcher.totalLength + " bytes.");
            } else {
                System.out.println();
                throw new IOException("Target length mismatch. Decoded " + patcher.totalLength + " bytes, target length = "
                        + target.length());
            }
        } else {
            System.out.println("\rProcessing finished successfully. Decoded " + patcher.totalLength + " bytes.");
        }
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
                    SeekableSource s = new RandomAccessFileSeekableSource(new RandomAccessFile(tempFile, "r"));
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
        SeekableSource ss = new RandomAccessFileSeekableSource(new RandomAccessFile(source, "r"));
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
        File diffTemp = File.createTempFile("diff-", ".tmp", new File("."));
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
        SeekableSource ss = new RandomAccessFileSeekableSource(new RandomAccessFile(diffTemp, "r"));
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

    private static class ByteBufferBackedInputStream extends InputStream {

        ByteBuffer buf;

        ByteBufferBackedInputStream(ByteBuffer buf) {
            this.buf = buf;
        }

        @Override
        public int read() throws IOException {
            if (!buf.hasRemaining()) {
                return -1;
            }
            return buf.get() & 0xFF;
        }

        @Override
        public int read(byte[] bytes, int off, int len)
                throws IOException {
            if (!buf.hasRemaining()) {
                return -1;
            }

            len = Math.min(len, buf.remaining());
            buf.get(bytes, off, len);
            return len;
        }
    }

    private static class Status {

        String statusFileName;
        int pass;
        long sourcepos;
        long sourcesize;
        long blocksize;
        File tempFile1;
        File tempFile2;
        File tempFile3;
        boolean preparation_pass;
        int targetpass;
        long targetpos;
        long targetblocksize;

        Status() {
            String deltaname = delta.getName();
            String deltapath = delta.getParent();
            if (deltapath == null) {
                deltapath = ".";
            }
            this.statusFileName = deltapath + "/" + "." + deltaname + ".status";
        }

        void read() throws IOException, ClassNotFoundException {
            if (!(new File(statusFileName).exists())) {
                return;
            }
            XMLDecoder is = new XMLDecoder(new FileInputStream(statusFileName));
            pass = (Integer) is.readObject();
            sourcepos = (Long) is.readObject();
            sourcesize = (Long) is.readObject();
            blocksize = (Long) is.readObject();
            preparation_pass = (Boolean) is.readObject();
            tempFile1 = new File((String) is.readObject());
            tempFile2 = new File((String) is.readObject());
            tempFile3 = new File((String) is.readObject());
            targetpass = (Integer) is.readObject();
            targetpos = (Long) is.readObject();
            targetblocksize = (Long) is.readObject();
            is.close();
        }

        void write() throws IOException {
            XMLEncoder os = new XMLEncoder(new FileOutputStream(statusFileName));
            os.writeObject(pass);
            os.writeObject(sourcepos);
            os.writeObject(sourcesize);
            os.writeObject(blocksize);
            os.writeObject(preparation_pass);
            os.writeObject(tempFile1.getPath());
            os.writeObject(tempFile2.getPath());
            os.writeObject(tempFile3.getPath());
            os.writeObject(targetpass);
            os.writeObject(targetpos);
            os.writeObject(targetblocksize);
            os.close();
        }
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
                    + "                            encode target from source, produce delta"
                    + "java -jar XDeltaEncoder.jar -d [options] source delta target\n"
                    + "                            decode source using delta, produce target"
                    + "java -jar XDeltaEncoder.jar -v [options] source delta target\n"
                    + "                            verify delta simulating decoding source to target"
                    + "java -jar XDeltaEncoder.jar -m [options] first second merged\n"
                    + "                            merge first and second delta, produce merged"
                    + "Options: -c chunksize     minimum chunk size in bytes - default 5\n"
                    + "         -b blocksize     block size processed in 1 pass in bytes - default 128m\n"
                    + "         -tb blocksize    target block size - split target and process in memory\n"
                    + "                              0 means no target splitting\n"
                    + "         -bt size         threshold of preprocessed finds to trigger block processing\n"
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
                    + "         -p               preprocess using full file size (suitable for large files"
                    + "                               with significant amount of identical blocks)\n"
                    + "         -z               zero additions instead of copying dest blocks\n"
                    + "                              decoded destination will contain copied source data,\n"
                    + "                              the rest will be filled in with zeroes.\n"
                    + "             -zb bytes    zero continuous block of size bytes or more only - default 10\n"
                    + "                              avoids breaking continuous blocks of data\n"
                    + "                              due to negligible differences\n"
                    + "             -zr percent  do not zero blocks when more than percent % data found\n"
                    + "                              in sliding 1Mb window - default 90 %\n"
                    + "         -f               read source block from file in memory\n"
                    + "                              slower but needs less memory\n"
                    + "         -a               auto encode/decode, i.e. ignore source and use target only"
                    + "                              source must be specified but is ignored"
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
        chunksize = min_chunksize;
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
            } else if (args[arcbase].equalsIgnoreCase("-a")) {
                autocode = true;
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
            } else if (args[arcbase].equalsIgnoreCase("-z")) {
                zeroAdditions = true;
            } else if (args[arcbase].equalsIgnoreCase("-c")) {
                arcbase++;
                min_chunksize = Integer.decode(args[arcbase]);
                if (min_chunksize < 5) {
                    min_chunksize = 5;
                    System.out.println("Invalid minimum chunk size. Used default value " + 5);
                }
                chunksize = min_chunksize;
            } else if (args[arcbase].equalsIgnoreCase("-zb")) {
                arcbase++;
                zeroMinBlock = Integer.decode(args[arcbase]);
                if (zeroMinBlock < 1) {
                    zeroMinBlock = -1;
                    System.out.println("Invalid minimum zeroes block size. Used default value "
                            + GDiffWriter.DEFAULT_ZERO_MIN_BLOCK + " bytes.");
                }
            } else if (args[arcbase].equalsIgnoreCase("-zr")) {
                arcbase++;
                zeroRatio = 1.0d * Integer.decode(args[arcbase]) / 100d;
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
            } else if (args[arcbase].equalsIgnoreCase("-tb")) {
                arcbase++;
                String ch = args[arcbase];
                int factor = 1;
                if (ch.endsWith("m")) {
                    factor = 1024 * 1024;
                    ch = ch.replace("m", "");
                }
                targetBlockSize = Integer.decode(ch) * factor;
            } else if (args[arcbase].equalsIgnoreCase("-bt")) {
                arcbase++;
                String ch = args[arcbase];
                int factor = 1;
                if (ch.endsWith("m")) {
                    factor = 1024 * 1024;
                    ch = ch.replace("m", "");
                }
                block_threshold = Integer.decode(ch) * factor;
            } else if (args[arcbase].equalsIgnoreCase("-r")) {
                useReverseDelta = true;
                arcbase++;
                reverseDelta = new File(args[arcbase]);
            } else if (args[arcbase].equalsIgnoreCase("-dm")) {
                debugMode = true;
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
        if (!(randomDataSource || autocode || source.exists() || ignoreWarnings)) {
            System.out.println("Source file " + source.getPath() + " does not exist.");
            System.exit(88);
        }
        if (!(randomDataSource || autocode)) {
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
            if (autocode) {
                sourceLength = target.length();
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
            preprocessor.setChunkSize(chunksize);
            mainprocessor.setChunkSize(chunksize);
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
        } catch (ClassNotFoundException ex) {
            Logger.getLogger(XDeltaEncoder.class.getName()).log(Level.SEVERE, null, ex);
        } catch (IOException ex) {
            if (!verify) {
                Logger.getLogger(XDeltaEncoder.class.getName()).log(Level.SEVERE, null, ex);
            } else {
                if (ex.getMessage() == null) {
                    ex.printStackTrace();
                } else {
                    System.out.println(ex.getMessage());
                }
                System.out.println("Verify error.");
                System.exit(2);
            }
        }
    }
}
