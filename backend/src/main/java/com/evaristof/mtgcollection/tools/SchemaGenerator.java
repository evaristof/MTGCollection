package com.evaristof.mtgcollection.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.tool.schema.spi.DelayedDropRegistryNotAvailableImpl;
import org.hibernate.tool.schema.spi.SchemaManagementToolCoordinator;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

/**
 * Build-time tool that writes the PostgreSQL DDL for the JPA model to
 * {@code schema-postgres.sql} (+ a matching {@code -drop.sql}).
 *
 * <p>Wired into the default Maven build via {@code exec-maven-plugin} on the
 * {@code process-classes} phase, so every {@code mvn clean install} (or
 * {@code mvn package}) produces a fresh copy under
 * {@code backend/target/generated-sql/}.
 *
 * <p>Does not need a running database: Hibernate is bootstrapped in
 * "metadata only" mode with the PostgreSQL dialect and JDBC metadata access
 * disabled. We then drive Hibernate's own schema tooling
 * ({@link SchemaManagementToolCoordinator}) with the standard
 * {@code jakarta.persistence.schema-generation.*} properties so the output
 * matches what the running app writes on boot under the {@code prod} profile
 * (see {@code application-prod.properties}).
 *
 * <p>Accepts one optional argument — the output directory. Defaults to the
 * current working directory so the CLI behaviour matches the runtime
 * schema-generation wired up in {@code application-prod.properties}.
 */
public final class SchemaGenerator {

    /** Package root scanned for {@link jakarta.persistence.Entity} classes. */
    private static final String ENTITY_PACKAGE = "com.evaristof.mtgcollection";

    private static final String CREATE_FILE = "schema-postgres.sql";
    private static final String DROP_FILE = "schema-postgres-drop.sql";

    /** Constant is not exposed via AvailableSettings in Hibernate 6.4. */
    private static final String ALLOW_JDBC_METADATA_ACCESS = "hibernate.boot.allow_jdbc_metadata_access";

    private SchemaGenerator() {
    }

    public static void main(String[] args) throws IOException {
        Path outDir = Paths.get(args.length > 0 && !args[0].isBlank() ? args[0] : ".")
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(outDir);

        Path createFile = outDir.resolve(CREATE_FILE);
        Path dropFile = outDir.resolve(DROP_FILE);
        // Schema tooling appends to the target files; start from a clean slate
        // so re-runs don't accumulate duplicate DDL.
        Files.deleteIfExists(createFile);
        Files.deleteIfExists(dropFile);

        Map<String, Object> settings = new HashMap<>();
        settings.put(AvailableSettings.DIALECT, "org.hibernate.dialect.PostgreSQLDialect");
        settings.put(AvailableSettings.HBM2DDL_AUTO, "none");
        settings.put(ALLOW_JDBC_METADATA_ACCESS, "false");
        // Standard JPA schema-generation settings — same keys used at runtime
        // from application-prod.properties.
        settings.put(AvailableSettings.JAKARTA_HBM2DDL_SCRIPTS_ACTION, "drop-and-create");
        settings.put(AvailableSettings.JAKARTA_HBM2DDL_CREATE_SOURCE, "metadata");
        settings.put(AvailableSettings.JAKARTA_HBM2DDL_DROP_SOURCE, "metadata");
        settings.put(AvailableSettings.JAKARTA_HBM2DDL_SCRIPTS_CREATE_TARGET, createFile.toString());
        settings.put(AvailableSettings.JAKARTA_HBM2DDL_SCRIPTS_DROP_TARGET, dropFile.toString());
        // Keep the output readable: one statement per logical line with ; as
        // the delimiter, matching the runtime-generated scripts.
        settings.put(AvailableSettings.HBM2DDL_DELIMITER, ";");
        settings.put("hibernate.format_sql", "true");

        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySettings(settings)
                .build();

        try {
            MetadataSources sources = new MetadataSources(registry);
            ClassPathScanningCandidateComponentProvider scanner =
                    new ClassPathScanningCandidateComponentProvider(false);
            scanner.addIncludeFilter(new AnnotationTypeFilter(jakarta.persistence.Entity.class));
            int found = 0;
            for (var bd : scanner.findCandidateComponents(ENTITY_PACKAGE)) {
                String className = bd.getBeanClassName();
                if (className != null) {
                    sources.addAnnotatedClassName(className);
                    found++;
                }
            }
            if (found == 0) {
                throw new IllegalStateException(
                        "No @Entity classes found under package " + ENTITY_PACKAGE
                                + ". SchemaGenerator produced nothing.");
            }

            Metadata metadata = sources.buildMetadata();

            SchemaManagementToolCoordinator.process(
                    metadata,
                    registry,
                    settings,
                    DelayedDropRegistryNotAvailableImpl.INSTANCE);

            System.out.println("SchemaGenerator: scanned " + found + " @Entity classes");
            System.out.println("SchemaGenerator: wrote " + createFile);
            System.out.println("SchemaGenerator: wrote " + dropFile);
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }
}
