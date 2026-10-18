package com.jumatech.jumarag.source;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/source-versions")
@ConditionalOnProperty(name = "app.runtime", havingValue = "api", matchIfMissing = true)
public final class ChunkApi {
    private final ChunkService service;

    public ChunkApi(ChunkService service) {
        this.service = service;
    }

    @PostMapping("/{versionId}/chunks")
    ResponseEntity<DocumentChunk> create(
            @PathVariable UUID versionId,
            @Valid @RequestBody CreateChunk request) {
        DocumentChunk created = service.create(
                versionId,
                request.chunkIndex(),
                request.text(),
                request.language(),
                request.headingPath(),
                request.pageStart(),
                request.pageEnd(),
                request.metadata());
        return ResponseEntity.created(URI.create("/v1/chunks/" + created.id())).body(created);
    }

    public record CreateChunk(
            @PositiveOrZero int chunkIndex,
            @NotBlank @Size(max = 100_000) String text,
            @NotBlank @Size(max = 35) String language,
            @Size(max = 2000) String headingPath,
            @Positive Integer pageStart,
            @Positive Integer pageEnd,
            JsonNode metadata) {}
}
