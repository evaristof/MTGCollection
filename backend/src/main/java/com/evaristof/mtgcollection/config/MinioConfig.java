package com.evaristof.mtgcollection.config;

import io.minio.MinioClient;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Configuration
public class MinioConfig {

    @Value("${minio.endpoint}")
    private String endpoint;

    @Value("${minio.access-key}")
    private String accessKey;

    @Value("${minio.secret-key}")
    private String secretKey;

    @Bean
    public MinioClient minioClient() {
        // MinIO's default OkHttp client caps concurrent requests per host at 5
        // and has no hard call timeout. Under load (the bulk download fires ~24
        // uploads at once while the UI also fetches set icons) that pool gets
        // exhausted and — if any request stalls — every MinIO op deadlocks,
        // hanging the download job. Give it a bigger pool/dispatcher and real
        // timeouts so a stalled request fails fast instead of blocking forever.
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(128);
        dispatcher.setMaxRequestsPerHost(64);
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .connectionPool(new ConnectionPool(64, 5, TimeUnit.MINUTES))
                .connectTimeout(Duration.ofSeconds(15))
                .writeTimeout(Duration.ofSeconds(60))
                .readTimeout(Duration.ofSeconds(60))
                .callTimeout(Duration.ofSeconds(120))
                .build();
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .httpClient(httpClient)
                .build();
    }
}
