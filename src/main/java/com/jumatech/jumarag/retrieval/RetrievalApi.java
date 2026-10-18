package com.jumatech.jumarag.retrieval;

import com.jumatech.jumarag.security.ApiClientService;
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
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/knowledge-bases")
@SecurityRequirement(name = OpenApiConfiguration.CLIENT_SCHEME)
@ConditionalOnProperty(name = "app.runtime", havingValue = "api", matchIfMissing = true)
public final class RetrievalApi {
    private final RetrievalService service;

    public RetrievalApi(RetrievalService service) {
        this.service = service;
    }

    @PostMapping("/{slug}/retrieval/search")
    @Operation(summary = "Retrieve ranked controlled evidence")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Ranked evidence chunks"),
        @ApiResponse(responseCode = "401", description = "Missing, invalid, or disabled client key"),
        @ApiResponse(responseCode = "403", description = "Client lacks a RETRIEVE grant"),
        @ApiResponse(responseCode = "404", description = "Knowledge base was not found")
    })
    SearchResponse search(
            @PathVariable String slug,
            @RequestAttribute(ApiClientService.CLIENT_ID_ATTRIBUTE) UUID clientId,
            @Valid @RequestBody SearchRequest request) {
        int limit = request.limit() == null ? 10 : request.limit();
        return new SearchResponse(service.searchAuthorized(
                clientId, slug, request.query(), request.language(), request.sourceId(), limit));
    }

    public record SearchRequest(
            @NotBlank @Size(max = 4000) String query,
            @Size(max = 35) String language,
            UUID sourceId,
            @Min(1) @Max(50) Integer limit) {}

    public record SearchResponse(List<RetrievalHit> hits) {}
}
