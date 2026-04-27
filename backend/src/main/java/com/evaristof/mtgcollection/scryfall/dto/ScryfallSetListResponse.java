package com.evaristof.mtgcollection.scryfall.dto;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * Wrapper for responses of the form { "object": "list", "data": [ ... ] }
 * returned by Scryfall's /sets endpoint.
 */
public class ScryfallSetListResponse {

    @SerializedName("object")
    private String object;

    @SerializedName("has_more")
    private Boolean hasMore;

    @SerializedName("data")
    private List<ScryfallSet> data;

    public ScryfallSetListResponse() {
    }

    public String getObject() {
        return object;
    }

    public void setObject(String object) {
        this.object = object;
    }

    public Boolean getHasMore() {
        return hasMore;
    }

    public void setHasMore(Boolean hasMore) {
        this.hasMore = hasMore;
    }

    public List<ScryfallSet> getData() {
        return data;
    }

    public void setData(List<ScryfallSet> data) {
        this.data = data;
    }
}
