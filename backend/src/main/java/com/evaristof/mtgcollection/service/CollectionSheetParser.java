package com.evaristof.mtgcollection.service;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * The layout and cell-reading rules of the user's collection spreadsheet, as
 * used by {@link CollectionReconciliationService}.
 *
 * <p>{@link CollectionImportService} predates this class and still carries its
 * own private copies of these helpers; they were extracted here rather than
 * changed there so the working import path stayed untouched. Anyone touching
 * the sheet format has to change both — folding the import into this class is
 * a worthwhile follow-up.</p>
 *
 * <pre>
 *   Row 3 (1-based) is the header row:
 *     A: "Number (Optional)"   F: "Quantity"
 *     B: "Card"                G: "Price"
 *     C: "Set"                 H: "Total (Dolar)"
 *     D: "Foil"                I: "Comentário"
 *     E: "Type"                J: "Language"
 *                              K: "Localização"
 * </pre>
 */
public final class CollectionSheetParser {

    public static final String HEADER_NUMBER = "Number (Optional)";
    public static final String HEADER_CARD = "Card";
    public static final String HEADER_SET = "Set";
    public static final String HEADER_FOIL = "Foil";
    public static final String HEADER_TYPE = "Type";
    public static final String HEADER_QTY = "Quantity";
    public static final String HEADER_PRICE = "Price";
    public static final String HEADER_TOTAL = "Total (Dolar)";
    public static final String HEADER_COMMENT = "Comentário";
    public static final String HEADER_LANG = "Language";
    public static final String HEADER_LOC = "Localização";

    public static final int HEADER_ROW_INDEX = 2;   // row 3 in 1-based
    public static final int FIRST_DATA_ROW = 3;     // row 4 in 1-based

    public static final int COL_NUMBER = 0;   // A
    public static final int COL_CARD = 1;     // B
    public static final int COL_SET = 2;      // C
    public static final int COL_FOIL = 3;     // D
    public static final int COL_TYPE = 4;     // E
    public static final int COL_QTY = 5;      // F
    public static final int COL_PRICE = 6;    // G
    public static final int COL_TOTAL = 7;    // H (fórmula G*F na planilha)
    public static final int COL_COMMENT = 8;  // I
    public static final int COL_LANG = 9;     // J
    public static final int COL_LOC = 10;     // K

    private CollectionSheetParser() {
    }

    /** True when the sheet's header row matches the expected A/B/C/D columns. */
    public static boolean hasExpectedHeader(Sheet sheet) {
        Row header = sheet.getRow(HEADER_ROW_INDEX);
        if (header == null) return false;
        return equalsIgnoreCase(readString(header.getCell(COL_NUMBER)), HEADER_NUMBER)
                && equalsIgnoreCase(readString(header.getCell(COL_CARD)), HEADER_CARD)
                && equalsIgnoreCase(readString(header.getCell(COL_SET)), HEADER_SET)
                && equalsIgnoreCase(readString(header.getCell(COL_FOIL)), HEADER_FOIL);
    }

    /** A row counts as data when it names a card or a set. */
    public static boolean isDataRow(Row row) {
        String card = readString(row.getCell(COL_CARD));
        String set = readString(row.getCell(COL_SET));
        return !isBlank(card) || !isBlank(set);
    }

    public static String readString(Cell cell) {
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> stripTrailingZero(cell.getNumericCellValue());
            case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
            case FORMULA -> readFormulaString(cell);
            default -> null;
        };
    }

    private static String readFormulaString(Cell cell) {
        return switch (cell.getCachedFormulaResultType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> stripTrailingZero(cell.getNumericCellValue());
            case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
            default -> null;
        };
    }

    private static String stripTrailingZero(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) {
            return Long.toString((long) v);
        }
        return Double.toString(v);
    }

    public static BigDecimal readBigDecimal(Cell cell) {
        if (cell == null) return null;
        try {
            switch (cell.getCellType()) {
                case NUMERIC -> { return BigDecimal.valueOf(cell.getNumericCellValue()); }
                case STRING -> {
                    String s = cell.getStringCellValue();
                    if (s == null || s.isBlank()) return null;
                    return new BigDecimal(s.trim());
                }
                case FORMULA -> {
                    if (cell.getCachedFormulaResultType() == CellType.NUMERIC) {
                        return BigDecimal.valueOf(cell.getNumericCellValue());
                    }
                }
                default -> { return null; }
            }
        } catch (NumberFormatException | IllegalStateException e) {
            return null;
        }
        return null;
    }

    /** Quantity column, defaulting to 1 for blank/unreadable cells. */
    public static int readQuantity(Cell cell) {
        if (cell == null) return 1;
        try {
            if (cell.getCellType() == CellType.NUMERIC) {
                return Math.max((int) cell.getNumericCellValue(), 1);
            }
            String s = readString(cell);
            if (s == null || s.isBlank()) return 1;
            return Math.max(Integer.parseInt(s.trim()), 1);
        } catch (NumberFormatException | IllegalStateException e) {
            return 1;
        }
    }

    public static boolean isFoilYes(String value) {
        if (value == null) return false;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("sim") || normalized.equals("s")
                || normalized.equals("yes") || normalized.equals("y")
                || normalized.equals("foil") || normalized.equals("true");
    }

    public static boolean equalsIgnoreCase(String actual, String expected) {
        return actual != null && actual.trim().equalsIgnoreCase(expected);
    }

    public static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Lower-cased/trimmed form used as a lookup key (set names, card names…). */
    public static String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }
}
