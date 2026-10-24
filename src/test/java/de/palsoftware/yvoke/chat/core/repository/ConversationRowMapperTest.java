package de.palsoftware.yvoke.chat.core.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.palsoftware.yvoke.chat.core.model.Conversation;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ConversationRowMapperTest {

    private ObjectMapper objectMapper;
    private ConversationRowMapper rowMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        rowMapper = new ConversationRowMapper(objectMapper);
    }

    @Test
    void mapRow_mapsAllFieldsIncludingArea() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        UUID id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        Array sqlArray = mock(Array.class);
        when(sqlArray.getArray()).thenReturn(new String[] {"public"});

        when(rs.getObject("id", UUID.class)).thenReturn(id);
        when(rs.getObject("user_id", UUID.class)).thenReturn(userId);
        when(rs.getString("title")).thenReturn("My Conversation");
        when(rs.getString("settings_text")).thenReturn("{\"model\":\"gpt-4\"}");
        when(rs.getTimestamp("created_at")).thenReturn(now);
        when(rs.getTimestamp("updated_at")).thenReturn(now);
        when(rs.getArray("tags")).thenReturn(sqlArray);
        when(rs.getString("source")).thenReturn("web");
        when(rs.getString("area")).thenReturn("OIM");

        Conversation conv = rowMapper.mapRow(rs, 1);

        assertThat(conv).isNotNull();
        assertThat(conv.id()).isEqualTo(id);
        assertThat(conv.userId()).isEqualTo(userId);
        assertThat(conv.title()).isEqualTo("My Conversation");
        assertThat(conv.source()).isEqualTo("web");
        assertThat(conv.area()).isEqualTo("OIM");
        assertThat(conv.tags()).containsExactly("public");
    }

    @Test
    void mapRow_handlesNullArea() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        UUID id = UUID.randomUUID();

        when(rs.getObject("id", UUID.class)).thenReturn(id);
        when(rs.getString("area")).thenReturn(null);

        Conversation conv = rowMapper.mapRow(rs, 1);

        assertThat(conv).isNotNull();
        assertThat(conv.area()).isNull();
    }
}
