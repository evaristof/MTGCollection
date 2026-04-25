package com.evaristof.mtgcollection.scryfall.dto;

import com.google.gson.annotations.SerializedName;

/**
 * Represents a single face of a multi-faced Scryfall card (transform, modal
 * DFC, flip, meld, etc.). Only the fields needed for image retrieval are mapped.
 */
public class ScryfallCardFace {

    @SerializedName("name")
    private String name;

    @SerializedName("image_uris")
    private ScryfallImageUris imageUris;

    public ScryfallCardFace() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public ScryfallImageUris getImageUris() {
        return imageUris;
    }

    public void setImageUris(ScryfallImageUris imageUris) {
        this.imageUris = imageUris;
    }
}
