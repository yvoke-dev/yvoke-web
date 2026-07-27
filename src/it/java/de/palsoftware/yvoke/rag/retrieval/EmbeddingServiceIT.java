package de.palsoftware.yvoke.rag.retrieval;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class EmbeddingServiceIT {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingServiceIT.class);

    private static final String KNOWN_TEXT = "Yvoke";
    private static final int EXPECTED_DIMENSION = 1024;
    private static final double COSINE_THRESHOLD = 0.95;

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private Environment environment;

    @Test
    public void testEmbeddingServiceIntegration() throws Exception {
        // Retrieve API key to verify if it is real
        String apiKey = environment.getProperty("app.ai.embedding.api-key");
        boolean isRealKey = apiKey != null && !apiKey.isBlank() && !apiKey.equals("mock-key-for-skeleton") && !apiKey.equals("placeholder-voyage-api-key");

        Assumptions.assumeTrue(isRealKey,
                "Skipping Voyage AI embedding integration test: no real VOYAGE_API_KEY configured.");

        // Embed single text
        float[] vector = embeddingService.embed(KNOWN_TEXT);
        assertThat(vector).isNotNull();
        assertThat(vector.length).isEqualTo(EXPECTED_DIMENSION);

        // Load reference vector from test resources
        float[] referenceVector = loadReferenceVector();
        assertThat(referenceVector.length).isEqualTo(EXPECTED_DIMENSION);

        // Calculate cosine similarity
        double similarity = calculateCosineSimilarity(vector, referenceVector);
        log.info("Calculated cosine similarity: {}", similarity);
        
        assertThat(similarity)
            .withFailMessage("Cosine similarity %.4f is below threshold %.2f", similarity, COSINE_THRESHOLD)
            .isGreaterThanOrEqualTo(COSINE_THRESHOLD);

        // Embed batch text
        List<float[]> batchVectors = embeddingService.embedBatch(List.of(KNOWN_TEXT, "Another piece of documentation"));
        assertThat(batchVectors).hasSize(2);
        assertThat(batchVectors.get(0).length).isEqualTo(EXPECTED_DIMENSION);
        assertThat(batchVectors.get(1).length).isEqualTo(EXPECTED_DIMENSION);
    }

    private float[] loadReferenceVector() throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("reference_vector.txt")) {
            if (is == null) {
                throw new IllegalStateException("reference_vector.txt not found in classpath");
            }
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            String[] tokens = content.split(",");
            float[] vector = new float[tokens.length];
            for (int i = 0; i < tokens.length; i++) {
                vector[i] = Float.parseFloat(tokens[i].trim());
            }
            return vector;
        }
    }

    private double calculateCosineSimilarity(float[] vectorA, float[] vectorB) {
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < vectorA.length; i++) {
            dotProduct += vectorA[i] * vectorB[i];
            normA += Math.pow(vectorA[i], 2);
            normB += Math.pow(vectorB[i], 2);
        }
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
