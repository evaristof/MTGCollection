package com.evaristof.mtgcollection.scryfall.dto;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * DTO for a Scryfall Card. Only the fields relevant to the current
 * use cases (pricing) are mapped; Scryfall returns many more fields.
 */
public class ScryfallCard {

    @SerializedName("object")
    private String object;

    @SerializedName("id")
    private String id;

    @SerializedName("name")
    private String name;

    @SerializedName("set")
    private String set;

    @SerializedName("collector_number")
    private String collectorNumber;

    @SerializedName("type_line")
    private String typeLine;

    @SerializedName("lang")
    private String lang;

    @SerializedName("rarity")
    private String rarity;

    @SerializedName("mana_cost")
    private String manaCost;

    @SerializedName("oracle_text")
    private String oracleText;

    @SerializedName("prices")
    private ScryfallPrices prices;

    @SerializedName("image_uris")
    private ScryfallImageUris imageUris;

    @SerializedName("card_faces")
    private List<ScryfallCardFace> cardFaces;

    public ScryfallCard() {
    }

    public String getObject() {
        return object;
    }

    public void setObject(String object) {
        this.object = object;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSet() {
        return set;
    }

    public void setSet(String set) {
        this.set = set;
    }

    public String getCollectorNumber() {
        return collectorNumber;
    }

    public void setCollectorNumber(String collectorNumber) {
        this.collectorNumber = collectorNumber;
    }

    public String getTypeLine() {
        return typeLine;
    }

    public void setTypeLine(String typeLine) {
        this.typeLine = typeLine;
    }

    public String getLang() {
        return lang;
    }

    public void setLang(String lang) {
        this.lang = lang;
    }

    public String getRarity() {
        return rarity;
    }

    public void setRarity(String rarity) {
        this.rarity = rarity;
    }

    public String getManaCost() {
        return manaCost;
    }

    public void setManaCost(String manaCost) {
        this.manaCost = manaCost;
    }

    public String getOracleText() {
        return oracleText;
    }

    public void setOracleText(String oracleText) {
        this.oracleText = oracleText;
    }

    public ScryfallPrices getPrices() {
        return prices;
    }

    public void setPrices(ScryfallPrices prices) {
        this.prices = prices;
    }

    public ScryfallImageUris getImageUris() {
        return imageUris;
    }

    public void setImageUris(ScryfallImageUris imageUris) {
        this.imageUris = imageUris;
    }

    public List<ScryfallCardFace> getCardFaces() {
        return cardFaces;
    }

    public void setCardFaces(List<ScryfallCardFace> cardFaces) {
        this.cardFaces = cardFaces;
    }
}
