package de.swfdtf.e2e.tier2;

import de.swfdtf.core.EngineApplication;
import de.swfdtf.core.EngineMode;
import de.swfdtf.e2e.support.E2eCliRunner;
import de.swfdtf.e2e.support.E2eFixtureFactory;
import de.swfdtf.e2e.support.E2ePendingFeatureAssumptions;
import de.swfdtf.e2e.support.E2eWorkspaceSupport;
import de.swfdtf.tools.AssetChecksumVerifier;
import de.swfdtf.tools.StandaloneBuilder;
import de.swfdtf.tools.VpakArchiver;
import de.swfdtf.tools.VpakManifestInspector;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier 2 security, integrity, and negative boundary E2E tests for Feature Area F11:
 * Zip Slip traversal, CRC32 tampering, header/TOC truncation, integer overflow claims,
 * encrypted archive tampering, lint-error abort, and corrupt plugin JAR handling.
 */
@Tag(E2ePendingFeatureAssumptions.TAG_TIER2)
public final class F11PackagingSecurityBoundaryE2eTest {

    /** Creates a new {@code F11PackagingSecurityBoundaryE2eTest} instance. */
    public F11PackagingSecurityBoundaryE2eTest() {
    }

    private static final int HEADER_SIZE = 20;

    /**
     * Verifies goal {@code #0946}: {@link VpakArchiver} blocks Zip Slip path traversal entries
     * such as {@code ../../evil_escape.txt} during {@code extractAll} without writing outside
     * the target directory.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if creating the test archive fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_BASELINE)
    void vpakExtractBlocksPathTraversalZipSlip(@TempDir Path tempDir) throws Exception {
        Path crafted = tempDir.resolve("zip_slip.vpak");
        byte[] payload = "pwned".getBytes(StandardCharsets.UTF_8);
        writeSingleEntryVpak(crafted, "../../evil_escape.txt", payload);

        Path extractDir = tempDir.resolve("safe_extract");
        try (VpakArchiver.VpakArchive archive = VpakArchiver.open(crafted)) {
            IOException ex = assertThrows(IOException.class, () -> archive.extractAll(extractDir));
            assertTrue(ex.getMessage().contains("unsafe") || ex.getMessage().contains("outside"));
        }
        assertFalse(Files.exists(tempDir.resolve("evil_escape.txt")));
    }

    /**
     * Verifies goal {@code #0946}: {@link VpakArchiver} detects CRC32 payload tampering and
     * {@link VpakManifestInspector} marks {@code integrityVerified == false}.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if packing or reading fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_BASELINE)
    void vpakDetectsCrc32Tampering(@TempDir Path tempDir) throws Exception {
        Path projectDir = preparePackableProject(tempDir.resolve("proj_crc"));
        Path vpakFile = tempDir.resolve("tampered.vpak");
        E2eCliRunner.packVpak(projectDir, vpakFile);

        byte[] raw = Files.readAllBytes(vpakFile);
        // Flip a byte inside the first compressed entry payload immediately after the 20-byte header.
        raw[HEADER_SIZE] ^= 0x55;
        Files.write(vpakFile, raw);

        VpakManifestInspector.VpakInspectionReport report = new VpakManifestInspector().inspect(vpakFile);
        assertFalse(report.integrityVerified(), "Tampered archive must fail integrity verification");
        assertFalse(report.corruptedEntries().isEmpty());

        try (VpakArchiver.VpakArchive archive = VpakArchiver.open(vpakFile)) {
            String firstEntry = archive.listEntries().get(0).path();
            assertThrows(IOException.class, () -> archive.readEntry(firstEntry));
        }
    }

    /**
     * Verifies goal {@code #0946}: {@link VpakArchiver#open(Path)} cleanly rejects truncated
     * headers, wrong magic bytes, unsupported versions, and truncated TOCs.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if writing crafted binaries fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_BASELINE)
    void vpakRejectsTruncatedHeaderWrongMagicAndTruncatedToc(@TempDir Path tempDir) throws Exception {
        Path shortFile = tempDir.resolve("short.vpak");
        Files.write(shortFile, new byte[]{1, 2, 3, 4, 5});
        assertThrows(IOException.class, () -> VpakArchiver.open(shortFile));

        Path badMagic = tempDir.resolve("bad_magic.vpak");
        ByteBuffer magicBuf = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
        magicBuf.put("NOPE".getBytes(StandardCharsets.US_ASCII));
        magicBuf.putInt(1);
        magicBuf.putInt(0);
        magicBuf.putLong(HEADER_SIZE);
        Files.write(badMagic, magicBuf.array());
        assertThrows(IOException.class, () -> VpakArchiver.open(badMagic));

        Path badVersion = tempDir.resolve("bad_ver.vpak");
        ByteBuffer verBuf = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
        verBuf.put("VPAK".getBytes(StandardCharsets.US_ASCII));
        verBuf.putInt(99);
        verBuf.putInt(0);
        verBuf.putLong(HEADER_SIZE);
        Files.write(badVersion, verBuf.array());
        assertThrows(IOException.class, () -> VpakArchiver.open(badVersion));

        Path truncatedToc = tempDir.resolve("trunc_toc.vpak");
        ByteBuffer tocBuf = ByteBuffer.allocate(HEADER_SIZE + 4).order(ByteOrder.BIG_ENDIAN);
        tocBuf.put("VPAK".getBytes(StandardCharsets.US_ASCII));
        tocBuf.putInt(1);
        tocBuf.putInt(2);
        tocBuf.putLong(HEADER_SIZE);
        tocBuf.putInt(10); // claims 10-byte path with 0 bytes remaining
        Files.write(truncatedToc, tocBuf.array());
        assertThrows(IOException.class, () -> VpakArchiver.open(truncatedToc));
    }

    /**
     * Verifies goal {@code #0946}: malicious {@code Integer.MAX_VALUE} {@code entryCount} and
     * inflated size claims are rejected cleanly without {@link OutOfMemoryError}.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if writing crafted binaries fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void vpakRejectsIntegerOverflowClaimsWithoutOom(@TempDir Path tempDir) throws Exception {
        // 1. 0-entry TOC with entryCount = Integer.MAX_VALUE
        Path hugeCount = tempDir.resolve("huge_count.vpak");
        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
        buf.put("VPAK".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(1);
        buf.putInt(Integer.MAX_VALUE);
        buf.putLong(HEADER_SIZE);
        Files.write(hugeCount, buf.array());
        assertThrows(IOException.class, () -> VpakArchiver.open(hugeCount));

        // 2. 1 valid TOC entry + inflated entryCount = Integer.MAX_VALUE (must not OOM LinkedHashMap)
        Path hugeCountWithEntry = tempDir.resolve("huge_count_one_entry.vpak");
        byte[] entryPath = "a.txt".getBytes(StandardCharsets.UTF_8);
        long oneEntryTocOffset = HEADER_SIZE + 1L;
        int oneEntryTocBytes = 4 + entryPath.length + 32;
        ByteBuffer oneBuf = ByteBuffer.allocate((int) oneEntryTocOffset + oneEntryTocBytes)
                .order(ByteOrder.BIG_ENDIAN);
        oneBuf.put("VPAK".getBytes(StandardCharsets.US_ASCII));
        oneBuf.putInt(1);
        oneBuf.putInt(Integer.MAX_VALUE);
        oneBuf.putLong(oneEntryTocOffset);
        oneBuf.put((byte) 0); // 1-byte dummy payload
        oneBuf.putInt(entryPath.length);
        oneBuf.put(entryPath);
        oneBuf.putLong(HEADER_SIZE);
        oneBuf.putLong(1L);
        oneBuf.putLong(1L);
        oneBuf.putLong(0L);
        Files.write(hugeCountWithEntry, oneBuf.array());
        assertThrows(IOException.class, () -> VpakArchiver.open(hugeCountWithEntry));

        // 3. Signed long overflow: dataOffset + compressedSize wraps negative when compressedSize = Long.MAX_VALUE
        Path overflowSizes = tempDir.resolve("overflow_sizes.vpak");
        byte[] overflowPath = "overflow.bin".getBytes(StandardCharsets.UTF_8);
        int overflowTocBytes = 4 + overflowPath.length + 32;
        ByteBuffer ovBuf = ByteBuffer.allocate(HEADER_SIZE + overflowTocBytes).order(ByteOrder.BIG_ENDIAN);
        ovBuf.put("VPAK".getBytes(StandardCharsets.US_ASCII));
        ovBuf.putInt(1);
        ovBuf.putInt(1);
        ovBuf.putLong(HEADER_SIZE);
        ovBuf.putInt(overflowPath.length);
        ovBuf.put(overflowPath);
        ovBuf.putLong(HEADER_SIZE);
        ovBuf.putLong(Long.MAX_VALUE);
        ovBuf.putLong(Long.MAX_VALUE);
        ovBuf.putLong(0L);
        Files.write(overflowSizes, ovBuf.array());
        assertThrows(IOException.class, () -> VpakArchiver.open(overflowSizes));
    }

    /**
     * Verifies goal {@code #0946}: {@link AssetChecksumVerifier} detects missing, modified, and
     * unexpected asset files as well as malformed manifest JSON.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if asset manipulation fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_BASELINE)
    void assetChecksumVerifierDetectsMissingModifiedAndUnexpectedAssets(@TempDir Path tempDir) throws Exception {
        Path projectDir = preparePackableProject(tempDir.resolve("proj_manifest_tamper"));
        AssetChecksumVerifier verifier = new AssetChecksumVerifier();
        AssetChecksumVerifier.AssetManifest baseline = verifier.generateManifest(projectDir);

        // Modify PNG, delete WAV, add unexpected asset
        E2eFixtureFactory.writeSolidPng(
                projectDir.resolve("assets/images/bg/bg_pack.png"), 64, 64, 255, 0, 0, 255);
        Files.delete(projectDir.resolve("assets/audio/tone.wav"));
        Files.writeString(
                projectDir.resolve("assets/extra_untracked.txt"), "extra", StandardCharsets.UTF_8);

        AssetChecksumVerifier.VerificationReport report = verifier.verify(projectDir, baseline);
        assertFalse(report.isIntact());
        assertEquals(List.of("assets/audio/tone.wav"), report.missingFiles());
        assertEquals(List.of("assets/images/bg/bg_pack.png"), report.modifiedFiles());
        assertEquals(List.of("assets/extra_untracked.txt"), report.unexpectedFiles());

        assertThrows(
                IllegalArgumentException.class,
                () -> AssetChecksumVerifier.AssetManifest.fromJson("{not_valid_json")
        );
        assertThrows(
                RuntimeException.class,
                () -> AssetChecksumVerifier.AssetManifest.fromJson("{\"missingAlgorithm\":true}")
        );
    }

    /**
     * Verifies {@link StandaloneBuilder#build(Path, Path, Path)} aborts with
     * {@link IllegalStateException} when the project contains lint errors.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if copying broken fixture fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_BASELINE)
    void standaloneBuilderAbortsWhenProjectHasLintErrors(@TempDir Path tempDir) throws Exception {
        Path brokenDir = tempDir.resolve("f11_broken");
        E2eWorkspaceSupport.copyResourceTree("e2e/f11/broken_project", brokenDir);
        Path distDir = tempDir.resolve("broken_dist");

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> E2eCliRunner.exportStandalone(brokenDir, distDir, null)
        );
        assertTrue(ex.getMessage().contains("error") || ex.getMessage().contains("Lint"));
        assertFalse(Files.exists(distDir.resolve("game.vpak")));
    }

    /**
     * Verifies goal {@code #0767} and {@code #0770}: encrypted {@code .vpak} rejects wrong key /
     * tampered ciphertext cleanly, and corrupt {@code plugins/*.jar} does not crash engine startup.
     *
     * @param tempDir temporary test workspace
     * @throws Exception if setup fails
     */
    @Test
    @Tag(E2ePendingFeatureAssumptions.TAG_PENDING_WAVE)
    void encryptedVpakWrongKeyAndCorruptPluginJarHandledSafely(@TempDir Path tempDir) throws Exception {
        Path projectDir = preparePackableProject(tempDir.resolve("proj_enc_bad"));
        Path descriptorFile = projectDir.resolve("project.vjava.json");
        String descriptorJson = Files.readString(descriptorFile, StandardCharsets.UTF_8);
        String withEncKey = descriptorJson.substring(0, descriptorJson.lastIndexOf('}'))
                + ",\n  \"vpakEncryptionKey\": \"correct-key\"\n}\n";
        Files.writeString(descriptorFile, withEncKey, StandardCharsets.UTF_8);

        Path distDir = tempDir.resolve("dist_enc_bad");
        E2eCliRunner.exportStandalone(projectDir, distDir, null);

        // Control check: --vpak-key with the correct key must SUCCEED (fails if --vpak-key is unimplemented).
        E2eCliRunner.ProcessResult goodKeyResult = E2eCliRunner.runJavaMainWithMacFirstThread(
                "de.swfdtf.StandaloneMain",
                List.of(
                        "--standalone",
                        distDir.toString(),
                        "--vpak-key",
                        "correct-key",
                        "--headless",
                        "--smoke-test"
                ),
                distDir,
                Duration.ofSeconds(20)
        );
        assertTrue(
                goodKeyResult.isSuccess(),
                "Correct --vpak-key must boot encrypted game.vpak, stderr=" + goodKeyResult.stderr()
        );

        E2eCliRunner.ProcessResult wrongKeyResult = E2eCliRunner.runJavaMainWithMacFirstThread(
                "de.swfdtf.StandaloneMain",
                List.of(
                        "--standalone",
                        distDir.toString(),
                        "--vpak-key",
                        "wrong-key",
                        "--headless",
                        "--smoke-test"
                ),
                distDir,
                Duration.ofSeconds(20)
        );
        assertFalse(wrongKeyResult.isSuccess(), "Wrong decryption key must fail cleanly");
        String wrongCombined = (wrongKeyResult.stdout() + "\n" + wrongKeyResult.stderr()).toLowerCase(Locale.ROOT);
        assertFalse(
                wrongCombined.contains("unknown cli argument") || wrongCombined.contains("unknown argument"),
                "Failure must be decryption/integrity error, not unknown --vpak-key flag: " + wrongCombined
        );
        assertTrue(
                wrongCombined.contains("key")
                        || wrongCombined.contains("decrypt")
                        || wrongCombined.contains("cipher")
                        || wrongCombined.contains("crc")
                        || wrongCombined.contains("integrity")
                        || wrongCombined.contains("vpak")
                        || wrongCombined.contains("corrupt"),
                "Expected decryption/integrity error for wrong key, got: " + wrongCombined
        );

        // #0767 acceptance: CRC32 / corruption checks must also reject tampered encrypted .vpak.
        Path vpakFile = distDir.resolve("game.vpak");
        byte[] encryptedBytes = Files.readAllBytes(vpakFile);
        encryptedBytes[HEADER_SIZE + 4] ^= 0x55;
        Files.write(vpakFile, encryptedBytes);
        E2eCliRunner.ProcessResult tamperedResult = E2eCliRunner.runJavaMainWithMacFirstThread(
                "de.swfdtf.StandaloneMain",
                List.of(
                        "--standalone",
                        distDir.toString(),
                        "--vpak-key",
                        "correct-key",
                        "--headless",
                        "--smoke-test"
                ),
                distDir,
                Duration.ofSeconds(20)
        );
        assertFalse(tamperedResult.isSuccess(), "Tampered encrypted .vpak must fail integrity check");

        Path pluginProj = preparePackableProject(tempDir.resolve("proj_bad_plugin"));
        Path pluginsDir = pluginProj.resolve("plugins");
        Files.createDirectories(pluginsDir);
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(pluginsDir.resolve("broken.jar")))) {
            jos.putNextEntry(new JarEntry("META-INF/services/de.swfdtf.core.EnginePlugin"));
            jos.write("de.swfdtf.nonexistent.MissingPluginClass\n".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        try (EngineApplication app = E2eCliRunner.startHeadlessEngine(
                pluginProj,
                EngineMode.STANDALONE_GAME,
                true
        )) {
            app.initialize();
            app.run();
        }
    }

    private static Path preparePackableProject(Path projectDir) throws IOException {
        E2eWorkspaceSupport.copyResourceTree("e2e/f11/packable_project", projectDir);
        E2eFixtureFactory.writeSolidPng(
                projectDir.resolve("assets/images/bg/bg_pack.png"), 64, 64, 60, 90, 140, 255);
        E2eFixtureFactory.writeSineWav(
                projectDir.resolve("assets/audio/tone.wav"), 440.0, 0.1, 22_050);
        return projectDir;
    }

    private static void writeSingleEntryVpak(Path dest, String entryPath, byte[] rawPayload) throws IOException {
        CRC32 crc = new CRC32();
        crc.update(rawPayload);
        byte[] compressed = deflate(rawPayload);
        byte[] pathBytes = entryPath.getBytes(StandardCharsets.UTF_8);

        long dataOffset = HEADER_SIZE;
        long tocOffset = HEADER_SIZE + compressed.length;
        int tocSize = 4 + pathBytes.length + 32;

        ByteBuffer buf = ByteBuffer.allocate((int) tocOffset + tocSize).order(ByteOrder.BIG_ENDIAN);
        buf.put("VPAK".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(1);
        buf.putInt(1);
        buf.putLong(tocOffset);
        buf.put(compressed);
        buf.putInt(pathBytes.length);
        buf.put(pathBytes);
        buf.putLong(dataOffset);
        buf.putLong(rawPayload.length);
        buf.putLong(compressed.length);
        buf.putLong(crc.getValue());
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
