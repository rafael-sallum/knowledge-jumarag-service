package com.jumatech.jumarag.knowledgebase;

import com.jumatech.jumarag.shared.web.DomainException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public final class KnowledgeBaseService {
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private final KnowledgeBaseRepository repository;
    private final Clock clock;

    @Autowired
    public KnowledgeBaseService(KnowledgeBaseRepository repository) {
        this(repository, Clock.systemUTC());
    }

    KnowledgeBaseService(KnowledgeBaseRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public KnowledgeBase create(String slug, String name, String description, String defaultLocale) {
        validateSlug(slug);
        validateLocale(defaultLocale);
        OffsetDateTime now = OffsetDateTime.now(clock);
        KnowledgeBase knowledgeBase = new KnowledgeBase(
                UUID.randomUUID(),
                slug,
                name.trim(),
                description.trim(),
                defaultLocale,
                KnowledgeBase.Status.ACTIVE,
                now,
                now);
        try {
            return repository.insert(knowledgeBase);
        } catch (DuplicateKeyException exception) {
            throw new DomainException(
                    "KNOWLEDGE_BASE_ALREADY_EXISTS", HttpStatus.CONFLICT, "Knowledge-base slug already exists.");
        }
    }

    public List<KnowledgeBase> list() {
        return repository.findAll();
    }

    public KnowledgeBase get(String idOrSlug) {
        try {
            return repository.findById(UUID.fromString(idOrSlug)).orElseThrow(this::notFound);
        } catch (IllegalArgumentException ignored) {
            return repository.findBySlug(idOrSlug).orElseThrow(this::notFound);
        }
    }

    public KnowledgeBase require(UUID id) {
        return repository.findById(id).orElseThrow(this::notFound);
    }

    public KnowledgeBase requireBySlug(String slug) {
        return repository.findBySlug(slug).orElseThrow(this::notFound);
    }

    static void validateSlug(String slug) {
        if (slug == null || !SLUG.matcher(slug).matches()) {
            throw new DomainException(
                    "INVALID_KNOWLEDGE_BASE_SLUG",
                    HttpStatus.BAD_REQUEST,
                    "Slug must contain lowercase letters, digits, and single hyphens.");
        }
    }

    public static void validateLocale(String locale) {
        if (locale == null || locale.isBlank() || "und".equals(Locale.forLanguageTag(locale).getLanguage())) {
            throw new DomainException("INVALID_LOCALE", HttpStatus.BAD_REQUEST, "A valid BCP 47 locale is required.");
        }
    }

    private DomainException notFound() {
        return new DomainException(
                "KNOWLEDGE_BASE_NOT_FOUND", HttpStatus.NOT_FOUND, "Knowledge base was not found.");
    }
}
