package com.jumatech.jumarag.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jumatech.jumarag.knowledgebase.KnowledgeBaseService;
import com.jumatech.jumarag.shared.web.DomainException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public final class ApiClientService {
    public static final String CLIENT_ID_ATTRIBUTE = "jumaragClientId";
    private static final String KEY_PREFIX = "jrag_";
    private static final int PUBLIC_ID_LENGTH = 16;
    private static final int SECRET_LENGTH = 43;
    private final ApiClientRepository repository;
    private final ApiKeyHasher hasher;
    private final KnowledgeBaseService knowledgeBases;
    private final ObjectMapper objectMapper;
    private final SecureRandom random;
    private final Clock clock;

    @Autowired
    public ApiClientService(
            ApiClientRepository repository,
            ApiKeyHasher hasher,
            KnowledgeBaseService knowledgeBases,
            ObjectMapper objectMapper) {
        this(repository, hasher, knowledgeBases, objectMapper, new SecureRandom(), Clock.systemUTC());
    }

    ApiClientService(
            ApiClientRepository repository,
            ApiKeyHasher hasher,
            KnowledgeBaseService knowledgeBases,
            ObjectMapper objectMapper,
            SecureRandom random,
            Clock clock) {
        this.repository = repository;
        this.hasher = hasher;
        this.knowledgeBases = knowledgeBases;
        this.objectMapper = objectMapper;
        this.random = random;
        this.clock = clock;
    }

    public CreatedApiClient create(String name, JsonNode metadata) {
        if (name == null || name.isBlank() || name.trim().length() > 200) {
            throw new DomainException("INVALID_API_CLIENT", HttpStatus.BAD_REQUEST, "Client name is required.");
        }
        if (metadata != null && !metadata.isObject()) {
            throw new DomainException(
                    "INVALID_API_CLIENT_METADATA", HttpStatus.BAD_REQUEST, "Client metadata must be a JSON object.");
        }
        String publicId = randomToken(12);
        String secret = randomToken(32);
        String credential = KEY_PREFIX + publicId + "_" + secret;
        OffsetDateTime now = OffsetDateTime.now(clock);
        ApiClient client = new ApiClient(
                UUID.randomUUID(),
                name.trim(),
                publicId,
                true,
                metadata == null ? objectMapper.createObjectNode() : metadata,
                now,
                null);
        try {
            repository.insert(client, hasher.hash(secret));
        } catch (DuplicateKeyException exception) {
            throw new DomainException(
                    "API_CLIENT_ALREADY_EXISTS", HttpStatus.CONFLICT, "An API client with that name already exists.");
        }
        return new CreatedApiClient(client, credential);
    }

    public List<ApiClient> list() {
        return repository.findAll();
    }

    public ApiClient disable(UUID id) {
        require(id);
        repository.disable(id, OffsetDateTime.now(clock));
        return require(id);
    }

    public KnowledgeBaseGrant grant(UUID clientId, UUID knowledgeBaseId) {
        requireEnabled(clientId);
        knowledgeBases.require(knowledgeBaseId);
        return repository.grant(new KnowledgeBaseGrant(
                clientId,
                knowledgeBaseId,
                KnowledgeBaseGrant.Permission.RETRIEVE,
                OffsetDateTime.now(clock)));
    }

    public List<KnowledgeBaseGrant> grants(UUID clientId) {
        require(clientId);
        return repository.findGrants(clientId);
    }

    public void removeGrant(UUID clientId, UUID knowledgeBaseId) {
        require(clientId);
        repository.removeGrant(clientId, knowledgeBaseId, KnowledgeBaseGrant.Permission.RETRIEVE);
    }

    public Optional<AuthenticatedClient> authenticate(String credential) {
        ParsedKey parsed = parse(credential);
        if (parsed == null) {
            return Optional.empty();
        }
        return repository.findAuthentication(parsed.publicId())
                .filter(ApiClientRepository.AuthenticationRecord::enabled)
                .filter(record -> hasher.matches(parsed.secret(), record.secretHash()))
                .map(record -> new AuthenticatedClient(record.clientId()));
    }

    public void requireRetrieveGrant(UUID clientId, UUID knowledgeBaseId) {
        requireEnabled(clientId);
        if (!repository.hasGrant(clientId, knowledgeBaseId, KnowledgeBaseGrant.Permission.RETRIEVE)) {
            throw new DomainException(
                    "KNOWLEDGE_BASE_ACCESS_FORBIDDEN",
                    HttpStatus.FORBIDDEN,
                    "The API client is not granted retrieval access to this knowledge base.");
        }
    }

    private ApiClient require(UUID id) {
        return repository.findById(id).orElseThrow(() -> new DomainException(
                "API_CLIENT_NOT_FOUND", HttpStatus.NOT_FOUND, "API client was not found."));
    }

    private ApiClient requireEnabled(UUID id) {
        ApiClient client = require(id);
        if (!client.enabled()) {
            throw new DomainException("API_CLIENT_DISABLED", HttpStatus.UNAUTHORIZED, "API client is disabled.");
        }
        return client;
    }

    private String randomToken(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    static ParsedKey parse(String value) {
        if (value == null || !value.startsWith(KEY_PREFIX)) {
            return null;
        }
        String payload = value.substring(KEY_PREFIX.length());
        if (payload.length() != PUBLIC_ID_LENGTH + 1 + SECRET_LENGTH
                || payload.charAt(PUBLIC_ID_LENGTH) != '_') {
            return null;
        }
        return new ParsedKey(
                payload.substring(0, PUBLIC_ID_LENGTH),
                payload.substring(PUBLIC_ID_LENGTH + 1));
    }

    public record CreatedApiClient(ApiClient client, String apiKey) {}

    public record AuthenticatedClient(UUID clientId) {}

    record ParsedKey(String publicId, String secret) {}
}
