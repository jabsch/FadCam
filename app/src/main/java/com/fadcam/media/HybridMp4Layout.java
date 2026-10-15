package com.fadcam.media;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Recognizes a recording that hybrid finalization already finished, from three
 * small reads instead of a walk over every fragment. A finished file is
 * {@code [ftyp][mdat (64-bit size) covering the fragments][moov]}, with the
 * moov ending the file (see FragmentedMp4MuxerWrapper.finalizeWithChannel).
 * Anything else, a damaged layout included, is left to the full finalizer.
 */
public final class HybridMp4Layout {

    private static final int FTYP = 0x66747970;
    private static final int MDAT = 0x6D646174;
    private static final int MOOV = 0x6D6F6F76;

    private HybridMp4Layout() {}

    /** True only when the file is exactly ftyp, a 64-bit mdat, then a moov that ends the file. */
    public static boolean isFinalized(FileChannel ch) throws IOException {
        long fileSize = ch.size();
        ByteBuffer ftyp = read(ch, 0, 8);
        if (ftyp == null || ftyp.getInt(4) != FTYP) return false;
        long mdatPos = ftyp.getInt(0) & 0xFFFFFFFFL;
        if (mdatPos < 8) return false;
        ByteBuffer mdat = read(ch, mdatPos, 16);
        if (mdat == null || mdat.getInt(0) != 1 || mdat.getInt(4) != MDAT) return false;
        long mdatSize = mdat.getLong(8);
        if (mdatSize < 16) return false;
        long moovPos = mdatPos + mdatSize;
        if (moovPos < mdatPos || moovPos + 8 > fileSize) return false;
        ByteBuffer moov = read(ch, moovPos, 8);
        if (moov == null || moov.getInt(4) != MOOV) return false;
        long moovSize = moov.getInt(0) & 0xFFFFFFFFL;
        return moovSize >= 8 && moovPos + moovSize == fileSize;
    }

    /** {@code length} bytes at {@code position}, or null if the file ends first. */
    private static ByteBuffer read(FileChannel ch, long position, int length) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(length);
        while (buf.hasRemaining()) {
            if (ch.read(buf, position + buf.position()) < 0) return null;
        }
        buf.flip();
        return buf;
    }
}
