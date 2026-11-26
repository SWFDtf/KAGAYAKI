package de.swfdtf.tools;

import de.swfdtf.parser.ProjectScanner;
import de.swfdtf.parser.VJavaAst;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Exports KAGAYAKI dialogue lines from a {@link VJavaAst.ProjectAst} to PO, CSV, and XLIFF
 * localization interchange formats and imports translated CSV sheets back into {@code .vjava}
 * {@code translate} block files.
 */
public final class TranslationExportService {

    /** Supported localization interchange file formats. */
    public enum ExportFormat {
        /** GNU Gettext Portable Object format ({@code .po}). */
        PO,
        /** RFC-4180 comma-separated spreadsheet format ({@code .csv}). */
        CSV,
        /** OASIS XLIFF 1.2 XML localization interchange format ({@code .xliff}). */
        XLIFF
    }

    private record ExportRow(String id, String speaker, String source) {
    }

    private static final Pattern VALID_LANGUAGE = Pattern.compile("[A-Za-z_][A-Za-z0-9_-]*");
    private static final Pattern NON_IDENT_CHARS = Pattern.compile("[^A-Za-z0-9_]");

    /**
     * Creates a new {@code TranslationExportService}.
     */
    public TranslationExportService() {
    }

    /**
     * Scans {@code projectDir} and exports all dialogue entries in the requested {@code format}.
     *
     * @param projectDir root directory of a KAGAYAKI project
     * @param format     target interchange format
     * @param outputFile target output file path
     * @throws IOException if scanning or writing fails
     */
    public void export(Path projectDir, ExportFormat format, Path outputFile) throws IOException {
        Objects.requireNonNull(projectDir, "projectDir must not be null");
        export(ProjectScanner.scan(projectDir), format, outputFile);
    }

    /**
     * Exports all dialogue entries from {@code project} in the requested {@code format}.
     *
     * @param project    parsed project AST
     * @param format     target interchange format
     * @param outputFile target output file path
     * @throws IOException if writing fails
     */
    public void export(VJavaAst.ProjectAst project, ExportFormat format, Path outputFile) throws IOException {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(format, "format must not be null");
        Objects.requireNonNull(outputFile, "outputFile must not be null");
        switch (format) {
            case PO -> exportPo(project, outputFile);
            case CSV -> exportCsv(project, outputFile);
            case XLIFF -> exportXliff(project, outputFile);
        }
    }

    /**
     * Exports project dialogues to GNU Gettext PO format ({@code outputFile}).
     *
     * @param project    parsed project AST
     * @param outputFile destination {@code .po} path
     * @throws IOException if writing fails
     */
    public void exportPo(VJavaAst.ProjectAst project, Path outputFile) throws IOException {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(outputFile, "outputFile must not be null");
        List<ExportRow> rows = collectDialogueRows(project);
        StringBuilder sb = new StringBuilder();
        sb.append("msgid \"\"\n");
        sb.append("msgstr \"\"\n");
        sb.append("\"Content-Type: text/plain; charset=UTF-8\\n\"\n\n");
        for (ExportRow row : rows) {
            sb.append("#: ").append(row.id()).append('\n');
            if (!row.speaker().isEmpty()) {
                sb.append("msgctxt \"").append(escapeQuoted(row.speaker())).append("\"\n");
            }
            sb.append("msgid \"").append(escapeQuoted(row.source())).append("\"\n");
            sb.append("msgstr \"\"\n\n");
        }
        writeUtf8(outputFile, sb.toString());
    }

    /**
     * Exports project dialogues to RFC-4180 CSV format ({@code outputFile}).
     *
     * @param project    parsed project AST
     * @param outputFile destination {@code .csv} path
     * @throws IOException if writing fails
     */
    public void exportCsv(VJavaAst.ProjectAst project, Path outputFile) throws IOException {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(outputFile, "outputFile must not be null");
        List<ExportRow> rows = collectDialogueRows(project);
        StringBuilder sb = new StringBuilder();
        sb.append("id,speaker,source,translation\n");
        for (ExportRow row : rows) {
            sb.append(escapeCsvField(row.id())).append(',');
            sb.append(escapeCsvField(row.speaker())).append(',');
            sb.append(escapeCsvField(row.source())).append(',');
            sb.append("\"\"\n");
        }
        writeUtf8(outputFile, sb.toString());
    }

