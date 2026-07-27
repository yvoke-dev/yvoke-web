package de.palsoftware.yvoke.kg.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

public class KgEntityTest {

    @Test
    public void testDisplayTagWithActiveTagMatch() {
        KgEntity entity =
            new KgEntity(UUID.randomUUID(), UUID.randomUUID(), "OIM - Custom - Install Kit", "AAD",
                "module", List.of("9.3.1", "10.0"), "Description", Map.of(), null);

        assertThat(entity.displayTag("10.0")).isEqualTo("10.0");
        assertThat(entity.displayTag("9.3.1")).isEqualTo("9.3.1");
    }

    @Test
    public void testDisplayTagWithNoActiveTag() {
        KgEntity entity =
            new KgEntity(UUID.randomUUID(), UUID.randomUUID(), "OIM - Custom - Install Kit", "AAD",
                "module", List.of("9.3.1", "10.0"), "Description", Map.of(), null);

        assertThat(entity.displayTag(null)).isEqualTo("9.3.1, 10.0");
        assertThat(entity.displayTag("  ")).isEqualTo("9.3.1, 10.0");
    }

    @Test
    public void testDisplayTagUnmatchedActiveTagFallback() {
        KgEntity entity =
            new KgEntity(UUID.randomUUID(), UUID.randomUUID(), "OIM - Custom - Install Kit", "AAD",
                "module", List.of("9.3.1", "10.0"), "Description", Map.of(), null);

        assertThat(entity.displayTag("11.0")).isEqualTo("9.3.1, 10.0");
    }
}
