package com.evaristof.mtgcollection.service;

import com.evaristof.mtgcollection.scryfall.dto.ScryfallPrices;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CardPriceResolverTest {

    private static ScryfallPrices prices(String usd, String usdFoil, String usdEtched, String eurFoil) {
        ScryfallPrices p = new ScryfallPrices();
        p.setUsd(usd);
        p.setUsdFoil(usdFoil);
        p.setUsdEtched(usdEtched);
        p.setEurFoil(eurFoil);
        return p;
    }

    // ------------------------------------------------------- cadeia do foil

    @Test
    void foilPrefersUsdFoil() {
        var resolved = CardPriceResolver.resolve(prices("1.00", "9.99", "7.00", "4.00"), true);

        assertThat(resolved.price()).isEqualByComparingTo("9.99");
        assertThat(resolved.currency()).isEqualTo("USD");
        assertThat(resolved.note()).isNull();
    }

    @Test
    void foilFallsBackToUsdEtchedBeforeTheEuroPrice() {
        var resolved = CardPriceResolver.resolve(prices("1.00", null, "7.00", "4.00"), true);

        assertThat(resolved.price()).isEqualByComparingTo("7.00");
        assertThat(resolved.currency()).isEqualTo("USD");
        assertThat(resolved.note()).isEqualTo("Carta Foil Etched");
    }

    @Test
    void foilFallsBackToEurFoilWhenThereIsNoDollarAtAll() {
        var resolved = CardPriceResolver.resolve(prices("1.00", null, null, "4.00"), true);

        assertThat(resolved.price()).isEqualByComparingTo("4.00");
        assertThat(resolved.currency()).isEqualTo("EUR");
        assertThat(resolved.note()).isEqualTo("Preço Foil em EUR");
    }

    @Test
    void foilWithNoPriceAtAllResolvesToNothing() {
        var resolved = CardPriceResolver.resolve(prices("1.00", null, null, null), true);

        assertThat(resolved.hasPrice()).isFalse();
        assertThat(resolved.note()).isNull();
    }

    @Test
    void nonFoilUsesOnlyTheRegularUsdPrice() {
        // Etched e euro existem só para cobrir buracos do foil.
        var resolved = CardPriceResolver.resolve(prices(null, "9.99", "7.00", "4.00"), false);

        assertThat(resolved.price()).isNull();
        assertThat(resolved.currency()).isEqualTo("USD");
        assertThat(resolved.note()).isNull();
    }

    @Test
    void handlesMissingOrGarbagePrices() {
        assertThat(CardPriceResolver.resolve((ScryfallPrices) null, true).price()).isNull();
        assertThat(CardPriceResolver.resolve(prices("", "  ", "", null), true).price()).isNull();
        assertThat(CardPriceResolver.resolve(prices("abc", null, "xyz", "?!"), true).price()).isNull();
    }

    // ------------------------------------------------ marcas no comentário

    private static CardPriceResolver.Resolved etched() {
        return CardPriceResolver.resolve(prices(null, null, "7.00", null), true);
    }

    private static CardPriceResolver.Resolved euro() {
        return CardPriceResolver.resolve(prices(null, null, null, "4.00"), true);
    }

    private static CardPriceResolver.Resolved plainUsd() {
        return CardPriceResolver.resolve(prices(null, "9.99", null, null), true);
    }

    @Test
    void noteIsAddedKeepingWhatTheUserWrote() {
        assertThat(CardPriceResolver.applyPriceNote(null, etched()))
                .isEqualTo("Carta Foil Etched");
        assertThat(CardPriceResolver.applyPriceNote("mint", euro()))
                .isEqualTo("mint · Preço Foil em EUR");
        // o flag que o import procura no começo do comentário continua no lugar
        assertThat(CardPriceResolver.applyPriceNote("*Conferir sempre Manualmente", etched()))
                .startsWith("*Conferir sempre Manualmente");
    }

    @Test
    void noteIsNotDuplicatedOnEverySync() {
        String once = CardPriceResolver.applyPriceNote("mint", etched());
        String twice = CardPriceResolver.applyPriceNote(once, etched());

        assertThat(twice).isEqualTo(once).isEqualTo("mint · Carta Foil Etched");
    }

    @Test
    void oneNoteReplacesTheOtherWhenThePriceSourceChanges() {
        String euroMarked = CardPriceResolver.applyPriceNote("mint", euro());

        assertThat(CardPriceResolver.applyPriceNote(euroMarked, etched()))
                .isEqualTo("mint · Carta Foil Etched");
        assertThat(CardPriceResolver.applyPriceNote(euroMarked, euro()))
                .isEqualTo("mint · Preço Foil em EUR");
    }

    @Test
    void noteIsRemovedWhenThePriceComesFromUsdFoilAgain() {
        assertThat(CardPriceResolver.applyPriceNote("mint · Preço Foil em EUR", plainUsd()))
                .isEqualTo("mint");
        assertThat(CardPriceResolver.applyPriceNote("Carta Foil Etched", plainUsd())).isNull();
        assertThat(CardPriceResolver.applyPriceNote("Preço Foil em EUR · mint", plainUsd()))
                .isEqualTo("mint");
    }

    @Test
    void commentsWithoutAnyNoteAreLeftAlone() {
        assertThat(CardPriceResolver.applyPriceNote("mint", plainUsd())).isEqualTo("mint");
        assertThat(CardPriceResolver.applyPriceNote("   ", plainUsd())).isNull();
        assertThat(CardPriceResolver.applyPriceNote(null, plainUsd())).isNull();
    }
}
