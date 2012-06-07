 /*
 *
 * Copyright (c) 2001 Torgeir Veimo
 * Copyright (c) 2002 Nicolas PERIDONT
 * Bug Fixes: Daniel Morrione dan@morrione.net
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
 *
 * Change Log:
 * iiimmddyyn  nnnnn  Description
 * ----------  -----  -------------------------------------------------------
 * gls100603a         Fixes from Torgeir Veimo and Dan Morrione
 * gls110603a         Stream not being closed thus preventing a file from
 *                       being subsequently deleted.
 * gls031504a         Error being written to stderr rather than throwing exception
 */
package com.nothome.delta;

import gnu.trove.TByteArrayList;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.text.DecimalFormat;

/**
 * Class for computing deltas against a source. The source file is read by
 * blocks and a hash is computed per block. Then the target is scanned for
 * matching blocks.
 * <p/>
 * This class is not thread safe. Use one instance per thread.
 * <p/>
 * This class should support files over 4GB in length, although you must use a
 * larger checksum size, such as 1K, as all checksums use "int" indexing. Newer
 * versions may eventually support paging in/out of checksums.
 */
public class Delta {

    /**
     * Debug flag.
     */
    final static boolean debug = false;
    /**
     * Default size of 16. For "Lorem ipsum" text files (see the tests) the
     * ideal size is about 14. Any smaller and the patch size becomes actually
     * be larger. <p> Use a size like 64 or 128 for large files.
     */
    public static final int DEFAULT_CHUNK_SIZE = 1 << 4;
    public static final int LONGEST_POSSIBLE_MATCH = Short.MAX_VALUE - 4;
    private static final DecimalFormat df = new DecimalFormat("0.00");
    /**
     * Chunk Size.
     */
    private int S;
    private SourceState source = null;
    private TargetState target;
    private DiffWriter output;
    private boolean keepSource = false;
    private long done = 0;
    public long found = 0;
    public boolean progress = false;
    public long targetsize = 0;
    public boolean firstMatch = false;
    public boolean acceptHash = false;
    public boolean duplicateChecksum = false;

    /**
     * Constructs a new Delta. In the future, additional constructor arguments
     * will set the algorithm details.
     */
    public Delta() {
        setChunkSize(DEFAULT_CHUNK_SIZE);
    }

    public int getChunkSize() {
        return S;
    }

    public long getCheksumPos() {
        if (source==null) {
            return 0;
        }
        if (source.checksum == null) {
            return 0;
        }
        long spos = source.checksum.spos;
        if (duplicateChecksum && (source.checksum2 != null)) {
            spos = (spos + source.checksum2.spos) / 2;
        }
        return spos;
    }

