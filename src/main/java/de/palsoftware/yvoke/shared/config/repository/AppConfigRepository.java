package de.palsoftware.yvoke.shared.config.repository;


import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AppConfigRepository {

    private final JdbcClient jdbcClient;

    public AppConfigRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public String get(String key, String defaultValue) {
        if (key == null) {
            return defaultValue;
        }
        Optional<String> val = jdbcClient.sql("SELECT value FROM app_config WHERE key = :key")
            .param("key", key).query(String.class).optional();
        return val.orElse(defaultValue);
    }

    public void set(String key, String value) {
        if (key == null || value == null) {
            return;
        }
        jdbcClient.sql("""
            INSERT INTO app_config (key, value)
            VALUES (:key, :value)
            ON CONFLICT (key) DO UPDATE SET
                value = EXCLUDED.value,
                updated_at = CURRENT_TIMESTAMP
            """).param("key", key).param("value", value).update();
    }
}
