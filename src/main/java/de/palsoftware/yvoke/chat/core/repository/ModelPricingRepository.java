package de.palsoftware.yvoke.chat.core.repository;

import de.palsoftware.yvoke.chat.core.model.ModelPricing;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ModelPricingRepository {
    private final JdbcClient jdbcClient;

    public ModelPricingRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional(readOnly = true)
    public List<ModelPricing> findAll() {
        String sql =
            """
                SELECT id, model_name, prompt_price_per_million, completion_price_per_million, cached_price_per_million, thought_price_per_million, updated_at
                FROM chat_model_pricing
                ORDER BY model_name ASC
                """;
        return jdbcClient.sql(sql).query((rs, rowNum) -> {
            Timestamp updatedTs = rs.getTimestamp("updated_at");
            return new ModelPricing(rs.getObject("id", UUID.class), rs.getString("model_name"),
                rs.getBigDecimal("prompt_price_per_million"),
                rs.getBigDecimal("completion_price_per_million"),
                rs.getBigDecimal("cached_price_per_million"),
                rs.getBigDecimal("thought_price_per_million"),
                updatedTs != null ? updatedTs.toInstant() : null);
        }).list();
    }

    @Transactional(readOnly = true)
    public Optional<ModelPricing> findByModelName(String modelName) {
        String sql =
            """
                SELECT id, model_name, prompt_price_per_million, completion_price_per_million, cached_price_per_million, thought_price_per_million, updated_at
                FROM chat_model_pricing
                WHERE model_name = :modelName
                """;
        return jdbcClient.sql(sql).param("modelName", modelName).query((rs, rowNum) -> {
            Timestamp updatedTs = rs.getTimestamp("updated_at");
            return new ModelPricing(rs.getObject("id", UUID.class), rs.getString("model_name"),
                rs.getBigDecimal("prompt_price_per_million"),
                rs.getBigDecimal("completion_price_per_million"),
                rs.getBigDecimal("cached_price_per_million"),
                rs.getBigDecimal("thought_price_per_million"),
                updatedTs != null ? updatedTs.toInstant() : null);
        }).optional();
    }

    public void upsert(ModelPricing pricing) {
        String sql =
            """
                INSERT INTO chat_model_pricing (id, model_name, prompt_price_per_million, completion_price_per_million, cached_price_per_million, thought_price_per_million, updated_at)
                VALUES (:id, :modelName, :promptPrice, :completionPrice, :cachedPrice, :thoughtPrice, :updatedAt)
                ON CONFLICT (model_name) DO UPDATE SET
                    prompt_price_per_million = EXCLUDED.prompt_price_per_million,
                    completion_price_per_million = EXCLUDED.completion_price_per_million,
                    cached_price_per_million = EXCLUDED.cached_price_per_million,
                    thought_price_per_million = EXCLUDED.thought_price_per_million,
                    updated_at = EXCLUDED.updated_at
                """;

        jdbcClient.sql(sql).param("id", pricing.id()).param("modelName", pricing.modelName())
            .param("promptPrice", pricing.promptPricePerMillion())
            .param("completionPrice", pricing.completionPricePerMillion())
            .param("cachedPrice", pricing.cachedPricePerMillion())
            .param("thoughtPrice", pricing.thoughtPricePerMillion()).param("updatedAt",
                pricing.updatedAt() != null ? Timestamp.from(pricing.updatedAt()) : null)
            .update();
    }

    public void deleteByModelName(String modelName) {
        String sql = "DELETE FROM chat_model_pricing WHERE model_name = :modelName";
        jdbcClient.sql(sql).param("modelName", modelName).update();
    }
}
