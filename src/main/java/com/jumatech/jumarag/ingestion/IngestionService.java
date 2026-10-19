package com.jumatech.jumarag.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jumatech.jumarag.embedding.EmbeddingGateway;
import com.jumatech.jumarag.embedding.EmbeddingProfileResolver;
import com.jumatech.jumarag.embedding.EmbeddingRepository;
import com.jumatech.jumarag.knowledgebase.KnowledgeBaseService;
import com.jumatech.jumarag.provider.ProviderException;
import com.jumatech.jumarag.shared.web.DomainException;
import com.jumatech.jumarag.source.KnowledgeSource;
import com.jumatech.jumarag.source.SourceVersion;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public final class IngestionService {
    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);
    private static final Pattern CLASSIFICATION = Pattern.compile("^[A-Z][A-Z0-9_]{0,79}$");
    private final IngestionRepository repository;
    private final KnowledgeBaseService knowledgeBases;
    private final ContentNormalizer normalizer;
    private final ChunkingService chunking;
    private final EmbeddingGateway embeddingGateway;
    private final EmbeddingRepository embeddings;
    private final EmbeddingProfileResolver profiles;
    private final IngestionProperties properties;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final MeterRegistry metrics;
    private final Clock clock;

    @Autowired
    public IngestionService(
            IngestionRepository repository,
            KnowledgeBaseService knowledgeBases,
            ContentNormalizer normalizer,
            ChunkingService chunking,
            EmbeddingGateway embeddingGateway,
            EmbeddingRepository embeddings,
            EmbeddingProfileResolver profiles,
            IngestionProperties properties,
            ObjectMapper objectMapper,
            TransactionTemplate transactions,
            MeterRegistry metrics) {
        this(
                repository,
                knowledgeBases,
                normalizer,
                chunking,
                embeddingGateway,
                embeddings,
                profiles,
                properties,
                objectMapper,
                transactions,
                metrics,
                Clock.systemUTC());
    }

    IngestionService(
            IngestionRepository repository,
            KnowledgeBaseService knowledgeBases,
            ContentNormalizer normalizer,
            ChunkingService chunking,
            EmbeddingGateway embeddingGateway,
            EmbeddingRepository embeddings,
            EmbeddingProfileResolver profiles,
            IngestionProperties properties,
            ObjectMapper objectMapper,
            TransactionTemplate transactions,
            MeterRegistry metrics,
            Clock clock) {
        this.repository = repository;
        this.knowledgeBases = knowledgeBases;
        this.normalizer = normalizer;
        this.chunking = chunking;
        this.embeddingGateway = embeddingGateway;
        this.embeddings = embeddings;
        this.profiles = profiles;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.transactions = transactions;
        this.metrics = metrics;
        this.clock = clock;
    }

    public IngestionResult ingestText(
            UUID knowledgeBaseId,
            String logicalName,
            String classification,
            String language,
            String title,
            String content,
            JsonNode metadata) {
        return ingest(new IngestionInput(
                knowledgeBaseId,
                KnowledgeSource.SourceType.TEXT,
                logicalName,
                classification,
                language,
                title,
                content,
                "text/plain",
                null,
                metadata,
                false));
    }

    public IngestionResult ingestFile(
            UUID knowledgeBaseId,
            String logicalName,
            String classification,
            String language,
            String title,
            JsonNode metadata,
            MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new DomainException("EMPTY_SOURCE_FILE", HttpStatus.BAD_REQUEST, "A non-empty file is required.");
        }
        if (file.getSize() > properties.maxUploadBytes()) {
            throw new DomainException("SOURCE_FILE_TOO_LARGE", HttpStatus.PAYLOAD_TOO_LARGE, "Source file exceeds the configured limit.");
        }
        String filename = safeFilename(file.getOriginalFilename());
        boolean markdown = filename.toLowerCase(Locale.ROOT).endsWith(".md");
        boolean text = filename.toLowerCase(Locale.ROOT).endsWith(".txt");
        if (!markdown && !text) {
            throw new DomainException(
                    "UNSUPPORTED_SOURCE_FILE", HttpStatus.UNPROCESSABLE_ENTITY, "Only UTF-8 .txt and .md files are supported.");
        }
        String contentType = file.getContentType();
        if (contentType != null
                && !contentType.equals("text/plain")
                && !contentType.equals("text/markdown")
                && !contentType.equals("application/octet-stream")) {
            throw new DomainException(
                    "UNSUPPORTED_SOURCE_MEDIA_TYPE",
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "File media type is not supported.");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException exception) {
            throw new DomainException("SOURCE_FILE_READ_FAILED", HttpStatus.UNPROCESSABLE_ENTITY, "Source file could not be read.");
        }
        return ingest(new IngestionInput(
                knowledgeBaseId,
                KnowledgeSource.SourceType.FILE,
                logicalName,
                classification,
                language,
                title,
                decodeUtf8(bytes),
                markdown ? "text/markdown" : "text/plain",
                filename,
                metadata,
                markdown));
    }

    public JsonNode parseMetadata(String value) {
        if (value == null || value.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode parsed = objectMapper.readTree(value);
            if (!parsed.isObject()) {
                throw new IllegalArgumentException();
            }
            return parsed;
        } catch (Exception exception) {
            throw new DomainException("INVALID_SOURCE_METADATA", HttpStatus.BAD_REQUEST, "Metadata must be a JSON object.");
        }
    }

    private IngestionResult ingest(IngestionInput input) {
        validate(input);
        var normalized = normalizer.normalize(input.content());
        if (normalized.text().isBlank()) {
            throw new DomainException("EMPTY_SOURCE_CONTENT", HttpStatus.BAD_REQUEST, "Normalized source content is empty.");
        }
        if (normalized.byteSize() > properties.maxUploadBytes()) {
            throw new DomainException("SOURCE_CONTENT_TOO_LARGE", HttpStatus.PAYLOAD_TOO_LARGE, "Source content exceeds the configured limit.");
        }
        String locale = Locale.forLanguageTag(input.language()).toLanguageTag();
        EmbeddingRepository.EmbeddingProfile profile = profiles.requireCompatible(properties.defaultEmbeddingProfile());
        PreparedIngestion prepared = transactions.execute(status -> prepare(input, normalized, locale));
        if (prepared == null) {
            throw new IllegalStateException("Ingestion preparation did not return a result.");
        }
        if (!prepared.process()) {
            return result(prepared.sourceId(), prepared.version(), true, profile);
        }

        Timer.Sample sample = Timer.start(metrics);
        try {
            List<ChunkingService.GeneratedChunk> generated = chunking.chunk(normalized.text(), input.markdown());
            if (generated.isEmpty()) {
                throw new IllegalStateException("Chunking produced no content.");
            }
            transactions.executeWithoutResult(status -> process(prepared.version().id(), locale, generated, profile));
            metrics.counter("jumarag.ingestion.completed", "result", "success").increment();
            metrics.summary("jumarag.ingestion.chunks").record(generated.size());
            log.info(
                    "Ingestion ready knowledgeBaseId={} sourceId={} sourceVersionId={} chunks={}",
                    input.knowledgeBaseId(),
                    prepared.sourceId(),
                    prepared.version().id(),
                    generated.size());
            return result(
                    prepared.sourceId(),
                    repository.requireVersion(prepared.version().id()),
                    prepared.duplicate(),
                    profile);
        } catch (RuntimeException exception) {
            transactions.executeWithoutResult(status -> repository.markFailed(
                    prepared.version().id(), "PROCESSING_ERROR", OffsetDateTime.now(clock)));
            metrics.counter("jumarag.ingestion.completed", "result", "failure").increment();
            log.warn(
                    "Ingestion failed knowledgeBaseId={} sourceId={} sourceVersionId={} reason={}",
                    input.knowledgeBaseId(),
                    prepared.sourceId(),
                    prepared.version().id(),
                    exception.getClass().getSimpleName());
            if (exception instanceof ProviderException) {
                throw new DomainException(
                        "AI_PROVIDER_UNAVAILABLE",
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Embedding provider is temporarily unavailable; source content remains safely retryable.");
            }
            throw new DomainException(
                    "SOURCE_INGESTION_FAILED", HttpStatus.UNPROCESSABLE_ENTITY,
                    "Source ingestion failed without publishing partial content.");
        } finally {
            sample.stop(metrics.timer("jumarag.ingestion.duration", "sourceType", input.type().name()));
        }
    }

    private PreparedIngestion prepare(
            IngestionInput input, ContentNormalizer.NormalizedContent normalized, String locale) {
        knowledgeBases.require(input.knowledgeBaseId());
        OffsetDateTime now = OffsetDateTime.now(clock);
        JsonNode metadata = input.metadata() == null ? objectMapper.createObjectNode() : input.metadata();
        IngestionRepository.SourceIdentity source = repository.resolveSource(
                input.knowledgeBaseId(),
                input.type(),
                input.logicalName().trim(),
                input.classification(),
                metadata,
                now);
        if (source.type() != input.type() || !source.classification().equals(input.classification())) {
            throw new DomainException(
                    "KNOWLEDGE_SOURCE_IDENTITY_CONFLICT",
                    HttpStatus.CONFLICT,
                    "Logical source already exists with a different type or classification.");
        }
        IngestionRepository.RegisteredVersion registered = repository.registerVersionIfAbsent(
                source.id(),
                normalized.checksum(),
                input.mediaType(),
                normalized.byteSize(),
                input.title() == null || input.title().isBlank() ? input.logicalName().trim() : input.title().trim(),
                locale,
                normalized.text(),
                input.originalFilename(),
                metadata,
                now);
        boolean process = registered.inserted();
        boolean duplicate = !registered.inserted();
        if (!process && registered.version().status() == SourceVersion.Status.FAILED) {
            process = repository.claimFailed(registered.version().id());
        }
        return new PreparedIngestion(source.id(), registered.version(), process, duplicate);
    }

    private void process(
            UUID versionId,
            String language,
            List<ChunkingService.GeneratedChunk> chunks,
            EmbeddingRepository.EmbeddingProfile profile) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        for (ChunkingService.GeneratedChunk chunk : chunks) {
            UUID chunkId = UUID.randomUUID();
            repository.insertChunk(chunkId, versionId, chunk, language, now);
            embeddings.upsert(chunkId, profile.id(), embeddingGateway.embed(chunk.text()), now);
        }
        IngestionRepository.Counts counts = repository.counts(versionId, profile.id());
        if (counts.chunks() != chunks.size() || counts.embeddings() != chunks.size()) {
            throw new IllegalStateException("Chunk and embedding counts do not match the ingestion plan.");
        }
        repository.markReady(versionId, now);
    }

    private IngestionResult result(
            UUID sourceId,
            IngestionRepository.VersionState version,
            boolean duplicate,
            EmbeddingRepository.EmbeddingProfile profile) {
        IngestionRepository.Counts counts = repository.counts(version.id(), profile.id());
        return new IngestionResult(
                sourceId,
                version.id(),
                version.checksum(),
                version.status(),
                duplicate,
                counts.chunks(),
                counts.embeddings(),
                profile.name());
    }

    private static void validate(IngestionInput input) {
        if (input.logicalName() == null || input.logicalName().isBlank() || input.logicalName().length() > 240) {
            throw new DomainException("INVALID_SOURCE_NAME", HttpStatus.BAD_REQUEST, "Logical source name is invalid.");
        }
        if (input.classification() == null || !CLASSIFICATION.matcher(input.classification()).matches()) {
            throw new DomainException(
                    "INVALID_SOURCE_CLASSIFICATION",
                    HttpStatus.BAD_REQUEST,
                    "Source classification must be an uppercase stable identifier.");
        }
        if (input.title() != null && input.title().length() > 500) {
            throw new DomainException("INVALID_SOURCE_TITLE", HttpStatus.BAD_REQUEST, "Source title is invalid.");
        }
        KnowledgeBaseService.validateLocale(input.language());
        if (input.metadata() != null && !input.metadata().isObject()) {
            throw new DomainException("INVALID_SOURCE_METADATA", HttpStatus.BAD_REQUEST, "Metadata must be a JSON object.");
        }
    }

    private static String safeFilename(String supplied) {
        if (supplied == null || supplied.isBlank()) {
            throw new DomainException("INVALID_SOURCE_FILENAME", HttpStatus.BAD_REQUEST, "Source filename is required.");
        }
        String normalized = supplied.replace('\\', '/');
        String basename = normalized.substring(normalized.lastIndexOf('/') + 1)
                .replaceAll("[\\p{Cntrl}]", "")
                .strip();
        if (basename.isBlank() || basename.length() > 255) {
            throw new DomainException("INVALID_SOURCE_FILENAME", HttpStatus.BAD_REQUEST, "Source filename is invalid.");
        }
        return basename;
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new DomainException(
                    "SOURCE_FILE_NOT_UTF8", HttpStatus.UNPROCESSABLE_ENTITY, "Source file must contain valid UTF-8 text.");
        }
    }

    private record IngestionInput(
            UUID knowledgeBaseId,
            KnowledgeSource.SourceType type,
            String logicalName,
            String classification,
            String language,
            String title,
            String content,
            String mediaType,
            String originalFilename,
            JsonNode metadata,
            boolean markdown) {}

    private record PreparedIngestion(
            UUID sourceId,
            IngestionRepository.VersionState version,
            boolean process,
            boolean duplicate) {}

    public record IngestionResult(
            UUID knowledgeSourceId,
            UUID sourceVersionId,
            String checksum,
            SourceVersion.Status status,
            boolean duplicate,
            int chunkCount,
            int embeddingCount,
            String embeddingProfile) {}
}
