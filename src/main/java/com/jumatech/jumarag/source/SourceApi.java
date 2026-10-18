package com.jumatech.jumarag.source;

import com.jumatech.jumarag.security.OpenApiConfiguration;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/admin")
@SecurityRequirement(name = OpenApiConfiguration.ADMIN_SCHEME)
@ConditionalOnProperty(name = "app.runtime", havingValue = "api", matchIfMissing = true)
public final class SourceApi {
    private final SourceService service;

    public SourceApi(SourceService service) {
        this.service = service;
    }

    @PostMapping("/knowledge-bases/{knowledgeBaseId}/sources")
    ResponseEntity<KnowledgeSource> createSource(
            @PathVariable UUID knowledgeBaseId,
            @Valid @RequestBody CreateSource request) {
        KnowledgeSource created = service.create(
                knowledgeBaseId, request.sourceType(), request.logicalName(), request.canonicalUri());
        return ResponseEntity.created(URI.create("/v1/admin/sources/" + created.id())).body(created);
    }

    @GetMapping("/knowledge-bases/{knowledgeBaseId}/sources")
    List<KnowledgeSource> listSources(@PathVariable UUID knowledgeBaseId) {
        return service.list(knowledgeBaseId);
    }

    @PostMapping("/sources/{sourceId}/versions")
    ResponseEntity<SourceVersion> createVersion(
            @PathVariable UUID sourceId,
            @Valid @RequestBody CreateVersion request) {
        SourceVersion created = service.createVersion(
                sourceId,
                request.versionLabel(),
                request.checksum(),
                request.mediaType(),
                request.byteSize(),
                request.effectiveFrom(),
                request.effectiveTo());
        return ResponseEntity.created(URI.create("/v1/admin/source-versions/" + created.id())).body(created);
    }

    public record CreateSource(
            @NotNull KnowledgeSource.SourceType sourceType,
            @NotBlank @Size(max = 240) String logicalName,
            @Size(max = 4000) String canonicalUri) {}

    public record CreateVersion(
            @Size(max = 120) String versionLabel,
            @NotBlank @Size(min = 64, max = 64) String checksum,
            @NotBlank @Size(max = 160) String mediaType,
            @PositiveOrZero long byteSize,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {}
}
