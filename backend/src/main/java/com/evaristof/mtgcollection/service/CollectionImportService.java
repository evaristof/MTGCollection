package com.evaristof.mtgcollection.service;

import com.evaristof.mtgcollection.domain.CollectionCard;
import com.evaristof.mtgcollection.domain.MagicSet;
import com.evaristof.mtgcollection.repository.CollectionCardRepository;
import com.evaristof.mtgcollection.repository.MagicSetRepository;
import com.evaristof.mtgcollection.scryfall.dto.ScryfallCard;
import com.evaristof.mtgcollection.scryfall.dto.ScryfallPrices;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses an uploaded Magic collection spreadsheet, enriches every row with
 * the card's current price and type (via Scryfall), persists each row as a
 * {@link CollectionCard}, and produces an enriched copy of the workbook.
 *
 * <p>The expected sheet layout follows the user's legacy template:</p>
 * <pre>
 *   Row 3 (1-based) is the header row:
 *     A: "Number (Optional)"
 *     B: "Card"
 *     C: "Set"
 *     D: "Foil"
 *     E: "Type"        (filled by this service)
 *     F: "Quantity"
 *     G: "Price"       (filled by this service)
 *     H: "Total (Dolar)"   (formula G*F, preserved)
 *     I: "Comentário"
 *     J: "Language"
 *     K: "Localização"
 * </pre>
 *
 * <p>Sheets that do not match the header contract (A/B/C/D) are left
 * untouched in the output workbook. Rows flagged with
 * {@code *Conferir sempre Manualmente} in column I skip the Scryfall lookup
 * but are still persisted.</p>
 */
@Service
public class CollectionImportService {

    private static final Logger log = LoggerFactory.getLogger(CollectionImportService.class);

    private static final String HEADER_NUMBER = "Number (Optional)";
    private static final String HEADER_CARD = "Card";
    private static final String HEADER_SET = "Set";
    private static final String HEADER_FOIL = "Foil";
    private static final String MANUAL_FLAG = "*Conferir sempre Manualmente";

    private static final int HEADER_ROW_INDEX = 2;   // row 3 in 1-based
    private static final int FIRST_DATA_ROW = 3;     // row 4 in 1-based

    private static final int COL_NUMBER = 0;   // A
    private static final int COL_CARD = 1;     // B
    private static final int COL_SET = 2;      // C
    private static final int COL_FOIL = 3;     // D
    private static final int COL_TYPE = 4;     // E
    private static final int COL_QTY = 5;      // F
    private static final int COL_PRICE = 6;    // G
    private static final int COL_COMMENT = 8;  // I
    private static final int COL_LANG = 9;     // J
    private static final int COL_LOC = 10;     // K

    private final CardLookupService cardLookupService;
    private final CollectionCardRepository cardRepository;
    private final MagicSetRepository setRepository;
    private final SetPersistenceService setPersistenceService;
    private final long throttleMs;

    public CollectionImportService(CardLookupService cardLookupService,
                                   CollectionCardRepository cardRepository,
                                   MagicSetRepository setRepository,
                                   SetPersistenceService setPersistenceService,
                                   @Value("${mtg.import.throttle-ms:300}") long throttleMs) {
        this.cardLookupService = cardLookupService;
        this.cardRepository = cardRepository;
        this.setRepository = setRepository;
        this.setPersistenceService = setPersistenceService;
        this.throttleMs = Math.max(0L, throttleMs);
    }

