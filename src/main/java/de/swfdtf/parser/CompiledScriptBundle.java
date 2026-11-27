package de.swfdtf.parser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * Compact binary container for precompiled {@code .vjava} label indices and JVM class bytecode
 * embedded inside {@code .vpak} release bundles for instant startup without runtime {@code javac}
 * ({@code #0080}).
 */
public final class CompiledScriptBundle {

    /** Four-byte magic identifying a serialized {@code .vjc} compiled script bundle. */
    public static final int MAGIC = 0x564A4331;

    /** Current binary bundle schema version. */
    public static final int FORMAT_VERSION = 1;

    private static final int MAX_LIST_ENTRIES = 65_536;
    private static final int MAX_CLASS_BYTE_SIZE = 16 * 1024 * 1024;

    /**
     * Deserialized script metadata and precompiled Java block bytecode ready for instant runtime boot.
     *
     * @param entryLabel     configured project entry label
     * @param resolvedLabels ordered list of pre-resolved script labels in the project
     * @param astSummaryJson structured JSON export of the compiled AST metadata
     * @param bytecode       precompiled JVM class bytes and block ID mapping
     */
    public record DeserializedBundle(
            String entryLabel,
            List<String> resolvedLabels,
            String astSummaryJson,
            JavaBlockCompiler.CompiledBytecodePackage bytecode
    ) {
        /** Enforces non-null fields and immutable collections. */
        public DeserializedBundle {
            entryLabel = entryLabel != null && !entryLabel.isBlank() ? entryLabel : "start";
            resolvedLabels = resolvedLabels != null ? List.copyOf(resolvedLabels) : List.of();
            astSummaryJson = astSummaryJson != null ? astSummaryJson : "{}";
            Objects.requireNonNull(bytecode, "bytecode must not be null");
        }

        /**
         * Instantiates runnable {@link JavaBlockCompiler.CompiledProjectBlocks} directly from the
         * deserialized JVM bytecode without invoking {@code javac}.
         *
         * @return instantiated {@link JavaBlockCompiler.CompiledProjectBlocks}
         */
        public JavaBlockCompiler.CompiledProjectBlocks loadCompiledBlocks() {
            return JavaBlockCompiler.loadFromBytecode(bytecode);
        }
    }

    private CompiledScriptBundle() {
    }

    /**
     * Serializes pre-resolved {@code project} metadata and precompiled Java block {@code bytecode}
     * into a CRC32-protected binary payload suitable for storage inside {@code .vpak} archives.
     *
     * @param project  parsed project AST (must not be {@code null})
     * @param bytecode precompiled Java block bytecode package (must not be {@code null})
     * @return serialized {@code .vjc} binary bytes
     */
    public static byte[] serialize(
            VJavaAst.ProjectAst project,
            JavaBlockCompiler.CompiledBytecodePackage bytecode
    ) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(bytecode, "bytecode must not be null");

        String entryLabel = project.descriptor() != null && project.descriptor().getEntryLabel() != null
                ? project.descriptor().getEntryLabel()
                : "start";
        List<String> labels = new ArrayList<>(project.labels().keySet());
        String astJson = AstJsonSerializer.toJson(project);

        ByteArrayOutputStream payloadOut = new ByteArrayOutputStream(4096);
        try (DataOutputStream out = new DataOutputStream(payloadOut)) {
            out.writeUTF(entryLabel);
            out.writeInt(labels.size());
            for (String label : labels) {
                out.writeUTF(label);
            }
            byte[] astBytes = astJson.getBytes(StandardCharsets.UTF_8);
            out.writeInt(astBytes.length);
            out.write(astBytes);

            out.writeBoolean(bytecode.hasUserTypes());
            out.writeInt(bytecode.classBytecode().size());
            for (Map.Entry<String, byte[]> entry : bytecode.classBytecode().entrySet()) {
                out.writeUTF(entry.getKey());
                out.writeInt(entry.getValue().length);
                out.write(entry.getValue());
            }
            out.writeInt(bytecode.snippetClassByBlockId().size());
            for (Map.Entry<String, String> entry : bytecode.snippetClassByBlockId().entrySet()) {
                out.writeUTF(entry.getKey());
                out.writeUTF(entry.getValue());
            }
            out.writeInt(bytecode.emptyBlockIds().size());
            for (String emptyId : bytecode.emptyBlockIds()) {
                out.writeUTF(emptyId);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed serializing CompiledScriptBundle payload", ex);
        }

        byte[] payload = payloadOut.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(payload);

        ByteArrayOutputStream containerOut = new ByteArrayOutputStream(payload.length + 16);
        try (DataOutputStream out = new DataOutputStream(containerOut)) {
            out.writeInt(MAGIC);
            out.writeInt(FORMAT_VERSION);
            out.writeLong(crc.getValue());
            out.writeInt(payload.length);
            out.write(payload);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed writing CompiledScriptBundle container", ex);
        }
        return containerOut.toByteArray();
    }

    /**
     * Deserializes a CRC32-verified {@link CompiledScriptBundle} byte array.
     *
     * @param bytes serialized {@code .vjc} binary payload (must not be {@code null})
     * @return verified {@link DeserializedBundle}
     */
    public static DeserializedBundle deserialize(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes must not be null");
        try (DataInputStream outer = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int magic = outer.readInt();
            if (magic != MAGIC) {
                throw new IllegalArgumentException("Invalid CompiledScriptBundle magic header: 0x"
                        + Integer.toHexString(magic));
            }
            int version = outer.readInt();
            if (version != FORMAT_VERSION) {
                throw new IllegalArgumentException("Unsupported CompiledScriptBundle version: " + version);
            }
            long expectedCrc = outer.readLong();
            int payloadLen = outer.readInt();
            if (payloadLen < 0 || payloadLen > bytes.length) {
                throw new IllegalArgumentException("Invalid CompiledScriptBundle payload length: " + payloadLen);
            }
            byte[] payload = outer.readNBytes(payloadLen);
            if (payload.length != payloadLen) {
                throw new IllegalArgumentException("Truncated CompiledScriptBundle payload");
            }
            CRC32 crc = new CRC32();
            crc.update(payload);
            if (crc.getValue() != expectedCrc) {
                throw new IllegalArgumentException("CompiledScriptBundle CRC32 mismatch");
            }

            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
                String entryLabel = in.readUTF();
                int labelCount = readBoundedCount(in.readInt(), MAX_LIST_ENTRIES, "labelCount");
                List<String> labels = new ArrayList<>(labelCount);
                for (int i = 0; i < labelCount; i++) {
                    labels.add(in.readUTF());
                }
                int astLen = readBoundedCount(in.readInt(), MAX_CLASS_BYTE_SIZE, "astJsonLength");
                byte[] astBytes = in.readNBytes(astLen);
                if (astBytes.length != astLen) {
                    throw new IllegalArgumentException("Truncated AST metadata inside CompiledScriptBundle");
                }
                String astJson = new String(astBytes, StandardCharsets.UTF_8);

                boolean hasUserTypes = in.readBoolean();
                int classCount = readBoundedCount(in.readInt(), MAX_LIST_ENTRIES, "classCount");
                Map<String, byte[]> classBytecode = new LinkedHashMap<>(classCount);
                for (int i = 0; i < classCount; i++) {
                    String name = in.readUTF();
                    int len = readBoundedCount(in.readInt(), MAX_CLASS_BYTE_SIZE, "classByteLength");
                    byte[] classBytes = in.readNBytes(len);
                    if (classBytes.length != len) {
                        throw new IllegalArgumentException("Truncated bytecode for class " + name);
                    }
                    classBytecode.put(name, classBytes);
                }

                int snippetCount = readBoundedCount(in.readInt(), MAX_LIST_ENTRIES, "snippetCount");
                Map<String, String> snippetClassByBlockId = new LinkedHashMap<>(snippetCount);
                for (int i = 0; i < snippetCount; i++) {
                    snippetClassByBlockId.put(in.readUTF(), in.readUTF());
                }

                int emptyCount = readBoundedCount(in.readInt(), MAX_LIST_ENTRIES, "emptyCount");
                Set<String> emptyBlockIds = new LinkedHashSet<>(emptyCount);
                for (int i = 0; i < emptyCount; i++) {
                    emptyBlockIds.add(in.readUTF());
                }

                JavaBlockCompiler.CompiledBytecodePackage pkg = new JavaBlockCompiler.CompiledBytecodePackage(
                        hasUserTypes,
                        classBytecode,
                        snippetClassByBlockId,
                        emptyBlockIds
                );
                return new DeserializedBundle(entryLabel, labels, astJson, pkg);
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("Corrupt CompiledScriptBundle data: " + ex.getMessage(), ex);
        }
    }

    private static int readBoundedCount(int value, int max, String label) {
        if (value < 0 || value > max) {
            throw new IllegalArgumentException("Out-of-bounds " + label + ": " + value);
        }
        return value;
    }
}
