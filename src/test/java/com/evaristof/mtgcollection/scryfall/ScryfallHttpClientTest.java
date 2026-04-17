package com.evaristof.mtgcollection.scryfall;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScryfallHttpClientTest {

    @Test
    void get_successReturnsBody() throws Exception {
        HttpClient underlying = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> ok = (HttpResponse<String>) mock(HttpResponse.class);
        when(ok.statusCode()).thenReturn(200);
        when(ok.body()).thenReturn("hello");
        when(underlying.send(any(HttpRequest.class), any())).thenAnswer(inv -> ok);

        ScryfallHttpClient client = new ScryfallHttpClient("https://api.scryfall.com", underlying);
        String body = client.get("/sets");

        assertThat(body).isEqualTo("hello");
        assertThat(client.getBaseUrl()).isEqualTo("https://api.scryfall.com");
    }

    @Test
    void get_non2xxThrows() throws Exception {
        HttpClient underlying = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> err = (HttpResponse<String>) mock(HttpResponse.class);
        when(err.statusCode()).thenReturn(500);
        when(err.body()).thenReturn("broken");
        when(underlying.send(any(HttpRequest.class), any())).thenAnswer(inv -> err);

        ScryfallHttpClient client = new ScryfallHttpClient("https://api.scryfall.com", underlying);

        assertThatThrownBy(() -> client.get("/sets"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("500");
    }
}
