package com.jumatech.jumarag.retrieval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jumatech.jumarag.embedding.EmbeddingRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RetrievalRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public RetrievalRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<RetrievalHit> search(
            UUID knowledgeBaseId,
            UUID snapshotId,
            UUID embeddingProfileId,
            float[] queryEmbedding,
            String language,
            UUID sourceId,
            int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT
                    dc.id AS chunk_id,
                    ks.id AS source_id,
                    sv.id AS source_version_id,
                    ks.logical_name,
                    ks.source_classification,
                    sv.title AS source_title,
                    sv.original_filename,
                    dc.chunk_index,
                    dc.text,
                    dc.language,
                    dc.heading_path,
                    dc.page_start,
                    dc.page_end,
                    dc.metadata::text AS metadata,
                    ks.metadata::text AS source_metadata,
                    sv.metadata::text AS source_version_metadata,
                    1 - (ce.embedding <=> CAST(:queryEmbedding AS vector)) AS similarity
                FROM chunk_embedding ce
                JOIN document_chunk dc ON dc.id = ce.chunk_id
                JOIN source_version sv ON sv.id = dc.source_version_id
                JOIN knowledge_source ks ON ks.id = sv.source_id
                JOIN knowledge_base kb ON kb.id = ks.knowledge_base_id
                JOIN knowledge_snapshot_item ksi
                  ON ksi.source_version_id = sv.id
                 AND ksi.knowledge_source_id = ks.id
                WHERE kb.id = :knowledgeBaseId
                  AND ksi.snapshot_id = :snapshotId
                  AND kb.status = 'ACTIVE'
                  AND ks.enabled = true
                  AND sv.status = 'READY'
                  AND ce.embedding_profile_id = :embeddingProfileId
                """);
        if (language != null) {
            sql.append(" AND dc.language = :language");
        }
        if (sourceId != null) {
            sql.append(" AND ks.id = :sourceId");
        }
        sql.append(" ORDER BY ce.embedding <=> CAST(:queryEmbedding AS vector), dc.id LIMIT :limit");

        JdbcClient.StatementSpec statement = jdbc.sql(sql.toString())
                .param("knowledgeBaseId", knowledgeBaseId)
                .param("snapshotId", snapshotId)
                .param("embeddingProfileId", embeddingProfileId)
                .param("queryEmbedding", EmbeddingRepository.vectorLiteral(queryEmbedding))
                .param("limit", limit);
        if (language != null) {
            statement = statement.param("language", language);
        }
        if (sourceId != null) {
            statement = statement.param("sourceId", sourceId);
        }
        return statement.query(this::map).list();
    }

    private RetrievalHit map(ResultSet row, int rowNumber) throws SQLException {
        return new RetrievalHit(
                row.getObject("chunk_id", UUID.class),
                row.getObject("source_id", UUID.class),
                row.getObject("source_version_id", UUID.class),
                row.getString("logical_name"),
                row.getString("source_classification"),
                row.getString("source_title"),
                row.getString("original_filename"),
                row.getInt("chunk_index"),
                row.getString("text"),
                row.getString("language"),
                row.getString("heading_path"),
                (Integer) row.getObject("page_start"),
                (Integer) row.getObject("page_end"),
                json(row.getString("metadata")),
                json(row.getString("source_metadata")),
                json(row.getString("source_version_metadata")),
                row.getDouble("similarity"));
    }

    private JsonNode json(String value) throws SQLException {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new SQLException("Stored chunk metadata is invalid JSON.", exception);
        }
    }
}
