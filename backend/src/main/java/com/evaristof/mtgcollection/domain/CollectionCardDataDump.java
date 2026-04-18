package com.evaristof.mtgcollection.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Point-in-time snapshot of a single row from {@link CollectionCard}.
 *
 * <p>Every time the user clicks the "Data Dump" button on the Cards page we
 * copy every row of {@code COLLECTION_CARD} into this table, stamping each
 * copy with the same {@link #dataDumpDateTime}. The collection of rows that
 * share a timestamp is one "data dump". Historical dumps are kept so we can
 * later compute things like "which card appreciated the most between two
 * snapshots".</p>
 *
 * <p>The primary key is a fresh synthetic {@code ID} so the originating
 * collection row can be deleted without cascading into the dumps.</p>
 */
@Entity
@Table(
        name = "COLLECTION_CARD_DATA_DUMP",
        indexes = {
                @Index(name = "IDX_DUMP_TIMESTAMP", columnList = "DATA_DUMP_DATE_TIME")
        })
public class CollectionCardDataDump {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID")
    private Long id;

    /**
     * Wall-clock moment the dump was captured. All rows belonging to the
     * same snapshot share the same value (to second precision).
     */
    @Column(name = "DATA_DUMP_DATE_TIME", nullable = false)
    private LocalDateTime dataDumpDateTime;

    /**
     * PK of the originating {@link CollectionCard} at dump time. We persist
     * it purely for traceability (e.g. "which card id used to exist?") —
     * there is no FK because the source row may later be deleted.
     */
    @Column(name = "SOURCE_CARD_ID")
    private Long sourceCardId;

    @Column(name = "CARD_NUMBER", length = 32)
    private String cardNumber;

    @Column(name = "CARD_NAME", nullable = false)
    private String cardName;

    @Column(name = "SET_CODE", length = 16)
    private String setCode;

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

    public CollectionCardDataDump() {
    }

    /** Copies every user-facing field of {@code source} — id and timestamp are not copied. */
    public static CollectionCardDataDump copyOf(CollectionCard source, LocalDateTime timestamp) {
        CollectionCardDataDump dump = new CollectionCardDataDump();
        dump.dataDumpDateTime = timestamp;
        dump.sourceCardId = source.getId();
        dump.cardNumber = source.getCardNumber();
        dump.cardName = source.getCardName();
        dump.setCode = source.getSetCode();
        dump.setNameRaw = source.getSetNameRaw();
        dump.foil = source.isFoil();
        dump.cardType = source.getCardType();
        dump.language = source.getLanguage();
        dump.quantity = source.getQuantity();
        dump.price = source.getPrice();
        dump.comentario = source.getComentario();
        dump.localizacao = source.getLocalizacao();
        return dump;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public LocalDateTime getDataDumpDateTime() { return dataDumpDateTime; }
    public void setDataDumpDateTime(LocalDateTime dataDumpDateTime) { this.dataDumpDateTime = dataDumpDateTime; }

    public Long getSourceCardId() { return sourceCardId; }
    public void setSourceCardId(Long sourceCardId) { this.sourceCardId = sourceCardId; }

    public String getCardNumber() { return cardNumber; }
    public void setCardNumber(String cardNumber) { this.cardNumber = cardNumber; }

    public String getCardName() { return cardName; }
    public void setCardName(String cardName) { this.cardName = cardName; }

    public String getSetCode() { return setCode; }
    public void setSetCode(String setCode) { this.setCode = setCode; }

    public String getSetNameRaw() { return setNameRaw; }
    public void setSetNameRaw(String setNameRaw) { this.setNameRaw = setNameRaw; }

    public boolean isFoil() { return foil; }
    public void setFoil(boolean foil) { this.foil = foil; }

    public String getCardType() { return cardType; }
    public void setCardType(String cardType) { this.cardType = cardType; }

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }

    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }

    public String getComentario() { return comentario; }
    public void setComentario(String comentario) { this.comentario = comentario; }

    public String getLocalizacao() { return localizacao; }
    public void setLocalizacao(String localizacao) { this.localizacao = localizacao; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CollectionCardDataDump that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
