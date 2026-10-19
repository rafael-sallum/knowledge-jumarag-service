package com.jumatech.jumarag.embedding;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class EmbeddingBackfillRepository {
    private final JdbcClient jdbc;

    public EmbeddingBackfillRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void lock(UUID knowledgeBaseId, UUID profileId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(:knowledgeBaseKey, :profileKey)")
                .param("knowledgeBaseKey", knowledgeBaseId.hashCode())
                .param("profileKey", profileId.hashCode())
                .query((row, number) -> true)
                .single();
    }

    public Counts counts(UUID knowledgeBaseId, UUID snapshotId, UUID profileId) {
        String snapshotFilter = snapshotId == null ? "" : """
                 AND EXISTS (
                     SELECT 1 FROM knowledge_snapshot_item ksi
                     WHERE ksi.snapshot_id = :snapshotId
                       AND ksi.source_version_id = sv.id
                       AND ksi.knowledge_source_id = ks.id
                 )
                """;
        JdbcClient.StatementSpec statement = jdbc.sql("""
                SELECT
                    COUNT(*) AS eligible,
                    COUNT(ce.chunk_id) AS embedded
                FROM document_chunk dc
                JOIN source_version sv ON sv.id = dc.source_version_id
                JOIN knowledge_source ks ON ks.id = sv.source_id
                LEFT JOIN chunk_embedding ce
                  ON ce.chunk_id = dc.id
                 AND ce.embedding_profile_id = :profileId
                WHERE ks.knowledge_base_id = :knowledgeBaseId
                  AND ks.enabled = true
                  AND sv.status = 'READY'
                """ + snapshotFilter)
                .param("knowledgeBaseId", knowledgeBaseId)
                .param("profileId", profileId);
        if (snapshotId != null) {
            statement = statement.param("snapshotId", snapshotId);
        }
        return statement.query((row, number) -> new Counts(row.getInt("eligible"), row.getInt("embedded"))).single();
    }

    public List<ChunkForEmbedding> findMissing(
            UUID knowledgeBaseId, UUID snapshotId, UUID profileId, int limit) {
        String snapshotFilter = snapshotId == null ? "" : """
                 AND EXISTS (
                     SELECT 1 FROM knowledge_snapshot_item ksi
                     WHERE ksi.snapshot_id = :snapshotId
                       AND ksi.source_version_id = sv.id
                       AND ksi.knowledge_source_id = ks.id
                 )
                """;
        JdbcClient.StatementSpec statement = jdbc.sql("""
                SELECT dc.id, dc.text
                FROM document_chunk dc
                JOIN source_version sv ON sv.id = dc.source_version_id
                JOIN knowledge_source ks ON ks.id = sv.source_id
                WHERE ks.knowledge_base_id = :knowledgeBaseId
                  AND ks.enabled = true
                  AND sv.status = 'READY'
                  AND NOT EXISTS (
                      SELECT 1 FROM chunk_embedding ce
                      WHERE ce.chunk_id = dc.id
                        AND ce.embedding_profile_id = :profileId
                  )
                """ + snapshotFilter + " ORDER BY dc.id LIMIT :limit")
                .param("knowledgeBaseId", knowledgeBaseId)
                .param("profileId", profileId)
                .param("limit", limit);
        if (snapshotId != null) {
            statement = statement.param("snapshotId", snapshotId);
        }
        return statement.query((row, number) -> new ChunkForEmbedding(
                row.getObject("id", UUID.class), row.getString("text"))).list();
    }

    public record Counts(int eligible, int embedded) {}

    public record ChunkForEmbedding(UUID id, String text) {}
}
