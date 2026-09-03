package com.evaristof.mtgcollection.service;

import com.evaristof.mtgcollection.domain.CollectionCard;
import com.evaristof.mtgcollection.domain.MagicSet;
import com.evaristof.mtgcollection.repository.CollectionCardRepository;
import com.evaristof.mtgcollection.repository.MagicSetRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compares a collection spreadsheet against what is actually registered in the
 * collection and reports the three kinds of difference the "Reconciliação
 * Coleção" screen shows: rows only in the sheet, cards only in the database,
 * and cards present in both (same location) with different quantities.
 *
 * <p>Read-only: nothing is written here. The screen applies each difference
 * one at a time through the existing cards API.</p>
 *
 * <h2>What counts as "the same card"</h2>
 * <p>set + card name + foil + language + location. The collector number is
 * deliberately <em>not</em> part of the key because the sheet's number column
 * is optional — including it would report the same card as missing on both
 * sides whenever the cell is empty. Rows that collapse onto the same key are
 * summed on both sides, so duplicates don't hide a difference.</p>
 *
 * <p>Language is compared through {@link LanguageNormalizer} ("en" matches
 * "English"), the set through its code (falling back to the sheet's set name
 * when it isn't in the local MAGIC_SET table), and the location cell is
 * expanded by {@link LocationNameParser}, so
 * {@code "Blue Pasta GameGenic (3) e Dragon Pasta Troca (5)"} is read as three
 * copies in one folder and five in the other.</p>
 *
 * <p>Only the locations named in the spreadsheet are considered on the
 * database side — a partial sheet (say, one binder) must not report the whole
 * rest of the collection as "sobrando".</p>
 */
@Service
public class CollectionReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(CollectionReconciliationService.class);

    private final CollectionCardRepository cardRepository;
    private final MagicSetRepository setRepository;

    public CollectionReconciliationService(CollectionCardRepository cardRepository,
                                           MagicSetRepository setRepository) {
        this.cardRepository = cardRepository;
        this.setRepository = setRepository;
    }

    // ------------------------------------------------------------------ API

    /** One difference, as rendered by a row of one of the three dashboards. */
    public record DiffRow(
            String cardName,
            /** Resolved set code; {@code null} when the sheet's set isn't in MAGIC_SET. */
            String setCode,
            /** Set label for the UI: the resolved name, or the raw sheet value. */
            String setName,
            /** Collector number, when either side has one (never part of the match). */
            String cardNumber,
            boolean foil,
            /** Canonical language (see {@link LanguageNormalizer}). */
            String language,
            /** Language exactly as stored in our row — what an update must send back. */
            String collectionLanguage,
            String location,
            int excelQuantity,
            int collectionQuantity,
            /** Our rows behind this line (more than one when the collection has duplicates). */
            List<Long> cardIds,
            /** Spreadsheet cells behind this line, e.g. {@code "Página1!12"}. */
            List<String> sheetRows) {
    }

    public record Summary(
            int sheetRows,
            int expandedRows,
            int matched,
            int onlyInExcel,
            int onlyInCollection,
            int quantityMismatch,
            int locations) {
    }

    public record ReconciliationResult(
            List<DiffRow> onlyInExcel,
            List<DiffRow> onlyInCollection,
            List<DiffRow> quantityMismatch,
            /** Sheet rows that could not be compared (no card name, unreadable). */
            List<String> ignored,
            Summary summary) {
    }

    @Transactional(readOnly = true)
    public ReconciliationResult reconcile(byte[] xlsxBytes) {
        Map<String, MagicSet> setsByName = indexSetsByName();
        Map<Key, Agg> excel = new LinkedHashMap<>();
        List<String> ignored = new ArrayList<>();
        int sheetRows = 0;
        int expandedRows = 0;

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsxBytes))) {
            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                Sheet sheet = workbook.getSheetAt(s);
                if (!CollectionSheetParser.hasExpectedHeader(sheet)) {
                    log.debug("Reconciliação: aba '{}' ignorada (cabeçalho diferente)", sheet.getSheetName());
                    continue;
                }
                for (int r = CollectionSheetParser.FIRST_DATA_ROW; r <= sheet.getLastRowNum(); r++) {
                    Row row = sheet.getRow(r);
                    if (row == null || !CollectionSheetParser.isDataRow(row)) continue;
                    sheetRows++;
                    String ref = sheet.getSheetName() + "!" + (r + 1);

                    String cardName = CollectionSheetParser.readString(row.getCell(CollectionSheetParser.COL_CARD));
                    if (CollectionSheetParser.isBlank(cardName)) {
                        ignored.add(ref + ": linha sem nome de carta");
                        continue;
                    }
                    String setNameRaw = CollectionSheetParser.readString(row.getCell(CollectionSheetParser.COL_SET));
                    MagicSet set = setsByName.get(CollectionSheetParser.normalize(setNameRaw));
                    boolean foil = CollectionSheetParser.isFoilYes(
                            CollectionSheetParser.readString(row.getCell(CollectionSheetParser.COL_FOIL)));
                    int quantity = CollectionSheetParser.readQuantity(row.getCell(CollectionSheetParser.COL_QTY));
                    String language = LanguageNormalizer.canonical(
                            CollectionSheetParser.readString(row.getCell(CollectionSheetParser.COL_LANG)));
                    String number = CollectionSheetParser.readString(row.getCell(CollectionSheetParser.COL_NUMBER));
                    String locationRaw = CollectionSheetParser.readString(row.getCell(CollectionSheetParser.COL_LOC));

                    // "A (3) e B (5)" vira duas entradas; qualquer outro valor
                    // é uma localização só, com a quantidade da coluna F.
                    List<LocationNameParser.Part> parts = LocationNameParser.split(locationRaw);
                    if (parts.isEmpty()) {
                        parts = List.of(new LocationNameParser.Part(
                                locationRaw == null ? "" : locationRaw.trim(), quantity));
                    }

                    for (LocationNameParser.Part part : parts) {
                        expandedRows++;
                        Key key = new Key(
                                setKey(set != null ? set.getSetCode() : null, setNameRaw),
                                CollectionSheetParser.normalize(cardName),
                                foil,
                                LanguageNormalizer.key(language),
                                CollectionSheetParser.normalize(part.location()));
                        Agg agg = excel.computeIfAbsent(key, k -> new Agg());
                        agg.cardName = firstNonBlank(agg.cardName, cardName.trim());
                        agg.setCode = agg.setCode != null ? agg.setCode
                                : (set != null ? set.getSetCode() : null);
                        agg.setName = firstNonBlank(agg.setName,
                                set != null ? set.getSetName() : setNameRaw);
                        agg.cardNumber = firstNonBlank(agg.cardNumber, number);
                        agg.foil = foil;
                        agg.language = language;
                        agg.location = firstNonBlank(agg.location, part.location());
                        agg.quantity += part.quantity();
                        agg.sheetRows.add(ref);
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Falha lendo planilha: " + e.getMessage(), e);
        }

        if (excel.isEmpty()) {
            throw new IllegalStateException(
                    "Nenhuma linha reconhecida na planilha. Confira se ela segue o mesmo layout "
                            + "do importador (cabeçalho na linha 3 com Number/Card/Set/Foil).");
        }

        // Só as localizações citadas na planilha entram na comparação, para uma
        // planilha parcial não listar o resto da coleção como sobra.
        Set<String> locationsInSheet = new java.util.HashSet<>();
        for (Key key : excel.keySet()) {
            locationsInSheet.add(key.locationKey());
        }

        Map<Key, Agg> mine = new LinkedHashMap<>();
        for (CollectionCard card : cardRepository.findAll()) {
            String locationKey = CollectionSheetParser.normalize(card.getLocalizacao());
            if (!locationsInSheet.contains(locationKey)) continue;

            Key key = new Key(
                    setKey(card.getSetCode(), card.getSetNameRaw()),
                    CollectionSheetParser.normalize(card.getCardName()),
                    card.isFoil(),
                    LanguageNormalizer.key(card.getLanguage()),
                    locationKey);
            Agg agg = mine.computeIfAbsent(key, k -> new Agg());
            agg.cardName = firstNonBlank(agg.cardName, card.getCardName());
            agg.setCode = agg.setCode != null ? agg.setCode : card.getSetCode();
            agg.setName = firstNonBlank(agg.setName,
                    card.getSetCode() != null ? card.getSetCode() : card.getSetNameRaw());
            agg.cardNumber = firstNonBlank(agg.cardNumber, card.getCardNumber());
            agg.foil = card.isFoil();
            agg.language = LanguageNormalizer.canonical(card.getLanguage());
            agg.collectionLanguage = firstNonBlank(agg.collectionLanguage, card.getLanguage());
            agg.location = firstNonBlank(agg.location, card.getLocalizacao());
            agg.quantity += card.getQuantity();
            agg.ids.add(card.getId());
        }

        List<DiffRow> onlyInExcel = new ArrayList<>();
        List<DiffRow> onlyInCollection = new ArrayList<>();
        List<DiffRow> mismatch = new ArrayList<>();
        int matched = 0;

        for (Map.Entry<Key, Agg> entry : excel.entrySet()) {
            Agg sheet = entry.getValue();
            Agg ours = mine.get(entry.getKey());
            if (ours == null) {
                onlyInExcel.add(toDiff(sheet, null));
            } else if (sheet.quantity != ours.quantity) {
                mismatch.add(toDiff(sheet, ours));
            } else {
                matched++;
            }
        }
        for (Map.Entry<Key, Agg> entry : mine.entrySet()) {
            if (!excel.containsKey(entry.getKey())) {
                onlyInCollection.add(toDiff(null, entry.getValue()));
            }
        }

        onlyInExcel.sort(DISPLAY_ORDER);
        onlyInCollection.sort(DISPLAY_ORDER);
        mismatch.sort(DISPLAY_ORDER);

        return new ReconciliationResult(
                onlyInExcel,
                onlyInCollection,
                mismatch,
                ignored,
                new Summary(sheetRows, expandedRows, matched, onlyInExcel.size(),
                        onlyInCollection.size(), mismatch.size(), locationsInSheet.size()));
    }

    // ------------------------------------------------------------------ internals

    private static final Comparator<DiffRow> DISPLAY_ORDER = Comparator
            .comparing((DiffRow d) -> d.cardName() == null ? "" : d.cardName(), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(d -> d.location() == null ? "" : d.location(), String.CASE_INSENSITIVE_ORDER);

    /** Match key: everything that makes two entries "the same card, same place". */
    private record Key(String setKey, String nameKey, boolean foil, String languageKey, String locationKey) {
    }

    /** Accumulates one key's quantity plus the fields shown on screen. */
    private static final class Agg {
        String cardName;
        String setCode;
        String setName;
        String cardNumber;
        boolean foil;
        String language;
        String collectionLanguage;
        String location;
        int quantity;
        final List<Long> ids = new ArrayList<>();
        final List<String> sheetRows = new ArrayList<>();
    }

    private static DiffRow toDiff(Agg sheet, Agg ours) {
        Agg any = sheet != null ? sheet : ours;
        return new DiffRow(
                any.cardName,
                firstNonBlank(sheet != null ? sheet.setCode : null, ours != null ? ours.setCode : null),
                firstNonBlank(sheet != null ? sheet.setName : null, ours != null ? ours.setName : null),
                firstNonBlank(sheet != null ? sheet.cardNumber : null, ours != null ? ours.cardNumber : null),
                any.foil,
                any.language,
                ours != null ? ours.collectionLanguage : null,
                firstNonBlank(sheet != null ? sheet.location : null, ours != null ? ours.location : null),
                sheet != null ? sheet.quantity : 0,
                ours != null ? ours.quantity : 0,
                ours != null ? List.copyOf(ours.ids) : List.of(),
                sheet != null ? List.copyOf(sheet.sheetRows) : List.of());
    }

    /**
     * Sets are matched by code; when the sheet names a set we don't have in
     * MAGIC_SET, the normalised name is used instead — so an unknown set still
     * matches our own rows that came from that same unknown set.
     */
    private static String setKey(String setCode, String setNameRaw) {
        if (setCode != null && !setCode.isBlank()) {
            return CollectionSheetParser.normalize(setCode);
        }
        return "?" + CollectionSheetParser.normalize(setNameRaw);
    }

    private static String firstNonBlank(String current, String candidate) {
        if (current != null && !current.isBlank()) return current;
        return candidate == null || candidate.isBlank() ? current : candidate.trim();
    }

    private Map<String, MagicSet> indexSetsByName() {
        List<MagicSet> all = setRepository.findAll();
        Map<String, MagicSet> byName = new HashMap<>(all.size() * 2);
        for (MagicSet set : all) {
            if (set.getSetName() != null) {
                byName.put(CollectionSheetParser.normalize(set.getSetName()), set);
            }
        }
        return byName;
    }
}
