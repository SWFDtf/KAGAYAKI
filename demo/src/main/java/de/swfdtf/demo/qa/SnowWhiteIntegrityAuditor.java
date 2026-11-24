package de.swfdtf.demo.qa;

import de.swfdtf.demo.service.SnowWhiteAssetGenerator;
import de.swfdtf.tools.AssetChecksumVerifier;
import de.swfdtf.tools.AssetChecksumVerifier.AssetManifest;
import de.swfdtf.tools.AssetChecksumVerifier.VerificationReport;
import de.swfdtf.tools.AssetMetadataInspector;
import de.swfdtf.tools.AssetMetadataInspector.AssetMetadata;
import de.swfdtf.tools.ExportPreFlightChecker;
import de.swfdtf.tools.ExportPreFlightChecker.PreFlightReport;
import de.swfdtf.tools.VpakArchiver;
import de.swfdtf.tools.VpakManifestInspector;
import de.swfdtf.tools.VpakManifestInspector.VpakInspectionReport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Führt eine vollständige Integritäts-, Metadaten-, Pre-Flight- und {@code .vpak}-Archivprüfung
 * für das Schneewittchen-{@code .vjava}-Projekt durch.
 */
public final class SnowWhiteIntegrityAuditor {

    /**
     * Strategie zum Bereitstellen bzw. Erzeugen der Projekt-Assets vor der Prüfung.
     */
    @FunctionalInterface
    public interface AssetPreparer {
        /**
         * Stellt sicher, dass die erwarteten Projekt-Assets unter {@code projectDir} vorliegen.
         *
         * @param projectDir Wurzelverzeichnis des Projekts
         * @throws IOException falls das Bereitstellen fehlschlägt
         */
        void ensureAssets(Path projectDir) throws IOException;
    }

    private final AssetChecksumVerifier checksumVerifier;
    private final AssetMetadataInspector metadataInspector;
    private final ExportPreFlightChecker preFlightChecker;
    private final VpakManifestInspector vpakInspector;
    private final AssetPreparer assetPreparer;

