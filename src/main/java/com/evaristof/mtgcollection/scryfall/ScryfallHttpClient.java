package com.evaristof.mtgcollection.scryfall;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Thin wrapper around java.net.http.HttpClient used to call the Scryfall
 * REST API. Kept as a Spring bean so it can be easily mocked in tests.
 */
@Component
public class ScryfallHttpClient {

    private final HttpClient httpClient;
    private final String baseUrl;

    public ScryfallHttpClient(@Value("${scryfall.api.base-url:https://api.scryfall.com}") String baseUrl,
                              HttpClient httpClient) {
        this.baseUrl = baseUrl;
        this.httpClient = httpClient;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * Performs a GET request against the Scryfall API and returns the response body as a String.
     *
     * @param path path starting with '/', e.g. "/sets"
     * @return the body of the response
     * @throws IOException if the HTTP call fails or the response status is not 2xx
     */
    public String get(String path) throws IOException, InterruptedException {
        String url = path.startsWith("http") ? path : baseUrl + path;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .header("User-Agent", "MTGCollection/0.1 (+https://github.com/evaristof/MTGCollection)")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new IOException("Scryfall request failed: " + status + " for " + url + " body=" + response.body());
        }
        return response.body();
    }
}
