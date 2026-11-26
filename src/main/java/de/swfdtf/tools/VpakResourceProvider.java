package de.swfdtf.tools;

import de.swfdtf.core.ResourceProvider;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * {@link ResourceProvider} backed by an indexed {@link VpakArchiver.VpakArchive} so standalone
 * builds can boot scripts, textures, audio, and translations directly from {@code game.vpak}
 * without extracting loose asset directories to disk.
 */
public final class VpakResourceProvider implements ResourceProvider {

    private final VpakArchiver.VpakArchive archive;
    private final String sourceDescription;

    private VpakResourceProvider(VpakArchiver.VpakArchive archive, String sourceDescription) {
        this.archive = Objects.requireNonNull(archive, "archive must not be null");
        this.sourceDescription = Objects.requireNonNull(sourceDescription, "sourceDescription must not be null");
    }

    /**
     * Opens {@code vpakFile} with an optional {@code decryptionKey} and wraps it as a
     * {@link VpakResourceProvider}.
     *
     * @param vpakFile      path to the {@code .vpak} archive (must not be {@code null})
     * @param decryptionKey optional passphrase for AES-CTR encrypted archives (nullable/blank for v1)
     * @return opened {@link VpakResourceProvider} handle
     * @throws IOException if the archive cannot be opened or header/TOC decryption fails
     */
    public static VpakResourceProvider open(Path vpakFile, String decryptionKey) throws IOException {
        Objects.requireNonNull(vpakFile, "vpakFile must not be null");
        Path normalized = vpakFile.toAbsolutePath().normalize();
        VpakArchiver.VpakArchive archive = VpakArchiver.open(normalized, decryptionKey);
        String name = normalized.getFileName() != null ? normalized.getFileName().toString() : normalized.toString();
        return new VpakResourceProvider(archive, name);
    }

    @Override
    public boolean contains(String relativePath) {
        return archive.contains(relativePath);
    }

    @Override
    public byte[] readBytes(String relativePath) throws IOException {
        return archive.readEntry(relativePath);
    }

    @Override
    public List<String> listPaths() {
        return archive.listEntries().stream().map(VpakArchiver.VpakEntryInfo::path).toList();
    }

    @Override
    public String getSourceDescription() {
        return sourceDescription;
    }

    @Override
    public void verifyIntegrity() throws IOException {
        for (VpakArchiver.VpakEntryInfo entry : archive.listEntries()) {
            archive.readEntry(entry.path());
        }
    }

    @Override
    public void close() throws IOException {
        archive.close();
    }
}