    /**
     * Exports project dialogues to OASIS XLIFF 1.2 XML format ({@code outputFile}).
     *
     * @param project    parsed project AST
     * @param outputFile destination {@code .xliff} path
     * @throws IOException if writing fails
     */
    public void exportXliff(VJavaAst.ProjectAst project, Path outputFile) throws IOException {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(outputFile, "outputFile must not be null");
        List<ExportRow> rows = collectDialogueRows(project);
        String sourceLang = (project.descriptor() != null && project.descriptor().getDefaultLanguage() != null)
                ? project.descriptor().getDefaultLanguage()
                : "deutsch";
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<xliff version=\"1.2\">\n");
        sb.append("  <file original=\"main.vjava\" source-language=\"")
                .append(escapeXml(sourceLang))
                .append("\" datatype=\"plaintext\">\n");
        sb.append("    <body>\n");
        for (ExportRow row : rows) {
            sb.append("      <trans-unit id=\"").append(escapeXml(row.id())).append("\">\n");
            sb.append("        <source>").append(escapeXml(row.source())).append("</source>\n");
            sb.append("        <target></target>\n");
            if (!row.speaker().isEmpty()) {
                sb.append("        <note>speaker: ").append(escapeXml(row.speaker())).append("</note>\n");
            }
            sb.append("      </trans-unit>\n");
        }
        sb.append("    </body>\n");
        sb.append("  </file>\n");
        sb.append("</xliff>\n");
        writeUtf8(outputFile, sb.toString());
    }

    /**
     * Strictly parses a translated CSV file and writes a lint-valid {@code .vjava} translation file
     * for {@code language} at {@code targetVjavaFile}. Malformed CSV input throws {@link IOException}
     * before creating or modifying {@code targetVjavaFile}.
     *
     * @param csvFile         source CSV path
     * @param language        target language identifier
     * @param targetVjavaFile destination {@code translations.vjava} path
     * @throws IOException if reading, strict CSV parsing, or writing fails
     */
    public void importCsv(Path csvFile, String language, Path targetVjavaFile) throws IOException {
        Objects.requireNonNull(csvFile, "csvFile must not be null");
        Objects.requireNonNull(language, "language must not be null");
        Objects.requireNonNull(targetVjavaFile, "targetVjavaFile must not be null");

        String trimmedLang = language.trim();
        if (!VALID_LANGUAGE.matcher(trimmedLang).matches()) {
            throw new IllegalArgumentException("Invalid translation language identifier: '" + language + "'");
        }

        String rawCsv = Files.readString(csvFile, StandardCharsets.UTF_8);
        List<List<String>> rows = parseStrictCsv(rawCsv);
        if (rows.isEmpty()) {
            throw new IOException("CSV translation file is empty: " + csvFile);
        }

        int startIdx = isHeaderRow(rows.get(0)) ? 1 : 0;
        StringBuilder vjava = new StringBuilder();
        int fallbackSeq = 1;
        for (int i = startIdx; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (row.size() < 4) {
                throw new IOException("Malformed CSV row at line " + (i + 1) + ": expected 4 columns, got "
                        + row.size());
            }
            String id = sanitizeId(row.get(0), fallbackSeq++);
            String speaker = row.get(1) != null ? row.get(1).trim() : "";
            String source = row.get(2) != null ? row.get(2) : "";
            String translation = row.get(3) != null ? row.get(3) : "";

            vjava.append("translate ").append(trimmedLang).append(' ').append(id).append(":\n");
            vjava.append("    # ");
            if (!speaker.isEmpty()) {
                vjava.append(speaker).append(' ');
            }
            vjava.append('"').append(escapeQuoted(source)).append("\"\n");
            vjava.append("    ");
            if (!speaker.isEmpty()) {
                vjava.append(speaker).append(' ');
            }
            vjava.append('"').append(escapeQuoted(translation)).append("\"\n\n");
        }
        writeUtf8(targetVjavaFile, vjava.toString());
    }

    private static List<ExportRow> collectDialogueRows(VJavaAst.ProjectAst project) {
        List<ExportRow> rows = new ArrayList<>();
        int[] seq = {1};
        for (VJavaAst.LabelNode label : project.labels().values()) {
            collectFromStatements(label.statements(), rows, seq);
        }
        return rows;
    }

    private static void collectFromStatements(
            List<VJavaAst.Statement> statements,
            List<ExportRow> rows,
            int[] seq
    ) {
        for (VJavaAst.Statement stmt : statements) {
            if (stmt instanceof VJavaAst.SayStatement say) {
                String text = say.text() != null ? say.text() : "";
                String speaker = say.speakerId() != null ? say.speakerId().trim() : "";
                String id = String.format("line_%02d", seq[0]++);
                rows.add(new ExportRow(id, speaker, text));
            } else if (stmt instanceof VJavaAst.IfStatement ifStmt) {
                for (VJavaAst.IfBranch branch : ifStmt.branches()) {
                    collectFromStatements(branch.body(), rows, seq);
                }
            } else if (stmt instanceof VJavaAst.MenuStatement menu) {
                for (VJavaAst.MenuChoice choice : menu.choices()) {
                    collectFromStatements(choice.body(), rows, seq);
                }
            } else if (stmt instanceof VJavaAst.MatchStatement matchStmt) {
                for (VJavaAst.MatchCase mc : matchStmt.cases()) {
                    collectFromStatements(mc.body(), rows, seq);
                }
                if (matchStmt.defaultBody() != null) {
                    collectFromStatements(matchStmt.defaultBody(), rows, seq);
                }
            } else if (stmt instanceof VJavaAst.ParallelStatement parallelStmt) {
                collectFromStatements(parallelStmt.statements(), rows, seq);
            }
        }
    }

    private static List<List<String>> parseStrictCsv(String raw) throws IOException {
        List<List<String>> records = new ArrayList<>();
        List<String> currentRow = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean fieldHasStarted = false;
        boolean quoteJustClosed = false;

        int i = 0;
        int len = raw.length();
        while (i < len) {
            char c = raw.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < len && raw.charAt(i + 1) == '"') {
                        field.append('"');
                        i += 2;
                    } else {
                        inQuotes = false;
                        quoteJustClosed = true;
                        i++;
                    }
                } else {
                    field.append(c);
                    i++;
                }
            } else {
                if (c == '"') {
                    if (fieldHasStarted || quoteJustClosed) {
                        throw new IOException("Malformed CSV: unexpected quote inside unquoted field at offset " + i);
                    }
                    inQuotes = true;
                    fieldHasStarted = true;
                    i++;
                } else if (c == ',') {
                    currentRow.add(field.toString());
                    field.setLength(0);
                    fieldHasStarted = false;
                    quoteJustClosed = false;
                    i++;
                } else if (c == '\r' || c == '\n') {
                    if (c == '\r' && i + 1 < len && raw.charAt(i + 1) == '\n') {
                        i++;
                    }
                    currentRow.add(field.toString());
                    field.setLength(0);
                    if (!(currentRow.size() == 1 && currentRow.get(0).isEmpty())) {
                        records.add(currentRow);
                    }
                    currentRow = new ArrayList<>();
                    fieldHasStarted = false;
                    quoteJustClosed = false;
                    i++;
                } else {
                    if (quoteJustClosed) {
                        throw new IOException("Malformed CSV: trailing character after closing quote at offset " + i);
                    }
                    field.append(c);
                    fieldHasStarted = true;
                    i++;
                }
            }
        }

        if (inQuotes) {
            throw new IOException("Malformed CSV: unterminated quoted field");
        }
        if (fieldHasStarted || quoteJustClosed || !currentRow.isEmpty()) {
            currentRow.add(field.toString());
            records.add(currentRow);
        }
        return records;
    }

    private static boolean isHeaderRow(List<String> firstRow) {
        return firstRow.size() >= 3
                && "id".equalsIgnoreCase(firstRow.get(0).trim())
                && "speaker".equalsIgnoreCase(firstRow.get(1).trim())
                && "source".equalsIgnoreCase(firstRow.get(2).trim());
    }

    private static String sanitizeId(String rawId, int seq) {
        String base = rawId == null ? "" : NON_IDENT_CHARS.matcher(rawId.trim()).replaceAll("_");
        if (base.isEmpty() || Character.isDigit(base.charAt(0))) {
            return "line_" + seq;
        }
        return base;
    }

    private static String escapeCsvField(String value) {
        String safe = value != null ? value : "";
        boolean needsQuotes = safe.indexOf(',') >= 0
                || safe.indexOf('"') >= 0
                || safe.indexOf('\n') >= 0
                || safe.indexOf('\r') >= 0;
        if (!needsQuotes) {
            return safe;
        }
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    private static String escapeQuoted(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }

    private static String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static void writeUtf8(Path outputFile, String content) throws IOException {
        Path parent = outputFile.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(outputFile, content, StandardCharsets.UTF_8);
    }
}
