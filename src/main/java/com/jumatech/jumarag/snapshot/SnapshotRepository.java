package com.jumatech.jumarag.snapshot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SnapshotRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public SnapshotRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void lockKnowledgeBase(UUID knowledgeBaseId) {
        jdbc.sql("SELECT id FROM knowledge_base WHERE id = :id FOR UPDATE")
                .param("id", knowledgeBaseId)
                .query(UUID.class)
                .single();
    }

    public void insertSnapshot(UUID id, UUID knowledgeBaseId, String name, JsonNode metadata, OffsetDateTime createdAt) {
        jdbc.sql("""
                        INSERT INTO knowledge_snapshot (
                            id, knowledge_base_id, name, status, metadata, created_at, sealed_at, activated_at
                        ) VALUES (
                            :id, :knowledgeBaseId, :name, 'DRAFT', CAST(:metadata AS jsonb), :createdAt, NULL, NULL
                        )
                        """)
                .param("id", id)
                .param("knowledgeBaseId", knowledgeBaseId)
                .param("name", name)
                .param("metadata", metadata.toString())
                .param("createdAt", createdAt)
                .update();
    }

    public void seal(UUID snapshotId, OffsetDateTime sealedAt) {
        jdbc.sql("""
                        UPDATE knowledge_snapshot
                        SET sealed_at = :sealedAt
                        WHERE id = :snapshotId AND sealed_at IS NULL
                        """)
                .param("snapshotId", snapshotId)
                .param("sealedAt", sealedAt)
                .update();
    }

    public void insertItem(UUID snapshotId, UUID knowledgeBaseId, UUID sourceId, UUID sourceVersionId, OffsetDateTime createdAt) {
        jdbc.sql("""
                        INSERT INTO knowledge_snapshot_item (
                            snapshot_id, knowledge_base_id, knowledge_source_id, source_version_id, created_at
                        ) VALUES (
                            :snapshotId, :knowledgeBaseId, :sourceId, :sourceVersionId, :createdAt
                        )
                        """)
                .param("snapshotId", snapshotId)
                .param("knowledgeBaseId", knowledgeBaseId)
                .param("sourceId", sourceId)
                .param("sourceVersionId", sourceVersionId)
                .param("createdAt", createdAt)
                .update();
    }

    public List<VersionCandidate> findVersionCandidates(Collection<UUID> versionIds) {
        if (versionIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT
                            sv.id AS source_version_id, sv.source_id, ks.knowledge_base_id,
                            ks.logical_name, ks.source_classification, ks.enabled,
                            sv.status, sv.title, sv.language
                        FROM source_version sv
                        JOIN knowledge_source ks ON ks.id = sv.source_id
                        WHERE sv.id IN (:versionIds)
                        ORDER BY ks.id, sv.id
                        """)
                .param("versionIds", versionIds)
                .query(SnapshotRepository::mapCandidate)
                .list();
    }

    public Optional<KnowledgeSnapshot> findById(UUID snapshotId) {
        return jdbc.sql("SELECT * FROM knowledge_snapshot WHERE id = :id")
                .param("id", snapshotId)
                .query(this::mapSnapshot)
                .optional()
                .map(this::withItems);
    }

    public Optional<KnowledgeSnapshot> findActive(UUID knowledgeBaseId) {
        return jdbc.sql("""
                        SELECT * FROM knowledge_snapshot
                        WHERE knowledge_base_id = :knowledgeBaseId AND status = 'ACTIVE'
                        """)
                .param("knowledgeBaseId", knowledgeBaseId)
                .query(this::mapSnapshot)
                .optional()
                .map(this::withItems);
    }

    public List<KnowledgeSnapshot> findByKnowledgeBase(UUID knowledgeBaseId) {
        return jdbc.sql("""
                        SELECT * FROM knowledge_snapshot
                        WHERE knowledge_base_id = :knowledgeBaseId
                        ORDER BY created_at DESC, id
                        """)
                .param("knowledgeBaseId", knowledgeBaseId)
                .query(this::mapSnapshot)
                .list()
                .stream()
                .map(this::withItems)
                .toList();
    }

    public List<VersionCandidate> findActivationCandidates(UUID snapshotId) {
        return jdbc.sql("""
                        SELECT
                            sv.id AS source_version_id, sv.source_id, ksi.knowledge_base_id,
                            ks.logical_name, ks.source_classification, ks.enabled,
                            sv.status, sv.title, sv.language
                        FROM knowledge_snapshot_item ksi
                        JOIN knowledge_source ks ON ks.id = ksi.knowledge_source_id
                        JOIN source_version sv ON sv.id = ksi.source_version_id
                        WHERE ksi.snapshot_id = :snapshotId
                        ORDER BY ks.id
                        """)
                .param("snapshotId", snapshotId)
                .query(SnapshotRepository::mapCandidate)
                .list();
    }

    public void activate(UUID knowledgeBaseId, UUID snapshotId, OffsetDateTime activatedAt) {
        jdbc.sql("""
                        UPDATE knowledge_snapshot
                        SET status = 'RETIRED'
                        WHERE knowledge_base_id = :knowledgeBaseId
                          AND status = 'ACTIVE'
                          AND id <> :snapshotId
                        """)
                .param("knowledgeBaseId", knowledgeBaseId)
                .param("snapshotId", snapshotId)
                .update();
        jdbc.sql("""
                        UPDATE knowledge_snapshot
                        SET status = 'ACTIVE', activated_at = :activatedAt
                        WHERE id = :snapshotId AND knowledge_base_id = :knowledgeBaseId
                        """)
                .param("snapshotId", snapshotId)
                .param("knowledgeBaseId", knowledgeBaseId)
                .param("activatedAt", activatedAt)
                .update();
    }

    private KnowledgeSnapshot withItems(KnowledgeSnapshot snapshot) {
        List<KnowledgeSnapshot.Item> items = jdbc.sql("""
                        SELECT ksi.knowledge_source_id, ksi.source_version_id,
                               ks.logical_name, ks.source_classification, sv.title, sv.language
                        FROM knowledge_snapshot_item ksi
                        JOIN knowledge_source ks ON ks.id = ksi.knowledge_source_id
                        JOIN source_version sv ON sv.id = ksi.source_version_id
                        WHERE ksi.snapshot_id = :snapshotId
                        ORDER BY ks.logical_name, ks.id
                        """)
                .param("snapshotId", snapshot.id())
                .query((row, number) -> new KnowledgeSnapshot.Item(
                        row.getObject("knowledge_source_id", UUID.class),
                        row.getObject("source_version_id", UUID.class),
                        row.getString("logical_name"),
                        row.getString("source_classification"),
                        row.getString("title"),
                        row.getString("language")))
                .list();
        return new KnowledgeSnapshot(
                snapshot.id(), snapshot.knowledgeBaseId(), snapshot.name(), snapshot.status(),
                snapshot.metadata(), snapshot.createdAt(), snapshot.activatedAt(), items);
    }

    private KnowledgeSnapshot mapSnapshot(ResultSet row, int rowNumber) throws SQLException {
        return new KnowledgeSnapshot(
                row.getObject("id", UUID.class),
                row.getObject("knowledge_base_id", UUID.class),
                row.getString("name"),
                KnowledgeSnapshot.Status.valueOf(row.getString("status")),
                json(row.getString("metadata")),
                row.getObject("created_at", OffsetDateTime.class),
                row.getObject("activated_at", OffsetDateTime.class),
                List.of());
    }

    private static VersionCandidate mapCandidate(ResultSet row, int number) throws SQLException {
        return new VersionCandidate(
                row.getObject("source_version_id", UUID.class),
                row.getObject("source_id", UUID.class),
                row.getObject("knowledge_base_id", UUID.class),
                row.getString("logical_name"),
                row.getString("source_classification"),
                row.getBoolean("enabled"),
                row.getString("status"),
                row.getString("title"),
                row.getString("language"));
    }

    private JsonNode json(String value) throws SQLException {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new SQLException("Stored snapshot metadata is invalid JSON.", exception);
        }
    }

    public record VersionCandidate(
            UUID versionId, UUID sourceId, UUID knowledgeBaseId, String logicalName,
            String sourceClassification, boolean sourceEnabled, String versionStatus,
            String title, String language) {}
}
