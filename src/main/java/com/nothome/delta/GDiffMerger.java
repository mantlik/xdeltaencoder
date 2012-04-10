/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.nothome.delta;

import com.nothome.delta.DiffWriter;
import com.nothome.delta.GDiffPatcher;
import com.nothome.delta.PatchException;
import com.nothome.delta.SeekableSource;
import static com.nothome.delta.GDiffWriter.*;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author fm
 */
public class GDiffMerger extends GDiffPatcher {

    /**
     * Interval between index entries in bytes
     */
    public static int INDEX_INTERVAL = 1000;
    private DiffWriter writer;
    private SeekableSource source;
    private ByteBuffer bb = ByteBuffer.allocate(1024);
    private Command command = new Command();
    private TreeMap<Long, Long> index = new TreeMap<Long, Long>();

    public GDiffMerger(DiffWriter writer) {
        this.writer = writer;
    }

    @Override
    void append(int length, InputStream patch, OutputStream output) throws IOException {
        for (int i = 0; i < length; i++) {
            writer.addData((byte) patch.read());
        }
    }

    @Override
    void copy(long offset, int length, SeekableSource source, OutputStream output) throws IOException {
        if (this.source == null) {
            this.source = source;
            indexSource();
        }
        copySource(offset, length);
    }

    @Override
    void flush(OutputStream os) throws IOException {
        writer.flush();
        writer.close();
    }

