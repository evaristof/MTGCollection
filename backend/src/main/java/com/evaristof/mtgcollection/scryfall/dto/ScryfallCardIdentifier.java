package com.evaristof.mtgcollection.scryfall.dto;

import com.google.gson.annotations.SerializedName;

/**
 * Identifier used in the body of {@code POST /cards/collection}. A single
 * request may carry up to 75 of these. Each identifier targets one card via
 * one of several shapes — we only rely on two here:
 *
 * <ul>
 *   <li>{@code { "set": "zen", "collector_number": "180" }}</li>
 *   <li>{@code { "name": "Lightning Bolt", "set": "m10" }}</li>
 * </ul>
 *
 * Fields that are not set are omitted from the serialized JSON (Gson skips
 * {@code null} by default), so Scryfall only receives the keys we populated.
 *
 * @see <a href="https://scryfall.com/docs/api/cards/collection">Scryfall docs</a>
 */
public class ScryfallCardIdentifier {

    @SerializedName("name")
    private String name;

    @SerializedName("set")
    private String set;

    @SerializedName("collector_number")
    private String collectorNumber;

    public ScryfallCardIdentifier() {
    }

    public static ScryfallCardIdentifier bySetAndNumber(String set, String collectorNumber) {
        ScryfallCardIdentifier id = new ScryfallCardIdentifier();
        id.set = set;
        id.collectorNumber = collectorNumber;
        return id;
    }

    public static ScryfallCardIdentifier byNameAndSet(String name, String set) {
        ScryfallCardIdentifier id = new ScryfallCardIdentifier();
        id.name = name;
        id.set = set;
        return id;
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
}
