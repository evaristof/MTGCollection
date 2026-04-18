package com.evaristof.mtgcollection.scryfall.dto;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * Wrapper for responses returned by {@code POST /cards/collection}.
 *
 * <p>Scryfall returns an object of the form
 * {@code { "object": "list", "data": [ Card, ... ], "not_found": [ Identifier, ... ] }}.
 * Cards not matched by any identifier in the request body are reported
 * individually under {@code not_found}.</p>
 */
public class ScryfallBatchResponse {

    @SerializedName("object")
    private String object;

    @SerializedName("data")
    private List<ScryfallCard> data;

    @SerializedName("not_found")
    private List<ScryfallCardIdentifier> notFound;

    public ScryfallBatchResponse() {
    }

    public String getObject() {
        return object;
    }

    public void setObject(String object) {
        this.object = object;
    }

    public List<ScryfallCard> getData() {
        return data;
    }

    public void setData(List<ScryfallCard> data) {
        this.data = data;
    }

    public List<ScryfallCardIdentifier> getNotFound() {
        return notFound;
    }

    public void setNotFound(List<ScryfallCardIdentifier> notFound) {
        this.notFound = notFound;
    }
}