    /**
     * Unveränderlicher Integritätsbericht über Checksummen, Asset-Metadaten, Pre-Flight-Check
     * und {@code .vpak}-Archiv-Verifikation.
     *
     * @param manifest        erzeugtes SHA-256-Asset-Manifest
     * @param checksumReport  Ergebnis der Checksummen-Verifikation gegen das Manifest
     * @param assetMetadata   unveränderliche Liste aller inspizierten Asset-Metadaten
     * @param preFlightReport Ergebnis der Release-Vorabprüfung
     * @param vpakReport      Ergebnis der temporären {@code .vpak}-Archiv-Prüfung
     * @param allPassed       {@code true}, wenn Checksummen, Pre-Flight und {@code .vpak}-Integrität bestanden sind
     */
    public record IntegrityAuditReport(
            AssetManifest manifest,
            VerificationReport checksumReport,
            List<AssetMetadata> assetMetadata,
            PreFlightReport preFlightReport,
            VpakInspectionReport vpakReport,
            boolean allPassed
    ) {

        /**
         * Validiert Pflichtfelder und erzwingt eine unveränderliche Kopie der Metadaten-Liste.
         */
        public IntegrityAuditReport {
            Objects.requireNonNull(manifest, "manifest darf nicht null sein");
            Objects.requireNonNull(checksumReport, "checksumReport darf nicht null sein");
            Objects.requireNonNull(preFlightReport, "preFlightReport darf nicht null sein");
            Objects.requireNonNull(vpakReport, "vpakReport darf nicht null sein");
            Objects.requireNonNull(assetMetadata, "assetMetadata darf nicht null sein");
            for (AssetMetadata entry : assetMetadata) {
                Objects.requireNonNull(entry, "assetMetadata darf keine null-Einträge enthalten");
            }
            assetMetadata = List.copyOf(assetMetadata);
        }

        /**
         * Sucht die Metadaten eines Assets anhand seines projekt-relativen Pfads.
         *
         * @param relativePath projekt-relativer Pfad (darf nicht {@code null} oder leer sein)
         * @return optionale {@link AssetMetadata}
         */
        public Optional<AssetMetadata> findMetadata(String relativePath) {
            Objects.requireNonNull(relativePath, "relativePath darf nicht null sein");
            if (relativePath.isBlank()) {
                throw new IllegalArgumentException("relativePath darf nicht leer sein");
            }
            String normalized = relativePath.trim().replace('\\', '/');
            for (AssetMetadata meta : assetMetadata) {
                if (meta.relativePath().equals(normalized)) {
                    return Optional.of(meta);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * Erzeugt einen {@code SnowWhiteIntegrityAuditor} mit Standard-Prüfkomponenten und
     * {@link SnowWhiteAssetGenerator#ensureAssets(Path)}.
     */
    public SnowWhiteIntegrityAuditor() {
        this(
                new AssetChecksumVerifier(),
                new AssetMetadataInspector(),
                new ExportPreFlightChecker(),
                new VpakManifestInspector(),
                SnowWhiteAssetGenerator::ensureAssets
        );
    }

    /**
     * Erzeugt einen {@code SnowWhiteIntegrityAuditor} mit explizit injizierten Prüfkomponenten.
     *
     * @param checksumVerifier  Prüfer für SHA-256-Manifeste (darf nicht {@code null} sein)
     * @param metadataInspector Inspektor für PNG-/WAV-Metadaten (darf nicht {@code null} sein)
     * @param preFlightChecker  Release-Vorabprüfer (darf nicht {@code null} sein)
     * @param vpakInspector     Inspektor für {@code .vpak}-Archive (darf nicht {@code null} sein)
     */
    public SnowWhiteIntegrityAuditor(
            AssetChecksumVerifier checksumVerifier,
            AssetMetadataInspector metadataInspector,
            ExportPreFlightChecker preFlightChecker,
            VpakManifestInspector vpakInspector
    ) {
        this(checksumVerifier, metadataInspector, preFlightChecker, vpakInspector, SnowWhiteAssetGenerator::ensureAssets);
    }

    /**
     * Erzeugt einen {@code SnowWhiteIntegrityAuditor} mit vollständig injizierbaren Komponenten inklusive
     * {@link AssetPreparer}.
     *
     * @param checksumVerifier  Prüfer für SHA-256-Manifeste (darf nicht {@code null} sein)
     * @param metadataInspector Inspektor für PNG-/WAV-Metadaten (darf nicht {@code null} sein)
     * @param preFlightChecker  Release-Vorabprüfer (darf nicht {@code null} sein)
     * @param vpakInspector     Inspektor für {@code .vpak}-Archive (darf nicht {@code null} sein)
     * @param assetPreparer     Strategie zur Asset-Bereitstellung (darf nicht {@code null} sein)
     */
    public SnowWhiteIntegrityAuditor(
            AssetChecksumVerifier checksumVerifier,
            AssetMetadataInspector metadataInspector,
            ExportPreFlightChecker preFlightChecker,
            VpakManifestInspector vpakInspector,
            AssetPreparer assetPreparer
    ) {
        this.checksumVerifier = Objects.requireNonNull(checksumVerifier, "checksumVerifier darf nicht null sein");
        this.metadataInspector = Objects.requireNonNull(metadataInspector, "metadataInspector darf nicht null sein");
        this.preFlightChecker = Objects.requireNonNull(preFlightChecker, "preFlightChecker darf nicht null sein");
        this.vpakInspector = Objects.requireNonNull(vpakInspector, "vpakInspector darf nicht null sein");
        this.assetPreparer = Objects.requireNonNull(assetPreparer, "assetPreparer darf nicht null sein");
    }

    /**
     * Stellt die Projekt-Assets sicher, generiert und verifiziert das SHA-256-Manifest,
     * inspiziert alle Asset-Header, führt den Pre-Flight-Check aus, packt ein temporäres
     * {@code .vpak}-Archiv in {@code workDir} und prüft dieses über {@link VpakManifestInspector}.
     *
     * @param projectDir Wurzelverzeichnis des {@code .vjava}-Projekts (darf nicht {@code null} sein)
     * @param workDir    Arbeitsverzeichnis für das temporäre {@code .vpak}-Archiv (darf nicht {@code null} sein)
     * @return unveränderlicher {@link IntegrityAuditReport}
     * @throws IOException              falls Dateisystem-, Pack- oder Leseoperationen fehlschlagen
     * @throws IllegalArgumentException falls {@code projectDir} kein gültiges Verzeichnis ist
     */
    public IntegrityAuditReport audit(Path projectDir, Path workDir) throws IOException {
        Objects.requireNonNull(projectDir, "projectDir darf nicht null sein");
        Objects.requireNonNull(workDir, "workDir darf nicht null sein");

        Path normalizedProject = projectDir.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalizedProject)) {
            throw new IllegalArgumentException("Projektverzeichnis existiert nicht oder ist kein Verzeichnis: " + normalizedProject);
        }
        Path normalizedWork = workDir.toAbsolutePath().normalize();
        Files.createDirectories(normalizedWork);

        assetPreparer.ensureAssets(normalizedProject);

        AssetManifest manifest = checksumVerifier.generateManifest(normalizedProject);
        VerificationReport checksumReport = checksumVerifier.verify(normalizedProject, manifest);
        List<AssetMetadata> metadata = metadataInspector.inspectAll(normalizedProject);
        PreFlightReport preFlightReport = preFlightChecker.check(normalizedProject);

        Path tempVpak = Files.createTempFile(normalizedWork, "snowwhite-audit-", ".vpak");
        VpakInspectionReport vpakReport;
        try {
            VpakArchiver.pack(normalizedProject, tempVpak);
            vpakReport = vpakInspector.inspect(tempVpak);
        } finally {
            Files.deleteIfExists(tempVpak);
        }

        boolean allPassed = checksumReport.isIntact()
                && preFlightReport.readyForRelease()
                && vpakReport.integrityVerified();

        return new IntegrityAuditReport(
                manifest,
                checksumReport,
                metadata,
                preFlightReport,
                vpakReport,
                allPassed
        );
    }
}
