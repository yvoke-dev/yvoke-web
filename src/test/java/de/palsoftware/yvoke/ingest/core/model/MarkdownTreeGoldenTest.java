package de.palsoftware.yvoke.ingest.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownTreeGoldenTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void javaChunkerMatchesPythonGoldenOutput() throws Exception {
        String markdown = readResource("/manuals/sample_manual.md");
        JsonNode golden = MAPPER.readTree(readResource("/manuals/sample_manual.golden.json"));

        ParsedMarkdown parsed = MarkdownTree.parse(markdown);
        List<Section> sections = MarkdownTree.buildOrderedSections(parsed);

        // H1 title is recorded, not emitted.
        assertThat(parsed.titleH1()).isEqualTo(golden.get("title_h1").asText());

        JsonNode goldenSections = golden.get("sections");
        assertThat(sections).hasSize(goldenSections.size());

        for (int i = 0; i < sections.size(); i++) {
            Section actual = sections.get(i);
            JsonNode expected = goldenSections.get(i);

            assertThat(actual.depth()).as("depth at index %d", i)
                .isEqualTo(expected.get("depth").asInt());
            assertThat(actual.title()).as("title at index %d", i)
                .isEqualTo(expected.get("title").asText());
            assertThat(actual.headingPath()).as("heading_path at index %d", i)
                .isEqualTo(toList(expected.get("heading_path")));
            assertThat(actual.toChunkText()).as("chunk_text at index %d", i)
                .isEqualTo(expected.get("chunk_text").asText());
        }
    }

    @Test
    void everyChunkBodyStaysWithinTheCap() {
        List<Section> sections =
            MarkdownTree.buildOrderedSections(readResource("/manuals/sample_manual.md"));
        for (Section s : sections) {
            assertThat(s.body().length()).as("body length for %s", s.title())
                .isLessThanOrEqualTo(MarkdownTree.CHUNK_BODY_MAX_CHARS);
        }
    }

    private static List<String> toList(JsonNode arrayNode) {
        List<String> out = new ArrayList<>();
        arrayNode.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static String readResource(String path) {
        try (InputStream in = MarkdownTreeGoldenTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing test resource: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read resource: " + path, e);
        }
    }
}
