package com.evaristof.mtgcollection.service;

import com.evaristof.mtgcollection.scryfall.dto.ScryfallPrices;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CardPriceResolverTest {

    private static ScryfallPrices prices(String usd, String usdFoil, String eurFoil) {
        ScryfallPrices p = new ScryfallPrices();
        p.setUsd(usd);
        p.setUsdFoil(usdFoil);
        p.setEurFoil(eurFoil);
        return p;
    }

    @Test
    void foilPrefersUsdFoil() {
        var resolved = CardPriceResolver.resolve(prices("1.00", "9.99", "4.00"), true);

        assertThat(resolved.price()).isEqualByComparingTo("9.99");
        assertThat(resolved.currency()).isEqualTo("USD");
        assertThat(resolved.isEurFoilFallback()).isFalse();
    }

    @Test
    void foilFallsBackToEurFoilWhenUsdFoilIsMissing() {
        var resolved = CardPriceResolver.resolve(prices("1.00", null, "4.00"), true);

        assertThat(resolved.price()).isEqualByComparingTo("4.00");
        assertThat(resolved.currency()).isEqualTo("EUR");
        assertThat(resolved.isEurFoilFallback()).isTrue();
    }

    @Test
    void foilWithNoPriceAtAllResolvesToNothing() {
        var resolved = CardPriceResolver.resolve(prices("1.00", null, null), true);

        assertThat(resolved.price()).isNull();
        assertThat(resolved.isEurFoilFallback()).isFalse();
    }

    @Test
    void nonFoilNeverUsesTheEuroPrice() {
        // O buraco que motivou o fallback é só do foil; carta normal sem usd
        // continua sem preço.
        var resolved = CardPriceResolver.resolve(prices(null, "9.99", "4.00"), false);

        assertThat(resolved.price()).isNull();
        assertThat(resolved.currency()).isEqualTo("USD");
    }

    @Test
    void handlesMissingPricesObject() {
        assertThat(CardPriceResolver.resolve((ScryfallPrices) null, true).price()).isNull();
        assertThat(CardPriceResolver.resolve(prices("", "  ", null), true).price()).isNull();
        assertThat(CardPriceResolver.resolve(prices("abc", null, "xyz"), true).price()).isNull();
    }

    @Test
    void noteIsAddedKeepingWhatTheUserWrote() {
        assertThat(CardPriceResolver.applyEurFoilNote(null, true))
                .isEqualTo("Preço Foil em EUR");
        assertThat(CardPriceResolver.applyEurFoilNote("mint", true))
                .isEqualTo("mint · Preço Foil em EUR");
        // o flag que o import procura no começo do comentário continua no lugar
        assertThat(CardPriceResolver.applyEurFoilNote("*Conferir sempre Manualmente", true))
                .startsWith("*Conferir sempre Manualmente");
    }

    @Test
    void noteIsNotDuplicatedOnEverySync() {
        String once = CardPriceResolver.applyEurFoilNote("mint", true);
        String twice = CardPriceResolver.applyEurFoilNote(once, true);

        assertThat(twice).isEqualTo(once);
    }

    @Test
    void noteIsRemovedWhenThePriceIsNoLongerInEuro() {
        assertThat(CardPriceResolver.applyEurFoilNote("mint · Preço Foil em EUR", false))
                .isEqualTo("mint");
        assertThat(CardPriceResolver.applyEurFoilNote("Preço Foil em EUR", false)).isNull();
        assertThat(CardPriceResolver.applyEurFoilNote("Preço Foil em EUR · mint", false))
                .isEqualTo("mint");
    }

    @Test
    void commentsWithoutTheNoteAreLeftAlone() {
        assertThat(CardPriceResolver.applyEurFoilNote("mint", false)).isEqualTo("mint");
        assertThat(CardPriceResolver.applyEurFoilNote("   ", false)).isNull();
        assertThat(CardPriceResolver.applyEurFoilNote(null, false)).isNull();
    }
}
