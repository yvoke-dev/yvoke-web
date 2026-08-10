package de.palsoftware.yvoke.chat.core.repository;

import de.palsoftware.yvoke.chat.core.model.ModelPricing;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.locations=filesystem:docker/db/migration"
})
public class ModelPricingRepositoryIT {

    @Autowired
    private ModelPricingRepository repository;

    /**
     * {@code chat_model_pricing} is keyed for humans by {@code model_name}, not by its surrogate
     * {@code id}, and the upsert must arbitrate on the column that carries that identity. The id in
     * the {@link ModelPricing} handed to {@code upsert} is therefore a suggestion that is ignored
     * whenever the model already exists — deliberately, because
     * {@code CostCalculationService.updateModelPricing} mints a fresh {@code UUID.randomUUID()}
     * whenever its {@code findByModelName} probe misses, and the admin pricing form posts only a
     * model name. Two saves racing on a model that was just created therefore routinely arrive
     * carrying an id that is not the stored row's. Arbitrating on {@code id} instead turns the
     * second save into a real INSERT that dies on the {@code model_name UNIQUE} constraint — a raw
     * 23505 out of an admin form for what is simply "change a price" — and there is no rename path
     * anywhere that would justify it. A second row for the same name would be worse still: every
     * cost query prices by model NAME through a name-keyed map, so a duplicate silently makes the
     * whole dashboard's valuation depend on row order. Nothing pins this today: the existing
     * round-trip test upserts each name exactly once and so never reaches the conflict branch.
     */
    @Test
    void upsertKeepsTheExistingRowsIdAndNeverDuplicatesAModel() {
        String modelName = "it-upsert-arbiter-" + UUID.randomUUID();
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        try {
            repository.upsert(new ModelPricing(firstId, modelName, new BigDecimal("1.000000"),
                new BigDecimal("2.000000"), new BigDecimal("0.100000"), new BigDecimal("0.000000"),
                Instant.now()));

            // Same model, a DIFFERENT id: this must update the row in place, not insert a second.
            repository.upsert(new ModelPricing(secondId, modelName, new BigDecimal("3.000000"),
                new BigDecimal("4.000000"), new BigDecimal("0.200000"), new BigDecimal("0.500000"),
                Instant.now()));

            Optional<ModelPricing> stored = repository.findByModelName(modelName);
            assertThat(stored).isPresent();
            assertThat(stored.get().id())
                .as("model_name is the arbiter, so the row keeps the id it was created with")
                .isEqualTo(firstId);
            assertThat(stored.get().id())
                .as("the caller's suggested id is ignored on conflict").isNotEqualTo(secondId);
            assertThat(stored.get().promptPricePerMillion()).isEqualByComparingTo("3.000000");
            assertThat(stored.get().completionPricePerMillion()).isEqualByComparingTo("4.000000");
            assertThat(stored.get().cachedPricePerMillion()).isEqualByComparingTo("0.200000");
            assertThat(stored.get().thoughtPricePerMillion()).isEqualByComparingTo("0.500000");

            List<ModelPricing> sameName = repository.findAll().stream()
                .filter(p -> modelName.equals(p.modelName())).toList();
            assertThat(sameName)
                .as("one model name is one row — every cost query prices by name").hasSize(1);
        } finally {
            repository.deleteByModelName(modelName);
        }
    }

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
