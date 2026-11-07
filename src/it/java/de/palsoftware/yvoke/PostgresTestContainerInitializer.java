package de.palsoftware.yvoke;

import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.Ports.Binding;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.core.CreateContainerCmdModifier;
import org.testcontainers.utility.DockerImageName;

public class PostgresTestContainerInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext>,
                CreateContainerCmdModifier {

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

    /**
     * Binds every Testcontainers exposed port (including internal containers such as
     * {@code RyukContainer} and {@code PostgreSQLContainer}) explicitly to IPv4 loopback
     * {@code 127.0.0.1} rather than the default {@code 0.0.0.0}/{@code [::]} wildcard.
     *
     * <p>On macOS, Docker Desktop's {@code com.docker.backend} allocates wildcard host ports by
     * calling {@code bind([::], 0)}, and the macOS kernel's IPv6 wildcard port allocator does not
     * check IPv4-only {@code 127.0.0.1} listeners (such as local IDE {@code language_server}
     * proxies). When a container's host port collides with one of those {@code 127.0.0.1}
     * listeners, {@code PostgreSQLContainer}'s log-based wait strategy still passes, and HikariCP's
     * first JDBC connection to {@code localhost:<port>} hits the HTTP proxy and blocks forever in
     * {@code PGStream.receiveChar()} waiting for a PostgreSQL authentication packet.
     */
    @Override
    public CreateContainerCmd modify(CreateContainerCmd cmd) {
        String dockerHost = System.getenv("DOCKER_HOST");
        if (dockerHost == null || dockerHost.isBlank()) {
            dockerHost = System.getProperty("docker.host");
        }
        if (isRemoteTcpDockerHost(dockerHost)) {
            return cmd;
        }
        HostConfig hostConfig = cmd.getHostConfig();
        if (hostConfig != null && hostConfig.getPortBindings() != null) {
            Ports ports = hostConfig.getPortBindings();
            Map<ExposedPort, Binding[]> bindings = ports.getBindings();
            if (bindings != null) {
                for (Binding[] portBindings : bindings.values()) {
                    if (portBindings != null) {
                        for (int i = 0; i < portBindings.length; i++) {
                            Binding binding = portBindings[i];
                            if (binding != null
                                    && (binding.getHostIp() == null || binding.getHostIp().isBlank())) {
                                portBindings[i] = new Binding("127.0.0.1", binding.getHostPortSpec());
                            }
                        }
                    }
                }
            }
        }
        return cmd;
    }

    static boolean isRemoteTcpDockerHost(String dockerHost) {
        if (dockerHost == null || dockerHost.isBlank()) {
            return false;
        }
        String trimmed = dockerHost.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("ssh://")) {
            return true;
        }
        if (!lower.startsWith("tcp://")) {
            return false;
        }
        try {
            String host = URI.create(trimmed).getHost();
            if (host == null || host.isBlank()) {
                return false;
            }
            return !"localhost".equalsIgnoreCase(host)
                    && !"127.0.0.1".equals(host)
                    && !"[::1]".equals(host)
                    && !"::1".equals(host);
        } catch (IllegalArgumentException e) {
            return false;
        }
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
                "management.server.port=0",
                // Bind RANDOM_PORT Tomcat instances explicitly to IPv4 loopback 127.0.0.1 rather
                // than the IPv6 wildcard ([::]:0). On macOS, the kernel's IPv6 wildcard port
                // allocator does not check IPv4-only 127.0.0.1 listeners (such as local IDE
                // language_server proxies), so an unbound server.address can be assigned a port
                // already held on 127.0.0.1 and lose localhost HTTP traffic to that proxy (400
                // "This is an explicit proxy server. Does not respond to relative URIs.").
                "server.address=127.0.0.1",
                "management.server.address=127.0.0.1");
    }
}
