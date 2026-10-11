package de.palsoftware.yvoke;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.containers.PostgreSQLContainer;

public class PostgresTestContainerInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final Logger log = LoggerFactory.getLogger(PostgresTestContainerInitializer.class);

    // Lazily started and shared across the IT suite.
    private static PostgreSQLContainer<?> postgres;

    @SuppressWarnings("resource")
    private static synchronized PostgreSQLContainer<?> container() {
        if (postgres == null) {
            postgres = new PostgreSQLContainer<>(
                    DockerImageName.parse("yvoke/pgvector-pg_search:pg16-0.24.0").asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("postgres")
                    .withUsername("postgres")
                    .withPassword("postgres")
                    .withCommand("postgres", "-c", "max_connections=1024", "-c", "shared_preload_libraries=pg_search",
                            "-c", "hnsw.iterative_scan=relaxed_order");
            log.info("Starting PostgreSQL Testcontainer with pgvector and pg_search...");
            postgres.start();
            log.info("PostgreSQL Testcontainer started. JDBC URL: {}", postgres.getJdbcUrl());
        }
        return postgres;
    }

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        // Every integration test runs under the "test" development profile. This is what makes
        // app.security.mock=true legal in the IT harness now that mock auth fails closed (SEC-09):
        // outside a dev/local/test profile the security context refuses to start.
        applicationContext.getEnvironment().addActiveProfile("test");

        PostgreSQLContainer<?> pg = container();
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                applicationContext,
                "spring.datasource.url=" + pg.getJdbcUrl(),
                "spring.datasource.username=" + pg.getUsername(),
                "spring.datasource.password=" + pg.getPassword(),
                "spring.flyway.enabled=true",
                "spring.flyway.locations=filesystem:docker/db/migration",
                // application.yml pins the actuator to the FIXED port 9090, so every RANDOM_PORT
                // context starts a second Tomcat on a hardcoded port. That makes the IT suite
                // unparallelizable and lets an overlapping JVM (a previous run still releasing the
                // port, or a locally running app) fail the bind. Give it an ephemeral port instead.
                // Set here rather than in an application-test.yml: the "test" profile is added by
                // this initializer, which runs AFTER Spring Boot's config-data processing, so a
                // profile-specific file would never be read. Being in the shared initializer also
                // means every IT gets it identically, so no new TestContext signature is minted
                // (see the context-cache pitfall in CLAUDE.md).
                // Safe: the only tests touching /actuator (SecurityGatingIT, SecurityMockGatingIT)
                // use MockMvc, which never goes through the management container.
                "management.server.port=0");
    }
}
