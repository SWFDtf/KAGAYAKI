package de.swfdtf.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security tests against {@link VpakArchiver} and {@link AssetChecksumVerifier} covering Zip Slip,
 * CRC32 tampering, header/TOC truncation, signed integer overflow, and zip-bomb size claims.
 */
class VpakAndChecksumSecurityTest {

    private static final int HEADER_SIZE = 20;

    @Test
    void rejectsZipSlipTraversalWithoutWritingOutsideTargetDir(@TempDir Path tempDir) throws IOException {
        Path vpak = tempDir.resolve("traversal.vpak");
        writeCraftedVpak(vpak, "../../escaped_secret.txt", "pwn".getBytes(StandardCharsets.UTF_8), 0x123456L);
        Path extractDir = tempDir.resolve("safe_dest");

        try (VpakArchiver.VpakArchive archive = VpakArchiver.open(vpak)) {
            IOException ex = assertThrows(IOException.class, () -> archive.extractAll(extractDir));
            assertTrue(ex.getMessage().contains("unsafe") || ex.getMessage().contains("outside"));
        }
        assertFalse(Files.exists(tempDir.resolve("escaped_secret.txt")));
    }

    @Test
    void rejectsCrcTamperingTruncationIntegerOverflowAndInflatedSizeClaims(@TempDir Path tempDir) throws IOException {
        Path crcVpak = tempDir.resolve("crc_bad.vpak");
        writeCraftedVpak(crcVpak, "data/test.txt", "hello".getBytes(StandardCharsets.UTF_8), 0xDEADBEEFL);
        try (VpakArchiver.VpakArchive archive = VpakArchiver.open(crcVpak)) {
            assertThrows(IOException.class, () -> archive.readEntry("data/test.txt"));
        }

        Path truncated = tempDir.resolve("truncated.vpak");
        Files.write(truncated, new byte[]{0x56, 0x50, 0x41});
        assertThrows(IOException.class, () -> VpakArchiver.open(truncated));

        Path hugeCount = tempDir.resolve("huge_entry_count.vpak");
        ByteBuffer hdr = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
        hdr.put("VPAK".getBytes(StandardCharsets.US_ASCII));
        hdr.putInt(1);
        hdr.putInt(Integer.MAX_VALUE);
        hdr.putLong(HEADER_SIZE);
        Files.write(hugeCount, hdr.array());
        assertThrows(IOException.class, () -> VpakArchiver.open(hugeCount));

        Path longOverflow = tempDir.resolve("long_overflow.vpak");
        byte[] pathBytes = "a.bin".getBytes(StandardCharsets.UTF_8);
        ByteBuffer ov = ByteBuffer.allocate(HEADER_SIZE + 4 + pathBytes.length + 32).order(ByteOrder.BIG_ENDIAN);
        ov.put("VPAK".getBytes(StandardCharsets.US_ASCII));
        ov.putInt(1);
        ov.putInt(1);
        ov.putLong(HEADER_SIZE);
        ov.putInt(pathBytes.length);
        ov.put(pathBytes);
        ov.putLong(HEADER_SIZE);
        ov.putLong(Long.MAX_VALUE);
        ov.putLong(Long.MAX_VALUE);
        ov.putLong(0L);
        Files.write(longOverflow, ov.array());
        assertThrows(IOException.class, () -> VpakArchiver.open(longOverflow));

        // Inflated uncompressedSize claim (zip-bomb style header): must throw IOException without OOM
        Path zipBomb = tempDir.resolve("inflated_claim.vpak");
        byte[] smallCompressed = deflate("tiny".getBytes(StandardCharsets.UTF_8));
        byte[] bombPath = "bomb.txt".getBytes(StandardCharsets.UTF_8);
        long tocOffset = HEADER_SIZE + smallCompressed.length;
        ByteBuffer bombBuf = ByteBuffer.allocate((int) tocOffset + 4 + bombPath.length + 32)
                .order(ByteOrder.BIG_ENDIAN);
        bombBuf.put("VPAK".getBytes(StandardCharsets.US_ASCII));
        bombBuf.putInt(1);
        bombBuf.putInt(1);
        bombBuf.putLong(tocOffset);
        bombBuf.put(smallCompressed);
        bombBuf.putInt(bombPath.length);
        bombBuf.put(bombPath);
        bombBuf.putLong(HEADER_SIZE);
        bombBuf.putLong(Integer.MAX_VALUE - 16);
        bombBuf.putLong(smallCompressed.length);
        CRC32 crc = new CRC32();
        crc.update("tiny".getBytes(StandardCharsets.UTF_8));
        bombBuf.putLong(crc.getValue());
        Files.write(zipBomb, bombBuf.array());

        try (VpakArchiver.VpakArchive archive = VpakArchiver.open(zipBomb)) {
            assertThrows(IOException.class, () -> archive.readEntry("bomb.txt"));
        }
    }

    @Test
    void assetChecksumVerifierFlagsTamperedAndUnexpectedAssets(@TempDir Path tempDir) throws IOException {
        Path project = tempDir.resolve("proj");
        Files.createDirectories(project.resolve("assets/audio"));
        Files.writeString(project.resolve("assets/audio/a.txt"), "alpha", StandardCharsets.UTF_8);

        AssetChecksumVerifier verifier = new AssetChecksumVerifier();
        AssetChecksumVerifier.AssetManifest manifest = verifier.generateManifest(project);

        Files.writeString(project.resolve("assets/audio/a.txt"), "tampered", StandardCharsets.UTF_8);
        Files.writeString(project.resolve("assets/audio/extra.txt"), "extra", StandardCharsets.UTF_8);

        AssetChecksumVerifier.VerificationReport report = verifier.verify(project, manifest);
        assertFalse(report.isIntact());
        assertEquals(List.of("assets/audio/a.txt"), report.modifiedFiles());
        assertEquals(List.of("assets/audio/extra.txt"), report.unexpectedFiles());
    }

    private static void writeCraftedVpak(Path dest, String entryPath, byte[] raw, long crcValue) throws IOException {
        byte[] compressed = deflate(raw);
        byte[] pathBytes = entryPath.getBytes(StandardCharsets.UTF_8);
        long tocOffset = HEADER_SIZE + compressed.length;
        ByteBuffer buf = ByteBuffer.allocate((int) tocOffset + 4 + pathBytes.length + 32)
                .order(ByteOrder.BIG_ENDIAN);
        buf.put("VPAK".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(1);
        buf.putInt(1);
        buf.putLong(tocOffset);
        buf.put(compressed);
        buf.putInt(pathBytes.length);
        buf.put(pathBytes);
        buf.putLong(HEADER_SIZE);
        buf.putLong(raw.length);
        buf.putLong(compressed.length);
        buf.putLong(crcValue);
        Files.write(dest, buf.array());
    }

    private static byte[] deflate(byte[] input) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        deflater.setInput(input);
        deflater.finish();
        byte[] out = new byte[input.length + 64];
        int len = deflater.deflate(out);
        deflater.end();
        byte[] trimmed = new byte[len];
        System.arraycopy(out, 0, trimmed, 0, len);
        return trimmed;
    }
}
