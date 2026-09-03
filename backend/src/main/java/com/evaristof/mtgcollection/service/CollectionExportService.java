package com.evaristof.mtgcollection.service;

import com.evaristof.mtgcollection.domain.CollectionCard;
import com.evaristof.mtgcollection.domain.MagicSet;
import com.evaristof.mtgcollection.repository.CollectionCardRepository;
import com.evaristof.mtgcollection.repository.MagicSetRepository;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the whole collection as a spreadsheet in the very layout the importer
 * reads (see {@link CollectionSheetParser}), so an export can be edited and
 * fed straight back into "Importar coleção" or the reconciliation screen.
 *
 * <p>The set column carries the set <em>name</em> (what the importer resolves
 * against MAGIC_SET), foil is written as "Sim"/"Não", and column H keeps the
 * {@code price * quantity} formula the original template has.</p>
 */
@Service
public class CollectionExportService {

    private static final String SHEET_NAME = "Coleção";

    private final CollectionCardRepository cardRepository;
    private final MagicSetRepository setRepository;

    public CollectionExportService(CollectionCardRepository cardRepository,
                                   MagicSetRepository setRepository) {
        this.cardRepository = cardRepository;
        this.setRepository = setRepository;
    }

    @Transactional(readOnly = true)
    public byte[] exportCollection() {
        Map<String, MagicSet> setsByCode = indexSetsByCode();
        List<CollectionCard> cards = cardRepository.findAll().stream()
                .sorted(Comparator
                        .comparing((CollectionCard c) -> setLabel(c, setsByCode), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(c -> nullToEmpty(c.getCardName()), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(c -> nullToEmpty(c.getLocalizacao()), String.CASE_INSENSITIVE_ORDER))
                .toList();

        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(SHEET_NAME);

            CellStyle headerStyle = workbook.createCellStyle();
            Font bold = workbook.createFont();
            bold.setBold(true);
            headerStyle.setFont(bold);

            Row header = sheet.createRow(CollectionSheetParser.HEADER_ROW_INDEX);
            writeHeader(header, headerStyle);

            int rowIndex = CollectionSheetParser.FIRST_DATA_ROW;
            for (CollectionCard card : cards) {
                Row row = sheet.createRow(rowIndex);
                int excelRow = rowIndex + 1; // 1-based, para a fórmula da coluna H

                setString(row, CollectionSheetParser.COL_NUMBER, card.getCardNumber());
                setString(row, CollectionSheetParser.COL_CARD, card.getCardName());
                setString(row, CollectionSheetParser.COL_SET, setLabel(card, setsByCode));
                setString(row, CollectionSheetParser.COL_FOIL, card.isFoil() ? "Sim" : "Não");
                setString(row, CollectionSheetParser.COL_TYPE, card.getCardType());
                row.createCell(CollectionSheetParser.COL_QTY, CellType.NUMERIC)
                        .setCellValue(card.getQuantity());
                if (card.getPrice() != null) {
                    row.createCell(CollectionSheetParser.COL_PRICE, CellType.NUMERIC)
                            .setCellValue(card.getPrice().doubleValue());
                }
                row.createCell(CollectionSheetParser.COL_TOTAL, CellType.FORMULA)
                        .setCellFormula("G" + excelRow + "*F" + excelRow);
                setString(row, CollectionSheetParser.COL_COMMENT, card.getComentario());
                setString(row, CollectionSheetParser.COL_LANG, card.getLanguage());
                setString(row, CollectionSheetParser.COL_LOC, card.getLocalizacao());

                rowIndex++;
            }

            applyColumnWidths(sheet);

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Falha gerando a planilha da coleção: " + e.getMessage(), e);
        }
    }

    private static void writeHeader(Row header, CellStyle style) {
        String[][] labels = {
                {String.valueOf(CollectionSheetParser.COL_NUMBER), CollectionSheetParser.HEADER_NUMBER},
                {String.valueOf(CollectionSheetParser.COL_CARD), CollectionSheetParser.HEADER_CARD},
                {String.valueOf(CollectionSheetParser.COL_SET), CollectionSheetParser.HEADER_SET},
                {String.valueOf(CollectionSheetParser.COL_FOIL), CollectionSheetParser.HEADER_FOIL},
                {String.valueOf(CollectionSheetParser.COL_TYPE), CollectionSheetParser.HEADER_TYPE},
                {String.valueOf(CollectionSheetParser.COL_QTY), CollectionSheetParser.HEADER_QTY},
                {String.valueOf(CollectionSheetParser.COL_PRICE), CollectionSheetParser.HEADER_PRICE},
                {String.valueOf(CollectionSheetParser.COL_TOTAL), CollectionSheetParser.HEADER_TOTAL},
                {String.valueOf(CollectionSheetParser.COL_COMMENT), CollectionSheetParser.HEADER_COMMENT},
                {String.valueOf(CollectionSheetParser.COL_LANG), CollectionSheetParser.HEADER_LANG},
                {String.valueOf(CollectionSheetParser.COL_LOC), CollectionSheetParser.HEADER_LOC},
        };
        for (String[] entry : labels) {
            Cell cell = header.createCell(Integer.parseInt(entry[0]), CellType.STRING);
            cell.setCellValue(entry[1]);
            cell.setCellStyle(style);
        }
    }

    private static void applyColumnWidths(Sheet sheet) {
        // Larguras fixas: autoSizeColumn percorreria todas as linhas de uma
        // coleção inteira só para calcular a largura.
        sheet.setColumnWidth(CollectionSheetParser.COL_NUMBER, 14 * 256);
        sheet.setColumnWidth(CollectionSheetParser.COL_CARD, 34 * 256);
        sheet.setColumnWidth(CollectionSheetParser.COL_SET, 30 * 256);
        sheet.setColumnWidth(CollectionSheetParser.COL_FOIL, 8 * 256);
        sheet.setColumnWidth(CollectionSheetParser.COL_TYPE, 28 * 256);
        sheet.setColumnWidth(CollectionSheetParser.COL_QTY, 10 * 256);
        sheet.setColumnWidth(CollectionSheetParser.COL_PRICE, 12 * 256);
        sheet.setColumnWidth(CollectionSheetParser.COL_TOTAL, 14 * 256);
        sheet.setColumnWidth(CollectionSheetParser.COL_COMMENT, 30 * 256);
        sheet.setColumnWidth(CollectionSheetParser.COL_LANG, 14 * 256);
        sheet.setColumnWidth(CollectionSheetParser.COL_LOC, 28 * 256);
    }

    /** Set name, falling back to the raw imported name and then to the code. */
    private static String setLabel(CollectionCard card, Map<String, MagicSet> setsByCode) {
        MagicSet set = setsByCode.get(CollectionSheetParser.normalize(card.getSetCode()));
        if (set != null && set.getSetName() != null && !set.getSetName().isBlank()) {
            return set.getSetName();
        }
        if (card.getSetNameRaw() != null && !card.getSetNameRaw().isBlank()) {
            return card.getSetNameRaw();
        }
        return nullToEmpty(card.getSetCode());
    }

    private Map<String, MagicSet> indexSetsByCode() {
        List<MagicSet> all = setRepository.findAll();
        Map<String, MagicSet> byCode = new HashMap<>(all.size() * 2);
        for (MagicSet set : all) {
            if (set.getSetCode() != null && !set.getSetCode().isBlank()) {
                byCode.put(CollectionSheetParser.normalize(set.getSetCode()), set);
            }
        }
        return byCode;
    }

    private static void setString(Row row, int column, String value) {
        if (value == null || value.isBlank()) return;
        row.createCell(column, CellType.STRING).setCellValue(value);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
