package com.evaristof.mtgcollection.service;

import com.evaristof.mtgcollection.domain.MagicSet;
import com.evaristof.mtgcollection.repository.CollectionCardRepository;
import com.evaristof.mtgcollection.repository.MagicSetRepository;
import com.evaristof.mtgcollection.scryfall.dto.ScryfallCard;
import com.evaristof.mtgcollection.scryfall.dto.ScryfallCardIdentifier;
import com.evaristof.mtgcollection.scryfall.dto.ScryfallPrices;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Reproduces the bug reported by the user: after running the import, the
 * output {@code -processado.xlsx} does not contain the {@code Type} (E) and
 * {@code Price} (G) values returned by Scryfall.
 */
class CollectionImportServiceTest {

    private CardBatchLookupService batchLookup;
    private CollectionCardRepository cardRepository;
    private MagicSetRepository setRepository;
    private SetPersistenceService setPersistenceService;
    private CollectionImportService service;

    @BeforeEach
    void setUp() {
        batchLookup = mock(CardBatchLookupService.class);
        cardRepository = mock(CollectionCardRepository.class);
        setRepository = mock(MagicSetRepository.class);
        setPersistenceService = mock(SetPersistenceService.class);
        service = new CollectionImportService(batchLookup, cardRepository, setRepository,
                setPersistenceService, 0L);
    }

