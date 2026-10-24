package de.palsoftware.yvoke.chat.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ModelLabelsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ModelLabels parse(String json) {
        return ModelLabels.parse(MAPPER, json);
    }

    @Test
    void blankOrEmptyJsonProducesEmptyTableAndFallsBackToRawModelName() {
        for (String empty : new String[] {null, "", "   ", "{}"}) {
            ModelLabels labels = parse(empty);
            assertThat(labels.byModel()).isEmpty();
            assertThat(labels.labelFor("gemini-3.8-flash")).isEqualTo("gemini-3.8-flash");
            assertThat(labels.labelFor(null)).isNull();
            assertThat(labels.labelFor("  ")).isEqualTo("  ");
        }
    }

    @Test
    void resolvesConfiguredLabelsCaseInsensitivelyAndFallsBackWhenUnmapped() {
        ModelLabels labels = parse("""
            {
              "gemini-3.8-flash": "Gemini - 3.8 Flash",
              "deepseek/deepseek-v4.1-flash-1017": "OpenRouter - DeepSeek 4.1 Flash",
              "DeepSeek-V4-Flash": "Azure - DeepSeek V4 Flash"
            }
            """);

        assertThat(labels.labelFor("gemini-3.8-flash")).isEqualTo("Gemini - 3.8 Flash");
        assertThat(labels.labelFor("GEMINI-3.8-FLASH")).isEqualTo("Gemini - 3.8 Flash");
        assertThat(labels.labelFor("deepseek/deepseek-v4.1-flash-1017"))
            .isEqualTo("OpenRouter - DeepSeek 4.1 Flash");
        assertThat(labels.labelFor("deepseek-v4-flash")).isEqualTo("Azure - DeepSeek V4 Flash");
        assertThat(labels.labelFor("unmapped-model")).isEqualTo("unmapped-model");
    }

    @Test
    void formatCsvTranslatesEachCommaSeparatedModelAndPreservesUnmappedEntries() {
        ModelLabels labels = parse("""
            {
              "gemini-3.8-flash": "Gemini - 3.8 Flash",
              "gpt-5.4-mini": "Azure - GPT 5.4 Mini"
            }
            """);

        assertThat(labels.formatCsv("gemini-3.8-flash, gpt-5.4-mini, unknown-model"))
            .isEqualTo("Gemini - 3.8 Flash, Azure - GPT 5.4 Mini, unknown-model");
        assertThat(labels.formatCsv("gemini-3.8-flash")).isEqualTo("Gemini - 3.8 Flash");
        assertThat(labels.formatCsv(null)).isNull();
        assertThat(labels.formatCsv("")).isEmpty();
    }

    @Test
    void invalidJsonOrMalformedEntriesAreRejectedAtStartup() {
        assertThatThrownBy(() -> parse("{not-json")).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("app.chat.model-labels is not valid JSON");

        assertThatThrownBy(() -> parse("[\"gemini-3.8-flash\"]"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("JSON object");

        assertThatThrownBy(() -> parse("{\"   \": \"Gemini\"}"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("blank model");

        assertThatThrownBy(() -> parse("{\"gemini-3.8-flash\": 123}"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("gemini-3.8-flash");

        assertThatThrownBy(() -> parse("{\"gemini-3.8-flash\": \"   \"}"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("blank label");

        assertThatThrownBy(
            () -> parse("{\"gemini-3.8-flash\": \"A\", \"GEMINI-3.8-FLASH\": \"B\"}"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("twice");
    }
}
