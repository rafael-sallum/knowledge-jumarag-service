package com.jumatech.jumarag.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jumatech.jumarag.knowledgebase.KnowledgeBaseService;
import com.jumatech.jumarag.shared.web.DomainException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public final class SnapshotService {
    private final SnapshotRepository repository;
    private final KnowledgeBaseService knowledgeBases;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Autowired
    public SnapshotService(SnapshotRepository repository, KnowledgeBaseService knowledgeBases,
            ObjectMapper objectMapper, TransactionTemplate transactions) {
        this(repository, knowledgeBases, objectMapper, transactions, Clock.systemUTC());
    }

    SnapshotService(SnapshotRepository repository, KnowledgeBaseService knowledgeBases,
            ObjectMapper objectMapper, TransactionTemplate transactions, Clock clock) {
        this.repository = repository;
        this.knowledgeBases = knowledgeBases;
        this.objectMapper = objectMapper;
        this.transactions = transactions;
        this.clock = clock;
    }

    public KnowledgeSnapshot create(UUID knowledgeBaseId, String name,
            List<UUID> sourceVersionIds, JsonNode metadata) {
        knowledgeBases.require(knowledgeBaseId);
        if (name == null || name.isBlank() || name.strip().length() > 200) {
            throw new DomainException("INVALID_SNAPSHOT_NAME", HttpStatus.BAD_REQUEST, "Snapshot name is invalid.");
        }
        if (sourceVersionIds == null || sourceVersionIds.isEmpty()) {
            throw new DomainException("EMPTY_SNAPSHOT", HttpStatus.BAD_REQUEST,
                    "A snapshot must contain at least one source version.");
        }
        if (new HashSet<>(sourceVersionIds).size() != sourceVersionIds.size()) {
            throw new DomainException("DUPLICATE_SNAPSHOT_VERSION", HttpStatus.CONFLICT,
                    "A source version may be included only once in a snapshot.");
        }
        if (metadata != null && !metadata.isObject()) {
            throw new DomainException("INVALID_SNAPSHOT_METADATA", HttpStatus.BAD_REQUEST,
                    "Metadata must be a JSON object.");
        }
        List<SnapshotRepository.VersionCandidate> versions = repository.findVersionCandidates(sourceVersionIds);
        if (versions.size() != sourceVersionIds.size()) {
            throw new DomainException("SOURCE_VERSION_NOT_FOUND", HttpStatus.NOT_FOUND,
                    "A source version was not found.");
        }
        validateCandidates(knowledgeBaseId, versions);
        if (versions.stream().map(SnapshotRepository.VersionCandidate::sourceId).distinct().count() != versions.size()) {
            throw new DomainException("DUPLICATE_LOGICAL_SOURCE", HttpStatus.CONFLICT,
                    "A snapshot may include at most one version of each logical source.");
        }

        UUID snapshotId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(clock);
        JsonNode safeMetadata = metadata == null ? objectMapper.createObjectNode() : metadata;
        try {
            transactions.executeWithoutResult(status -> {
                repository.insertSnapshot(snapshotId, knowledgeBaseId, name.strip(), safeMetadata, now);
                for (SnapshotRepository.VersionCandidate version : versions) {
                    repository.insertItem(snapshotId, knowledgeBaseId, version.sourceId(), version.versionId(), now);
                }
                repository.seal(snapshotId, now);
            });
        } catch (DataIntegrityViolationException exception) {
            throw new DomainException("SNAPSHOT_ALREADY_EXISTS", HttpStatus.CONFLICT,
                    "Snapshot name or contents conflict with existing data.");
        }
        return require(knowledgeBaseId, snapshotId);
    }

    public KnowledgeSnapshot activate(UUID knowledgeBaseId, UUID snapshotId) {
        knowledgeBases.require(knowledgeBaseId);
        return transactions.execute(status -> {
            repository.lockKnowledgeBase(knowledgeBaseId);
            KnowledgeSnapshot snapshot = require(knowledgeBaseId, snapshotId);
            validateCandidates(knowledgeBaseId, repository.findActivationCandidates(snapshotId));
            repository.activate(knowledgeBaseId, snapshotId, OffsetDateTime.now(clock));
            return require(knowledgeBaseId, snapshotId);
        });
    }

    public List<KnowledgeSnapshot> list(UUID knowledgeBaseId) {
        knowledgeBases.require(knowledgeBaseId);
        return repository.findByKnowledgeBase(knowledgeBaseId);
    }

    public KnowledgeSnapshot require(UUID knowledgeBaseId, UUID snapshotId) {
        KnowledgeSnapshot snapshot = repository.findById(snapshotId).orElseThrow(() -> new DomainException(
                "KNOWLEDGE_SNAPSHOT_NOT_FOUND", HttpStatus.NOT_FOUND, "Knowledge snapshot was not found."));
        if (!snapshot.knowledgeBaseId().equals(knowledgeBaseId)) {
            throw new DomainException("SNAPSHOT_OUTSIDE_KNOWLEDGE_BASE", HttpStatus.BAD_REQUEST,
                    "Snapshot does not belong to the selected knowledge base.");
        }
        return snapshot;
    }

    public KnowledgeSnapshot resolveForClient(UUID knowledgeBaseId, UUID requestedSnapshotId) {
        if (requestedSnapshotId == null) {
            return repository.findActive(knowledgeBaseId).orElseThrow(() -> new DomainException(
                    "ACTIVE_SNAPSHOT_REQUIRED", HttpStatus.CONFLICT,
                    "The knowledge base has no active snapshot."));
        }
        KnowledgeSnapshot snapshot = require(knowledgeBaseId, requestedSnapshotId);
        if (snapshot.status() == KnowledgeSnapshot.Status.DRAFT) {
            throw new DomainException("SNAPSHOT_NOT_PUBLISHED", HttpStatus.CONFLICT,
                    "Draft snapshots are not available to API clients.");
        }
        return snapshot;
    }

    private static void validateCandidates(UUID knowledgeBaseId,
            List<SnapshotRepository.VersionCandidate> versions) {
        if (versions.isEmpty()) {
            throw new DomainException("EMPTY_SNAPSHOT", HttpStatus.BAD_REQUEST,
                    "A snapshot must contain source versions.");
        }
        for (SnapshotRepository.VersionCandidate version : versions) {
            if (!version.knowledgeBaseId().equals(knowledgeBaseId)) {
                throw new DomainException("SOURCE_VERSION_OUTSIDE_KNOWLEDGE_BASE", HttpStatus.BAD_REQUEST,
                        "All source versions must belong to the selected knowledge base.");
            }
            if (!version.sourceEnabled()) {
                throw new DomainException("SNAPSHOT_SOURCE_DISABLED", HttpStatus.CONFLICT,
                        "Disabled sources cannot be included in an active snapshot.");
            }
            if (!"READY".equals(version.versionStatus())) {
                throw new DomainException("SNAPSHOT_VERSION_NOT_READY", HttpStatus.CONFLICT,
                        "Only READY source versions may be included in a snapshot.");
            }
        }
    }
}
