package com.jumatech.jumarag.embedding;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class EmbeddingRepository {
    public static final UUID PHASE_ZERO_PROFILE_ID = UUID.fromString("00000000-0000-4000-8000-000000001536");
    private static final int PHASE_ZERO_DIMENSIONS = 1536;
    private final JdbcClient jdbc;

    public EmbeddingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void upsert(UUID chunkId, UUID profileId, float[] vector, OffsetDateTime createdAt) {
        validate(vector);
        jdbc.sql("""
                        INSERT INTO chunk_embedding (chunk_id, embedding_profile_id, embedding, created_at)
                        VALUES (:chunkId, :profileId, CAST(:embedding AS vector), :createdAt)
                        ON CONFLICT (chunk_id, embedding_profile_id)
                        DO UPDATE SET embedding = EXCLUDED.embedding, created_at = EXCLUDED.created_at
                        """)
                .param("chunkId", chunkId)
                .param("profileId", profileId)
                .param("embedding", vectorLiteral(vector))
                .param("createdAt", createdAt)
                .update();
    }

    public boolean exists(UUID chunkId, UUID profileId) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM chunk_embedding
                            WHERE chunk_id = :chunkId AND embedding_profile_id = :profileId
                        )
                        """)
                .param("chunkId", chunkId)
                .param("profileId", profileId)
                .query(Boolean.class)
                .single();
    }

    public Optional<EmbeddingProfile> findProfileByName(String name) {
        return jdbc.sql("""
                        SELECT id, name, provider, model, dimensions, distance_metric
                        FROM embedding_profile WHERE name = :name
                        """)
                .param("name", name)
                .query((row, number) -> new EmbeddingProfile(
                        row.getObject("id", UUID.class),
                        row.getString("name"),
                        row.getString("provider"),
                        row.getString("model"),
                        row.getInt("dimensions"),
                        row.getString("distance_metric")))
                .optional();
    }

    public static String vectorLiteral(float[] vector) {
        validate(vector);
        StringBuilder literal = new StringBuilder(PHASE_ZERO_DIMENSIONS * 10).append('[');
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) {
                literal.append(',');
            }
            literal.append(Float.toString(vector[index]));
        }
        return literal.append(']').toString();
    }

    static void validate(float[] vector) {
        if (vector == null || vector.length != PHASE_ZERO_DIMENSIONS) {
            throw new IllegalArgumentException("Phase-0 vector must have exactly 1536 dimensions.");
        }
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("Vector values must be finite.");
            }
        }
    }

    public record EmbeddingProfile(
            UUID id, String name, String provider, String model, int dimensions, String distanceMetric) {}
}
