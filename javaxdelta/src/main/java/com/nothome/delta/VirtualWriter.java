/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.nothome.delta;

import com.nothome.delta.DiffWriter;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 *
 * @author fm
 * simple instructions writer
 * Instructions: byte 1=copy_sourcedata long sourceoffset int sourcelength 
 *               byte 2=add_targetdata int targetlength
 *               byte 3=end
 */
public class VirtualWriter implements DiffWriter {
    
    private DataOutputStream output = null;
    private long dataLength;
    public int filterFactor = 0;
    public long filteredData = 0;
    public long totalLength = 0;
    
    public VirtualWriter (DataOutputStream os) {
        this.output = os;
        dataLength = 0;
    }

    @Override
    public void addCopy(long offset, int length) throws IOException {
        if (length==0) {
            return;
        }
        totalLength += length;
        if (length <= filterFactor) {
            dataLength += length;
            filteredData += length;
            return;
        }
        writeData();
        output.writeByte(1);
        output.writeLong(offset);
        output.writeInt(length);
    }

    @Override
    public void addData(byte b) throws IOException {
        dataLength++;
        totalLength ++;
    }

    @Override
    public void flush() throws IOException {
        writeData();
        output.flush();
    }

    @Override
    public void close() throws IOException {
        writeData();
        output.writeByte(3);
        output.flush();
        output.close();
    }
    
    private void writeData() throws IOException {
        while (dataLength >= Integer.MAX_VALUE) {
            int length = Integer.MAX_VALUE;
            output.writeByte(2);
            output.writeInt(length);
            dataLength -= length;
        }
        if (dataLength>0) {
            output.writeByte(2);
            output.writeInt((int)dataLength);
            dataLength = 0;
        }
    }
    
}
