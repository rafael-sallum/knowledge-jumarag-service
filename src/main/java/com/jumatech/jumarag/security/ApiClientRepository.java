package com.jumatech.jumarag.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ApiClientRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public ApiClientRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public ApiClient insert(ApiClient client, byte[] secretHash) {
        jdbc.sql("""
                        INSERT INTO api_client (
                            id, name, key_prefix, secret_hash, enabled, metadata, created_at, disabled_at
                        ) VALUES (
                            :id, :name, :keyPrefix, :secretHash, :enabled,
                            CAST(:metadata AS jsonb), :createdAt, :disabledAt
                        )
                        """)
                .param("id", client.id())
                .param("name", client.name())
                .param("keyPrefix", client.keyPrefix())
                .param("secretHash", secretHash)
                .param("enabled", client.enabled())
                .param("metadata", client.metadata().toString())
                .param("createdAt", client.createdAt())
                .param("disabledAt", client.disabledAt())
                .update();
        return client;
    }

    public List<ApiClient> findAll() {
        return jdbc.sql("SELECT * FROM api_client ORDER BY created_at, id")
                .query(this::map)
                .list();
    }

    public Optional<ApiClient> findById(UUID id) {
        return jdbc.sql("SELECT * FROM api_client WHERE id = :id")
                .param("id", id)
                .query(this::map)
                .optional();
    }

    public Optional<AuthenticationRecord> findAuthentication(String keyPrefix) {
        return jdbc.sql("""
                        SELECT id, key_prefix, secret_hash, enabled
                        FROM api_client
                        WHERE key_prefix = :keyPrefix
                        """)
                .param("keyPrefix", keyPrefix)
                .query((row, number) -> new AuthenticationRecord(
                        row.getObject("id", UUID.class),
                        row.getString("key_prefix"),
                        row.getBytes("secret_hash"),
                        row.getBoolean("enabled")))
                .optional();
    }

    public boolean disable(UUID id, OffsetDateTime disabledAt) {
        return jdbc.sql("""
                        UPDATE api_client
                        SET enabled = false, disabled_at = :disabledAt
                        WHERE id = :id AND enabled = true
                        """)
                        .param("id", id)
                        .param("disabledAt", disabledAt)
                        .update()
                == 1;
    }

    public KnowledgeBaseGrant grant(KnowledgeBaseGrant grant) {
        jdbc.sql("""
                        INSERT INTO knowledge_base_grant (
                            api_client_id, knowledge_base_id, permission, created_at
                        ) VALUES (:clientId, :knowledgeBaseId, :permission, :createdAt)
                        ON CONFLICT (api_client_id, knowledge_base_id, permission) DO NOTHING
                        """)
                .param("clientId", grant.apiClientId())
                .param("knowledgeBaseId", grant.knowledgeBaseId())
                .param("permission", grant.permission().name())
                .param("createdAt", grant.createdAt())
                .update();
        return findGrant(grant.apiClientId(), grant.knowledgeBaseId(), grant.permission()).orElseThrow();
    }

    public List<KnowledgeBaseGrant> findGrants(UUID clientId) {
        return jdbc.sql("""
                        SELECT * FROM knowledge_base_grant
                        WHERE api_client_id = :clientId
                        ORDER BY knowledge_base_id, permission
                        """)
                .param("clientId", clientId)
                .query(ApiClientRepository::mapGrant)
                .list();
    }

    public Optional<KnowledgeBaseGrant> findGrant(
            UUID clientId, UUID knowledgeBaseId, KnowledgeBaseGrant.Permission permission) {
        return jdbc.sql("""
                        SELECT * FROM knowledge_base_grant
                        WHERE api_client_id = :clientId
                          AND knowledge_base_id = :knowledgeBaseId
                          AND permission = :permission
                        """)
                .param("clientId", clientId)
                .param("knowledgeBaseId", knowledgeBaseId)
                .param("permission", permission.name())
                .query(ApiClientRepository::mapGrant)
                .optional();
    }

    public boolean hasGrant(UUID clientId, UUID knowledgeBaseId, KnowledgeBaseGrant.Permission permission) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM knowledge_base_grant
                            WHERE api_client_id = :clientId
                              AND knowledge_base_id = :knowledgeBaseId
                              AND permission = :permission
                        )
                        """)
                .param("clientId", clientId)
                .param("knowledgeBaseId", knowledgeBaseId)
                .param("permission", permission.name())
                .query(Boolean.class)
                .single();
    }

    public boolean removeGrant(UUID clientId, UUID knowledgeBaseId, KnowledgeBaseGrant.Permission permission) {
        return jdbc.sql("""
                        DELETE FROM knowledge_base_grant
                        WHERE api_client_id = :clientId
                          AND knowledge_base_id = :knowledgeBaseId
                          AND permission = :permission
                        """)
                        .param("clientId", clientId)
                        .param("knowledgeBaseId", knowledgeBaseId)
                        .param("permission", permission.name())
                        .update()
                == 1;
    }

    private ApiClient map(ResultSet row, int rowNumber) throws SQLException {
        return new ApiClient(
                row.getObject("id", UUID.class),
                row.getString("name"),
                row.getString("key_prefix"),
                row.getBoolean("enabled"),
                json(row.getString("metadata")),
                row.getObject("created_at", OffsetDateTime.class),
                row.getObject("disabled_at", OffsetDateTime.class));
    }

    private static KnowledgeBaseGrant mapGrant(ResultSet row, int rowNumber) throws SQLException {
        return new KnowledgeBaseGrant(
                row.getObject("api_client_id", UUID.class),
                row.getObject("knowledge_base_id", UUID.class),
                KnowledgeBaseGrant.Permission.valueOf(row.getString("permission")),
                row.getObject("created_at", OffsetDateTime.class));
    }

    private JsonNode json(String value) throws SQLException {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new SQLException("Stored API-client metadata is invalid JSON.", exception);
        }
    }

    public record AuthenticationRecord(UUID clientId, String keyPrefix, byte[] secretHash, boolean enabled) {}
}
