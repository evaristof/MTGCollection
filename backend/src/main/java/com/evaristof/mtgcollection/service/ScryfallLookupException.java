package com.evaristof.mtgcollection.service;

/**
 * Raised when a Scryfall card lookup fails. Carries the fully-qualified URL
 * that was requested so callers (e.g. the import service) can log it or show
 * it to the user for manual inspection.
 */
public class ScryfallLookupException extends RuntimeException {

    private final String url;

    public ScryfallLookupException(String url, String message, Throwable cause) {
        super(message, cause);
        this.url = url;
    }

    public String getUrl() {
        return url;
    }
}
