package de.palsoftware.yvoke.chat.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "app.pricing")
public class PricingProperties {
    private Map<String, ModelPrice> models = new HashMap<>();

    public Map<String, ModelPrice> getModels() {
        return models;
    }

    public void setModels(Map<String, ModelPrice> models) {
        this.models = models;
    }

    public static class ModelPrice {
        private BigDecimal prompt = BigDecimal.ZERO;
        private BigDecimal completion = BigDecimal.ZERO;
        private BigDecimal cached = BigDecimal.ZERO;
        private BigDecimal thought = BigDecimal.ZERO;

        public BigDecimal getPrompt() {
            return prompt;
        }

        public void setPrompt(BigDecimal prompt) {
            this.prompt = prompt;
        }

        public BigDecimal getCompletion() {
            return completion;
        }

        public void setCompletion(BigDecimal completion) {
            this.completion = completion;
        }

        public BigDecimal getCached() {
            return cached;
        }

        public void setCached(BigDecimal cached) {
            this.cached = cached;
        }

        public BigDecimal getThought() {
            return thought;
        }

        public void setThought(BigDecimal thought) {
            this.thought = thought;
        }
    }
}
