package com.jumatech.jumarag.embedding;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.embedding.provider", havingValue = "fake", matchIfMissing = true)
@EnableConfigurationProperties(FakeEmbeddingGateway.EmbeddingProperties.class)
public final class FakeEmbeddingGateway implements EmbeddingGateway {
    private final int dimensions;

    public FakeEmbeddingGateway(EmbeddingProperties properties) {
        if (properties.dimensions() != 1536) {
            throw new IllegalArgumentException("Phase-0 embeddings must have 1536 dimensions.");
        }
        this.dimensions = properties.dimensions();
    }

    @Override
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Embedding text must not be blank.");
        }
        float[] vector = new float[dimensions];
        String normalized = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "");
        Arrays.stream(normalized.split("[^a-z0-9]+"))
                .filter(token -> !token.isBlank())
                .forEach(token -> addToken(vector, token));
        normalize(vector);
        return vector;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public String provider() {
        return "FAKE";
    }

    @Override
    public String model() {
        return "deterministic-token-hash-v1";
    }

    private void addToken(float[] vector, String token) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime.", exception);
        }
        for (int offset = 0; offset < 16; offset += 4) {
            int raw = ByteBuffer.wrap(digest, offset, 4).getInt();
            int index = Math.floorMod(raw, dimensions);
            vector[index] += (digest[16 + offset / 4] & 1) == 0 ? 1.0f : -1.0f;
        }
    }

    private static void normalize(float[] vector) {
        double squared = 0;
        for (float value : vector) {
            squared += value * value;
        }
        if (squared == 0) {
            throw new IllegalArgumentException("Embedding text contains no indexable tokens.");
        }
        float norm = (float) Math.sqrt(squared);
        for (int index = 0; index < vector.length; index++) {
            vector[index] /= norm;
        }
    }

    @ConfigurationProperties(prefix = "app.embedding")
    public record EmbeddingProperties(String provider, int dimensions) {}
}
