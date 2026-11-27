package de.swfdtf.state;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import de.swfdtf.state.SaveSlotData.SaveMetadata;
import de.swfdtf.state.SaveSlotData.SavedVoiceBookmark;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * High-level save integrity, portable `.vsave` packaging, cloud-sync merging, multi-profile isolation,
 * quick-save rings, trash/undo recovery, telemetry, and migration batch validation services
 * ({@code #0520}–{@code #0595}, excluding P3 {@code #0530}).
 */
public final class SaveWorkflowServices {

    private SaveWorkflowServices() {
    }

    /**
     * Save integrity signing/verification, GZIP/UTF-8 auto-detection, `.vsave` transport packaging,
     * persistent auto-repair, and reflective snapshot diffing
     * ({@code #0520}, {@code #0521}, {@code #0534}, {@code #0556}, {@code #0562}, {@code #0569},
     * {@code #0583}, {@code #0589}, {@code #0592}).
     */
    public static final class SaveIntegrityAndCodec {

        private static final String HMAC_ALG = "HmacSHA256";
        private static final byte[] BUILTIN_SEAL_KEY = "kagayaki-sealed-secret-v1".getBytes(StandardCharsets.UTF_8);

        /**
         * Report returned by persistent file auto-repair ({@code #0562}).
         *
         * @param repaired       {@code true} if corrupt/empty file was repaired
         * @param backupPath     path to {@code .corrupt} backup if created (nullable)
         * @param warningMessage diagnostic user-facing warning message
         */
        public record PersistentRepairReport(boolean repaired, Optional<Path> backupPath, String warningMessage) {
        }

        /**
         * Result returned by {@link #importVsavePackage}.
         *
         * @param slotData  imported {@link SaveSlotData}
         * @param projectId embedded project identifier from the package manifest
         */
        public record ImportedVsaveResult(SaveSlotData slotData, String projectId) {
        }

        /**
         * Creates a {@code SaveIntegrityAndCodec}.
         */
        public SaveIntegrityAndCodec() {
        }

        /**
         * Computes lowercase hex HMAC-SHA256 for {@code payload} using {@code secret}.
         *
         * @param payload payload bytes
         * @param secret  HMAC key bytes
         * @return 64-character hex digest
         */
        public static String computeHmacSha256Hex(byte[] payload, byte[] secret) {
            Objects.requireNonNull(payload, "payload must not be null");
            Objects.requireNonNull(secret, "secret must not be null");
            try {
                Mac mac = Mac.getInstance(HMAC_ALG);
                mac.init(new SecretKeySpec(secret, HMAC_ALG));
                byte[] digest = mac.doFinal(payload);
                StringBuilder hex = new StringBuilder(digest.length * 2);
                for (byte b : digest) {
                    hex.append(Character.forDigit((b >>> 4) & 0x0F, 16));
                    hex.append(Character.forDigit(b & 0x0F, 16));
                }
                return hex.toString();
            } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
                throw new IllegalStateException("HMAC-SHA256 unavailable: " + ex.getMessage(), ex);
            }
        }

        /**
         * Wraps raw UTF-8 JSON inside an integrity envelope containing an HMAC-SHA256 signature ({@code #0520}).
         *
         * @param utf8Json valid JSON string
         * @param secret   integrity secret passphrase
         * @return signed JSON envelope string
         */
        public static String wrapSignedJson(String utf8Json, String secret) {
            Objects.requireNonNull(utf8Json, "utf8Json must not be null");
            Objects.requireNonNull(secret, "secret must not be null");
            JsonElement parsed = validateNonTruncatedJson(utf8Json);
            String canonical = parsed.toString();
            String hmac = computeHmacSha256Hex(
                    canonical.getBytes(StandardCharsets.UTF_8),
                    secret.getBytes(StandardCharsets.UTF_8)
            );
            JsonObject env = new JsonObject();
            env.addProperty("format", "kagayaki-signed-save-v1");
            env.addProperty("hmacSha256", hmac);
            env.add("payload", parsed);
            return env.toString();
        }

        /**
         * Validates JSON structural completeness and HMAC-SHA256 signature, rejecting truncated or
         * tampered save payloads before object deserialization ({@code #0520}).
         *
         * @param signedEnvelope signed JSON string
         * @param secret         expected integrity secret
         * @return verified inner JSON string
         * @throws IOException if JSON is truncated, malformed, or HMAC verification fails
         */
        public static String verifyAndUnwrapSignedJson(String signedEnvelope, String secret) throws IOException {
            Objects.requireNonNull(signedEnvelope, "signedEnvelope must not be null");
            Objects.requireNonNull(secret, "secret must not be null");
            JsonElement parsed;
            try {
                parsed = validateNonTruncatedJson(signedEnvelope);
            } catch (IllegalArgumentException ex) {
                throw new IOException("Corrupt or truncated JSON save payload: " + ex.getMessage(), ex);
            }
            if (!parsed.isJsonObject()) {
                throw new IOException("Save envelope is not a JSON object");
            }
            JsonObject obj = parsed.getAsJsonObject();
            if (!obj.has("hmacSha256") || !obj.has("payload")) {
                throw new IOException("Missing HMAC envelope fields");
            }
            String expected = obj.get("hmacSha256").getAsString();
            JsonElement payloadElem = obj.get("payload");
            String canonical = payloadElem.toString();
            String actual = computeHmacSha256Hex(
                    canonical.getBytes(StandardCharsets.UTF_8),
                    secret.getBytes(StandardCharsets.UTF_8)
            );
            if (!MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.US_ASCII),
                    actual.getBytes(StandardCharsets.US_ASCII))) {
                throw new IOException("Save integrity HMAC check failed (file modified or corrupt)");
            }
            return canonical;
        }

        /**
         * Validates that JSON text parses completely and is not truncated.
         *
         * @param rawJson candidate JSON text
         * @return parsed root {@link JsonElement}
         */
        public static JsonElement validateNonTruncatedJson(String rawJson) {
            if (rawJson == null || rawJson.isBlank()) {
                throw new IllegalArgumentException("Empty JSON payload");
            }
            try {
                JsonElement elem = JsonParser.parseString(rawJson);
                if (elem == null || elem.isJsonNull()) {
                    throw new IllegalArgumentException("Null JSON root");
                }
                return elem;
            } catch (JsonParseException ex) {
                throw new IllegalArgumentException("Truncated or invalid JSON: " + ex.getMessage(), ex);
            }
        }

        /**
         * Serializes an arbitrary object/JSON payload to UTF-8 bytes, optionally pretty-printed
         * ({@code #0569}) and GZIP-compressed ({@code #0534}, {@code #0592}).
         *
         * @param value        object or already-built {@link JsonElement}
         * @param compressGzip {@code true} to GZIP-compress bytes
         * @param prettyPrint  {@code true} for readable indented JSON
         * @return UTF-8 JSON bytes or GZIP bytes
         * @throws IOException if GZIP compression fails
         */
        public static byte[] encodeJsonPayload(Object value, boolean compressGzip, boolean prettyPrint)
                throws IOException {
            Objects.requireNonNull(value, "value must not be null");
            Gson gson = SaveValueCodec.createGson(prettyPrint);
            String json = gson.toJson(value);
            byte[] utf8 = json.getBytes(StandardCharsets.UTF_8);
            if (!compressGzip) {
                return utf8;
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(128, utf8.length / 2));
            try (GZIPOutputStream gz = new GZIPOutputStream(baos)) {
                gz.write(utf8);
            }
            return baos.toByteArray();
        }

        /**
         * Auto-detects GZIP magic header ({@code 0x1F 0x8B}) vs plain UTF-8 JSON object ({@code 0x7B})
         * and decodes cleanly into a UTF-8 JSON string ({@code #0534}, {@code #0592}).
         *
         * @param rawBytes raw save bytes
         * @return decoded UTF-8 JSON string
         * @throws IOException if bytes are empty, truncated, or neither valid GZIP nor valid JSON
         */
        public static String decodeAutoDetectGzipOrJson(byte[] rawBytes) throws IOException {
            if (rawBytes == null || rawBytes.length == 0) {
                throw new IOException("Empty save byte stream");
            }
            String utf8;
            if (rawBytes.length >= 2 && (rawBytes[0] & 0xFF) == 0x1F && (rawBytes[1] & 0xFF) == 0x8B) {
                try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(rawBytes))) {
                    utf8 = new String(gis.readAllBytes(), StandardCharsets.UTF_8);
                }
            } else {
                utf8 = new String(rawBytes, StandardCharsets.UTF_8);
            }
            validateNonTruncatedJson(utf8);
            return utf8;
        }

        /**
         * Exports a {@link SaveSlotData} into a portable {@code .vsave} ZIP bundle including embedded
         * PNG thumbnail and {@code projectId} manifest ({@code #0521}).
         *
         * @param slot      save slot snapshot
         * @param projectId originating project ID
         * @return portable {@code .vsave} ZIP bytes
         * @throws IOException if ZIP packaging fails
         */
        public static byte[] exportVsavePackage(SaveSlotData slot, String projectId) throws IOException {
            Objects.requireNonNull(slot, "slot must not be null");
            if (projectId == null || projectId.isBlank()) {
                throw new IllegalArgumentException("projectId must not be null or blank");
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream(2048);
            Gson gson = SaveValueCodec.createGson(false);
            try (ZipOutputStream zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {
                JsonObject manifest = new JsonObject();
                manifest.addProperty("format", "kagayaki-vsave-v1");
                manifest.addProperty("projectId", projectId);
                manifest.addProperty("exportedAt", Instant.now().toString());
                zos.putNextEntry(new ZipEntry("manifest.json"));
                zos.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();

                zos.putNextEntry(new ZipEntry("slot.json"));
                zos.write(gson.toJson(slot).getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();

                byte[] thumb = slot.metadata() != null ? slot.metadata().thumbnailPng() : new byte[0];
                if (thumb.length > 0) {
                    zos.putNextEntry(new ZipEntry("thumbnail.png"));
                    zos.write(thumb);
                    zos.closeEntry();
                }
            }
            return baos.toByteArray();
        }

        /**
         * Validates {@code expectedProjectId} compatibility and imports a portable {@code .vsave} ZIP
         * bundle into {@code repository} under {@code targetSlotId} ({@code #0521}).
         *
         * @param vsaveBytes        portable {@code .vsave} archive bytes
         * @param expectedProjectId target project ID that must match the manifest
         * @param repository        target save repository (nullable to decode without writing)
         * @param targetSlotId      target slot ID when importing into {@code repository}
         * @return imported {@link ImportedVsaveResult}
         * @throws IOException if package is corrupt or project IDs mismatch
         */
        public static ImportedVsaveResult importVsavePackage(
                byte[] vsaveBytes,
                String expectedProjectId,
                SaveRepository repository,
                String targetSlotId
        ) throws IOException {
            Objects.requireNonNull(vsaveBytes, "vsaveBytes must not be null");
            Objects.requireNonNull(expectedProjectId, "expectedProjectId must not be null");
            String manifestJson = null;
            String slotJson = null;
            byte[] thumbBytes = null;
            try (ZipInputStream zis = new ZipInputStream(
                    new ByteArrayInputStream(vsaveBytes), StandardCharsets.UTF_8)) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    String name = entry.getName();
                    if ("manifest.json".equals(name)) {
                        manifestJson = new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                    } else if ("slot.json".equals(name)) {
                        slotJson = new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                    } else if ("thumbnail.png".equals(name)) {
                        thumbBytes = zis.readAllBytes();
                    }
                    zis.closeEntry();
                }
            }
            if (manifestJson == null || slotJson == null) {
                throw new IOException("Invalid .vsave archive: missing manifest.json or slot.json");
            }
            JsonObject manifest = JsonParser.parseString(manifestJson).getAsJsonObject();
            String actualProjectId = manifest.has("projectId") ? manifest.get("projectId").getAsString() : "";
            if (!expectedProjectId.equals(actualProjectId)) {
                throw new IOException("Incompatible .vsave projectId: expected '"
                        + expectedProjectId + "' but found '" + actualProjectId + "'");
            }
            Gson gson = SaveValueCodec.createGson(false);
            SaveSlotData parsed = gson.fromJson(slotJson, SaveSlotData.class);
            if (parsed == null) {
                throw new IOException("Empty slot.json inside .vsave");
            }
            SaveMetadata origMeta = parsed.metadata();
            String finalSlotId = targetSlotId != null ? SaveRepository.validateSlotId(targetSlotId)
                    : (origMeta != null ? origMeta.slotId() : "imported_1");
            byte[] finalThumb = thumbBytes != null ? thumbBytes
                    : (origMeta != null ? origMeta.thumbnailPng() : new byte[0]);
            SaveMetadata updatedMeta = origMeta != null
                    ? new SaveMetadata(finalSlotId, origMeta.customName(), origMeta.savedAt(),
                    origMeta.currentLabel(), origMeta.dialoguePreview(), origMeta.playtimeSeconds(),
                    origMeta.gameVersion(), origMeta.thumbnailWidth(), origMeta.thumbnailHeight(), finalThumb)
                    : new SaveMetadata(finalSlotId, finalSlotId, Instant.now(), "", "", 0.0, "1.0.0", 0, 0, finalThumb);
            SaveSlotData finalSlot = parsed.withMetadata(updatedMeta);
            if (repository != null) {
                repository.write(finalSlotId, encodeJsonPayload(finalSlot, true, false));
            }
            return new ImportedVsaveResult(finalSlot, actualProjectId);
        }

        /**
         * Checks {@code persistentJsonFile}; if empty or syntactically corrupt, moves it to
         * {@code <name>.corrupt} and recreates a clean JSON file with warning ({@code #0562}).
         *
         * @param persistentJsonFile path to persistent JSON file
         * @return {@link PersistentRepairReport}
         * @throws IOException if filesystem operations fail
         */
        public static PersistentRepairReport repairPersistentJsonIfCorrupt(Path persistentJsonFile)
                throws IOException {
            Objects.requireNonNull(persistentJsonFile, "persistentJsonFile must not be null");
            if (!Files.exists(persistentJsonFile)) {
                return new PersistentRepairReport(false, Optional.empty(), "");
            }
            String content = Files.readString(persistentJsonFile, StandardCharsets.UTF_8);
            boolean valid = false;
            try {
                if (!content.isBlank()) {
                    JsonElement el = JsonParser.parseString(content);
                    valid = el != null && el.isJsonObject();
                }
            } catch (JsonParseException ignored) {
                valid = false;
            }
            if (valid) {
                return new PersistentRepairReport(false, Optional.empty(), "");
            }
            Path corruptBackup = persistentJsonFile.resolveSibling(
                    persistentJsonFile.getFileName().toString() + ".corrupt");
            Files.move(persistentJsonFile, corruptBackup, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(persistentJsonFile, "{}", StandardCharsets.UTF_8);
            return new PersistentRepairReport(true, Optional.of(corruptBackup),
                    "Defekte persistent.json als .corrupt gesichert und sauber neu initialisiert.");
        }

        /**
         * Resolves old removed/unknown transform names safely to {@code "center"} ({@code #0556}).
         *
         * @param transformName   saved transform name (nullable)
         * @param validTransforms known valid transform names
         * @return valid transform name or {@code "center"}
         */
        public static String resolveSafeShowPosition(String transformName, Set<String> validTransforms) {
            if (transformName == null || transformName.isBlank()) {
                return "center";
            }
            if (validTransforms != null && validTransforms.contains(transformName)) {
                return transformName;
            }
            return "center";
        }

        /**
         * Returns sorted list of mod/plugin IDs required by a save that are missing locally ({@code #0583}).
         *
         * @param requiredMods  required mod IDs recorded in save
         * @param installedMods currently installed mod IDs
         * @return immutable sorted list of missing mod IDs
         */
        public static List<String> previewMissingMods(Collection<String> requiredMods, Set<String> installedMods) {
            if (requiredMods == null || requiredMods.isEmpty()) {
                return List.of();
            }
            Set<String> available = installedMods != null ? installedMods : Set.of();
            TreeSet<String> missing = new TreeSet<>();
            for (String mod : requiredMods) {
                if (mod != null && !mod.isBlank() && !available.contains(mod)) {
                    missing.add(mod);
                }
            }
            return List.copyOf(missing);
        }

        /**
         * Compares two snapshots recursively and returns the first differing field path and values
         * if unequal ({@code #0589}).
         *
         * @param expected expected snapshot object
         * @param actual   actual snapshot object
         * @return descriptive mismatch message if unequal, or {@link Optional#empty()} when identical
         */
        public static Optional<String> diffSnapshots(Object expected, Object actual) {
            return diffRecursive("root", expected, actual, new LinkedHashSet<>());
        }

        /**
         * Assertion helper failing with the exact differing field path when snapshots diverge ({@code #0589}).
         *
         * @param expected expected snapshot object
         * @param actual   actual snapshot object
         * @throws AssertionError if any field differs
         */
        public static void assertSnapshotsEqual(Object expected, Object actual) {
            Optional<String> diff = diffSnapshots(expected, actual);
            if (diff.isPresent()) {
                throw new AssertionError("Snapshot mismatch: " + diff.get());
            }
        }

        /**
         * Encodes a signed obfuscated secret token deterministically without external keys ({@code #0557}).
         *
         * @param secretId secret identifier
         * @param value    secret value
         * @return portable signed token
         */
        public static String encodeSealedSecret(String secretId, String value) {
            Objects.requireNonNull(secretId, "secretId must not be null");
            Objects.requireNonNull(value, "value must not be null");
            String body = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    (secretId + "\n" + value).getBytes(StandardCharsets.UTF_8));
            String sig = computeHmacSha256Hex(body.getBytes(StandardCharsets.UTF_8), BUILTIN_SEAL_KEY);
            return body + "." + sig;
        }

        /**
         * Verifies and decodes a sealed secret token created by {@link #encodeSealedSecret} ({@code #0557}).
         *
         * @param token signed sealed token
         * @return {@code Map.entry(secretId, value)} if valid
         */
        public static Optional<Map.Entry<String, String>> decodeSealedSecret(String token) {
            if (token == null || token.isBlank()) {
                return Optional.empty();
            }
            int dot = token.lastIndexOf('.');
            if (dot <= 0 || dot >= token.length() - 1) {
                return Optional.empty();
            }
            String body = token.substring(0, dot);
            String sig = token.substring(dot + 1);
            String expectedSig = computeHmacSha256Hex(body.getBytes(StandardCharsets.UTF_8), BUILTIN_SEAL_KEY);
            if (!MessageDigest.isEqual(
                    sig.getBytes(StandardCharsets.US_ASCII),
                    expectedSig.getBytes(StandardCharsets.US_ASCII))) {
                return Optional.empty();
            }
            String decoded = new String(Base64.getUrlDecoder().decode(body), StandardCharsets.UTF_8);
            int nl = decoded.indexOf('\n');
            if (nl < 0) {
                return Optional.empty();
            }
            return Optional.of(Map.entry(decoded.substring(0, nl), decoded.substring(nl + 1)));
        }

        private static Optional<String> diffRecursive(
                String path,
                Object a,
                Object b,
                Set<String> visited
        ) {
            if (a == b) {
                return Optional.empty();
            }
            if (a == null || b == null) {
                return Optional.of(path + ": expected <" + a + "> but was <" + b + ">");
            }
            if (!a.getClass().equals(b.getClass())) {
                return Optional.of(path + " class mismatch: "
                        + a.getClass().getSimpleName() + " vs " + b.getClass().getSimpleName());
            }
            if (a instanceof Number || a instanceof String || a instanceof Boolean
                    || a instanceof Character || a instanceof Enum<?> || a instanceof Instant) {
                return Objects.equals(a, b) ? Optional.empty()
                        : Optional.of(path + ": expected <" + a + "> but was <" + b + ">");
            }
            if (a instanceof Map<?, ?> ma && b instanceof Map<?, ?> mb) {
                if (!ma.keySet().equals(mb.keySet())) {
                    return Optional.of(path + " keys differ: " + ma.keySet() + " vs " + mb.keySet());
                }
                for (Object k : ma.keySet()) {
                    Optional<String> d = diffRecursive(path + "." + k, ma.get(k), mb.get(k), visited);
                    if (d.isPresent()) {
                        return d;
                    }
                }
                return Optional.empty();
            }
            if (a instanceof List<?> la && b instanceof List<?> lb) {
                if (la.size() != lb.size()) {
                    return Optional.of(path + " size mismatch: " + la.size() + " vs " + lb.size());
                }
                for (int i = 0; i < la.size(); i++) {
                    Optional<String> d = diffRecursive(path + "[" + i + "]", la.get(i), lb.get(i), visited);
                    if (d.isPresent()) {
                        return d;
                    }
                }
                return Optional.empty();
            }
            String pairId = System.identityHashCode(a) + ":" + System.identityHashCode(b);
            if (!visited.add(pairId)) {
                return Optional.empty();
            }
            if (a.getClass().getName().startsWith("de.swfdtf.")) {
                for (Field field : a.getClass().getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    try {
                        field.setAccessible(true);
                        Optional<String> d = diffRecursive(
                                path + "." + field.getName(), field.get(a), field.get(b), visited);
                        if (d.isPresent()) {
                            return d;
                        }
                    } catch (ReflectiveOperationException ignored) {
                        // Ignore inaccessible JDK internals safely
                    }
                }
                return Optional.empty();
            }
            return Objects.equals(a, b) ? Optional.empty()
                    : Optional.of(path + ": expected <" + a + "> but was <" + b + ">");
        }
    }

    /**
     * Multi-profile isolation, OS save root resolution, cloud-sync persistent conflict resolution,
     * New-Game+, label compatibility fallback, content-hash read tracking, episode import,
     * codex timeline, chapter stats JSON, opt-in telemetry, and bonus scene unlocks
     * ({@code #0527}, {@code #0528}, {@code #0529}, {@code #0531}, {@code #0536}, {@code #0548},
     * {@code #0558}, {@code #0564}, {@code #0565}, {@code #0574}, {@code #0585}, {@code #0591}, {@code #0593}).
     */
    public static final class CloudSyncAndProfileManager {

        /**
         * Snapshot payload used during cloud-sync merge ({@code #0531}).
         *
         * @param updatedAt     UTC modification timestamp
         * @param readLines     unlocked read line IDs
         * @param endings       unlocked endings
         * @param cgs           unlocked gallery CG IDs
         * @param tracks        unlocked music track IDs
         * @param achievements  unlocked achievement IDs
         * @param latestSlot    newest save slot metadata/payload (optional)
         */
        public record PersistentMergeInput(
                Instant updatedAt,
                Set<String> readLines,
                Set<String> endings,
                Set<String> cgs,
                Set<String> tracks,
                Set<String> achievements,
                Optional<SaveSlotData> latestSlot
        ) {
            /** Defensively copies sets. */
            public PersistentMergeInput {
                readLines = readLines != null ? Set.copyOf(readLines) : Set.of();
                endings = endings != null ? Set.copyOf(endings) : Set.of();
                cgs = cgs != null ? Set.copyOf(cgs) : Set.of();
                tracks = tracks != null ? Set.copyOf(tracks) : Set.of();
                achievements = achievements != null ? Set.copyOf(achievements) : Set.of();
                latestSlot = latestSlot != null ? latestSlot : Optional.empty();
            }
        }

        /**
         * Result of merging two conflicting device/cloud states ({@code #0531}).
         *
         * @param mergedReadLines    union of read lines
         * @param mergedEndings      union of endings
         * @param mergedCgs          union of CGs
         * @param mergedTracks       union of music tracks
         * @param mergedAchievements union of achievements
         * @param winningLatestSlot  newer save slot by timestamp (remote wins on exact tie)
         */
        public record CloudMergeResult(
                Set<String> mergedReadLines,
                Set<String> mergedEndings,
                Set<String> mergedCgs,
                Set<String> mergedTracks,
                Set<String> mergedAchievements,
                Optional<SaveSlotData> winningLatestSlot
        ) {
        }

        /**
         * Outcome of resolving a possibly renamed or removed label on save load ({@code #0527}).
         *
         * @param targetLabel  resolved valid target label
         * @param usedFallback {@code true} if fallback alias or chapter start was selected
         * @param notice       optional player-facing notice message
         */
        public record LabelResolution(String targetLabel, boolean usedFallback, String notice) {
        }

        /**
         * Codex glossary unlock event entry ({@code #0565}).
         *
         * @param entryId    codex entry ID
         * @param chapterId  chapter/label where unlocked
         * @param unlockedAt UTC unlock timestamp
         */
        public record CodexUnlockRecord(String entryId, String chapterId, Instant unlockedAt) {
        }

        /**
         * Creates a {@code CloudSyncAndProfileManager}.
         */
        public CloudSyncAndProfileManager() {
        }

        /**
         * Resolves isolated per-player save directory {@code <baseSavesDir>/<profile>/} ({@code #0529}).
         *
         * @param baseSavesDir root saves directory
         * @param profileId    player profile identifier
         * @return normalized profile save directory path
         */
        public static Path resolveProfileDirectory(Path baseSavesDir, String profileId) {
            Objects.requireNonNull(baseSavesDir, "baseSavesDir must not be null");
            String clean = SaveRepository.validateSlotId(profileId);
            Path resolved = baseSavesDir.resolve(clean).normalize();
            if (!resolved.startsWith(baseSavesDir.normalize())) {
                throw new IllegalArgumentException("Profile directory escapes baseSavesDir: " + profileId);
            }
            return resolved;
        }

        /**
         * Resolves OS-appropriate save directory falling back from portable {@code installDir/saves}
         * to user home AppData / Application Support / XDG when read-only ({@code #0536}).
         *
         * @param appName            application folder name
         * @param portableMode       whether portable mode is preferred when writable
         * @param installDir         installation directory
         * @param installDirWritable whether {@code installDir} is writable
         * @param userHome           user home path
         * @param osName             target OS name ({@code System.getProperty("os.name")})
         * @return resolved save directory path
         */
        public static Path resolvePlatformSaveDirectory(
                String appName,
                boolean portableMode,
                Path installDir,
                boolean installDirWritable,
                Path userHome,
                String osName
        ) {
            Objects.requireNonNull(appName, "appName must not be null");
            Objects.requireNonNull(installDir, "installDir must not be null");
            Objects.requireNonNull(userHome, "userHome must not be null");
            if (portableMode && installDirWritable) {
                return installDir.resolve("saves").normalize();
            }
            String os = osName != null ? osName.toLowerCase(Locale.ROOT) : "";
            if (os.contains("win")) {
                return userHome.resolve("AppData").resolve("Roaming").resolve(appName).resolve("saves");
            }
            if (os.contains("mac")) {
                return userHome.resolve("Library").resolve("Application Support").resolve(appName).resolve("saves");
            }
            return userHome.resolve(".local").resolve("share").resolve(appName).resolve("saves");
        }

        /**
         * Resolves cloud-sync conflict via union merge of persistent unlocks and newest-wins for save slot
         * ({@code #0531}).
         *
         * @param local  local device persistent state
         * @param remote remote cloud persistent state
         * @return conflict-free {@link CloudMergeResult}
         */
        public static CloudMergeResult mergePersistentSnapshots(
                PersistentMergeInput local,
                PersistentMergeInput remote
        ) {
            Objects.requireNonNull(local, "local must not be null");
            Objects.requireNonNull(remote, "remote must not be null");
            Optional<SaveSlotData> newestSlot = chooseNewestSlot(local.latestSlot(), remote.latestSlot());
            return new CloudMergeResult(
                    union(local.readLines(), remote.readLines()),
                    union(local.endings(), remote.endings()),
                    union(local.cgs(), remote.cgs()),
                    union(local.tracks(), remote.tracks()),
                    union(local.achievements(), remote.achievements()),
                    newestSlot
            );
        }

        /**
         * Resets run-local variables cleanly for New-Game+ while preserving carried-over keys and keeping
         * cross-save {@link PersistentAccess} meta-data completely intact ({@code #0528}).
         *
         * @param runStore      active run state store
         * @param persistent    persistent meta store
         * @param carryOverKeys variable keys retained into New-Game+
         */
        public static void resetRunKeepPersistent(
                StateStore runStore,
                PersistentAccess persistent,
                Set<String> carryOverKeys
        ) {
            Objects.requireNonNull(runStore, "runStore must not be null");
            Objects.requireNonNull(persistent, "persistent must not be null");
            Map<String, Object> carried = new LinkedHashMap<>();
            if (carryOverKeys != null) {
                for (String key : carryOverKeys) {
                    Object val = runStore.get(key);
                    if (val != null) {
                        carried.put(key, RevertableMap.cloneValue(val));
                    }
                }
            }
            runStore.clear();
            for (Map.Entry<String, Object> entry : carried.entrySet()) {
                runStore.set(entry.getKey(), entry.getValue());
            }
            runStore.set("ng_plus_active", true);
            persistent.set("ng_plus_unlocked", true);
        }

        /**
         * Resolves a saved label safely against renamed aliases or chapter fallback ({@code #0527}).
         *
         * @param savedLabel       label recorded in save
         * @param chapterStartFallback fallback chapter start label
         * @param availableLabels  valid labels in current script build
         * @param renameAliases    map of deprecated label name to new label name
         * @return {@link LabelResolution} without throwing on missing label
         */
        public static LabelResolution resolveLabelCompatibility(
                String savedLabel,
                String chapterStartFallback,
                Set<String> availableLabels,
                Map<String, String> renameAliases
        ) {
            Set<String> valid = availableLabels != null ? availableLabels : Set.of();
            if (savedLabel != null && valid.contains(savedLabel)) {
                return new LabelResolution(savedLabel, false, "");
            }
            if (savedLabel != null && renameAliases != null && renameAliases.containsKey(savedLabel)) {
                String aliased = renameAliases.get(savedLabel);
                if (valid.contains(aliased)) {
                    return new LabelResolution(aliased, true,
                            "Label '" + savedLabel + "' wurde zu '" + aliased + "' migriert.");
                }
            }
            String fallback = (chapterStartFallback != null && valid.contains(chapterStartFallback))
                    ? chapterStartFallback : "start";
            return new LabelResolution(fallback, true,
                    "Label '" + savedLabel + "' entfernt; sicherer Start am Kapitelanfang '" + fallback + "'.");
        }

        /**
         * Generates a content-hash line ID invariant to line insertions above in a label ({@code #0548}).
         *
         * @param label        enclosing label name
         * @param sentenceText dialogue sentence text
         * @return deterministic content-hash read identifier
         */
        public static String computeContentLineId(String label, String sentenceText) {
            String safeLabel = label != null ? label.trim() : "";
            String normalizedText = sentenceText != null ? sentenceText.trim().replaceAll("\\s+", " ") : "";
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] hash = md.digest((safeLabel + "::" + normalizedText).getBytes(StandardCharsets.UTF_8));
                StringBuilder hex = new StringBuilder(16);
                for (int i = 0; i < 8; i++) {
                    hex.append(Character.forDigit((hash[i] >>> 4) & 0x0F, 16));
                    hex.append(Character.forDigit(hash[i] & 0x0F, 16));
                }
                return safeLabel + "#" + hex;
            } catch (NoSuchAlgorithmException ex) {
                throw new IllegalStateException("SHA-256 unavailable", ex);
            }
        }

        /**
         * Evaluates whether skip mode should stop at {@code contentLineId} ({@code #0564}).
         *
         * @param prefs         player preferences
         * @param persistent    persistent read-line store
         * @param contentLineId current line ID
         * @return {@code true} when skip must stop because line is unread and skipUnread is disabled
         */
        public static boolean shouldStopSkipAtLine(
                PreferencesState prefs,
                PersistentAccess persistent,
                String contentLineId
        ) {
            Objects.requireNonNull(prefs, "prefs must not be null");
            Objects.requireNonNull(persistent, "persistent must not be null");
            if (prefs.isSkipUnread()) {
                return false;
            }
            return contentLineId == null || !persistent.isLineRead(contentLineId);
        }

        /**
         * Imports allowlisted state variables from a completed prior episode save ({@code #0558}).
         *
         * @param previousRepo      prior episode save repository
         * @param slotId            source slot ID
         * @param allowedExportKeys allowlisted keys permitted to transfer
         * @param targetStore       target episode state store
         * @return map of imported key/value pairs
         * @throws IOException if slot does not exist or cannot be decoded
         */
        public static Map<String, Object> importPreviousEpisodeSave(
                SaveRepository previousRepo,
                String slotId,
                Set<String> allowedExportKeys,
                StateStore targetStore
        ) throws IOException {
            Objects.requireNonNull(previousRepo, "previousRepo must not be null");
            Objects.requireNonNull(targetStore, "targetStore must not be null");
            Optional<byte[]> bytes = previousRepo.read(slotId);
            if (bytes.isEmpty()) {
                throw new IOException("Previous episode save not found: " + slotId);
            }
            String json = SaveIntegrityAndCodec.decodeAutoDetectGzipOrJson(bytes.get());
            SaveSlotData slot = SaveValueCodec.createGson(false).fromJson(json, SaveSlotData.class);
            Map<String, Object> imported = new LinkedHashMap<>();
            if (slot != null && slot.variables() != null && allowedExportKeys != null) {
                for (String key : allowedExportKeys) {
                    if (slot.variables().containsKey(key)) {
                        Object val = RevertableMap.cloneValue(slot.variables().get(key));
                        targetStore.set(key, val);
                        imported.put(key, val);
                    }
                }
            }
            return Collections.unmodifiableMap(imported);
        }

        /**
         * Computes overall chapter discovery percentage in {@code [0.0, 100.0]} ({@code #0574}).
         *
         * @param visitedLabels visited story labels
         * @param allStoryLabels all story labels in project
         * @return percentage from {@code 0.0} to {@code 100.0}
         */
        public static double calculateDiscoveryPercent(Set<String> visitedLabels, Set<String> allStoryLabels) {
            if (allStoryLabels == null || allStoryLabels.isEmpty()) {
                return 0.0;
            }
            Set<String> visited = visitedLabels != null ? visitedLabels : Set.of();
            long count = allStoryLabels.stream().filter(visited::contains).count();
            return (count * 100.0) / allStoryLabels.size();
        }

        /**
         * Exports chapter duration and discovery analytics as stable JSON ({@code #0591}).
         *
         * @param chapterDurationsSec elapsed seconds per chapter
         * @param visitedLabels       visited story labels
         * @param allStoryLabels      all story labels
         * @return formatted UTF-8 JSON report
         */
        public static String exportChapterStatsJson(
                Map<String, Double> chapterDurationsSec,
                Set<String> visitedLabels,
                Set<String> allStoryLabels
        ) {
            JsonObject root = new JsonObject();
            root.addProperty("schemaVersion", 1);
            root.addProperty("discoveryPercent", calculateDiscoveryPercent(visitedLabels, allStoryLabels));
            JsonObject durations = new JsonObject();
            if (chapterDurationsSec != null) {
                for (Map.Entry<String, Double> e : chapterDurationsSec.entrySet()) {
                    durations.addProperty(e.getKey(), e.getValue());
                }
            }
            root.add("chapterDurationsSec", durations);
            return root.toString();
        }

        /**
         * Tracks codex unlock chronology and chapter provenance ({@code #0565}).
         */
        public static final class CodexUnlockTimeline {

            private final List<CodexUnlockRecord> records = new ArrayList<>();

            /**
             * Creates an empty {@code CodexUnlockTimeline}.
             */
            public CodexUnlockTimeline() {
            }

            /**
             * Records unlock if not already present in timeline.
             *
             * @param entryId    codex entry ID
             * @param chapterId  chapter where unlocked
             * @param unlockedAt UTC timestamp
             */
            public synchronized void recordUnlock(String entryId, String chapterId, Instant unlockedAt) {
                Objects.requireNonNull(entryId, "entryId must not be null");
                for (CodexUnlockRecord r : records) {
                    if (entryId.equals(r.entryId())) {
                        return;
                    }
                }
                records.add(new CodexUnlockRecord(entryId, chapterId != null ? chapterId : "",
                        unlockedAt != null ? unlockedAt : Instant.now()));
            }

            /**
             * Returns timeline records sorted chronologically.
             *
             * @return immutable chronological list
             */
            public synchronized List<CodexUnlockRecord> listChronological() {
                List<CodexUnlockRecord> copy = new ArrayList<>(records);
                copy.sort(Comparator.comparing(CodexUnlockRecord::unlockedAt));
                return List.copyOf(copy);
            }

            /**
             * Returns timeline records sorted alphabetically by entry ID.
             *
             * @return immutable alphabetical list
             */
            public synchronized List<CodexUnlockRecord> listAlphabetical() {
                List<CodexUnlockRecord> copy = new ArrayList<>(records);
                copy.sort(Comparator.comparing(CodexUnlockRecord::entryId));
                return List.copyOf(copy);
            }
        }

        /**
         * Opt-in local-only betatest decision telemetry collecting zero network requests ({@code #0585}).
         */
        public static final class BetaDecisionTelemetry {

            private boolean optInEnabled = false;
            private final List<String> recordedChoices = new ArrayList<>();

            /**
             * Creates a {@code BetaDecisionTelemetry} instance with opt-in disabled by default.
             */
            public BetaDecisionTelemetry() {
            }

            /**
             * Enables or disables local anonymous decision logging.
             *
             * @param optInEnabled {@code true} only after explicit player consent
             */
            public synchronized void setOptInEnabled(boolean optInEnabled) {
                this.optInEnabled = optInEnabled;
            }

            /**
             * Returns whether telemetry opt-in is enabled.
             *
             * @return {@code true} when enabled
             */
            public synchronized boolean isOptInEnabled() {
                return optInEnabled;
            }

            /**
             * Records a decision step locally only when opted in.
             *
             * @param label  choice menu label
             * @param option chosen option index or caption
             */
            public synchronized void recordDecision(String label, String option) {
                if (!optInEnabled) {
                    return;
                }
                recordedChoices.add((label != null ? label : "") + "->" + (option != null ? option : ""));
            }

            /**
             * Exports locally collected choice sequence as UTF-8 JSON without making any network calls.
             *
             * @return local telemetry JSON string
             */
            public synchronized String exportLocalJson() {
                JsonObject obj = new JsonObject();
                obj.addProperty("optInEnabled", optInEnabled);
                obj.add("decisions", SaveValueCodec.createGson(false).toJsonTree(recordedChoices));
                return obj.toString();
            }
        }

        /**
         * Unlocks and queries post-ending bonus scenes persistently across runs ({@code #0593}).
         */
        public static final class BonusSceneUnlockRegistry {

            private static final String PREFIX = "bonus_scene.";
            private final PersistentAccess persistentAccess;

            /**
             * Creates a {@code BonusSceneUnlockRegistry} backed by {@code persistentAccess}.
             *
             * @param persistentAccess persistent storage
             */
            public BonusSceneUnlockRegistry(PersistentAccess persistentAccess) {
                this.persistentAccess = Objects.requireNonNull(persistentAccess, "persistentAccess must not be null");
            }

            /**
             * Persistently unlocks {@code bonusSceneId}.
             *
             * @param bonusSceneId bonus scene identifier
             */
            public void unlockBonusScene(String bonusSceneId) {
                StateCoreEnhancements.validateStateKey(bonusSceneId);
                persistentAccess.set(PREFIX + bonusSceneId, true);
            }

            /**
             * Returns whether {@code bonusSceneId} was unlocked across any prior run.
             *
             * @param bonusSceneId bonus scene identifier
             * @return {@code true} if unlocked
             */
            public boolean isBonusSceneUnlocked(String bonusSceneId) {
                Object val = persistentAccess.get(PREFIX + bonusSceneId);
                return Boolean.TRUE.equals(val);
            }
        }

        private static Optional<SaveSlotData> chooseNewestSlot(
                Optional<SaveSlotData> a,
                Optional<SaveSlotData> b
        ) {
            if (a.isEmpty()) {
                return b;
            }
            if (b.isEmpty()) {
                return a;
            }
            Instant ta = a.get().metadata() != null && a.get().metadata().savedAt() != null
                    ? a.get().metadata().savedAt() : Instant.EPOCH;
            Instant tb = b.get().metadata() != null && b.get().metadata().savedAt() != null
                    ? b.get().metadata().savedAt() : Instant.EPOCH;
            return tb.isBefore(ta) ? a : b;
        }

        private static Set<String> union(Set<String> a, Set<String> b) {
            Set<String> merged = new LinkedHashSet<>(a);
            merged.addAll(b);
            return Collections.unmodifiableSet(merged);
        }
    }

    /**
     * Save/load UX workflow controller handling async background saving, timed-choice/hook preconditions,
     * quick-save ring buffers, pre-choice slots, quit autosaves, trash undo recovery, clock-driven
     * indicators, deterministic PRNG seeds, batch checkpoint QA, and default-variable documentation
     * ({@code #0526}, {@code #0537}–{@code #0542}, {@code #0545}–{@code #0547}, {@code #0561},
     * {@code #0563}, {@code #0567}, {@code #0568}, {@code #0570}, {@code #0572}, {@code #0575},
     * {@code #0576}, {@code #0579}, {@code #0580}, {@code #0586}, {@code #0588}, {@code #0590}, {@code #0594}).
     */
    public static final class SaveWorkflowController {

        /**
         * Result of attempting a save operation through precondition checks ({@code #0539}, {@code #0575}).
         *
         * @param saved       {@code true} if save succeeded or was dispatched
         * @param slotId      target slot identifier
         * @param message     toast / denial reason message
         * @param writeFuture completion future for async disk flush ({@code #0546})
         */
        public record SaveOutcome(
                boolean saved,
                String slotId,
                String message,
                CompletableFuture<Void> writeFuture
        ) {
        }

        /**
         * Single-slot report entry from QA batch checkpoint validation ({@code #0570}).
         *
         * @param slotId          slot identifier
         * @param passed          {@code true} when load + migration succeeded
         * @param sourceVersion   original schema version
         * @param targetVersion   migrated schema version
         * @param migrationTrail  applied migration steps
         * @param errorMessage    error description when {@code passed == false}
         */
        public record BatchCheckpointResult(
                String slotId,
                boolean passed,
                int sourceVersion,
                int targetVersion,
                List<String> migrationTrail,
                String errorMessage
        ) {
            /** Defensively copies migration trail. */
            public BatchCheckpointResult {
                migrationTrail = migrationTrail != null ? List.copyOf(migrationTrail) : List.of();
            }
        }

        /**
         * Variable documentation metadata extracted from project {@code default} declarations ({@code #0576}).
         *
         * @param name         variable name
         * @param typeName     declared/inferred type name
         * @param defaultValue initial default value literal
         * @param description  author note
         */
        public record DefaultVariableDoc(String name, String typeName, String defaultValue, String description) {
        }

        private record TrashedSlot(String slotId, byte[] primaryBytes, byte[] backupBytes) {
        }

        private final SaveRepository repository;
        private final Clock clock;
        private final Executor ioExecutor;
        private final Map<String, Object> slotWriteLocks = new ConcurrentHashMap<>();
        private BooleanSupplier savePrecondition = () -> true;
        private Supplier<String> preconditionDenyMessage = () -> "Speichern aktuell nicht möglich.";
        private boolean allowSaveInTimedChoice = false;
        private Instant indicatorVisibleUntil = Instant.EPOCH;
        private final Deque<TrashedSlot> trashBin = new ArrayDeque<>();

        /**
         * Creates a synchronous {@code SaveWorkflowController} using {@code Runnable::run}.
         *
         * @param repository backing save repository
         * @param clock      injected clock for deterministic testing ({@code #0567})
         */
        public SaveWorkflowController(SaveRepository repository, Clock clock) {
            this(repository, clock, Runnable::run);
        }

        /**
         * Creates a {@code SaveWorkflowController} with configurable async I/O executor ({@code #0546}).
         *
         * @param repository backing save repository
         * @param clock      injected clock
         * @param ioExecutor background executor for non-blocking disk/storage writes
         */
        public SaveWorkflowController(SaveRepository repository, Clock clock, Executor ioExecutor) {
            this.repository = Objects.requireNonNull(repository, "repository must not be null");
            this.clock = Objects.requireNonNull(clock, "clock must not be null");
            this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor must not be null");
        }

        /**
         * Configures a custom save precondition hook (e.g. disabling save during real-time minigames) ({@code #0539}).
         *
         * @param precondition supplier returning {@code true} when saving is permitted
         * @param denyReason   supplier returning explanation shown when denied
         */
        public synchronized void setSavePrecondition(BooleanSupplier precondition, Supplier<String> denyReason) {
            this.savePrecondition = precondition != null ? precondition : () -> true;
            this.preconditionDenyMessage = denyReason != null ? denyReason : () -> "Speichern aktuell gesperrt.";
        }

        /**
         * Sets whether saving is allowed while a timed {@code menu} choice is active ({@code #0575}).
         *
         * @param allowSaveInTimedChoice {@code true} to allow saving during timed choices
         */
        public synchronized void setAllowSaveInTimedChoice(boolean allowSaveInTimedChoice) {
            this.allowSaveInTimedChoice = allowSaveInTimedChoice;
        }

        /**
         * Saves an immutable snapshot to {@code slotId}, checking preconditions, timed-choice rules,
         * developer taint marking ({@code #0545}), per-slot write lock ({@code #0588}), and async dispatch
         * ({@code #0546}), while activating the non-blocking 0.8 s save indicator ({@code #0579}).
         *
         * @param slotId          target slot ID
         * @param snapshot        immutable save slot snapshot
         * @param inTimedChoice   whether a timed {@code menu} choice is active
         * @param studioCheatUsed whether studio warp/cheats were used in this run
         * @return {@link SaveOutcome}
         */
        public SaveOutcome saveSlot(
                String slotId,
                SaveSlotData snapshot,
                boolean inTimedChoice,
                boolean studioCheatUsed
        ) {
            String cleanSlot = SaveRepository.validateSlotId(slotId);
            Objects.requireNonNull(snapshot, "snapshot must not be null");
            synchronized (this) {
                if (inTimedChoice && !allowSaveInTimedChoice) {
                    return new SaveOutcome(false, cleanSlot,
                            "Speichern während zeitbegrenzter Auswahl deaktiviert.",
                            CompletableFuture.completedFuture(null));
                }
                if (!savePrecondition.getAsBoolean()) {
                    return new SaveOutcome(false, cleanSlot, preconditionDenyMessage.get(),
                            CompletableFuture.completedFuture(null));
                }
                indicatorVisibleUntil = clock.instant().plusMillis(800);
            }
            SaveSlotData finalized = applyTaintFlagIfNeeded(cleanSlot, snapshot, studioCheatUsed);
            Object lock = slotWriteLocks.computeIfAbsent(cleanSlot, k -> new Object());
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                synchronized (lock) {
                    try {
                        byte[] encoded = SaveIntegrityAndCodec.encodeJsonPayload(finalized, true, false);
                        repository.write(cleanSlot, encoded);
                    } catch (IOException ex) {
                        throw new RuntimeException("Failed async save for slot " + cleanSlot, ex);
                    }
                }
            }, ioExecutor);
            return new SaveOutcome(true, cleanSlot, "Speichert…", future);
        }

        /**
         * Saves into a rotating 3-slot quick-save ring ({@code quick-1..quick-3}) where {@code quick-1} is
         * always the newest snapshot ({@code #0538}).
         *
         * @param snapshot current state snapshot
         * @return completed {@link SaveOutcome} for {@code quick-1}
         * @throws IOException if ring rotation fails
         */
        public synchronized SaveOutcome quickSaveRing(SaveSlotData snapshot) throws IOException {
            Objects.requireNonNull(snapshot, "snapshot must not be null");
            shiftSlot("quick-2", "quick-3");
            shiftSlot("quick-1", "quick-2");
            return saveSlot("quick-1", withSlotId(snapshot, "quick-1"), false, false);
        }

        /**
         * Loads the latest quick-save slot ({@code quick-1}) ({@code #0538}).
         *
         * @return loaded {@link SaveSlotData} if present
         * @throws IOException if decoding fails
         */
        public Optional<SaveSlotData> quickLoadLatest() throws IOException {
            return loadSlot("quick-1");
        }

        /**
         * Saves automatic branch backup slot {@code last_choice} right before presenting choices ({@code #0590}).
         *
         * @param snapshot current pre-choice snapshot
         * @return {@link SaveOutcome}
         */
        public SaveOutcome savePreChoice(SaveSlotData snapshot) {
            return saveSlot("last_choice", withSlotId(snapshot, "last_choice"), false, false);
        }

        /**
         * Executes automatic save on normal quit when {@link PreferencesState#isAutosaveOnQuit()}
         * is true ({@code #0526}).
         *
         * @param prefs    user preferences
         * @param snapshot closing state snapshot
         * @return {@link SaveOutcome}
         */
        public SaveOutcome autosaveOnQuit(PreferencesState prefs, SaveSlotData snapshot) {
            Objects.requireNonNull(prefs, "prefs must not be null");
            if (!prefs.isAutosaveOnQuit()) {
                return new SaveOutcome(false, "auto_quit", "Auto-Save beim Beenden deaktiviert.",
                        CompletableFuture.completedFuture(null));
            }
            return saveSlot("auto_quit", withSlotId(snapshot, "auto_quit"), false, false);
        }

        /**
         * Updates the custom player note on an existing save slot without modifying state ({@code #0541}).
         *
         * @param slotId   existing slot ID
         * @param noteText updated player note
         * @return updated {@link SaveSlotData}
         * @throws IOException if slot missing or write fails
         */
        public SaveSlotData updateSlotNote(String slotId, String noteText) throws IOException {
            SaveSlotData existing = loadSlot(slotId)
                    .orElseThrow(() -> new IOException("Slot not found: " + slotId));
            SaveMetadata old = existing.metadata();
            SaveMetadata updated = new SaveMetadata(
                    old.slotId(),
                    noteText != null ? noteText : "",
                    old.savedAt(),
                    old.currentLabel(),
                    old.dialoguePreview(),
                    old.playtimeSeconds(),
                    old.gameVersion(),
                    old.thumbnailWidth(),
                    old.thumbnailHeight(),
                    old.thumbnailPng()
            );
            SaveSlotData modified = existing.withMetadata(updated);
            repository.write(slotId, SaveIntegrityAndCodec.encodeJsonPayload(modified, true, false));
            return modified;
        }

        /**
         * Deletes {@code slotId} into the session trash bin when {@code confirmed == true}
         * ({@code #0542}, {@code #0568}).
         *
         * @param slotId    target slot ID
         * @param confirmed whether deletion was explicitly confirmed
         * @return {@code true} if deleted and placed in undo trash
         * @throws IOException if storage error occurs
         */
        public synchronized boolean deleteSlot(String slotId, boolean confirmed) throws IOException {
            if (!confirmed) {
                return false;
            }
            String clean = SaveRepository.validateSlotId(slotId);
            Optional<byte[]> primary = repository.read(clean);
            if (primary.isEmpty()) {
                return false;
            }
            Optional<byte[]> backup = repository.readBackup(clean);
            trashBin.push(new TrashedSlot(clean, primary.get(), backup.orElse(null)));
            return repository.delete(clean);
        }

        /**
         * Restores the most recently deleted save slot (including thumbnail and metadata) ({@code #0542}).
         *
         * @return restored slot ID if trash had an entry
         * @throws IOException if write fails
         */
        public synchronized Optional<String> undoLastDelete() throws IOException {
            if (trashBin.isEmpty()) {
                return Optional.empty();
            }
            TrashedSlot item = trashBin.pop();
            if (item.backupBytes() != null) {
                repository.write(item.slotId(), item.backupBytes());
            }
            repository.write(item.slotId(), item.primaryBytes());
            return Optional.of(item.slotId());
        }

        /**
         * Returns whether non-blocking `"Speichert…"` HUD badge is visible at current clock instant ({@code #0579}).
         *
         * @return {@code true} within 0.8 s after last save
         */
        public synchronized boolean isSaveIndicatorVisible() {
            return clock.instant().isBefore(indicatorVisibleUntil);
        }

        /**
         * Returns whether loading requires a confirmation dialog to protect unsaved progress ({@code #0547}).
         *
         * @param inActiveGameplay   {@code false} on main menu, {@code true} during gameplay
         * @param hasUnsavedProgress {@code true} if unstored steps exist since last save
         * @return {@code true} when prompt should be shown
         */
        public static boolean shouldConfirmBeforeLoad(boolean inActiveGameplay, boolean hasUnsavedProgress) {
            return inActiveGameplay && hasUnsavedProgress;
        }

        /**
         * Returns whether starting a New Game requires a confirmation reminder ({@code #0572}).
         *
         * @param hasUnsavedProgress {@code true} when current run has unsaved steps
         * @return {@code true} when reminder prompt should be shown
         */
        public static boolean shouldConfirmBeforeNewGame(boolean hasUnsavedProgress) {
            return hasUnsavedProgress;
        }

        /**
         * Lists paginated metadata filtered by optional chapter label and sorted by
         * {@code sortMode} ({@code "LAST_SAVED"} or {@code "SLOT_NUMBER"})
         * ({@code #0561}, {@code #0580}, {@code #0594}).
         *
         * @param chapterFilter optional label/chapter filter (null or blank means all chapters)
         * @param sortMode      sort mode ({@code "LAST_SAVED"} or {@code "SLOT_NUMBER"})
         * @param pageIndex     0-based page index
         * @param pageSize      items per page (clamped {@code [6, 100]})
         * @return immutable list of matching {@link SaveMetadata}
         * @throws IOException if repository inspection fails
         */
        public List<SaveMetadata> listMetadataPage(
                String chapterFilter,
                String sortMode,
                int pageIndex,
                int pageSize
        ) throws IOException {
            int clampedSize = Math.max(6, Math.min(100, pageSize));
            List<SaveMetadata> all = new ArrayList<>();
            for (String id : repository.listSlotIds()) {
                Optional<SaveSlotData> slot = loadSlot(id);
                if (slot.isPresent() && slot.get().metadata() != null) {
                    SaveMetadata meta = slot.get().metadata();
                    if (chapterFilter == null || chapterFilter.isBlank()
                            || chapterFilter.equals(meta.currentLabel())) {
                        all.add(meta);
                    }
                }
            }
            if ("SLOT_NUMBER".equalsIgnoreCase(sortMode)) {
                all.sort(Comparator.comparing(SaveMetadata::slotId));
            } else {
                all.sort(Comparator.comparing(
                        (SaveMetadata m) -> m.savedAt() != null ? m.savedAt() : Instant.EPOCH,
                        Comparator.reverseOrder()
                ).thenComparing(SaveMetadata::slotId));
            }
            int safePage = Math.max(0, pageIndex);
            int from = safePage * clampedSize;
            if (from >= all.size()) {
                return List.of();
            }
            int to = Math.min(all.size(), from + clampedSize);
            return List.copyOf(all.subList(from, to));
        }

        /**
         * Loads and migrates a list of QA checkpoints sequentially, producing PASS/FAIL reports ({@code #0570}).
         *
         * @param slotIds          checkpoint slot IDs to test
         * @param migrationService configured migration service
         * @param targetVersion    target save schema version
         * @return list of {@link BatchCheckpointResult}
         */
        public List<BatchCheckpointResult> batchValidateCheckpoints(
                List<String> slotIds,
                SaveMigrationService migrationService,
                int targetVersion
        ) {
            Objects.requireNonNull(slotIds, "slotIds must not be null");
            Objects.requireNonNull(migrationService, "migrationService must not be null");
            List<BatchCheckpointResult> results = new ArrayList<>(slotIds.size());
            for (String id : slotIds) {
                try {
                    SaveSlotData loaded = loadSlot(id)
                            .orElseThrow(() -> new IOException("Slot missing: " + id));
                    int srcVer = migrationService.detectVersion(loaded);
                    SaveSlotData migrated = migrationService.migrateToLatest(loaded);
                    int actualTarget = migrationService.detectVersion(migrated);
                    if (actualTarget != targetVersion) {
                        throw new IllegalStateException("Expected target version " + targetVersion
                                + " but migrated to " + actualTarget);
                    }
                    @SuppressWarnings("unchecked")
                    List<String> trail = migrated.variables() != null
                            && migrated.variables().get(SaveMigrationService.MIGRATION_TRAIL_KEY) instanceof List<?> l
                            ? l.stream().map(String::valueOf).toList() : List.of();
                    results.add(new BatchCheckpointResult(id, true, srcVer, targetVersion, trail, ""));
                } catch (Exception ex) {
                    results.add(new BatchCheckpointResult(id, false, 0, targetVersion, List.of(), ex.getMessage()));
                }
            }
            return List.copyOf(results);
        }

        /**
         * Exports markdown table documenting project {@code default} variables for co-authors ({@code #0576}).
         *
         * @param declarations declared default variable docs
         * @return formatted Markdown table string
         */
        public static String exportDefaultVariablesMarkdown(List<DefaultVariableDoc> declarations) {
            StringBuilder sb = new StringBuilder();
            sb.append("| Variable | Type | Default | Description |\n");
            sb.append("| :--- | :--- | :--- | :--- |\n");
            if (declarations != null) {
                for (DefaultVariableDoc doc : declarations) {
                    sb.append("| `").append(doc.name()).append("` | `").append(doc.typeName())
                            .append("` | `").append(doc.defaultValue()).append("` | ")
                            .append(doc.description() != null ? doc.description() : "").append(" |\n");
                }
            }
            return sb.toString();
        }

        /**
         * Stores/restores deterministic PRNG seed in {@link StateStore} to keep RNG reproducible across
         * QuickSave/QuickLoad ({@code #0540}).
         *
         * @param store state store
         * @return next deterministic random double in {@code [0, 1)} with updated seed saved
         */
        public static double nextDeterministicRandom(StateStore store) {
            Objects.requireNonNull(store, "store must not be null");
            Object raw = store.get("randomSeed");
            long seed = raw instanceof Number n ? n.longValue() : 0x5EEDBEEFL;
            Random rng = new Random(seed);
            double value = rng.nextDouble();
            store.set("randomSeed", rng.nextLong());
            return value;
        }

        /**
         * Synchronizes active voice bookmark metadata into save state and clears on track end ({@code #0586}).
         *
         * @param slot      current save slot
         * @param bookmark  active bookmark or {@code null} to clear
         * @param trackEnded whether the voice clip finished playing
         * @return updated {@link SaveSlotData}
         */
        public static SaveSlotData syncVoiceBookmark(
                SaveSlotData slot,
                SavedVoiceBookmark bookmark,
                boolean trackEnded
        ) {
            Objects.requireNonNull(slot, "slot must not be null");
            SavedVoiceBookmark effective = trackEnded ? null : bookmark;
            return new SaveSlotData(
                    slot.metadata(),
                    slot.variables(),
                    slot.scriptPosition(),
                    slot.historyEntries(),
                    effective
            );
        }

        /**
         * Loads and decodes {@code slotId} cleanly from {@link SaveRepository}.
         *
         * @param slotId validated slot ID
         * @return decoded {@link SaveSlotData} if present
         * @throws IOException if file cannot be decoded
         */
        public Optional<SaveSlotData> loadSlot(String slotId) throws IOException {
            Optional<byte[]> bytes = repository.read(slotId);
            if (bytes.isEmpty()) {
                return Optional.empty();
            }
            String json = SaveIntegrityAndCodec.decodeAutoDetectGzipOrJson(bytes.get());
            return Optional.ofNullable(SaveValueCodec.createGson(false).fromJson(json, SaveSlotData.class));
        }

        private void shiftSlot(String fromId, String toId) throws IOException {
            Optional<byte[]> data = repository.read(fromId);
            if (data.isPresent()) {
                repository.write(toId, data.get());
            }
        }

        private static SaveSlotData withSlotId(SaveSlotData data, String newSlotId) {
            SaveMetadata old = data.metadata();
            if (old == null) {
                return data;
            }
            SaveMetadata updated = new SaveMetadata(
                    newSlotId, old.customName(), old.savedAt(), old.currentLabel(),
                    old.dialoguePreview(), old.playtimeSeconds(), old.gameVersion(),
                    old.thumbnailWidth(), old.thumbnailHeight(), old.thumbnailPng()
            );
            return data.withMetadata(updated);
        }

        private static SaveSlotData applyTaintFlagIfNeeded(String slotId, SaveSlotData data, boolean cheatUsed) {
            if (!cheatUsed) {
                return withSlotId(data, slotId);
            }
            Map<String, Object> vars = new LinkedHashMap<>(data.variables());
            vars.put("developerTainted", true);
            SaveSlotData tainted = new SaveSlotData(
                    data.metadata(), vars, data.scriptPosition(), data.historyEntries(), data.voiceBookmark());
            return withSlotId(tainted, slotId);
        }

        /**
         * Active session playtime accumulator continuing accurately across save/load timestamps ({@code #0537}).
         */
        public static final class PlaytimeAccumulator {

            private final Clock clock;
            private Instant resumeAnchor;
            private double baseSeconds;

            /**
             * Creates a {@code PlaytimeAccumulator} driven by {@code clock}.
             *
             * @param clock injected clock source
             */
            public PlaytimeAccumulator(Clock clock) {
                this.clock = Objects.requireNonNull(clock, "clock must not be null");
                this.resumeAnchor = clock.instant();
                this.baseSeconds = 0.0;
            }

            /**
             * Resumes playtime counting from {@code loadedPlaytimeSeconds} saved in a loaded slot.
             *
             * @param loadedPlaytimeSeconds accumulated playtime seconds from save metadata
             */
            public synchronized void resumeFromSave(double loadedPlaytimeSeconds) {
                this.baseSeconds = Math.max(0.0, loadedPlaytimeSeconds);
                this.resumeAnchor = clock.instant();
            }

            /**
             * Returns total accumulated active playtime in seconds.
             *
             * @return total playtime seconds
             */
            public synchronized double getTotalPlaytimeSeconds() {
                long elapsedMillis = Math.max(0L, clock.instant().toEpochMilli() - resumeAnchor.toEpochMilli());
                return baseSeconds + (elapsedMillis / 1000.0);
            }
        }
    }

    /**
     * Formats descriptive WASD arena save metadata subtitles showing current coordinates ({@code #1003}).
     *
     * @param arenaLabel optional arena label (nullable)
     * @param x          player X coordinate
     * @param y          player Y coordinate
     * @return formatted save badge string
     */
    public static String formatWasdArenaBadge(String arenaLabel, float x, float y) {
        String name = (arenaLabel == null || arenaLabel.isBlank()) ? "WASD-Arena" : arenaLabel.trim();
        return name + " (x=" + Math.round(x) + ", y=" + Math.round(y) + ")";
    }

    /**
     * Resets active session variables, WASD motion controllers, and audio state cleanly when
     * returning to the title screen so repeated runs behave identically ({@code #1012}).
     */
    public static final class SessionResetCoordinator {

        /**
         * Creates a {@code SessionResetCoordinator}.
         */
        public SessionResetCoordinator() {
        }

        /**
         * Clears transient campaign state and runs optional subsystem cleanup ({@code #1012}).
         *
         * @param store              campaign state store (must not be {@code null})
         * @param clearMotionAndAudio optional motion/tween/audio reset hook (nullable)
         */
        public static void resetToTitle(StateStore store, Runnable clearMotionAndAudio) {
            Objects.requireNonNull(store, "store must not be null");
            store.clear();
            if (clearMotionAndAudio != null) {
                clearMotionAndAudio.run();
            }
        }
    }
}
