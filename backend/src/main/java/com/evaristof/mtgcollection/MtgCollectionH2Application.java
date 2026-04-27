package com.evaristof.mtgcollection;

import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * Alternate entry point that runs the app against the bundled H2 in-memory
 * database. Useful for quick local smoke tests, demos, or when you don't have
 * a PostgreSQL instance handy.
 *
 * <p>Under the hood this just bootstraps {@link MtgCollectionApplication} with
 * the {@code h2} Spring profile activated — there is deliberately only one
 * {@code @SpringBootApplication} class in the codebase so that
 * {@code @SpringBootTest} never has to disambiguate between multiple
 * configuration roots.
 *
 * <p>All data stored in H2 is lost when the process exits.
 */
public final class MtgCollectionH2Application {

    private MtgCollectionH2Application() {
        // Entry point only.
    }

    public static void main(String[] args) {
        System.setProperty("java.net.preferIPv4Stack", "true");
        System.setProperty("java.net.preferIPv4Addresses", "true");

        new SpringApplicationBuilder(MtgCollectionApplication.class)
                .profiles("h2")
                .run(args);
    }
}