    private void indexSource() {
        long diffOffset;
        long patchOffset = 0;
        long lastIndex = -INDEX_INTERVAL - 1;
        index.clear();
        try {
            source.seek(0);
            bb.clear();
            int bytes = source.read(bb);
            bb.flip();
            if (bytes < 0) {
                return;
            }
            // the magic string is 'd1 ff d1 ff' + the version number
            if ((bb.get() & 0xff) != 0xd1
                    || (bb.get() & 0xff) != 0xff
                    || (bb.get() & 0xff) != 0xd1
                    || (bb.get() & 0xff) != 0xff) {

                throw new PatchException("magic string not found, aborting!");
            }
            int flag = (bb.get() & 0xff);
            if (flag != 0x04) {
                throw new PatchException("magic string not found, aborting!");
            }
            diffOffset = 5;
            command = new Command();
            while (!command.isEnd) {
                command.readCommand(patchOffset, diffOffset);
                if ((patchOffset - lastIndex) >= INDEX_INTERVAL) {
                    index.put(patchOffset, diffOffset);
                    lastIndex = patchOffset;
                    int mb = (int) (diffOffset / 1024 / 1024);
                    System.out.print("Indexing ... " + mb + " mb\r");
                }
                patchOffset += command.patchLength;
                diffOffset += command.commandLength;
            }

        } catch (IOException ex) {
            Logger.getLogger(GDiffMerger.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    private void copySource(long offset, int length) throws IOException {
        //System.out.println("copy o=" + offset + " l=" + length);
        long startOffs = index.firstKey();
        if (offset > startOffs) {
            startOffs = index.lowerKey(offset);
        }
        command = new Command();
        long diffOffs = index.get(startOffs);
        command.readCommand(startOffs, diffOffs);
        //System.out.println("Command: o=" + startOffs + " do=" + diffOffs + " cp=" + command.isCopy
        //        + " o=" + command.sourceOffset + " l=" + command.patchLength);
        if (command.isEnd) {
            return;
        }
        // read commands until current command contains data to copy
        while (offset >= startOffs + command.patchLength) {
            startOffs += command.patchLength;
            diffOffs += command.commandLength;
            command.readCommand(startOffs, diffOffs);
            if (command.isEnd) {
                return;
            }
        }
        // process commands
        while (startOffs < offset + length) {
            if (command.isCopy) {
                long shift = 0;  // skip shift bytes at the beginning
                if (startOffs < offset) {
                    shift = offset - startOffs;
                }
                startOffs += shift;
                long remaining = offset + length - startOffs;
                long copyoffs = command.sourceOffset + shift;
                long copylen = command.patchLength - shift;
                if (copylen > remaining) {
                    copylen = remaining;
                }
                writer.addCopy(copyoffs, (int) copylen);
                //System.out.println(" - copy o=" + copyoffs + " l=" + copylen);
                startOffs += copylen;
            } else {
                // append command
                long remaining = command.patchLength;
                // skip unneeded bytes
                while (startOffs < offset) {
                    startOffs++;
                    bb.get();
                    remaining--;
                    if (!bb.hasRemaining()) {
                        bb.clear();
                        source.read(bb);
                        bb.flip();
                    }
                }
                int dlen = 0;
                while ((remaining > 0) && (startOffs < offset + length)) {
                    writer.addData(bb.get());
                    dlen ++;
                    startOffs++;
                    remaining--;
                    if (!bb.hasRemaining()) {
                        bb.clear();
                        source.read(bb);
                        bb.flip();
                    }
                }
                //System.out.println(" - add l=" + dlen);
            }
            diffOffs += command.commandLength;
            command.readCommand(startOffs, diffOffs);
            if (command.isEnd) {
                return;
            }
        }
    }

    private class Command {

        boolean isCopy;     // false means append
        boolean isEnd;      // true for the END command
        long patchOffset;   // start position in patched file
        long diffOffset;    // offset of the command from the beginning of diff file
        long commandLength; // length of the command in bytes
        long patchLength;   // length of the resulting patch in bytes
        long sourceOffset;   // source offset of copy command

        private void readCommand(long patchOffset, long diffOffset) {
            commandLength = -1;  // unsuccessful result
            try {
                this.diffOffset = diffOffset;
                this.patchOffset = patchOffset;
                bb.clear();
                source.seek(diffOffset);
                int bytes = source.read(bb);
                bb.flip();
                if (bytes <= 0) {
                    return;
                }
                isCopy = false;
                isEnd = false;
                int command = bb.get() & 0xff;
                if (command == EOF) {
                    isEnd = true;
                    commandLength = 1;
                    return;
                }
                int length;
                long offset;

                if (command <= DATA_MAX) {  // implicit append
                    commandLength = command + 1;
                    patchLength = command;
                    return;
                }

                switch (command) {
                    case DATA_USHORT: // ushort, n bytes following; append
                        patchLength = bb.getShort() & 0xffff;
                        commandLength = 3 + patchLength;
                        break;
                    case DATA_INT: // int, n bytes following; append
                        patchLength = bb.getInt() & 0xffffffff;
                        commandLength = 5 + patchLength;
                        break;
                    case COPY_USHORT_UBYTE:
                        isCopy = true;
                        offset = bb.getShort() & 0xffff;
                        length = bb.get() & 0xff;
                        commandLength = 4;
                        patchLength = length;
                        sourceOffset = offset;
                        break;
                    case COPY_USHORT_USHORT:
                        isCopy = true;
                        offset = bb.getShort() & 0xffff;
                        length = bb.getShort() & 0xffff;
                        commandLength = 5;
                        patchLength = length;
                        sourceOffset = offset;
                        break;
                    case COPY_USHORT_INT:
                        isCopy = true;
                        offset = bb.getShort() & 0xffff;
                        length = bb.getInt() & 0xffffffff;
                        commandLength = 7;
                        patchLength = length;
                        sourceOffset = offset;
                        break;
                    case COPY_UBYTE_UBYTE:
                        isCopy = true;
                        offset = bb.get() & 0xff;
                        length = bb.get() & 0xff;
                        commandLength = 3;
                        patchLength = length;
                        sourceOffset = offset;
                        break;
                    case COPY_UBYTE_USHORT:
                        isCopy = true;
                        offset = bb.get() & 0xff;
                        length = bb.getShort() & 0xffff;
                        commandLength = 4;
                        patchLength = length;
                        sourceOffset = offset;
                        break;
                    case COPY_UBYTE_INT:
                        isCopy = true;
                        offset = bb.get() & 0xff;
                        length = bb.getInt() & 0xffffffff;
                        commandLength = 6;
                        patchLength = length;
                        sourceOffset = offset;
                        break;
                    case COPY_INT_UBYTE:
                        isCopy = true;
                        offset = bb.getInt() & 0xffffffff;
                        length = bb.get() & 0xff;
                        commandLength = 6;
                        patchLength = length;
                        sourceOffset = offset;
                        break;
                    case COPY_INT_USHORT:
                        isCopy = true;
                        offset = bb.getInt() & 0xffffffff;
                        length = bb.getShort() & 0xffff;
                        commandLength = 7;
                        patchLength = length;
                        sourceOffset = offset;
                        break;
                    case COPY_INT_INT:
                        isCopy = true;
                        offset = bb.getInt() & 0xffffffff;
                        length = bb.getInt() & 0xffffffff;
                        commandLength = 9;
                        patchLength = length;
                        sourceOffset = offset;
                        break;
                    case COPY_LONG_INT:
                        isCopy = true;
                        offset = bb.getLong();
                        length = bb.getInt() & 0xffffffff;
                        commandLength = 13;
                        patchLength = length;
                        sourceOffset = offset;
                        break;
                    default:
                        throw new IllegalStateException("command " + command);
                }

            } catch (IOException ex) {
                Logger.getLogger(GDiffMerger.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
    }
}
