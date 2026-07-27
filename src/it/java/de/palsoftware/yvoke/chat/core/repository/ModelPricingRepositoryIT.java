package de.palsoftware.yvoke.chat.core.repository;

import de.palsoftware.yvoke.chat.core.model.ModelPricing;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=filesystem:docker/db/migration"
})
public class ModelPricingRepositoryIT {

    @Autowired
    private ModelPricingRepository repository;

    @Test
    void testUpsertFindAndDeleteModelPricing() {
        String modelName = "it-test-model-pricing";
        ModelPricing pricing = new ModelPricing(
            UUID.randomUUID(),
            modelName,
            BigDecimal.valueOf(1.2345),
            BigDecimal.valueOf(5.6789),
            BigDecimal.valueOf(0.1234),
            BigDecimal.valueOf(5.6789),
            Instant.now()
        );

        repository.upsert(pricing);

        Optional<ModelPricing> fetched = repository.findByModelName(modelName);
        assertThat(fetched).isPresent();
        assertThat(fetched.get().modelName()).isEqualTo(modelName);
        assertThat(fetched.get().promptPricePerMillion()).isEqualByComparingTo(BigDecimal.valueOf(1.2345));
        assertThat(fetched.get().completionPricePerMillion()).isEqualByComparingTo(BigDecimal.valueOf(5.6789));

        repository.deleteByModelName(modelName);
        assertThat(repository.findByModelName(modelName)).isEmpty();
    }
}
