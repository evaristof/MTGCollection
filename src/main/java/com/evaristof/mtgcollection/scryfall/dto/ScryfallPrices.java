package com.evaristof.mtgcollection.scryfall.dto;

import com.google.gson.annotations.SerializedName;

/**
 * Represents the "prices" object nested inside a Scryfall card response.
 * All amounts are strings in Scryfall's API (e.g. "1.23"), and any of them
 * may be null if Scryfall has no price for that variant.
 */
public class ScryfallPrices {

    @SerializedName("usd")
    private String usd;

    @SerializedName("usd_foil")
    private String usdFoil;

    @SerializedName("usd_etched")
    private String usdEtched;

    @SerializedName("eur")
    private String eur;

    @SerializedName("eur_foil")
    private String eurFoil;

    @SerializedName("tix")
    private String tix;

    public ScryfallPrices() {
    }

    public String getUsd() {
        return usd;
    }

    public void setUsd(String usd) {
        this.usd = usd;
    }

    public String getUsdFoil() {
        return usdFoil;
    }

    public void setUsdFoil(String usdFoil) {
        this.usdFoil = usdFoil;
    }

    public String getUsdEtched() {
        return usdEtched;
    }

    public void setUsdEtched(String usdEtched) {
        this.usdEtched = usdEtched;
    }

    public String getEur() {
        return eur;
    }

    public void setEur(String eur) {
        this.eur = eur;
    }

    public String getEurFoil() {
        return eurFoil;
    }

    public void setEurFoil(String eurFoil) {
        this.eurFoil = eurFoil;
    }

    public String getTix() {
        return tix;
    }

    public void setTix(String tix) {
        this.tix = tix;
    }
}
