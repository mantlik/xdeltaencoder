package com.nothome.delta;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.sql.Blob;
import java.sql.SQLException;

public class BlobSeekableSource implements SeekableSource {

    private Blob lob;
    private long pos = 0;
    private InputStream is;

    /**
     * Constructs a new BlobSeekableSource.
     * @throws IOException 
     */
    public BlobSeekableSource(Blob lob) throws IOException {
        this.lob = lob;
        this.is = getStream();
    }

    public void close() throws IOException {
        is.close();
    }
    
    InputStream getStream() throws IOException {
        try {
            return lob.getBinaryStream();
        } catch (SQLException e) {
            throw (IOException)(new IOException().initCause(e));
        }
    }

    public long length() throws IOException {
        try {
            return lob.length();
        } catch (SQLException e) {
            throw (IOException)(new IOException().initCause(e));
        }
    }

    public int read(byte[] b, int off, int len) throws IOException {
        if (is != null)
            return is.read(b, off, len);
        try {
            byte[] read = lob.getBytes(pos, len);
            pos += read.length;
            System.arraycopy(read, 0, b, off, len);
            return read.length;
        } catch (SQLException e) {
            throw (IOException)(new IOException().initCause(e));
        }
    }

    public void seek(long pos) throws IOException {
        if (pos == 0) {
            is = getStream();
        } else {
            is.close();
            is = null;
        }
        this.pos = pos;
    }

    public int read(ByteBuffer bb) throws IOException {
        return 0;
    }
}
