package de.swfdtf.core;

import java.io.IOException;
import java.util.List;

/**
 * Abstraction for read-only access to packed or filesystem-backed project resources
 * ({@code project.vjava.json}, {@code .vjava} scripts, textures, audio, and translations).
 */
public interface ResourceProvider extends AutoCloseable {

    /**
     * Checks whether a resource exists at {@code relativePath}.
     *
     * @param relativePath relative Unix-style path inside the project root or archive
     * @return {@code true} if the resource is present
     */
    boolean contains(String relativePath);

    /**
     * Reads and returns all bytes of the resource at {@code relativePath}.
     *
     * @param relativePath relative Unix-style path inside the project root or archive
     * @return decoded resource payload bytes
     * @throws IOException if the resource is missing, cannot be decrypted, or fails integrity verification
     */
    byte[] readBytes(String relativePath) throws IOException;

    /**
     * Returns an immutable list of all normalized relative resource paths known to this provider.
     *
     * @return immutable list of resource paths
     */
    List<String> listPaths();

    /**
     * Returns a short human-readable description of the backing source (e.g. {@code "game.vpak"}).
     *
     * @return backing source label
     */
    String getSourceDescription();

    /**
     * Performs full integrity verification across all entries supplied by this provider.
     *
     * @throws IOException if any entry is corrupt or fails cryptographic/checksum validation
     */
    default void verifyIntegrity() throws IOException {
    }

    @Override
    default void close() throws IOException {
    }
}
