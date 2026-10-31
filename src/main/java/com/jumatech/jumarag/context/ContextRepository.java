package com.jumatech.jumarag.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jumatech.jumarag.retrieval.RetrievalHit;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ContextRepository {
    private static final String SELECT = """
            SELECT dc.id AS chunk_id, ks.id AS source_id, sv.id AS source_version_id,
                   ks.logical_name, ks.source_classification, sv.title AS source_title,
                   sv.original_filename, dc.chunk_index, dc.text, dc.language, dc.heading_path,
                   dc.page_start, dc.page_end, dc.metadata::text AS metadata,
                   ks.metadata::text AS source_metadata, sv.metadata::text AS source_version_metadata
            FROM document_chunk dc
            JOIN source_version sv ON sv.id = dc.source_version_id
            JOIN knowledge_source ks ON ks.id = sv.source_id
            JOIN knowledge_base kb ON kb.id = ks.knowledge_base_id
            JOIN knowledge_snapshot_item ksi
              ON ksi.source_version_id = sv.id AND ksi.knowledge_source_id = ks.id
            WHERE kb.id = :knowledgeBaseId AND ksi.snapshot_id = :snapshotId
              AND kb.status = 'ACTIVE' AND ks.enabled = true AND sv.status = 'READY'
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public ContextRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public SnapshotStats stats(UUID knowledgeBaseId, UUID snapshotId, String language) {
        String sql = """
                SELECT count(*) AS chunk_count, COALESCE(sum(length(dc.text)), 0) AS character_count
                FROM document_chunk dc
                JOIN source_version sv ON sv.id = dc.source_version_id
                JOIN knowledge_source ks ON ks.id = sv.source_id
                JOIN knowledge_base kb ON kb.id = ks.knowledge_base_id
                JOIN knowledge_snapshot_item ksi
                  ON ksi.source_version_id = sv.id AND ksi.knowledge_source_id = ks.id
                WHERE kb.id = :knowledgeBaseId AND ksi.snapshot_id = :snapshotId
                  AND kb.status = 'ACTIVE' AND ks.enabled = true AND sv.status = 'READY'
                """ + (language == null ? "" : " AND dc.language = :language");
        JdbcClient.StatementSpec statement = jdbc.sql(sql)
                .param("knowledgeBaseId", knowledgeBaseId).param("snapshotId", snapshotId);
        if (language != null) {
            statement = statement.param("language", language);
        }
        return statement.query((row, number) -> new SnapshotStats(
                row.getInt("chunk_count"), row.getInt("character_count"))).single();
    }

    public List<RetrievalHit> findSnapshotChunks(UUID knowledgeBaseId, UUID snapshotId, String language) {
        String sql = SELECT + (language == null ? "" : " AND dc.language = :language")
                + " ORDER BY ks.logical_name, ks.id, sv.id, dc.chunk_index, dc.id";
        JdbcClient.StatementSpec statement = jdbc.sql(sql)
                .param("knowledgeBaseId", knowledgeBaseId).param("snapshotId", snapshotId);
        if (language != null) {
            statement = statement.param("language", language);
        }
        return statement.query(this::map).list();
    }

    public List<RetrievalHit> findNeighbors(UUID knowledgeBaseId, UUID snapshotId, String language,
            List<RetrievalHit> anchors, int radius) {
        if (anchors.isEmpty() || radius == 0) {
            return List.of();
        }
        StringBuilder conditions = new StringBuilder(" AND (");
        for (int index = 0; index < anchors.size(); index++) {
            if (index > 0) {
                conditions.append(" OR ");
            }
            conditions.append("(dc.source_version_id = :version").append(index)
                    .append(" AND dc.chunk_index BETWEEN :from").append(index)
                    .append(" AND :to").append(index).append(')');
        }
        conditions.append(')');
        String sql = SELECT + (language == null ? "" : " AND dc.language = :language") + conditions
                + " ORDER BY ks.logical_name, ks.id, sv.id, dc.chunk_index, dc.id";
        JdbcClient.StatementSpec statement = jdbc.sql(sql)
                .param("knowledgeBaseId", knowledgeBaseId).param("snapshotId", snapshotId);
        if (language != null) {
            statement = statement.param("language", language);
        }
        for (int index = 0; index < anchors.size(); index++) {
            RetrievalHit anchor = anchors.get(index);
            statement = statement.param("version" + index, anchor.sourceVersionId())
                    .param("from" + index, Math.max(0, anchor.chunkIndex() - radius))
                    .param("to" + index, anchor.chunkIndex() + radius);
        }
        return statement.query(this::map).list();
    }

    private RetrievalHit map(ResultSet row, int rowNumber) throws SQLException {
        return new RetrievalHit(
                row.getObject("chunk_id", UUID.class), row.getObject("source_id", UUID.class),
                row.getObject("source_version_id", UUID.class), row.getString("logical_name"),
                row.getString("source_classification"), row.getString("source_title"),
                row.getString("original_filename"), row.getInt("chunk_index"), row.getString("text"),
                row.getString("language"), row.getString("heading_path"), (Integer) row.getObject("page_start"),
                (Integer) row.getObject("page_end"), json(row.getString("metadata")),
                json(row.getString("source_metadata")), json(row.getString("source_version_metadata")), 0.0);
    }

    private JsonNode json(String value) throws SQLException {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new SQLException("Stored context metadata is invalid JSON.", exception);
        }
    }

    public record SnapshotStats(int chunkCount, int characterCount) {
        public int estimatedTokens() {
            return ContextBudget.estimatedTokens(characterCount);
        }
    }
}
