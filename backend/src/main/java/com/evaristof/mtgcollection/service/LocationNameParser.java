package com.evaristof.mtgcollection.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Understands the "two folders in one cell" notation the user's old
 * spreadsheet uses for copies spread across locations:
 *
 * <pre>
 *   "Blue Pasta GameGenic (3) e Dragon Pasta Troca (5)"
 *       -> 3 cópias em "Blue Pasta GameGenic"
 *       -> 5 cópias em "Dragon Pasta Troca"
 * </pre>
 *
 * <p>Only strings made <em>entirely</em> of two or more {@code nome (n)}
 * parts joined by "e" are split. A single part is left alone on purpose, so a
 * location legitimately named "Deck Modern (2023)" is not read as 2023 copies
 * of anything. The "e" that separates parts is only recognised between them,
 * so "Pasta Escura e Clara (2) e Caixa 7 (1)" splits into "Pasta Escura e
 * Clara" and "Caixa 7".</p>
 */
public final class LocationNameParser {

    /** The whole string must be "nome (n)" repeated, joined by "e". */
    private static final Pattern COMPOUND = Pattern.compile(
            "^\\s*[^()]+\\(\\s*\\d+\\s*\\)(?:\\s*e\\s*[^()]+\\(\\s*\\d+\\s*\\))+\\s*$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PART = Pattern.compile("([^()]+?)\\s*\\(\\s*(\\d+)\\s*\\)");

    private static final Pattern LEADING_E = Pattern.compile("^\\s*e\\s+", Pattern.CASE_INSENSITIVE);

    private LocationNameParser() {
    }

    /** One location and how many copies of the row's card sit in it. */
    public record Part(String location, int quantity) {
    }

    /**
     * Splits a compound location cell into its parts, or returns an empty
     * list when the value is a plain location name (the common case).
     */
    public static List<Part> split(String raw) {
        if (raw == null || raw.isBlank() || !COMPOUND.matcher(raw).matches()) {
            return List.of();
        }
        List<Part> parts = new ArrayList<>(2);
        Matcher m = PART.matcher(raw);
        while (m.find()) {
            String name = LEADING_E.matcher(m.group(1)).replaceFirst("").trim();
            if (name.isEmpty()) {
                continue;
            }
            parts.add(new Part(name, Integer.parseInt(m.group(2))));
        }
        return parts.size() >= 2 ? List.copyOf(parts) : List.of();
    }
}