    @Test
    void rowWithExistingFormulasInTypeAndPriceCells_isOverwrittenByScryfallData() throws Exception {
        MagicSet uds = new MagicSet();
        uds.setSetCode("uds");
        uds.setSetName("Urza's Destiny");
        when(setRepository.findAll()).thenReturn(List.of(uds));

        ScryfallCard treachery = new ScryfallCard();
        treachery.setName("Treachery");
        treachery.setSet("uds");
        treachery.setCollectorNumber("39");
        treachery.setTypeLine("Sorcery");
        ScryfallPrices prices = new ScryfallPrices();
        prices.setUsdFoil("400.00");
        treachery.setPrices(prices);
        when(batchLookup.getCardsBatch(ArgumentMatchers.<List<ScryfallCardIdentifier>>any(),
                anyLong(), any()))
                .thenReturn(List.of(treachery));

        byte[] input = buildTemplateWithPreExistingFormulas();

        ImportJob job = new ImportJob("colecao.xlsx");
        service.runImport(job, input);

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(job.getResultBytes()))) {
            Sheet sheet = wb.getSheetAt(0);
            Row dataRow = sheet.getRow(3);

            Cell typeCell = dataRow.getCell(4);
            Cell priceCell = dataRow.getCell(6);

            assertThat(typeCell.getCellType())
                    .as("Type cell must NOT remain a formula cell after import")
                    .isNotEqualTo(CellType.FORMULA);
            assertThat(priceCell.getCellType())
                    .as("Price cell must NOT remain a formula cell after import")
                    .isNotEqualTo(CellType.FORMULA);

            assertThat(readString(typeCell)).isEqualTo("Sorcery");
            assertThat(readNumeric(priceCell)).isEqualTo(400.00);
        }
    }

    private byte[] buildTemplateWithPreExistingFormulas() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Colecao");
            Row header = sheet.createRow(2);
            header.createCell(0, CellType.STRING).setCellValue("Number (Optional)");
            header.createCell(1, CellType.STRING).setCellValue("Card");
            header.createCell(2, CellType.STRING).setCellValue("Set");
            header.createCell(3, CellType.STRING).setCellValue("Foil");
            header.createCell(4, CellType.STRING).setCellValue("Type");
            header.createCell(5, CellType.STRING).setCellValue("Quantity");
            header.createCell(6, CellType.STRING).setCellValue("Price");
            header.createCell(7, CellType.STRING).setCellValue("Total (Dolar)");
            header.createCell(8, CellType.STRING).setCellValue("Comentário");
            header.createCell(9, CellType.STRING).setCellValue("Language");
            header.createCell(10, CellType.STRING).setCellValue("Localização");

            Row data = sheet.createRow(3);
            data.createCell(1, CellType.STRING).setCellValue("Treachery");
            data.createCell(2, CellType.STRING).setCellValue("Urza's Destiny");
            data.createCell(3, CellType.STRING).setCellValue("Sim");
            // Simulates a legacy template that has formulas in E/G (e.g. pulling
            // from another sheet). The current import flow fails to overwrite
            // these, so the output keeps the stale formula value.
            data.createCell(4, CellType.FORMULA).setCellFormula("\"\"");
            data.createCell(5, CellType.NUMERIC).setCellValue(1);
            data.createCell(6, CellType.FORMULA).setCellFormula("0");
            data.createCell(7, CellType.FORMULA).setCellFormula("G4*F4");

            wb.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void treacheryFoilRow_receivesTypeLineAndFoilPriceInOutputWorkbook() throws Exception {
        MagicSet uds = new MagicSet();
        uds.setSetCode("uds");
        uds.setSetName("Urza's Destiny");
        when(setRepository.findAll()).thenReturn(List.of(uds));

        ScryfallCard treachery = new ScryfallCard();
        treachery.setName("Treachery");
        treachery.setSet("uds");
        treachery.setCollectorNumber("39");
        treachery.setTypeLine("Sorcery");
        ScryfallPrices prices = new ScryfallPrices();
        prices.setUsd("350.00");
        prices.setUsdFoil("400.00");
        treachery.setPrices(prices);

        when(batchLookup.getCardsBatch(ArgumentMatchers.<List<ScryfallCardIdentifier>>any(),
                anyLong(), any()))
                .thenReturn(List.of(treachery));

        byte[] input = buildTemplateWorkbook("Treachery", "Urza's Destiny", "Sim");

        ImportJob job = new ImportJob("colecao.xlsx");
        service.runImport(job, input);

        byte[] output = job.getResultBytes();
        assertThat(output)
                .as("runImport must produce an output workbook")
                .isNotNull();

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(output))) {
            Sheet sheet = wb.getSheetAt(0);
            Row dataRow = sheet.getRow(3); // row 4 in 1-based = first data row

            String type = readString(dataRow.getCell(4));
            double price = readNumeric(dataRow.getCell(6));

            assertThat(type)
                    .as("Column E (Type) should be populated from Scryfall type_line")
                    .isEqualTo("Sorcery");
            assertThat(price)
                    .as("Column G (Price) should be the USD foil price for foil rows")
                    .isEqualTo(400.00);
        }
    }

    @Test
    void doubleFacedCard_isResolvedByFrontFaceName() throws Exception {
        // Reproduces the bug where double-faced cards (e.g. "Jace, Vryn's Prodigy"
        // → "Jace, Telepath Unbound") always land in "Não encontradas" even
        // though Scryfall did return the card. Root cause: Scryfall responds
        // with the full canonical name "Front // Back", which doesn't match
        // the row's match key built from just the front-face name.
        MagicSet ori = new MagicSet();
        ori.setSetCode("ori");
        ori.setSetName("Magic Origins");
        when(setRepository.findAll()).thenReturn(List.of(ori));

        ScryfallCard jace = new ScryfallCard();
        jace.setName("Jace, Vryn's Prodigy // Jace, Telepath Unbound");
        jace.setSet("ori");
        jace.setCollectorNumber("60");
        jace.setTypeLine("Legendary Creature — Human Wizard");
        ScryfallPrices prices = new ScryfallPrices();
        prices.setUsd("5.00");
        jace.setPrices(prices);

        when(batchLookup.getCardsBatch(ArgumentMatchers.<List<ScryfallCardIdentifier>>any(),
                anyLong(), any()))
                .thenReturn(List.of(jace));

        byte[] input = buildTemplateWorkbook("Jace, Vryn's Prodigy", "Magic Origins", "Não");

        ImportJob job = new ImportJob("colecao.xlsx");
        service.runImport(job, input);

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(job.getResultBytes()))) {
            Sheet sheet = wb.getSheetAt(0);
            Row dataRow = sheet.getRow(3);

            assertThat(readString(dataRow.getCell(4)))
                    .as("DFC front-face lookup should resolve to the card's type_line")
                    .isEqualTo("Legendary Creature — Human Wizard");
            assertThat(readNumeric(dataRow.getCell(6)))
                    .as("DFC front-face lookup should resolve to the USD price")
                    .isEqualTo(5.00);
        }
    }

    private byte[] buildTemplateWorkbook(String cardName, String setName, String foil) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Colecao");

            Row header = sheet.createRow(2); // row 3 in 1-based
            header.createCell(0, CellType.STRING).setCellValue("Number (Optional)");
            header.createCell(1, CellType.STRING).setCellValue("Card");
            header.createCell(2, CellType.STRING).setCellValue("Set");
            header.createCell(3, CellType.STRING).setCellValue("Foil");
            header.createCell(4, CellType.STRING).setCellValue("Type");
            header.createCell(5, CellType.STRING).setCellValue("Quantity");
            header.createCell(6, CellType.STRING).setCellValue("Price");
            header.createCell(7, CellType.STRING).setCellValue("Total (Dolar)");
            header.createCell(8, CellType.STRING).setCellValue("Comentário");
            header.createCell(9, CellType.STRING).setCellValue("Language");
            header.createCell(10, CellType.STRING).setCellValue("Localização");

            Row data = sheet.createRow(3); // row 4 in 1-based
            // Intentionally leave A (number) blank — forces byNameAndSet lookup.
            data.createCell(1, CellType.STRING).setCellValue(cardName);
            data.createCell(2, CellType.STRING).setCellValue(setName);
            data.createCell(3, CellType.STRING).setCellValue(foil);
            data.createCell(5, CellType.NUMERIC).setCellValue(1);

            wb.write(out);
            return out.toByteArray();
        }
    }

    private static String readString(Cell cell) {
        if (cell == null) return null;
        if (cell.getCellType() == CellType.STRING) return cell.getStringCellValue();
        if (cell.getCellType() == CellType.NUMERIC) return Double.toString(cell.getNumericCellValue());
        return null;
    }

    private static double readNumeric(Cell cell) {
        if (cell == null) return Double.NaN;
        if (cell.getCellType() == CellType.NUMERIC) return cell.getNumericCellValue();
        if (cell.getCellType() == CellType.STRING) {
            try { return Double.parseDouble(cell.getStringCellValue()); } catch (Exception e) { return Double.NaN; }
        }
        return Double.NaN;
    }
}
