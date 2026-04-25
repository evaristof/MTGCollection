package com.evaristof.mtgcollection.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Persistent entity representing a Magic: The Gathering set (expansion)
 * as returned by the Scryfall API.
 */
@Entity
@Table(name = "MAGIC_SET")
public class MagicSet {

    @Id
    @Column(name = "SET_CODE", nullable = false, length = 16)
    private String setCode;

    @Column(name = "SET_NAME", nullable = false)
    private String setName;

    @Column(name = "RELEASE_DATE")
    private LocalDate releaseDate;

    @Column(name = "SET_TYPE")
    private String setType;

    @Column(name = "CARD_COUNT")
    private Integer cardCount;

    @Column(name = "PRINTED_SIZE")
    private Integer printedSize;

    @Column(name = "BLOCK_CODE")
    private String blockCode;

    @Column(name = "BLOCK_NAME")
    private String blockName;

    @Column(name = "ICON_SVG_URI")
    private String iconSvgUri;

    public MagicSet() {
    }

    public MagicSet(String setCode, String setName, LocalDate releaseDate, String setType,
                    Integer cardCount, Integer printedSize, String blockCode, String blockName) {
        this.setCode = setCode;
        this.setName = setName;
        this.releaseDate = releaseDate;
        this.setType = setType;
        this.cardCount = cardCount;
        this.printedSize = printedSize;
        this.blockCode = blockCode;
        this.blockName = blockName;
    }

    public String getSetCode() {
        return setCode;
    }

    public void setSetCode(String setCode) {
        this.setCode = setCode;
    }

    public String getSetName() {
        return setName;
    }

    public void setSetName(String setName) {
        this.setName = setName;
    }

    public LocalDate getReleaseDate() {
        return releaseDate;
    }

    public void setReleaseDate(LocalDate releaseDate) {
        this.releaseDate = releaseDate;
    }

    public String getSetType() {
        return setType;
    }

    public void setSetType(String setType) {
        this.setType = setType;
    }

    public Integer getCardCount() {
        return cardCount;
    }

    public void setCardCount(Integer cardCount) {
        this.cardCount = cardCount;
    }

    public Integer getPrintedSize() {
        return printedSize;
    }

    public void setPrintedSize(Integer printedSize) {
        this.printedSize = printedSize;
    }

    public String getBlockCode() {
        return blockCode;
    }

    public void setBlockCode(String blockCode) {
        this.blockCode = blockCode;
    }

    public String getBlockName() {
        return blockName;
    }

    public void setBlockName(String blockName) {
        this.blockName = blockName;
    }

    public String getIconSvgUri() {
        return iconSvgUri;
    }

    public void setIconSvgUri(String iconSvgUri) {
        this.iconSvgUri = iconSvgUri;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MagicSet that)) return false;
        return Objects.equals(setCode, that.setCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(setCode);
    }
}
