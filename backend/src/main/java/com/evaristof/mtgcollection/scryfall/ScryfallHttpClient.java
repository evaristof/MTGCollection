package com.evaristof.mtgcollection.scryfall;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Thin wrapper around java.net.http.HttpClient used to call the Scryfall
 * REST API. Kept as a Spring bean so it can be easily mocked in tests.
 */
@Component
public class ScryfallHttpClient {

    private static final Logger log = LoggerFactory.getLogger(ScryfallHttpClient.class);

    private final HttpClient httpClient;
    private final String baseUrl;
    private final int maxRetries;
    private final long retryBackoffMs;

    public ScryfallHttpClient(@Value("${scryfall.api.base-url:https://api.scryfall.com}") String baseUrl,
                              @Value("${scryfall.api.max-retries:3}") int maxRetries,
                              @Value("${scryfall.api.retry-backoff-ms:800}") long retryBackoffMs,
                              HttpClient httpClient) {
        this.baseUrl = baseUrl;
        this.maxRetries = Math.max(0, maxRetries);
        this.retryBackoffMs = Math.max(0L, retryBackoffMs);
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
        return send(request, url);
    }

    /**
     * POSTs a JSON body to the Scryfall API and returns the response body.
     * Used for endpoints like {@code POST /cards/collection} that take a JSON
     * payload and return a list of cards.
     */
    public String postJson(String path, String jsonBody) throws IOException, InterruptedException {
        String url = path.startsWith("http") ? path : baseUrl + path;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("User-Agent", "MTGCollection/0.1 (+https://github.com/evaristof/MTGCollection)")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        return send(request, url);
    }

    private String send(HttpRequest request, String url) throws IOException, InterruptedException {
        IOException lastError = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            HttpResponse<String> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (IOException ioe) {
                lastError = ioe;
                if (attempt == maxRetries) throw ioe;
                sleepBackoff(attempt, url, "I/O: " + ioe.getMessage());
                continue;
            }
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return response.body();
            }
            // Retry on 429 (rate limit) and 5xx; fail fast on other 4xx.
            boolean retriable = status == 429 || (status >= 500 && status < 600);
            if (!retriable || attempt == maxRetries) {
                throw new IOException("Scryfall request failed: " + status + " for " + url
                        + " body=" + response.body());
            }
            lastError = new IOException("HTTP " + status);
            sleepBackoff(attempt, url, "HTTP " + status);
        }
        // Unreachable — the loop always either returns or throws.
        throw lastError != null ? lastError : new IOException("Scryfall request failed: " + url);
    }

    private void sleepBackoff(int attempt, String url, String reason) throws InterruptedException {
        long waitMs = retryBackoffMs * (1L << attempt); // base, 2x, 4x, ...
        log.warn("Scryfall retry {}/{} in {} ms for {} ({})",
                attempt + 1, maxRetries, waitMs, url, reason);
        Thread.sleep(waitMs);
    }
}
