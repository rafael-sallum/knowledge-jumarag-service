package com.jumatech.jumarag.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.jumatech.jumarag.source.KnowledgeSource;
import com.jumatech.jumarag.source.SourceVersion;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class IngestionRepository {
    private final JdbcClient jdbc;

    public IngestionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public SourceIdentity resolveSource(
            UUID knowledgeBaseId,
            KnowledgeSource.SourceType type,
            String logicalName,
            String classification,
            JsonNode metadata,
            OffsetDateTime now) {
        UUID candidateId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO knowledge_source (
                            id, knowledge_base_id, source_type, logical_name, canonical_uri,
                            enabled, source_classification, metadata, created_at, updated_at
                        ) VALUES (
                            :id, :knowledgeBaseId, :sourceType, :logicalName, NULL,
                            true, :classification, CAST(:metadata AS jsonb), :createdAt, :updatedAt
                        )
                        ON CONFLICT (knowledge_base_id, logical_name) DO NOTHING
                        """)
                .param("id", candidateId)
                .param("knowledgeBaseId", knowledgeBaseId)
                .param("sourceType", type.name())
                .param("logicalName", logicalName)
                .param("classification", classification)
                .param("metadata", metadata.toString())
                .param("createdAt", now)
                .param("updatedAt", now)
                .update();
        return jdbc.sql("""
                        SELECT id, source_type, source_classification
                        FROM knowledge_source
                        WHERE knowledge_base_id = :knowledgeBaseId AND logical_name = :logicalName
                        """)
                .param("knowledgeBaseId", knowledgeBaseId)
                .param("logicalName", logicalName)
                .query((row, number) -> new SourceIdentity(
                        row.getObject("id", UUID.class),
                        KnowledgeSource.SourceType.valueOf(row.getString("source_type")),
                        row.getString("source_classification")))
                .single();
    }

    public RegisteredVersion registerVersionIfAbsent(
            UUID sourceId,
            String checksum,
            String mediaType,
            long byteSize,
            String title,
            String language,
            String normalizedContent,
            String originalFilename,
            JsonNode metadata,
            OffsetDateTime now) {
        UUID candidateId = UUID.randomUUID();
        int inserted = jdbc.sql("""
                        INSERT INTO source_version (
                            id, source_id, version_label, checksum, media_type, byte_size,
                            effective_from, effective_to, status, created_at,
                            title, language, normalized_content, original_filename, metadata,
                            failure_code, processed_at
                        ) VALUES (
                            :id, :sourceId, NULL, :checksum, :mediaType, :byteSize,
                            NULL, NULL, 'PROCESSING', :createdAt,
                            :title, :language, :normalizedContent, :originalFilename, CAST(:metadata AS jsonb),
                            NULL, NULL
                        )
                        ON CONFLICT (source_id, checksum) DO NOTHING
                        """)
                .param("id", candidateId)
                .param("sourceId", sourceId)
                .param("checksum", checksum)
                .param("mediaType", mediaType)
                .param("byteSize", byteSize)
                .param("createdAt", now)
                .param("title", title)
                .param("language", language)
                .param("normalizedContent", normalizedContent)
                .param("originalFilename", originalFilename)
                .param("metadata", metadata.toString())
                .update();
        VersionState state = findVersion(sourceId, checksum).orElseThrow();
        return new RegisteredVersion(state, inserted == 1);
    }

    public Optional<VersionState> findVersion(UUID sourceId, String checksum) {
        return jdbc.sql("""
                        SELECT id, source_id, checksum, status, created_at, processed_at
                        FROM source_version
                        WHERE source_id = :sourceId AND checksum = :checksum
                        """)
                .param("sourceId", sourceId)
                .param("checksum", checksum)
                .query(IngestionRepository::mapVersion)
                .optional();
    }

    public VersionState requireVersion(UUID versionId) {
        return jdbc.sql("""
                        SELECT id, source_id, checksum, status, created_at, processed_at
                        FROM source_version WHERE id = :id
                        """)
                .param("id", versionId)
                .query(IngestionRepository::mapVersion)
                .single();
    }

    public boolean claimFailed(UUID versionId) {
        return jdbc.sql("""
                        UPDATE source_version
                        SET status = 'PROCESSING', failure_code = NULL, processed_at = NULL
                        WHERE id = :id AND status = 'FAILED'
                        """)
                        .param("id", versionId)
                        .update()
                == 1;
    }

    public void insertChunk(
            UUID id,
            UUID versionId,
            ChunkingService.GeneratedChunk chunk,
            String language,
            OffsetDateTime createdAt) {
        jdbc.sql("""
                        INSERT INTO document_chunk (
                            id, source_version_id, chunk_index, text, language, heading_path,
                            page_start, page_end, metadata, created_at, checksum
                        ) VALUES (
                            :id, :versionId, :chunkIndex, :text, :language, :headingPath,
                            NULL, NULL, CAST(:metadata AS jsonb), :createdAt, :checksum
                        )
                        """)
                .param("id", id)
                .param("versionId", versionId)
                .param("chunkIndex", chunk.index())
                .param("text", chunk.text())
                .param("language", language)
                .param("headingPath", chunk.headingPath())
                .param("metadata", chunk.metadata().toString())
                .param("createdAt", createdAt)
                .param("checksum", chunk.checksum())
                .update();
    }

    public void markReady(UUID versionId, OffsetDateTime processedAt) {
        jdbc.sql("""
                        UPDATE source_version
                        SET status = 'READY', processed_at = :processedAt, failure_code = NULL
                        WHERE id = :id AND status = 'PROCESSING'
                        """)
                .param("id", versionId)
                .param("processedAt", processedAt)
                .update();
    }

    public void markFailed(UUID versionId, String failureCode, OffsetDateTime processedAt) {
        jdbc.sql("""
                        UPDATE source_version
                        SET status = 'FAILED', failure_code = :failureCode, processed_at = :processedAt
                        WHERE id = :id AND status = 'PROCESSING'
                        """)
                .param("id", versionId)
                .param("failureCode", failureCode)
                .param("processedAt", processedAt)
                .update();
    }

    public Counts counts(UUID versionId) {
        return jdbc.sql("""
                        SELECT
                            COUNT(DISTINCT dc.id) AS chunks,
                            COUNT(ce.chunk_id) AS embeddings
                        FROM source_version sv
                        LEFT JOIN document_chunk dc ON dc.source_version_id = sv.id
                        LEFT JOIN chunk_embedding ce ON ce.chunk_id = dc.id
                        WHERE sv.id = :versionId
                        GROUP BY sv.id
                        """)
                .param("versionId", versionId)
                .query((row, number) -> new Counts(row.getInt("chunks"), row.getInt("embeddings")))
                .single();
    }

    private static VersionState mapVersion(ResultSet row, int rowNumber) throws SQLException {
        return new VersionState(
                row.getObject("id", UUID.class),
                row.getObject("source_id", UUID.class),
                row.getString("checksum"),
                SourceVersion.Status.valueOf(row.getString("status")),
                row.getObject("created_at", OffsetDateTime.class),
                row.getObject("processed_at", OffsetDateTime.class));
    }

    public record SourceIdentity(UUID id, KnowledgeSource.SourceType type, String classification) {}

    public record RegisteredVersion(VersionState version, boolean inserted) {}

    public record VersionState(
            UUID id,
            UUID sourceId,
            String checksum,
            SourceVersion.Status status,
            OffsetDateTime createdAt,
            OffsetDateTime processedAt) {}

    public record Counts(int chunks, int embeddings) {}
}
