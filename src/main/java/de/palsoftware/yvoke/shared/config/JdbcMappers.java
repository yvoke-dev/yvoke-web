package de.palsoftware.yvoke.shared.config;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class JdbcMappers {

    private static final Logger log = LoggerFactory.getLogger(JdbcMappers.class);

    private JdbcMappers() {}

    public static Map<String, Object> jsonbToMap(ResultSet rs, String column,
        ObjectMapper objectMapper) throws SQLException {
        String raw = rs.getString(column);
        if (raw == null || raw.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize JSONB column '{}': {}", column, raw, e);
            return Collections.emptyMap();
        }
    }

    public static List<String> arrayToStringList(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        if (array == null) {
            return Collections.emptyList();
        }
        String[] values = (String[]) array.getArray();
        return List.of(values);
    }

    /**
     * Extracts a Postgres {@code uuid[]} column into an immutable {@code List<UUID>}, dropping
     * nulls.
     */
    public static List<UUID> arrayToUuidList(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        if (array == null) {
            return Collections.emptyList();
        }
        Object[] values = (Object[]) array.getArray();
        return Arrays.stream(values).filter(Objects::nonNull).map(UUID.class::cast).toList();
    }
}
