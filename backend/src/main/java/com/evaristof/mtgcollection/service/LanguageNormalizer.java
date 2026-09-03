package com.evaristof.mtgcollection.service;

import java.util.Locale;
import java.util.Map;

/**
 * Maps the many spellings of a language onto one canonical name so the
 * reconciliation doesn't report "en" and "English" as two different cards.
 *
 * <p>The old spreadsheet uses codes ("en", "pt"), while the "Cadastro Cartas"
 * screen writes the full names ("English", "Portuguese"). Anything not
 * recognised is kept as-is (trimmed) — an unknown language still compares
 * consistently with itself.</p>
 */
public final class LanguageNormalizer {

    private static final Map<String, String> CANONICAL = Map.ofEntries(
            Map.entry("en", "English"),
            Map.entry("eng", "English"),
            Map.entry("english", "English"),
            Map.entry("ingles", "English"),
            Map.entry("inglês", "English"),
            Map.entry("pt", "Portuguese"),
            Map.entry("por", "Portuguese"),
            Map.entry("ptbr", "Portuguese"),
            Map.entry("pt-br", "Portuguese"),
            Map.entry("portugues", "Portuguese"),
            Map.entry("português", "Portuguese"),
            Map.entry("portuguese", "Portuguese"),
            Map.entry("ja", "Japanese"),
            Map.entry("jp", "Japanese"),
            Map.entry("jpn", "Japanese"),
            Map.entry("japanese", "Japanese"),
            Map.entry("japones", "Japanese"),
            Map.entry("japonês", "Japanese"),
            Map.entry("fr", "French"),
            Map.entry("fra", "French"),
            Map.entry("fre", "French"),
            Map.entry("french", "French"),
            Map.entry("frances", "French"),
            Map.entry("francês", "French"),
            Map.entry("de", "German"),
            Map.entry("ger", "German"),
            Map.entry("deu", "German"),
            Map.entry("german", "German"),
            Map.entry("alemao", "German"),
            Map.entry("alemão", "German"),
            Map.entry("it", "Italian"),
            Map.entry("ita", "Italian"),
            Map.entry("italian", "Italian"),
            Map.entry("italiano", "Italian"),
            Map.entry("es", "Spanish"),
            Map.entry("sp", "Spanish"),
            Map.entry("spa", "Spanish"),
            Map.entry("spanish", "Spanish"),
            Map.entry("espanhol", "Spanish"),
            Map.entry("zh", "Chinese"),
            Map.entry("cn", "Chinese"),
            Map.entry("chinese", "Chinese"),
            Map.entry("chines", "Chinese"),
            Map.entry("chinês", "Chinese"),
            // Grafia errada que existe na planilha antiga — mapeada de
            // propósito para não virar uma diferença falsa na reconciliação.
            Map.entry("chinise", "Chinese"),
            Map.entry("ko", "Korean"),
            Map.entry("kor", "Korean"),
            Map.entry("korean", "Korean"),
            Map.entry("coreano", "Korean"),
            Map.entry("ru", "Russian"),
            Map.entry("rus", "Russian"),
            Map.entry("russian", "Russian"),
            Map.entry("russo", "Russian"));

    private LanguageNormalizer() {
    }

    /** Canonical language name, or {@code ""} when the value is blank. */
    public static String canonical(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        return CANONICAL.getOrDefault(key, raw.trim());
    }

    /** Comparison key: canonical name, lower-cased. */
    public static String key(String raw) {
        return canonical(raw).toLowerCase(Locale.ROOT);
    }
}