    /**
     * Sets the chunk size used. Larger chunks are faster and use less memory,
     * but create larger patches as well.
     *
     * @param size
     */
    public final void setChunkSize(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("Invalid size");
        }
        S = size;
    }

    /**
     * Compares the source bytes with target bytes, writing to output.
     */
    public void compute(byte source[], byte target[], OutputStream output)
            throws IOException {
        compute(new ByteBufferSeekableSource(source),
                new ByteArrayInputStream(target),
                new GDiffWriter(output), 0, true);
    }

    /**
     * Compares the source bytes with target bytes, returning output.
     */
    public byte[] compute(byte source[], byte target[])
            throws IOException {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        compute(source, target, os);
        return os.toByteArray();
    }

    /**
     * Compares the source bytes with target input, writing to output.
     */
    public void compute(byte[] sourceBytes, InputStream inputStream,
            DiffWriter diffWriter) throws IOException {
        compute(new ByteBufferSeekableSource(sourceBytes),
                inputStream, diffWriter, 0, true);
    }

    /**
     * Compares the source file with a target file, writing to output.
     *
     * @param output will be closed
     */
    public void compute(File sourceFile, File targetFile, DiffWriter output, long sourceOffset, boolean closeOutput)
            throws IOException {
        RandomAccessFileSeekableSource asource = new RandomAccessFileSeekableSource(new RandomAccessFile(sourceFile, "r"));
        InputStream is = new BufferedInputStream(new FileInputStream(targetFile));
        try {
            compute(asource, is, output, sourceOffset, closeOutput);
        } finally {
            asource.close();
            is.close();
        }
    }

    public void compute(File sourceFile, File targetFile, DiffWriter output, long sourceOffset) throws IOException {
        compute(sourceFile, targetFile, output, sourceOffset, true);
    }

    public void compute(File sourceFile, File targetFile, DiffWriter output) throws IOException {
        compute(sourceFile, targetFile, output, 0, true);
    }

    /*
     * SourceState will be reused if exists
     */
    public void setKeepSource(boolean keepSource) {
        this.keepSource = keepSource;
    }

    public void clearSource() {
        source = null;
    }

    /**
     * Compares the source with a target, writing to output.
     *
     * @param output will be closed
     */
    public void compute(SeekableSource seekSource, InputStream targetIS, DiffWriter output, long sourceOffset,
            boolean closeOutput)
            throws IOException {

        if (debug) {
            debug("using match length S = " + S);
        }

        if ((source == null) || (!keepSource)) {
            source = new SourceState(seekSource);
            source.checksum.init(seekSource, S);
            if (duplicateChecksum) {
                source.checksum2.init(seekSource, S);
            }
        }
        target = new TargetState(targetIS);
        this.output = output;
        if (debug) {
            debug("checksums " + source.checksum);
        }
        done = 0;
        long nextDone = done;

        long loops = 0;
        long dupHashes = 0;
        while (!target.eof()) {
            loops++;
            debug("!target.eof()");
            int index = target.find(source);
            if (index > -1) {
                if (debug) {
                    debug("found hash " + index);
                }
                long offset = ((long) index) * S;
                int match;
                if (acceptHash) {
                    match = S;
                    target.tbuf.position(target.tbuf.position() + match);
                    target.hashReset = true;
                } else {
                    source.seek(offset);
                    target.matched.clear();
                    match = target.longestMatch(source);
                }
                debug("best match " + match + " at index " + (offset / S));
                if (match >= S) {
                    if (debug) {
                        debug("output.addCopy(" + offset + "," + match + ")");
                    }
                    output.addCopy(sourceOffset + offset, match);
                    found += match;
                    done += match;
                } else {
                    // move the position back according to how much we can't copy
                    /*
                     * if (tposition != (target.tbuf.position()-match)) {
                     * System.err.println("Target position mismatch:
                     * "+tposition+" != "+(target.tbuf.position()-match)); }
                     */
                    try {
                        target.tbuf.position(target.tbuf.position() - match);
                        target.hashReset = true;
                    } catch (IllegalArgumentException ex) {
                        System.err.println();
                        System.err.println("Match = " + match + " position = " + target.tbuf.position());
                        ex.printStackTrace();
                    }
                    //target.hashReset = false;
                    //target.tbuf.position(tposition);
                    addData();
                    done++;
                }
            } else {
                addData();
                done++;
            }
            if (progress && (done >= nextDone)) {
                while (done > nextDone) {
                    nextDone += 1024 * 1024;
                }
                if (targetsize == 0) {
                    System.out.print("Processed " + nextDone / 1024 / 1024 + " mb so far fitted "
                            + found / 1024 / 1024 + " mb   \r");
                } else {
                    System.out.print("Processed " + nextDone / 1024 / 1024 + " mb ("
                            + df.format(100.00d * nextDone / targetsize) + " %) so far fitted "
                            + found / 1024 / 1024 + " mb   \r");
                }
                nextDone += 1024 * 1024;
            }
        }
        if (closeOutput) {
            output.close();
        }
    }

    private void addData() throws IOException {
        int i = target.read();
        if (debug) {
            debug("addData " + Integer.toHexString(i));
        }
        if (i == -1) {
            return;
        }
        output.addData((byte) i);
    }

    class SourceState {

        private SeekableSource source;
        private Checksum checksum, checksum2;

        public SourceState(SeekableSource source) throws IOException {
            checksum = new Checksum(source, S);
            if (duplicateChecksum) {
                checksum2 = new Checksum(source, S, System.currentTimeMillis());
            }
            this.source = source;
            source.seek(0);
        }

        public void seek(long index) throws IOException {
            source.seek(index);
        }

        /**
         * Returns a debug
         * <code>String</code>.
         */
        @Override
        public String toString() {
            return "Source"
                    + " checksum=" + this.checksum
                    + " source=" + this.source
                    + "";
        }
    }

    class TargetState {

        private ReadableByteChannel c;
        private ByteBuffer tbuf = ByteBuffer.allocate(blocksize());
        private ByteBuffer sbuf = ByteBuffer.allocate(S);
        private long hash, hash2;
        private boolean invalidHash = true;
        private boolean hashReset = true;
        private boolean eof;
        private TByteArrayList matched = new TByteArrayList();

        TargetState(InputStream targetIS) throws IOException {
            c = Channels.newChannel(targetIS);
            tbuf.limit(0);
        }

        private int blocksize() {
            return Math.max(Math.min(1024 * 16, S * 4), S + 1024);
        }

        /**
         * Returns the index of the next N bytes of the stream.
         */
        public int find(SourceState source) throws IOException {
            if (eof) {
                return -1;
            }
            sbuf.clear();
            sbuf.limit(0);
            if (hashReset) {
                debug("hashReset");
                while (tbuf.remaining() < S) {
                    tbuf.compact();
                    int read = c.read(tbuf);
                    tbuf.flip();
                    if (read == -1) {
                        debug("target ending");
                        return -1;
                    }
                }
                hash();
                //hash = Checksum.queryChecksum(tbuf, S);
                //hashReset = false;
            }
            if (invalidHash) {
                return -1;
            }
            if (debug) {
                debug("hash " + hash + " " + dump());
            }
            int index = source.checksum.findChecksumIndex(hash);
            if (index == -1) {
                return index;
            }
            if (duplicateChecksum) {
                int index2 = source.checksum2.findChecksumIndex(hash2);
                if (index2 != index) {
                    return -1;
                }
            }
            return index;
        }

        public boolean eof() {
            return eof;
        }

        /**
         * Reads a byte.
         *
         * @throws IOException
         */
        public int read() throws IOException {
            if (tbuf.remaining() <= S) {
                readMore();
                if (!tbuf.hasRemaining()) {
                    eof = true;
                    return -1;
                }
            }
            byte b = tbuf.get();
            if (tbuf.remaining() >= S) {
                byte nchar = tbuf.get(tbuf.position() + S - 1);
                hash = source.checksum.incrementChecksum(hash, b, nchar, S);
                if (duplicateChecksum) {
                    hash2 = source.checksum2.incrementChecksum(hash2, b, nchar, S);
                }
                invalidHash = false;
            } else {
                debug("out of char");
                invalidHash = true;
            }
            return b & 0xFF;
        }

        public void incrementChecksum(byte b) {
            if (tbuf.remaining() >= S) {
                byte nchar = tbuf.get(tbuf.position() + S - 1);
                hash = source.checksum.incrementChecksum(hash, b, nchar, S);
                if (duplicateChecksum) {
                    hash2 = source.checksum2.incrementChecksum(hash2, b, nchar, S);
                }
                invalidHash = false;
            } else {
                debug("out of char");
                invalidHash = true;
            }
        }

        /**
         * Returns the longest match length at the source location.
         */
        public int longestMatch(SourceState source) throws IOException {
            debug("longestMatch");
            int match = 0;
            if (!matched.isEmpty()) {
                for (int i = 0; i < matched.size(); i++) {
                    if (!sbuf.hasRemaining()) {
                        sbuf.clear();
                        int read = source.source.read(sbuf);
                        sbuf.flip();
                        if (read == -1) {
                            return match;
                        }
                    }
                    if (sbuf.get() != matched.get(i)) {
                        return match;
                    }
                    match++;
                    if (match >= LONGEST_POSSIBLE_MATCH) {
                        debug("longest possible match");
                        return match;
                    }
                }
            }
            //hashReset = true;
            while (true) {
                if (!sbuf.hasRemaining()) {
                    sbuf.clear();
                    int read = source.source.read(sbuf);
                    sbuf.flip();
                    if (read == -1) {
                        return match;
                    }
                }
                if (!tbuf.hasRemaining()) {
                    /*
                     * if (match <= S) { eof = true; return match; // end of
                     * buffer }
                     */
                    readMore();
                    if (!tbuf.hasRemaining()) {
                        debug("target ending");
                        eof = true;
                        return match;
                    }
                }
                byte b = tbuf.get();
                if (b != sbuf.get()) {
                    tbuf.position(tbuf.position() - 1);
                    return match;
                }
                incrementChecksum(b);
                matched.add(b);
                match++;
                if (match >= LONGEST_POSSIBLE_MATCH) {
                    debug("longest possible match");
                    //System.err.println("Max match length exceeded.");
                    return match;
                }
            }
        }

        private void readMore() throws IOException {
            if (debug) {
                debug("readMore " + tbuf);
            }
            tbuf.compact();
            c.read(tbuf);
            tbuf.flip();
        }

        void hash() {
            if (tbuf.remaining() >= S) {
                hash = source.checksum.queryChecksum(tbuf, S);
                if (duplicateChecksum) {
                    hash2 = source.checksum2.queryChecksum(tbuf, S);
                }
                invalidHash = false;
            } else {
                invalidHash = true;
            }
            hashReset = false;
        }

        /**
         * Returns a debug
         * <code>String</code>.
         */
        @Override
        public String toString() {
            return "Target["
                    + " targetBuff=" + dump() + // this.tbuf +
                    " sourceBuff=" + this.sbuf
                    + " hashf=" + this.hash
                    + " eof=" + this.eof
                    + "]";
        }

        private String dump() {
            return dump(tbuf);
        }

        private String dump(ByteBuffer bb) {
            return getTextDump(bb);
        }

        private void append(StringBuffer sb, int value) {
            char b1 = (char) ((value >> 4) & 0x0F);
            char b2 = (char) ((value) & 0x0F);
            sb.append(Character.forDigit(b1, 16));
            sb.append(Character.forDigit(b2, 16));
        }

        public String getTextDump(ByteBuffer bb) {
            StringBuffer sb = new StringBuffer(bb.remaining() * 2);
            bb.mark();
            while (bb.hasRemaining()) {
                int val = bb.get();
                if (val > 32 && val < 127) {
                    sb.append(" ").append((char) val);
                } else {
                    append(sb, val);
                }
            }
            bb.reset();
            return sb.toString();
        }
    }

    /**
     * Creates a patch using file names.
     */
    public static void main(String argv[]) throws Exception {
        if (argv.length != 3) {
            System.err.println("usage Delta [-d] source target [output]");
            System.err.println("either -d or an output filename must be specified.");
            System.err.println("aborting..");
            return;
        }
        DiffWriter output = null;
        File sourceFile = null;
        File targetFile = null;
        if (argv[0].equals("-d")) {
            sourceFile = new File(argv[1]);
            targetFile = new File(argv[2]);
            output = new DebugDiffWriter();
        } else {
            sourceFile = new File(argv[0]);
            targetFile = new File(argv[1]);
            output =
                    new GDiffWriter(
                    new DataOutputStream(
                    new BufferedOutputStream(
                    new FileOutputStream(new File(argv[2])))));
        }

        if (sourceFile.length() > Integer.MAX_VALUE
                || targetFile.length() > Integer.MAX_VALUE) {
            System.err.println(
                    "source or target is too large, max length is "
                    + Integer.MAX_VALUE);
            System.err.println("aborting..");
            return;
        }

        Delta d = new Delta();
        d.compute(sourceFile, targetFile, output);

        output.flush();
        output.close();
        if (debug) //gls031504a
        {
            System.out.println("finished generating delta");
        }
    }

    private void debug(String s) {
        if (debug) {
            System.err.println(s);
        }
    }
}
