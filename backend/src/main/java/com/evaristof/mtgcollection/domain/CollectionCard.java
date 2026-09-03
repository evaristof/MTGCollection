package com.evaristof.mtgcollection.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A single card owned in the user's Magic: The Gathering collection.
 *
 * <p>Persisted via JPA on the {@code COLLECTION_CARD} table. Each row
 * represents a stack of identical copies (same set + number + foil +
 * language + location) — the {@code QUANTITY} column captures how many the
 * user owns.</p>
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

    /**
     * Physical storage location — a FK to the {@code LOCATION} catalog.
     *
     * <p>This used to be a free-text {@code LOCALIZACAO} column; it now
     * points at {@link Location} so the same box/binder is a single managed
     * row instead of a string retyped (and mistyped) per card. See
     * {@code V4__migrate_localizacao_to_location_fk.sql} for the data
     * migration.</p>
     *
     * <p>Fetched eagerly on purpose: the location name is part of every JSON
     * response ({@link #getLocalizacao()}), the catalog is tiny, and rows
     * sharing a location resolve from the first-level cache, so this costs a
     * handful of queries per request rather than one per card.</p>
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "LOCATION_ID")
    private Location location;

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

    /**
     * The location entity itself. Kept out of the JSON payload so the API
     * contract stays flat — clients read {@link #getLocalizacao()} (name)
     * and {@link #getLocationId()}.
     */
    @JsonIgnore
    public Location getLocation() {
        return location;
    }

    public void setLocation(Location location) {
        this.location = location;
    }

    /** FK value, serialized as {@code location_id}. */
    @Transient
    public Long getLocationId() {
        return location != null ? location.getId() : null;
    }

    /**
     * Location name, still serialized as {@code localizacao} so the grids,
     * sorting, snapshots and spreadsheet export keep working unchanged after
     * the move to a FK.
     */
    @Transient
    public String getLocalizacao() {
        return location != null ? location.getName() : null;
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
