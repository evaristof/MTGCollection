package com.evaristof.mtgcollection.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LocationNameParserTest {

    @Test
    void splitsTwoFoldersWithQuantities() {
        List<LocationNameParser.Part> parts =
                LocationNameParser.split("Blue Pasta GameGenic (3) e Dragon Pasta Troca (5)");

        assertThat(parts).containsExactly(
                new LocationNameParser.Part("Blue Pasta GameGenic", 3),
                new LocationNameParser.Part("Dragon Pasta Troca", 5));
    }

    @Test
    void toleratesMissingSpaceBeforeParenthesisAndUppercaseE() {
        assertThat(LocationNameParser.split("Blue Pasta GameGenic (2) E Dragon Pasta Troca(1)"))
                .containsExactly(
                        new LocationNameParser.Part("Blue Pasta GameGenic", 2),
                        new LocationNameParser.Part("Dragon Pasta Troca", 1));
    }

    @Test
    void splitsMoreThanTwoFolders() {
        assertThat(LocationNameParser.split("A (1) e B (2) e C (3)"))
                .containsExactly(
                        new LocationNameParser.Part("A", 1),
                        new LocationNameParser.Part("B", 2),
                        new LocationNameParser.Part("C", 3));
    }

    @Test
    void keepsFolderNamesThatContainTheWordE() {
        assertThat(LocationNameParser.split("Pasta Escura e Clara (2) e Caixa 7 (1)"))
                .containsExactly(
                        new LocationNameParser.Part("Pasta Escura e Clara", 2),
                        new LocationNameParser.Part("Caixa 7", 1));
    }

    @Test
    void plainLocationsAreNotSplit() {
        // Um único "nome (n)" não é notação de duas pastas — senão uma
        // localização chamada "Deck Modern (2023)" viraria 2023 cópias.
        assertThat(LocationNameParser.split("Caixa 3")).isEmpty();
        assertThat(LocationNameParser.split("Deck Modern (2023)")).isEmpty();
        assertThat(LocationNameParser.split("Caixa (grande)")).isEmpty();
        assertThat(LocationNameParser.split("   ")).isEmpty();
        assertThat(LocationNameParser.split(null)).isEmpty();
    }
}
