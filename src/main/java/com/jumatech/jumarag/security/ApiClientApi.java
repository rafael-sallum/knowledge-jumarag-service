package com.jumatech.jumarag.security;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/admin/api-clients")
@SecurityRequirement(name = OpenApiConfiguration.ADMIN_SCHEME)
@ConditionalOnProperty(name = "app.runtime", havingValue = "api", matchIfMissing = true)
public final class ApiClientApi {
    private final ApiClientService service;

    public ApiClientApi(ApiClientService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Create an API client", description = "The raw API key is returned exactly once.")
    ResponseEntity<ApiClientService.CreatedApiClient> create(@Valid @RequestBody CreateClient request) {
        var created = service.create(request.name(), request.metadata());
        return ResponseEntity.created(URI.create("/v1/admin/api-clients/" + created.client().id())).body(created);
    }

    @GetMapping
    List<ApiClient> list() {
        return service.list();
    }

    @PostMapping("/{clientId}/disable")
    ApiClient disable(@PathVariable UUID clientId) {
        return service.disable(clientId);
    }

    @PostMapping("/{clientId}/grants")
    @Operation(summary = "Grant RETRIEVE or ASK", description = "ASK permits internal retrieval for /ask but not direct retrieval/search.")
    KnowledgeBaseGrant grant(@PathVariable UUID clientId, @Valid @RequestBody CreateGrant request) {
        KnowledgeBaseGrant.Permission permission = request.permission() == null
                ? KnowledgeBaseGrant.Permission.RETRIEVE
                : request.permission();
        return service.grant(clientId, request.knowledgeBaseId(), permission);
    }

    @GetMapping("/{clientId}/grants")
    List<KnowledgeBaseGrant> grants(@PathVariable UUID clientId) {
        return service.grants(clientId);
    }

    @DeleteMapping("/{clientId}/grants/{knowledgeBaseId}")
    ResponseEntity<Void> removeGrant(
            @PathVariable UUID clientId,
            @PathVariable UUID knowledgeBaseId,
            @RequestParam(defaultValue = "RETRIEVE") KnowledgeBaseGrant.Permission permission) {
        service.removeGrant(clientId, knowledgeBaseId, permission);
        return ResponseEntity.noContent().build();
    }

    public record CreateClient(
            @NotBlank @Size(max = 200) String name,
            JsonNode metadata) {}

    public record CreateGrant(
            @NotNull UUID knowledgeBaseId,
            KnowledgeBaseGrant.Permission permission) {}
}
