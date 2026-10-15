package com.fadcam.media;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import org.junit.Test;

public class HybridMp4LayoutTest {

    private static byte[] box(String type, int payload) {
        ByteBuffer b = ByteBuffer.allocate(8 + payload);
        b.putInt(8 + payload);
        b.put(type.getBytes());
        return b.array();
    }

    private static byte[] header16(String type, long size) {
        ByteBuffer b = ByteBuffer.allocate(16);
        b.putInt(1);
        b.put(type.getBytes());
        b.putLong(size);
        return b.array();
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        ByteBuffer b = ByteBuffer.allocate(n);
        for (byte[] p : parts) b.put(p);
        return b.array();
    }

    /** ftyp, the init moov, then {@code fragments} moof+mdat pairs, as the muxer writes them. */
    private static byte[] fragmented(int fragments, byte[] placeholder) {
        byte[] body = concat(placeholder, box("moov", 40));
        for (int i = 0; i < fragments; i++) {
            body = concat(body, box("moof", 24), box("mdat", 100));
        }
        return concat(box("ftyp", 12), body);
    }

    private static byte[] finalized(int fragments) {
        byte[] ftyp = box("ftyp", 12);
        byte[] rest = fragmented(fragments, new byte[16]);
        long mdatSize = rest.length - ftyp.length;
        byte[] file = concat(rest, box("moov", 60));
        System.arraycopy(header16("mdat", mdatSize), 0, file, ftyp.length, 16);
        return file;
    }

    private static boolean check(byte[] content) throws Exception {
        File f = File.createTempFile("hybrid", ".mp4");
        try {
            try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
                raf.write(content);
            }
            try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                FileChannel ch = raf.getChannel();
                return HybridMp4Layout.isFinalized(ch);
            }
        } finally {
            f.delete();
        }
    }

    @Test
    public void finishedFileIsRecognized() throws Exception {
        assertTrue(check(finalized(3)));
        assertTrue(check(finalized(0)));
    }

    @Test
    public void openFragmentedFileIsNot() throws Exception {
        byte[] free = concat(box("free", 8));
        assertFalse(check(fragmented(3, free)));
    }

    @Test
    public void moovNotEndingTheFileIsNot() throws Exception {
        // A fragment appended after the moov, or a moov cut short, goes to the full finalizer
        assertFalse(check(concat(finalized(2), box("moof", 24))));
        byte[] full = finalized(2);
        byte[] cut = new byte[full.length - 10];
        System.arraycopy(full, 0, cut, 0, cut.length);
        assertFalse(check(cut));
    }

    @Test
    public void mdatClaimingTheWholeFileIsNot() throws Exception {
        // Damaged layout from an old build: the header covers everything, moov hidden inside
        byte[] ftyp = box("ftyp", 12);
        byte[] rest = fragmented(2, new byte[16]);
        byte[] file = concat(rest, box("moov", 60));
        System.arraycopy(header16("mdat", file.length - ftyp.length), 0, file, ftyp.length, 16);
        assertFalse(check(file));
    }

    @Test
    public void tinyOrForeignFilesAreNot() throws Exception {
        assertFalse(check(new byte[0]));
        assertFalse(check(new byte[5]));
        assertFalse(check(concat(box("abcd", 12), box("moov", 8))));
    }
}
