package com.evaristof.mtgcollection.service;

import com.evaristof.mtgcollection.domain.CollectionCard;
import com.evaristof.mtgcollection.domain.Location;
import com.evaristof.mtgcollection.domain.MagicSet;
import com.evaristof.mtgcollection.repository.CollectionCardRepository;
import com.evaristof.mtgcollection.repository.MagicSetRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CollectionExportServiceTest {

    private CollectionCardRepository cardRepository;
    private MagicSetRepository setRepository;
    private CollectionExportService service;

    @BeforeEach
    void setUp() {
        cardRepository = mock(CollectionCardRepository.class);
        setRepository = mock(MagicSetRepository.class);
        service = new CollectionExportService(cardRepository, setRepository);
    }

    private static MagicSet set(String code, String name) {
        MagicSet s = new MagicSet();
        s.setSetCode(code);
        s.setSetName(name);
        return s;
    }

    private static Location location(long id, String name) {
        Location l = new Location(name, null);
        l.setId(id);
        return l;
    }

    private static CollectionCard card(long id, String name, String setCode, String number,
                                       boolean foil, String language, int quantity,
                                       BigDecimal price, Location location) {
        CollectionCard c = new CollectionCard();
        c.setId(id);
        c.setCardName(name);
        c.setSetCode(setCode);
        c.setCardNumber(number);
        c.setFoil(foil);
        c.setLanguage(language);
        c.setQuantity(quantity);
        c.setPrice(price);
        c.setLocation(location);
        return c;
    }

    @Test
    void writesTheImporterLayoutWithSetNamesAndFoilAsSimNao() throws Exception {
        Location blue = location(1L, "Blue Pasta GameGenic");
        when(setRepository.findAll()).thenReturn(List.of(set("ons", "Onslaught")));
        when(cardRepository.findAll()).thenReturn(List.of(
                card(10L, "Complicate", "ons", "62", true, "English", 3,
                        new BigDecimal("1.25"), blue)));

        byte[] xlsx = service.exportCollection();

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(CollectionSheetParser.hasExpectedHeader(sheet)).isTrue();

            Row row = sheet.getRow(CollectionSheetParser.FIRST_DATA_ROW);
            assertThat(CollectionSheetParser.readString(row.getCell(CollectionSheetParser.COL_NUMBER)))
                    .isEqualTo("62");
            assertThat(CollectionSheetParser.readString(row.getCell(CollectionSheetParser.COL_CARD)))
                    .isEqualTo("Complicate");
            // set vai pelo NOME, que é o que o importador resolve
            assertThat(CollectionSheetParser.readString(row.getCell(CollectionSheetParser.COL_SET)))
                    .isEqualTo("Onslaught");
            assertThat(CollectionSheetParser.readString(row.getCell(CollectionSheetParser.COL_FOIL)))
                    .isEqualTo("Sim");
            assertThat(CollectionSheetParser.readQuantity(row.getCell(CollectionSheetParser.COL_QTY)))
                    .isEqualTo(3);
            assertThat(CollectionSheetParser.readBigDecimal(row.getCell(CollectionSheetParser.COL_PRICE)))
                    .isEqualByComparingTo("1.25");
            assertThat(CollectionSheetParser.readString(row.getCell(CollectionSheetParser.COL_LANG)))
                    .isEqualTo("English");
            assertThat(CollectionSheetParser.readString(row.getCell(CollectionSheetParser.COL_LOC)))
                    .isEqualTo("Blue Pasta GameGenic");
            // coluna H mantém a fórmula preço * quantidade do template
            assertThat(row.getCell(CollectionSheetParser.COL_TOTAL).getCellFormula())
                    .isEqualTo("G4*F4");
        }
    }

    @Test
    void exportedSheetReconcilesWithZeroDifferences() {
        // Round-trip: o que exportamos tem que ser lido de volta como
        // exatamente a mesma coleção — nenhuma diferença nos três painéis.
        Location blue = location(1L, "Blue Pasta GameGenic");
        Location dragon = location(2L, "Dragon Pasta Troca");
        List<MagicSet> sets = List.of(set("ons", "Onslaught"), set("cmr", "Commander Legends"));
        List<CollectionCard> cards = List.of(
                card(10L, "Complicate", "ons", "62", false, "English", 2, new BigDecimal("1.25"), blue),
                card(11L, "Sol Ring", "cmr", "1", false, "Portuguese", 5, new BigDecimal("2.00"), dragon),
                card(12L, "Counterspell", "ons", null, true, "English", 1, null, blue));
        when(setRepository.findAll()).thenReturn(sets);
        when(cardRepository.findAll()).thenReturn(cards);

        byte[] xlsx = service.exportCollection();

        CollectionReconciliationService reconciliation =
                new CollectionReconciliationService(cardRepository, setRepository);
        var result = reconciliation.reconcile(xlsx);

        assertThat(result.onlyInExcel()).isEmpty();
        assertThat(result.onlyInCollection()).isEmpty();
        assertThat(result.quantityMismatch()).isEmpty();
        assertThat(result.summary().matched()).isEqualTo(3);
    }

    @Test
    void cardsWithoutSetOrOptionalFieldsStillExport() throws Exception {
        when(setRepository.findAll()).thenReturn(List.of());
        CollectionCard semSet = card(20L, "Carta Solta", null, null, false, null, 1, null, null);
        semSet.setSetNameRaw("Set Desconhecido");
        when(cardRepository.findAll()).thenReturn(List.of(semSet));

        byte[] xlsx = service.exportCollection();

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Row row = wb.getSheetAt(0).getRow(CollectionSheetParser.FIRST_DATA_ROW);
            assertThat(CollectionSheetParser.readString(row.getCell(CollectionSheetParser.COL_SET)))
                    .isEqualTo("Set Desconhecido");
            assertThat(CollectionSheetParser.readString(row.getCell(CollectionSheetParser.COL_FOIL)))
                    .isEqualTo("Não");
            assertThat(row.getCell(CollectionSheetParser.COL_PRICE)).isNull();
            assertThat(row.getCell(CollectionSheetParser.COL_LANG)).isNull();
        }
    }
}
