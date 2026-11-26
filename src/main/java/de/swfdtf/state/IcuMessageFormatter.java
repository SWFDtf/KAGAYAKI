package de.swfdtf.state;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Lightweight ICU MessageFormat evaluator supporting CLDR {@code plural} and {@code select}
 * expressions across German, English, Polish, French, Spanish, and Japanese with safe degradation
 * on malformed syntax.
 */
public final class IcuMessageFormatter {

    private IcuMessageFormatter() {
    }

    /**
     * Expands any {@code {var, plural, ...}} and {@code {var, select, ...}} ICU blocks inside
     * {@code template} using {@code language} rules and {@code variableLookup}.
     *
     * <p>Plain style tags such as {@code {b}}, {@code {color=#58a6ff}}, {@code {w=0.4}}, and
     * {@code [variable]} placeholders are preserved untouched for downstream rich-text parsing.</p>
     *
     * @param template       raw or localized message text (nullable)
     * @param language       active language identifier (nullable; defaults to {@code "deutsch"})
     * @param variableLookup callback resolving variable names to runtime values (nullable)
     * @return expanded string, or empty string if {@code template} is {@code null}
     */
    public static String format(
            String template,
            String language,
            Function<String, Object> variableLookup
    ) {
        if (template == null || template.isEmpty()) {
            return template == null ? "" : template;
        }
        StringBuilder out = new StringBuilder(template.length());
        int len = template.length();
        int i = 0;
        while (i < len) {
            char c = template.charAt(i);
            if (c == '{') {
                if (i + 1 < len && template.charAt(i + 1) == '{') {
                    out.append("{{");
                    i += 2;
                    continue;
                }
                int closeIdx = findMatchingBrace(template, i);
                if (closeIdx < 0) {
                    out.append(template.substring(i));
                    break;
                }
                String inner = template.substring(i + 1, closeIdx);
                String evaluated = tryEvaluateIcuBlock(inner, language, variableLookup);
                if (evaluated != null) {
                    out.append(evaluated);
                } else {
                    out.append(template, i, closeIdx + 1);
                }
                i = closeIdx + 1;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static int findMatchingBrace(String text, int openIndex) {
        int depth = 0;
        for (int i = openIndex; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String tryEvaluateIcuBlock(
            String inner,
            String language,
            Function<String, Object> variableLookup
    ) {
        int firstComma = findTopLevelComma(inner, 0);
        if (firstComma <= 0) {
            return null;
        }
        int secondComma = findTopLevelComma(inner, firstComma + 1);
        if (secondComma <= firstComma + 1) {
            return null;
        }
        String varName = inner.substring(0, firstComma).trim();
        String kind = inner.substring(firstComma + 1, secondComma).trim().toLowerCase(Locale.ROOT);
        if (!"plural".equals(kind) && !"select".equals(kind)) {
            return null;
        }
        String branchesRaw = inner.substring(secondComma + 1);
        Map<String, String> branches = parseBranches(branchesRaw);
        if (branches == null || branches.isEmpty()) {
            return null;
        }

        Object rawVal = variableLookup != null ? variableLookup.apply(varName) : null;
        if ("select".equals(kind)) {
            String key = rawVal != null ? String.valueOf(rawVal).trim() : "other";
            String chosen = resolveSelectBranch(branches, key);
            return format(chosen, language, variableLookup);
        }

        double numeric = toDouble(rawVal);
        String chosen = resolvePluralBranch(branches, numeric, language);
        String numText = formatNumber(numeric);
        String withHash = replaceHashOutsideBraces(chosen, numText);
        return format(withHash, language, variableLookup);
    }

    private static int findTopLevelComma(String s, int fromIndex) {
        int depth = 0;
        for (int i = fromIndex; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth = Math.max(0, depth - 1);
            } else if (c == ',' && depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static Map<String, String> parseBranches(String raw) {
        Map<String, String> result = new LinkedHashMap<>();
        int i = 0;
        int len = raw.length();
        while (i < len) {
            while (i < len && Character.isWhitespace(raw.charAt(i))) {
                i++;
            }
            if (i >= len) {
                break;
            }
            int keyStart = i;
            while (i < len && !Character.isWhitespace(raw.charAt(i)) && raw.charAt(i) != '{') {
                i++;
            }
            String selector = raw.substring(keyStart, i).trim();
            while (i < len && Character.isWhitespace(raw.charAt(i))) {
                i++;
            }
            if (selector.isEmpty() || i >= len || raw.charAt(i) != '{') {
                return null;
            }
            int bodyClose = findMatchingBrace(raw, i);
            if (bodyClose < 0) {
                return null;
            }
            String body = raw.substring(i + 1, bodyClose);
            result.putIfAbsent(selector, body);
            i = bodyClose + 1;
        }
        return result;
    }

    private static String resolveSelectBranch(Map<String, String> branches, String key) {
        if (branches.containsKey(key)) {
            return branches.get(key);
        }
        for (Map.Entry<String, String> entry : branches.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        if (branches.containsKey("other")) {
            return branches.get("other");
        }
        return branches.values().iterator().next();
    }

    private static String resolvePluralBranch(Map<String, String> branches, double numeric, String language) {
        boolean isWhole = Math.abs(numeric - Math.rint(numeric)) < 1.0e-9;
        if (isWhole) {
            long intVal = Math.round(numeric);
            String exactKey = "=" + intVal;
            if (branches.containsKey(exactKey)) {
                return branches.get(exactKey);
            }
        }
        String category = resolvePluralCategory(numeric, language);
        if (branches.containsKey(category)) {
            return branches.get(category);
        }
        if (branches.containsKey("other")) {
            return branches.get("other");
        }
        return branches.values().iterator().next();
    }

    /**
     * Resolves the CLDR plural category ({@code one}, {@code few}, {@code many}, or {@code other})
     * for {@code value} in the configured {@code language}.
     *
     * @param value    numeric quantity
     * @param language language identifier (nullable)
     * @return CLDR plural category keyword
     */
    public static String resolvePluralCategory(double value, String language) {
        if (!Double.isFinite(value) || value < 0.0) {
            return "other";
        }
        boolean isWhole = Math.abs(value - Math.rint(value)) < 1.0e-9;
        if (!isWhole) {
            return "other";
        }
        long n = Math.round(value);
        String lang = normalizeLang(language);

        if ("japanese".equals(lang) || "ja".equals(lang)) {
            return "other";
        }
        if ("polish".equals(lang) || "pl".equals(lang)) {
            if (n == 1L) {
                return "one";
            }
            long mod10 = n % 10L;
            long mod100 = n % 100L;
            if (mod10 >= 2L && mod10 <= 4L && !(mod100 >= 12L && mod100 <= 14L)) {
                return "few";
            }
            if ((mod10 <= 1L) || (mod10 >= 5L && mod10 <= 9L) || (mod100 >= 12L && mod100 <= 14L)) {
                return "many";
            }
            return "other";
        }
        if ("french".equals(lang) || "fr".equals(lang)) {
            return (n == 0L || n == 1L) ? "one" : "other";
        }
        return n == 1L ? "one" : "other";
    }

    private static String normalizeLang(String language) {
        if (language == null || language.isBlank()) {
            return "deutsch";
        }
        String lower = language.trim().toLowerCase(Locale.ROOT);
        int sep = lower.indexOf('_');
        if (sep < 0) {
            sep = lower.indexOf('-');
        }
        return sep > 0 ? lower.substring(0, sep) : lower;
    }

    private static double toDouble(Object rawVal) {
        if (rawVal instanceof Number n) {
            return n.doubleValue();
        }
        if (rawVal instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return 0.0;
            }
        }
        return 0.0;
    }

    private static String formatNumber(double value) {
        if (!Double.isFinite(value)) {
            return "0";
        }
        if (Math.abs(value - Math.rint(value)) < 1.0e-9) {
            return Long.toString(Math.round(value));
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static String replaceHashOutsideBraces(String body, String replacement) {
        StringBuilder sb = new StringBuilder(body.length() + replacement.length());
        int depth = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '{') {
                depth++;
                sb.append(c);
            } else if (c == '}') {
                depth = Math.max(0, depth - 1);
                sb.append(c);
            } else if (c == '#' && depth == 0) {
                sb.append(replacement);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
