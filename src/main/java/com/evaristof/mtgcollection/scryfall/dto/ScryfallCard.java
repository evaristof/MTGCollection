package com.evaristof.mtgcollection.scryfall.dto;

import com.google.gson.annotations.SerializedName;

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

    @SerializedName("prices")
    private ScryfallPrices prices;

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

    public ScryfallPrices getPrices() {
        return prices;
    }

    public void setPrices(ScryfallPrices prices) {
        this.prices = prices;
    }
}
