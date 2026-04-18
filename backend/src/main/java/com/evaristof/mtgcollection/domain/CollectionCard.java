package com.evaristof.mtgcollection.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A single card owned in the user's Magic: The Gathering collection.
 *
 * <p>Persisted via JPA on the {@code COLLECTION_CARD} table. Each row
 * represents a stack of identical copies (same set + number + foil +
 * language) — the {@code QUANTITY} column captures how many the user
 * owns.</p>
 */
@Entity
@Table(name = "COLLECTION_CARD")
public class CollectionCard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID")
    private Long id;

    @Column(name = "CARD_NUMBER", length = 32)
    private String cardNumber;

    @Column(name = "CARD_NAME", nullable = false)
    private String cardName;

    @Column(name = "SET_CODE", length = 16)
    private String setCode;

    /**
     * Original set name from the imported spreadsheet when {@link #setCode}
     * could not be resolved (e.g. sets not yet synced from Scryfall).
     */
    @Column(name = "SET_NAME_RAW", length = 255)
    private String setNameRaw;

    @Column(name = "FOIL", nullable = false)
    private boolean foil;

    @Column(name = "CARD_TYPE")
    private String cardType;

    @Column(name = "LANGUAGE", length = 32)
    private String language;

    @Column(name = "QUANTITY", nullable = false)
    private int quantity;

    @Column(name = "PRICE", precision = 12, scale = 2)
    private BigDecimal price;

    @Column(name = "COMENTARIO", length = 1024)
    private String comentario;

    @Column(name = "LOCALIZACAO", length = 255)
    private String localizacao;

    public CollectionCard() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCardNumber() {
        return cardNumber;
    }

    public void setCardNumber(String cardNumber) {
        this.cardNumber = cardNumber;
    }

    public String getCardName() {
        return cardName;
    }

    public void setCardName(String cardName) {
        this.cardName = cardName;
    }

    public String getSetCode() {
        return setCode;
    }

    public void setSetCode(String setCode) {
        this.setCode = setCode;
    }

    public boolean isFoil() {
        return foil;
    }

    public void setFoil(boolean foil) {
        this.foil = foil;
    }

    public String getCardType() {
        return cardType;
    }

    public void setCardType(String cardType) {
        this.cardType = cardType;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public String getComentario() {
        return comentario;
    }

    public void setComentario(String comentario) {
        this.comentario = comentario;
    }

    public String getLocalizacao() {
        return localizacao;
    }

    public void setLocalizacao(String localizacao) {
        this.localizacao = localizacao;
    }

    public String getSetNameRaw() {
        return setNameRaw;
    }

    public void setSetNameRaw(String setNameRaw) {
        this.setNameRaw = setNameRaw;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CollectionCard that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
