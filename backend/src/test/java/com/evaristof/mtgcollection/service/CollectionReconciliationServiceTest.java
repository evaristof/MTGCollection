package com.evaristof.mtgcollection.service;

import com.evaristof.mtgcollection.domain.CollectionCard;
import com.evaristof.mtgcollection.domain.Location;
import com.evaristof.mtgcollection.domain.MagicSet;
import com.evaristof.mtgcollection.repository.CollectionCardRepository;
import com.evaristof.mtgcollection.repository.MagicSetRepository;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CollectionReconciliationServiceTest {

    private CollectionCardRepository cardRepository;
    private MagicSetRepository setRepository;
    private CollectionReconciliationService service;

    @BeforeEach
    void setUp() {
        cardRepository = mock(CollectionCardRepository.class);
        setRepository = mock(MagicSetRepository.class);
        service = new CollectionReconciliationService(cardRepository, setRepository);
    }

    // ------------------------------------------------------------ fixtures

    /** One spreadsheet row: card, set, foil, quantity, language, location. */
    private record SheetRow(String card, String set, String foil, int quantity,
                            String language, String location) {
    }

    private static byte[] workbook(List<SheetRow> rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Blue");
            Row header = sheet.createRow(2);
            String[] labels = {"Number (Optional)", "Card", "Set", "Foil", "Type", "Quantity",
                    "Price", "Total (Dolar)", "Comentário", "Language", "Localização"};
            for (int i = 0; i < labels.length; i++) {
                header.createCell(i, CellType.STRING).setCellValue(labels[i]);
            }
            int r = 3;
            for (SheetRow row : rows) {
                Row data = sheet.createRow(r++);
                data.createCell(1, CellType.STRING).setCellValue(row.card());
                data.createCell(2, CellType.STRING).setCellValue(row.set());
                data.createCell(3, CellType.STRING).setCellValue(row.foil());
                data.createCell(5, CellType.NUMERIC).setCellValue(row.quantity());
                data.createCell(9, CellType.STRING).setCellValue(row.language());
                data.createCell(10, CellType.STRING).setCellValue(row.location());
            }
            wb.write(out);
            return out.toByteArray();
        }
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

    private static CollectionCard card(long id, String name, String setCode, boolean foil,
                                       String language, int quantity, Location location) {
        CollectionCard c = new CollectionCard();
        c.setId(id);
        c.setCardName(name);
        c.setSetCode(setCode);
        c.setFoil(foil);
        c.setLanguage(language);
        c.setQuantity(quantity);
        c.setLocation(location);
        return c;
    }

    // ------------------------------------------------------------ tests

    @Test
    void reportsTheThreeKindsOfDifference() throws Exception {
        Location blue = location(1L, "Blue Pasta GameGenic");
        when(setRepository.findAll()).thenReturn(List.of(
                set("mmq", "Mercadian Masques"),
                set("ons", "Onslaught"),
                set("zen", "Zendikar")));
        when(cardRepository.findAll()).thenReturn(List.of(
                // igual à planilha → não entra em nenhum dash
                card(10L, "Bribery", "mmq", false, "English", 1, blue),
                // quantidade diferente (planilha tem 2)
                card(11L, "Complicate", "ons", false, "English", 1, blue),
                // não está na planilha → sobra na base
                card(12L, "Counterspell", "ons", false, "English", 4, blue)));

        byte[] xlsx = workbook(List.of(
                new SheetRow("Bribery", "Mercadian Masques", "Não", 1, "English", "Blue Pasta GameGenic"),
                new SheetRow("Complicate", "Onslaught", "Não", 2, "English", "Blue Pasta GameGenic"),
                new SheetRow("Bloodchief Ascension", "Zendikar", "Sim", 2, "English", "Blue Pasta GameGenic")));

        var result = service.reconcile(xlsx);

        assertThat(result.onlyInExcel()).singleElement().satisfies(d -> {
            assertThat(d.cardName()).isEqualTo("Bloodchief Ascension");
            assertThat(d.setCode()).isEqualTo("zen");
            assertThat(d.foil()).isTrue();
            assertThat(d.excelQuantity()).isEqualTo(2);
            assertThat(d.collectionQuantity()).isZero();
            assertThat(d.cardIds()).isEmpty();
        });
        assertThat(result.onlyInCollection()).singleElement().satisfies(d -> {
            assertThat(d.cardName()).isEqualTo("Counterspell");
            assertThat(d.collectionQuantity()).isEqualTo(4);
            assertThat(d.cardIds()).containsExactly(12L);
            // A tela mostra "nome - sigla": as linhas que vêm só da base
            // também precisam trazer o NOME do set, não apenas o código.
            assertThat(d.setName()).isEqualTo("Onslaught");
            assertThat(d.setCode()).isEqualTo("ons");
        });
        assertThat(result.quantityMismatch()).singleElement().satisfies(d -> {
            assertThat(d.cardName()).isEqualTo("Complicate");
            assertThat(d.excelQuantity()).isEqualTo(2);
            assertThat(d.collectionQuantity()).isEqualTo(1);
            assertThat(d.cardIds()).containsExactly(11L);
        });
        assertThat(result.summary().matched()).isEqualTo(1);
        assertThat(result.summary().sheetRows()).isEqualTo(3);
    }

    @Test
    void expandsCompoundLocationsIntoOneEntryPerFolder() throws Exception {
        Location blue = location(1L, "Blue Pasta GameGenic");
        when(setRepository.findAll()).thenReturn(List.of(set("cmr", "Commander Legends")));
        when(cardRepository.findAll()).thenReturn(List.of(
                // as 3 cópias da primeira pasta já estão cadastradas
                card(20L, "Sol Ring", "cmr", false, "English", 3, blue)));

        byte[] xlsx = workbook(List.of(new SheetRow(
                "Sol Ring", "Commander Legends", "Não", 8, "English",
                "Blue Pasta GameGenic (3) e Dragon Pasta Troca (5)")));

        var result = service.reconcile(xlsx);

        // A linha virou duas entradas: a de "Blue" bate, a de "Dragon" falta.
        assertThat(result.summary().sheetRows()).isEqualTo(1);
        assertThat(result.summary().expandedRows()).isEqualTo(2);
        assertThat(result.summary().matched()).isEqualTo(1);
        assertThat(result.onlyInExcel()).singleElement().satisfies(d -> {
            assertThat(d.location()).isEqualTo("Dragon Pasta Troca");
            assertThat(d.excelQuantity()).isEqualTo(5);
        });
        assertThat(result.quantityMismatch()).isEmpty();
    }

    @Test
    void matchesLanguagesWrittenDifferently() throws Exception {
        Location blue = location(1L, "Blue Pasta GameGenic");
        when(setRepository.findAll()).thenReturn(List.of(set("ons", "Onslaught")));
        when(cardRepository.findAll()).thenReturn(List.of(
                card(30L, "Complicate", "ons", false, "en", 1, blue)));

        byte[] xlsx = workbook(List.of(new SheetRow(
                "Complicate", "Onslaught", "Não", 1, "English", "Blue Pasta GameGenic")));

        var result = service.reconcile(xlsx);

        assertThat(result.summary().matched()).isEqualTo(1);
        assertThat(result.onlyInExcel()).isEmpty();
        assertThat(result.onlyInCollection()).isEmpty();
    }

    @Test
    void ignoresCollectionCardsFromLocationsTheSheetDoesNotMention() throws Exception {
        Location blue = location(1L, "Blue Pasta GameGenic");
        Location outra = location(2L, "Caixa Fora do Escopo");
        when(setRepository.findAll()).thenReturn(List.of(set("ons", "Onslaught")));
        when(cardRepository.findAll()).thenReturn(List.of(
                card(40L, "Complicate", "ons", false, "English", 1, blue),
                card(41L, "Shock", "ons", false, "English", 9, outra)));

        byte[] xlsx = workbook(List.of(new SheetRow(
                "Complicate", "Onslaught", "Não", 1, "English", "Blue Pasta GameGenic")));

        var result = service.reconcile(xlsx);

        assertThat(result.onlyInCollection()).isEmpty();
        assertThat(result.summary().locations()).isEqualTo(1);
    }

    @Test
    void sumsRepeatedRowsOfTheSameCardOnBothSides() throws Exception {
        Location blue = location(1L, "Blue Pasta GameGenic");
        when(setRepository.findAll()).thenReturn(List.of(set("ons", "Onslaught")));
        when(cardRepository.findAll()).thenReturn(List.of(
                card(50L, "Complicate", "ons", false, "English", 1, blue),
                card(51L, "Complicate", "ons", false, "English", 2, blue)));

        byte[] xlsx = workbook(List.of(
                new SheetRow("Complicate", "Onslaught", "Não", 2, "English", "Blue Pasta GameGenic"),
                new SheetRow("Complicate", "Onslaught", "Não", 2, "English", "Blue Pasta GameGenic")));

        var result = service.reconcile(xlsx);

        // planilha 2+2=4, base 1+2=3 → uma diferença só, apontando as duas linhas
        assertThat(result.quantityMismatch()).singleElement().satisfies(d -> {
            assertThat(d.excelQuantity()).isEqualTo(4);
            assertThat(d.collectionQuantity()).isEqualTo(3);
            assertThat(d.cardIds()).containsExactly(50L, 51L);
            assertThat(d.sheetRows()).hasSize(2);
        });
    }

    @Test
    void unresolvedSetStillMatchesOurOwnRowsFromThatSet() throws Exception {
        Location blue = location(1L, "Blue Pasta GameGenic");
        when(setRepository.findAll()).thenReturn(List.of());
        CollectionCard raw = card(60L, "Carta Estranha", null, false, "English", 1, blue);
        raw.setSetNameRaw("Set Desconhecido");
        when(cardRepository.findAll()).thenReturn(List.of(raw));

        byte[] xlsx = workbook(List.of(new SheetRow(
                "Carta Estranha", "Set Desconhecido", "Não", 3, "English", "Blue Pasta GameGenic")));

        var result = service.reconcile(xlsx);

        assertThat(result.quantityMismatch()).singleElement().satisfies(d -> {
            assertThat(d.setCode()).isNull();   // a tela desabilita "Cadastrar"
            assertThat(d.excelQuantity()).isEqualTo(3);
            assertThat(d.collectionQuantity()).isEqualTo(1);
        });
    }

    @Test
    void rejectsSpreadsheetWithoutRecognisedRows() throws Exception {
        when(setRepository.findAll()).thenReturn(List.of());
        byte[] xlsx = workbook(new ArrayList<>());

        assertThatThrownBy(() -> service.reconcile(xlsx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Nenhuma linha reconhecida");
    }
}
