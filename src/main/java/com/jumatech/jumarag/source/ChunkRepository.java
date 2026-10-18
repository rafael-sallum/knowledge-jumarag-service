package com.jumatech.jumarag.source;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ChunkRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public ChunkRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public DocumentChunk insert(DocumentChunk chunk) {
        jdbc.sql("""
                        INSERT INTO document_chunk (
                            id, source_version_id, chunk_index, text, language, heading_path,
                            page_start, page_end, metadata, created_at
                        ) VALUES (
                            :id, :sourceVersionId, :chunkIndex, :text, :language, :headingPath,
                            :pageStart, :pageEnd, CAST(:metadata AS jsonb), :createdAt
                        )
                        """)
                .param("id", chunk.id())
                .param("sourceVersionId", chunk.sourceVersionId())
                .param("chunkIndex", chunk.chunkIndex())
                .param("text", chunk.text())
                .param("language", chunk.language())
                .param("headingPath", chunk.headingPath())
                .param("pageStart", chunk.pageStart())
                .param("pageEnd", chunk.pageEnd())
                .param("metadata", chunk.metadata().toString())
                .param("createdAt", chunk.createdAt())
                .update();
        return chunk;
    }

    public Optional<DocumentChunk> findById(UUID id) {
        return jdbc.sql("SELECT * FROM document_chunk WHERE id = :id")
                .param("id", id)
                .query(this::map)
                .optional();
    }

    private DocumentChunk map(ResultSet row, int rowNumber) throws SQLException {
        return new DocumentChunk(
                row.getObject("id", UUID.class),
                row.getObject("source_version_id", UUID.class),
                row.getInt("chunk_index"),
                row.getString("text"),
                row.getString("language"),
                row.getString("heading_path"),
                (Integer) row.getObject("page_start"),
                (Integer) row.getObject("page_end"),
                json(row.getString("metadata")),
                row.getObject("created_at", OffsetDateTime.class));
    }

    private JsonNode json(String value) throws SQLException {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new SQLException("Stored chunk metadata is invalid JSON.", exception);
        }
    }
}
