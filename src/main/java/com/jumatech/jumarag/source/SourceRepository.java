package com.jumatech.jumarag.source;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SourceRepository {
    private final JdbcClient jdbc;

    public SourceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public KnowledgeSource insert(KnowledgeSource source) {
        jdbc.sql("""
                        INSERT INTO knowledge_source (
                            id, knowledge_base_id, source_type, logical_name, canonical_uri,
                            enabled, created_at, updated_at
                        ) VALUES (
                            :id, :knowledgeBaseId, :sourceType, :logicalName, :canonicalUri,
                            :enabled, :createdAt, :updatedAt
                        )
                        """)
                .param("id", source.id())
                .param("knowledgeBaseId", source.knowledgeBaseId())
                .param("sourceType", source.sourceType().name())
                .param("logicalName", source.logicalName())
                .param("canonicalUri", source.canonicalUri())
                .param("enabled", source.enabled())
                .param("createdAt", source.createdAt())
                .param("updatedAt", source.updatedAt())
                .update();
        return source;
    }

    public List<KnowledgeSource> findByKnowledgeBase(UUID knowledgeBaseId) {
        return jdbc.sql("""
                        SELECT * FROM knowledge_source
                        WHERE knowledge_base_id = :knowledgeBaseId
                        ORDER BY logical_name
                        """)
                .param("knowledgeBaseId", knowledgeBaseId)
                .query(SourceRepository::mapSource)
                .list();
    }

    public Optional<KnowledgeSource> findById(UUID id) {
        return jdbc.sql("SELECT * FROM knowledge_source WHERE id = :id")
                .param("id", id)
                .query(SourceRepository::mapSource)
                .optional();
    }

    public SourceVersion insertVersion(SourceVersion version) {
        jdbc.sql("""
                        INSERT INTO source_version (
                            id, source_id, version_label, checksum, media_type, byte_size,
                            effective_from, effective_to, status, created_at
                        ) VALUES (
                            :id, :sourceId, :versionLabel, :checksum, :mediaType, :byteSize,
                            :effectiveFrom, :effectiveTo, :status, :createdAt
                        )
                        """)
                .param("id", version.id())
                .param("sourceId", version.sourceId())
                .param("versionLabel", version.versionLabel())
                .param("checksum", version.checksum())
                .param("mediaType", version.mediaType())
                .param("byteSize", version.byteSize())
                .param("effectiveFrom", version.effectiveFrom())
                .param("effectiveTo", version.effectiveTo())
                .param("status", version.status().name())
                .param("createdAt", version.createdAt())
                .update();
        return version;
    }

    public Optional<SourceVersion> findVersionById(UUID id) {
        return jdbc.sql("SELECT * FROM source_version WHERE id = :id")
                .param("id", id)
                .query(SourceRepository::mapVersion)
                .optional();
    }

    private static KnowledgeSource mapSource(ResultSet row, int rowNumber) throws SQLException {
        return new KnowledgeSource(
                row.getObject("id", UUID.class),
                row.getObject("knowledge_base_id", UUID.class),
                KnowledgeSource.SourceType.valueOf(row.getString("source_type")),
                row.getString("logical_name"),
                row.getString("canonical_uri"),
                row.getBoolean("enabled"),
                row.getObject("created_at", OffsetDateTime.class),
                row.getObject("updated_at", OffsetDateTime.class));
    }

    private static SourceVersion mapVersion(ResultSet row, int rowNumber) throws SQLException {
        return new SourceVersion(
                row.getObject("id", UUID.class),
                row.getObject("source_id", UUID.class),
                row.getString("version_label"),
                row.getString("checksum"),
                row.getString("media_type"),
                row.getLong("byte_size"),
                row.getObject("effective_from", LocalDate.class),
                row.getObject("effective_to", LocalDate.class),
                SourceVersion.Status.valueOf(row.getString("status")),
                row.getObject("created_at", OffsetDateTime.class));
    }
}
