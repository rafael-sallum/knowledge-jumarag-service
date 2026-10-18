package com.jumatech.jumarag.knowledgebase;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class KnowledgeBaseRepository {
    private final JdbcClient jdbc;

    public KnowledgeBaseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public KnowledgeBase insert(KnowledgeBase knowledgeBase) {
        jdbc.sql("""
                        INSERT INTO knowledge_base (
                            id, slug, name, description, default_locale, status, created_at, updated_at
                        ) VALUES (
                            :id, :slug, :name, :description, :defaultLocale, :status, :createdAt, :updatedAt
                        )
                        """)
                .param("id", knowledgeBase.id())
                .param("slug", knowledgeBase.slug())
                .param("name", knowledgeBase.name())
                .param("description", knowledgeBase.description())
                .param("defaultLocale", knowledgeBase.defaultLocale())
                .param("status", knowledgeBase.status().name())
                .param("createdAt", knowledgeBase.createdAt())
                .param("updatedAt", knowledgeBase.updatedAt())
                .update();
        return knowledgeBase;
    }

    public List<KnowledgeBase> findAll() {
        return jdbc.sql("SELECT * FROM knowledge_base ORDER BY slug")
                .query(KnowledgeBaseRepository::map)
                .list();
    }

    public Optional<KnowledgeBase> findById(UUID id) {
        return jdbc.sql("SELECT * FROM knowledge_base WHERE id = :id")
                .param("id", id)
                .query(KnowledgeBaseRepository::map)
                .optional();
    }

    public Optional<KnowledgeBase> findBySlug(String slug) {
        return jdbc.sql("SELECT * FROM knowledge_base WHERE slug = :slug")
                .param("slug", slug)
                .query(KnowledgeBaseRepository::map)
                .optional();
    }

    private static KnowledgeBase map(ResultSet row, int rowNumber) throws SQLException {
        return new KnowledgeBase(
                row.getObject("id", UUID.class),
                row.getString("slug"),
                row.getString("name"),
                row.getString("description"),
                row.getString("default_locale"),
                KnowledgeBase.Status.valueOf(row.getString("status")),
                row.getObject("created_at", OffsetDateTime.class),
                row.getObject("updated_at", OffsetDateTime.class));
    }
}
