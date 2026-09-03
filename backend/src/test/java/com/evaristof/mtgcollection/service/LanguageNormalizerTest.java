package com.evaristof.mtgcollection.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LanguageNormalizerTest {

    @Test
    void mapsCodesAndSpellingsOntoTheCanonicalName() {
        assertThat(LanguageNormalizer.canonical("en")).isEqualTo("English");
        assertThat(LanguageNormalizer.canonical(" English ")).isEqualTo("English");
        assertThat(LanguageNormalizer.canonical("pt")).isEqualTo("Portuguese");
        assertThat(LanguageNormalizer.canonical("Português")).isEqualTo("Portuguese");
        assertThat(LanguageNormalizer.canonical("Espanhol")).isEqualTo("Spanish");
        // grafia errada existente na planilha antiga
        assertThat(LanguageNormalizer.canonical("Chinise")).isEqualTo("Chinese");
    }

    @Test
    void unknownLanguagesAreKeptAsTyped() {
        assertThat(LanguageNormalizer.canonical(" Klingon ")).isEqualTo("Klingon");
    }

    @Test
    void blankBecomesEmpty() {
        assertThat(LanguageNormalizer.canonical(null)).isEmpty();
        assertThat(LanguageNormalizer.canonical("   ")).isEmpty();
    }

    @Test
    void keyIsCaseInsensitive() {
        assertThat(LanguageNormalizer.key("EN")).isEqualTo(LanguageNormalizer.key("english"));
        assertThat(LanguageNormalizer.key("pt")).isEqualTo(LanguageNormalizer.key("Portuguese"));
    }
}
