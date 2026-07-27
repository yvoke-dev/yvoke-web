package de.palsoftware.yvoke.llm.core.repository;

import de.palsoftware.yvoke.llm.core.model.LlmCallLog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=filesystem:docker/db/migration"
})
public class LlmCallLogRepositoryIT {

    @Autowired
    private LlmCallLogRepository repository;

    @Test
    void testInsertAndGetModelUsageSummaries() {
        String modelName = "it-test-llm-call-log";
        LlmCallLog logEntry = new LlmCallLog(
            UUID.randomUUID(),
            null,
            null,
            null,
            null,
            "test_source",
            "test_role",
            modelName,
            100,
            50,
            10,
            0,
            160,
            BigDecimal.valueOf(1.50),
            BigDecimal.valueOf(10.00),
            BigDecimal.valueOf(0.20),
            BigDecimal.ZERO,
            BigDecimal.valueOf(0.000650),
            120,
            Instant.now()
        );

        repository.insert(logEntry);

        List<Map<String, Object>> summaries = repository.getModelUsageSummaries();
        assertThat(summaries).isNotEmpty();

        boolean found = summaries.stream().anyMatch(s -> modelName.equals(s.get("model")));
        assertThat(found).isTrue();
    }
}
