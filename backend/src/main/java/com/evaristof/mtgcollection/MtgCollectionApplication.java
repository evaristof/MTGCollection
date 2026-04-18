package com.evaristof.mtgcollection;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MtgCollectionApplication {

    public static void main(String[] args) {
        // Força IPv4 antes de qualquer classe de rede inicializar. Evita o erro
        // java.net.BindException: Cannot assign requested address: getsockopt em
        // máquinas com IPv6 mal configurado (comum no Windows) ao chamar APIs
        // externas como o Scryfall.
        System.setProperty("java.net.preferIPv4Stack", "true");
        System.setProperty("java.net.preferIPv4Addresses", "true");
        SpringApplication.run(MtgCollectionApplication.class, args);
    }
}
