package com.jumatech.jumarag.embedding;

import com.jumatech.jumarag.security.OpenApiConfiguration;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/admin/knowledge-bases/{knowledgeBaseId}/embeddings")
@SecurityRequirement(name = OpenApiConfiguration.ADMIN_SCHEME)
@ConditionalOnProperty(name = "app.runtime", havingValue = "api", matchIfMissing = true)
public final class EmbeddingBackfillApi {
    private final EmbeddingBackfillService service;

    public EmbeddingBackfillApi(EmbeddingBackfillService service) {
        this.service = service;
    }

    @PostMapping("/backfill")
    @Operation(
            summary = "Backfill an explicit embedding profile",
            description = "Adds missing vectors to immutable chunks without deleting or replacing vectors from other profiles. The requested profile must match the configured runtime provider.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Bounded synchronous batch completed"),
        @ApiResponse(responseCode = "401", description = "Missing or invalid administrator credential"),
        @ApiResponse(responseCode = "404", description = "Knowledge Base, snapshot, or profile not found"),
        @ApiResponse(responseCode = "409", description = "Requested profile is incompatible with runtime provider"),
        @ApiResponse(responseCode = "503", description = "Provider unavailable; completed vectors remain retryable")
    })
    EmbeddingBackfillService.BackfillResult backfill(
            @PathVariable UUID knowledgeBaseId, @Valid @RequestBody BackfillRequest request) {
        return service.backfill(
                knowledgeBaseId, request.embeddingProfile(), request.snapshotId(), request.batchSize());
    }

    public record BackfillRequest(
            @NotBlank @Size(max = 120) String embeddingProfile,
            UUID snapshotId,
            @Min(1) @Max(100) Integer batchSize) {}
}
