package com.jumatech.jumarag.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import com.jumatech.jumarag.security.OpenApiConfiguration;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.net.URI;
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
@RequestMapping("/v1/admin/knowledge-bases/{knowledgeBaseId}/snapshots")
@SecurityRequirement(name = OpenApiConfiguration.ADMIN_SCHEME)
@ConditionalOnProperty(name = "app.runtime", havingValue = "api", matchIfMissing = true)
public final class SnapshotApi {
    private final SnapshotService service;

    public SnapshotApi(SnapshotService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Create an immutable knowledge snapshot")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Immutable DRAFT snapshot created"),
        @ApiResponse(responseCode = "401", description = "Missing or invalid administrator credential"),
        @ApiResponse(responseCode = "404", description = "Knowledge Base or source version not found"),
        @ApiResponse(responseCode = "409", description = "Duplicate source, name, or non-ready source version")
    })
    ResponseEntity<KnowledgeSnapshot> create(@PathVariable UUID knowledgeBaseId,
            @Valid @RequestBody CreateSnapshot request) {
        KnowledgeSnapshot created = service.create(
                knowledgeBaseId, request.name(), request.sourceVersionIds(), request.metadata());
        return ResponseEntity.created(URI.create(
                "/v1/admin/knowledge-bases/" + knowledgeBaseId + "/snapshots/" + created.id())).body(created);
    }

    @GetMapping
    List<KnowledgeSnapshot> list(@PathVariable UUID knowledgeBaseId) {
        return service.list(knowledgeBaseId);
    }

    @GetMapping("/{snapshotId}")
    KnowledgeSnapshot get(@PathVariable UUID knowledgeBaseId, @PathVariable UUID snapshotId) {
        return service.require(knowledgeBaseId, snapshotId);
    }

    @PostMapping("/{snapshotId}/activate")
    @Operation(summary = "Atomically activate or reactivate a knowledge snapshot")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Snapshot is the only ACTIVE snapshot for the Knowledge Base"),
        @ApiResponse(responseCode = "401", description = "Missing or invalid administrator credential"),
        @ApiResponse(responseCode = "404", description = "Snapshot not found"),
        @ApiResponse(responseCode = "409", description = "Snapshot contains a disabled or non-ready source version")
    })
    KnowledgeSnapshot activate(@PathVariable UUID knowledgeBaseId, @PathVariable UUID snapshotId) {
        return service.activate(knowledgeBaseId, snapshotId);
    }

    public record CreateSnapshot(
            @NotBlank @Size(max = 200) String name,
            @NotEmpty List<UUID> sourceVersionIds,
            JsonNode metadata) {}
}
