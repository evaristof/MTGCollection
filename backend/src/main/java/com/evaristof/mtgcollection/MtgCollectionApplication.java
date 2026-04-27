package com.evaristof.mtgcollection;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Default entry point for the packaged jar.
 *
 * <p>Activates the {@code prod} Spring profile by default, which points the
 * datasource at PostgreSQL (see {@code application-prod.properties}). Any
 * property can still be overridden via environment variables or CLI arguments,
 * e.g.:
 *
 * <pre>
 *   java -jar mtgcollection.jar --spring.datasource.url=...
 *   SPRING_DATASOURCE_URL=... java -jar mtgcollection.jar
 * </pre>
 *
 * <p>If you explicitly pass {@code --spring.profiles.active=h2} (or set
 * {@code SPRING_PROFILES_ACTIVE}), that takes precedence over the default set
 * here and the app will run against H2.
 *
 * <p>For an in-memory H2 run without juggling profiles, use
 * {@link MtgCollectionH2Application} instead.
 */
@SpringBootApplication
@EnableAsync
public class MtgCollectionApplication {

    public static void main(String[] args) {
        // Force IPv4 before any networking class initializes. Avoids the
        // java.net.BindException: Cannot assign requested address: getsockopt
        // seen on machines with broken IPv6 (common on Windows) when calling
        // external APIs such as Scryfall.
        System.setProperty("java.net.preferIPv4Stack", "true");
        System.setProperty("java.net.preferIPv4Addresses", "true");

        new SpringApplicationBuilder(MtgCollectionApplication.class)
                // "Default" profile: only applied if the user hasn't set
                // spring.profiles.active via env var / CLI.
                .profiles("prod")
                .run(args);
    }
}