    /**
     * Runs the import synchronously on the calling thread. The provided
     * {@link ImportJob} is updated in place so that callers (e.g. the HTTP
     * status endpoint) can observe progress.
     */
    public void runImport(ImportJob job, byte[] xlsxBytes) {
        Map<String, MagicSet> setsByName = indexSetsByName();
        if (setsByName.isEmpty()) {
            log.warn("Magic set table is empty — attempting to sync from Scryfall before import");
            try {
                setPersistenceService.syncSetsFromScryfall();
                setsByName = indexSetsByName();
            } catch (RuntimeException e) {
                log.warn("Auto-sync of sets failed: {}", e.getMessage());
                job.addError("Não foi possível sincronizar os sets automaticamente: " + e.getMessage()
                        + ". As linhas ser\u00e3o gravadas sem set_code resolvido.");
            }
        }

        List<UnresolvedRow> unresolved = new ArrayList<>();

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsxBytes))) {
            job.setTotal(countImportableRows(workbook));

            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                Sheet sheet = workbook.getSheetAt(s);
                if (!hasExpectedHeader(sheet)) {
                    log.debug("Skipping sheet '{}' (header mismatch)", sheet.getSheetName());
                    continue;
                }
                job.setCurrentSheet(sheet.getSheetName());
                processSheet(sheet, setsByName, job, unresolved);
            }

            appendUnresolvedSheet(workbook, unresolved);

            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                workbook.write(out);
                String outputName = deriveOutputFileName(job.getFileName());
                job.setResult(out.toByteArray(), outputName);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Falha lendo planilha", e);
        }
    }

    private Map<String, MagicSet> indexSetsByName() {
        List<MagicSet> all = setRepository.findAll();
        Map<String, MagicSet> byName = new HashMap<>(all.size() * 2);
        for (MagicSet set : all) {
            if (set.getSetName() != null) {
                byName.put(normalize(set.getSetName()), set);
            }
        }
        return byName;
    }

    private int countImportableRows(XSSFWorkbook workbook) {
        int total = 0;
        for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
            Sheet sheet = workbook.getSheetAt(s);
            if (!hasExpectedHeader(sheet)) continue;
            for (int r = FIRST_DATA_ROW; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                if (!isDataRow(row)) continue;
                total++;
            }
        }
        return total;
    }

    private boolean hasExpectedHeader(Sheet sheet) {
        Row header = sheet.getRow(HEADER_ROW_INDEX);
        if (header == null) return false;
        return equalsIgnoreCase(readString(header.getCell(COL_NUMBER)), HEADER_NUMBER)
                && equalsIgnoreCase(readString(header.getCell(COL_CARD)), HEADER_CARD)
                && equalsIgnoreCase(readString(header.getCell(COL_SET)), HEADER_SET)
                && equalsIgnoreCase(readString(header.getCell(COL_FOIL)), HEADER_FOIL);
    }

    private boolean isDataRow(Row row) {
        String card = readString(row.getCell(COL_CARD));
        String set = readString(row.getCell(COL_SET));
        return !isBlank(card) || !isBlank(set);
    }

    private void processSheet(Sheet sheet,
                              Map<String, MagicSet> setsByName,
                              ImportJob job,
                              List<UnresolvedRow> unresolved) {
        for (int r = FIRST_DATA_ROW; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            if (!isDataRow(row)) continue;

            String rowRef = sheet.getSheetName() + "!" + (r + 1);
            try {
                processRow(sheet, row, r, rowRef, setsByName, job, unresolved);
            } catch (RuntimeException e) {
                log.warn("Erro processando {}: {}", rowRef, e.getMessage());
                job.addError(rowRef + ": " + e.getMessage());
            } finally {
                job.incrementProcessed();
            }
        }
    }

    private void processRow(Sheet sheet,
                            Row row,
                            int rowIndex,
                            String rowRef,
                            Map<String, MagicSet> setsByName,
                            ImportJob job,
                            List<UnresolvedRow> unresolved) {
        String number = readString(row.getCell(COL_NUMBER));
        String cardName = readString(row.getCell(COL_CARD));
        String setName = readString(row.getCell(COL_SET));
        String foilText = readString(row.getCell(COL_FOIL));
        int quantity = readQuantity(row.getCell(COL_QTY));
        String comentario = readString(row.getCell(COL_COMMENT));
        String language = readString(row.getCell(COL_LANG));
        String localizacao = readString(row.getCell(COL_LOC));

        boolean foil = isFoilYes(foilText);
        boolean manualOverride = comentario != null
                && comentario.trim().toLowerCase(Locale.ROOT)
                        .startsWith(MANUAL_FLAG.toLowerCase(Locale.ROOT));

        MagicSet resolvedSet = setsByName.get(normalize(setName));
        String setCode = resolvedSet != null ? resolvedSet.getSetCode() : null;
        String cardNumber = number;
        String cardType = null;
        BigDecimal price = null;

        if (manualOverride) {
            // For manual overrides we must preserve the user's existing E/G
            // values (often formulas) in the spreadsheet. Read the cached
            // results for DB persistence but leave the cells untouched.
            cardType = readString(row.getCell(COL_TYPE));
            price = readBigDecimal(row.getCell(COL_PRICE));
        } else {
            String unresolvedReason = null;
            if (setCode == null) {
                unresolvedReason = "set '" + setName + "' não encontrado na base de sets";
                job.addError(rowRef + ": " + unresolvedReason
                        + " — linha gravada sem preço (sincronize os sets para enriquecer).");
            } else {
                try {
                    ScryfallCard scryfallCard = lookupCard(cardName, number, setCode, rowRef);
                    if (scryfallCard != null) {
                        cardType = scryfallCard.getTypeLine();
                        if (cardNumber == null || cardNumber.isBlank()) {
                            cardNumber = scryfallCard.getCollectorNumber();
                        }
                        price = priceFrom(scryfallCard, foil);
                    } else {
                        unresolvedReason = "Scryfall retornou resposta vazia";
                    }
                } catch (RuntimeException e) {
                    unresolvedReason = "Scryfall falhou: " + e.getMessage();
                    job.addError(rowRef + ": " + unresolvedReason + " — linha gravada sem preço.");
                }
                throttle();
            }
            // Only overwrite the cells when we actually resolved a value,
            // so rows whose lookup failed keep whatever the user had there.
            if (cardType != null) writeCellString(row, COL_TYPE, cardType);
            if (price != null) writeCellNumeric(row, COL_PRICE, price);

            if (unresolvedReason != null) {
                unresolved.add(new UnresolvedRow(
                        sheet.getSheetName(), rowIndex + 1,
                        number, cardName, setName, foilText, unresolvedReason));
            }
        }

        persist(rowRef, cardName, setCode, setName, foil, quantity, cardType,
                cardNumber, price, comentario, language, localizacao, job);
    }

    private ScryfallCard lookupCard(String cardName, String number, String setCode, String rowRef) {
        if (number != null && !number.isBlank()) {
            try {
                return cardLookupService.getCardBySetAndNumber(setCode, number);
            } catch (RuntimeException e) {
                log.debug("{} lookup by number failed ({}), falling back to name", rowRef, e.getMessage());
            }
        }
        if (cardName == null || cardName.isBlank()) {
            throw new IllegalArgumentException("Linha sem nome de carta nem número");
        }
        return cardLookupService.getCardByNameAndSet(cardName, setCode);
    }

    private BigDecimal priceFrom(ScryfallCard card, boolean foil) {
        if (card == null || card.getPrices() == null) return null;
        ScryfallPrices prices = card.getPrices();
        String raw = foil ? prices.getUsdFoil() : prices.getUsd();
        if (raw == null || raw.isBlank()) return null;
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void persist(String rowRef,
                         String cardName,
                         String setCode,
                         String setName,
                         boolean foil,
                         int quantity,
                         String cardType,
                         String cardNumber,
                         BigDecimal price,
                         String comentario,
                         String language,
                         String localizacao,
                         ImportJob job) {
        if (cardName == null || cardName.isBlank()) {
            job.addError(rowRef + ": nome da carta vazio, não gravado no banco");
            return;
        }

        CollectionCard entity = new CollectionCard();
        entity.setCardName(cardName);
        entity.setSetCode(setCode);
        entity.setSetNameRaw(setName);
        entity.setFoil(foil);
        entity.setQuantity(Math.max(quantity, 1));
        entity.setCardType(cardType);
        entity.setCardNumber(cardNumber == null || cardNumber.isBlank() ? null : cardNumber);
        entity.setPrice(price);
        entity.setComentario(comentario);
        entity.setLanguage(language == null || language.isBlank() ? null : language);
        entity.setLocalizacao(localizacao);
        cardRepository.save(entity);
        job.incrementPersisted();
    }

    // ------------------------------------------------------------------ Excel helpers

    private String readString(Cell cell) {
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> stripTrailingZero(cell.getNumericCellValue());
            case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
            case FORMULA -> readFormulaString(cell);
            default -> null;
        };
    }

    private String readFormulaString(Cell cell) {
        return switch (cell.getCachedFormulaResultType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> stripTrailingZero(cell.getNumericCellValue());
            case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
            default -> null;
        };
    }

    private String stripTrailingZero(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) {
            return Long.toString((long) v);
        }
        return Double.toString(v);
    }

    private BigDecimal readBigDecimal(Cell cell) {
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

    private int readQuantity(Cell cell) {
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

    private boolean isFoilYes(String value) {
        if (value == null) return false;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("sim") || normalized.equals("s")
                || normalized.equals("yes") || normalized.equals("y")
                || normalized.equals("foil") || normalized.equals("true");
    }

    private void writeCellString(Row row, int columnIndex, String value) {
        Cell cell = row.getCell(columnIndex);
        if (cell == null) cell = row.createCell(columnIndex, CellType.STRING);
        if (value == null) {
            cell.setBlank();
        } else {
            cell.setCellValue(value);
        }
    }

    private void writeCellNumeric(Row row, int columnIndex, BigDecimal value) {
        Cell cell = row.getCell(columnIndex);
        if (cell == null) cell = row.createCell(columnIndex, CellType.NUMERIC);
        if (value == null) {
            cell.setBlank();
        } else {
            cell.setCellValue(value.doubleValue());
        }
    }

    private boolean equalsIgnoreCase(String actual, String expected) {
        return actual != null && actual.trim().equalsIgnoreCase(expected);
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private String deriveOutputFileName(String original) {
        if (original == null || original.isBlank()) return "colecao-importada.xlsx";
        int dot = original.lastIndexOf('.');
        String base = dot > 0 ? original.substring(0, dot) : original;
        return base + "-processado.xlsx";
    }

    private void throttle() {
        try {
            if (throttleMs > 0) Thread.sleep(throttleMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Appends a "Não encontradas" sheet to the output workbook summarising
     * every data row whose Scryfall lookup failed or whose set could not be
     * resolved in the local MagicSet table. The sheet is only created when
     * at least one unresolved row exists.
     */
    private void appendUnresolvedSheet(XSSFWorkbook workbook, List<UnresolvedRow> unresolved) {
        if (unresolved.isEmpty()) return;

        String name = "Não encontradas";
        int suffix = 1;
        while (workbook.getSheet(name) != null) {
            name = "Não encontradas (" + (++suffix) + ")";
        }
        Sheet sheet = workbook.createSheet(name);

        String[] headers = {"Aba", "Linha", "Number", "Card", "Set", "Foil", "Motivo"};
        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            headerRow.createCell(i, CellType.STRING).setCellValue(headers[i]);
        }

        int r = 1;
        for (UnresolvedRow u : unresolved) {
            Row row = sheet.createRow(r++);
            row.createCell(0, CellType.STRING).setCellValue(u.sheet());
            row.createCell(1, CellType.NUMERIC).setCellValue(u.row());
            row.createCell(2, CellType.STRING).setCellValue(u.number() == null ? "" : u.number());
            row.createCell(3, CellType.STRING).setCellValue(u.card() == null ? "" : u.card());
            row.createCell(4, CellType.STRING).setCellValue(u.set() == null ? "" : u.set());
            row.createCell(5, CellType.STRING).setCellValue(u.foil() == null ? "" : u.foil());
            row.createCell(6, CellType.STRING).setCellValue(u.reason() == null ? "" : u.reason());
        }

        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    private record UnresolvedRow(String sheet,
                                 int row,
                                 String number,
                                 String card,
                                 String set,
                                 String foil,
                                 String reason) {
    }
}
