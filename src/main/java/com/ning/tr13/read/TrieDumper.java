package com.ning.tr13.read;

import java.io.*;
import java.util.Arrays;

import com.ning.tr13.KeyValueReader;
import com.ning.tr13.TrieConstants;
import com.ning.tr13.util.VInt;

/**
 * Simple utility class that can read a Trie file, and dump
 * its contents in format that default
 * {@link com.ning.tr13.KeyValueReader} could read.
 */
public class TrieDumper
    extends TrieConstants
{
    private final long[] tmpLongValueBuffer = new long[1];

    private final static byte[] LF = new byte[] { '\n' };
    
    protected final char valueSeparator;
    
    public TrieDumper(char valueSeparator) {
        this.valueSeparator = valueSeparator;
    }
    
    public void dump(InputStream in, OutputStream out) throws IOException
    {
        // header:
        byte[] header = new byte[16];
        readFully(in, header);
        // First: let's verify signature
        for (int i = 0, len = HEADER_TEMPLATE.length; i < len; ++i) {
            if (header[i] != HEADER_TEMPLATE[i]) {
                throw new IOException("Malformed input: no valid trie header found (first 8 bytes)");
            }
        }

        long len = header[8];
        for (int i = 9; i < 16; ++i) {
            len = (len << 8) | (header[i] & 0xFF);
        }
        byte[] payload = new byte[(int) len];
        readFully(in, payload);

        // Ok, let's traverse then
        byte[] keyBuffer = new byte[200];
        readAndDump(out, payload, 0, keyBuffer, 0);
    }

    protected int readAndDump(OutputStream out,
            byte[] block, int offset, byte[] keyBuffer, int keyLen) throws IOException
    {
        // First things first: block type, length
        int firstByte = block[offset];
        if ((firstByte & 0x80)  == 0) { // leaf
            // leaf types have 6 bits in first byte for value
            offset = VInt.bytesToUnsigned(FIRST_BYTE_BITS_FOR_LEAVES, block, offset, tmpLongValueBuffer);
            long value = tmpLongValueBuffer[0];
            if ((firstByte & 0x40)  == 0) { // simple
                // simple leaf only has value, so output stuff as is
                _writeValue(out, keyBuffer, keyLen, value, null, 0, 0);
            } else { // valued
                int lenOffset = offset;
                // suffix-leaf has additional key suffix following value
                offset = VInt.bytesToUnsigned(8, block, offset, tmpLongValueBuffer);
                long l = tmpLongValueBuffer[0];
                // let's do some sanity checks
                if (l < 0) {
                    throw new IOException("Corrupt trie structure: negative suffix length at index "+lenOffset);                    
                }
                if ((offset + l) > block.length) {
                    throw new IOException("Corrupt trie structure: leaf suffix length "+l+" (at offset "+lenOffset+") would extend past input end");                    
                }
                int suffixLen = (int) l;
                _writeValue(out, keyBuffer, keyLen, value, block, offset, suffixLen);
                offset += suffixLen;
            }
            return offset;
        }

        // Nope; branch
        // leaf types have 6 bits in first byte for value
        int origOffset = offset;
        offset = VInt.bytesToUnsigned(FIRST_BYTE_BITS_FOR_BRANCHES, block, offset, tmpLongValueBuffer);
        long blockLen = tmpLongValueBuffer[0];
        if (blockLen < 0L) { // sanity check
            throw new IOException("Corrupt trie structure: branch had negative block length at index "+origOffset);
        }
        final long end = offset + blockLen;
        origOffset = offset;
        
        if ((firstByte & 0x40)  == 0) { // simple branch
            do {
                byte nextByte = block[offset++];
                keyBuffer = _appendKey(keyBuffer, nextByte, keyLen);
                offset = readAndDump(out, block, offset, keyBuffer, keyLen+1);
            } while (offset < end);
            if (offset != end) { // sanity check
                throw new IOException("Corrupt trie structure: simple branch block declared to extend from "
                        +origOffset+" to "+(end-1)+"; extended to "+(offset-1));
            }
        } else { // branch with value
            // followed by value, in this case
            offset = VInt.bytesToUnsigned(8, block, offset, tmpLongValueBuffer);
            // which we need to output first
            _writeValue(out, keyBuffer, keyLen, tmpLongValueBuffer[0], null, 0, 0);
            do {
                byte nextByte = block[offset++];
                keyBuffer = _appendKey(keyBuffer, nextByte, keyLen);
                offset = readAndDump(out, block, offset, keyBuffer, keyLen+1);
            } while (offset < end);
            if (offset != end) { // sanity check
                throw new IOException("Corrupt trie structure: value branch block declared to extend from "
                        +origOffset+" to "+(end-1)+"; extended to "+(offset-1));
            }
        }
        return offset;
    }

    private void _writeValue(OutputStream out, byte[] keyBuffer, int keyLen, long value,
            byte[] extraKey, int extraKeyOffset, int extraKeyLen) throws IOException
    {
        out.write(keyBuffer, 0, keyLen);
        if (extraKey != null) {
            out.write(extraKey, extraKeyOffset, extraKeyLen);
        }
        out.write(valueSeparator);
        // numbers are ASCII, so let's just do:
        String numStr = String.valueOf(value);
        for (int i = 0, len = numStr.length(); i < len; ++i) {
            out.write(numStr.charAt(i));
        }
        out.write(LF);
    }

    private byte[] _appendKey(byte[] keyBuffer, byte nextKeyByte, int keyLen)
    {
        if (keyLen >= keyBuffer.length) {
            keyBuffer = Arrays.copyOf(keyBuffer, keyBuffer.length * 2);
        }
        keyBuffer[keyLen] = nextKeyByte;
        return keyBuffer;
    }
    
    private void readFully(InputStream in, byte[] buffer) throws IOException
    {
        int offset = 0;
        while (offset < buffer.length) {
            int count = in.read(buffer, offset, buffer.length - offset);
            if (count < 1) {
                throw new IllegalArgumentException("Could not read "+buffer.length+" bytes; only got "+offset);
            }
            offset += count;
        }
    }
    
    public static void main(String[] args) throws IOException
    {
        if (args.length != 1) {
            System.err.println("USAGE: java ... [trie-file]");
            System.exit(1);
        }      
        FileInputStream in = new FileInputStream(args[0]);
        BufferedOutputStream out = new BufferedOutputStream(System.out);
        new TrieDumper(KeyValueReader.DEFAULT_SEPARATOR_CHAR).dump(in, out); 
        in.close();
        out.flush();
        System.out.flush();
    }
}
