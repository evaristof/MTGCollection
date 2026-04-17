package com.evaristof.mtgcollection.scryfall.dto;

import com.google.gson.annotations.SerializedName;

/**
 * DTO for a Scryfall Set as returned by /sets.
 * See: https://scryfall.com/docs/api/sets
 */
public class ScryfallSet {

    @SerializedName("code")
    private String code;

    @SerializedName("name")
    private String name;

    @SerializedName("released_at")
    private String releasedAt;

    @SerializedName("set_type")
    private String setType;

    @SerializedName("card_count")
    private Integer cardCount;

    @SerializedName("printed_size")
    private Integer printedSize;

    @SerializedName("block_code")
    private String blockCode;

    @SerializedName("block")
    private String block;

    public ScryfallSet() {
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getReleasedAt() {
        return releasedAt;
    }

    public void setReleasedAt(String releasedAt) {
        this.releasedAt = releasedAt;
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

    public String getBlock() {
        return block;
    }

    public void setBlock(String block) {
        this.block = block;
    }
}
