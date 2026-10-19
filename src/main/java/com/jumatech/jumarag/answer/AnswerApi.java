package com.jumatech.jumarag.answer;

import com.fasterxml.jackson.databind.JsonNode;
import com.jumatech.jumarag.security.ApiClientService;
import com.jumatech.jumarag.security.OpenApiConfiguration;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
public final class AnswerApi {
    private final AnswerService service;

    public AnswerApi(AnswerService service) {
        this.service = service;
    }

    @PostMapping("/{slug}/ask")
    @Operation(summary = "Generate a grounded answer from an immutable snapshot")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Grounded answer, insufficient evidence, or source conflict"),
        @ApiResponse(responseCode = "401", description = "Missing, invalid, or disabled client key"),
        @ApiResponse(responseCode = "403", description = "Client lacks an ASK grant"),
        @ApiResponse(responseCode = "409", description = "Knowledge base has no active snapshot"),
        @ApiResponse(responseCode = "422", description = "Grounded generation contract failed"),
        @ApiResponse(responseCode = "503", description = "Configured embedding or chat provider is temporarily unavailable")
    })
    AnswerService.AnswerResponse ask(
            @PathVariable String slug,
            @RequestAttribute(ApiClientService.CLIENT_ID_ATTRIBUTE) UUID clientId,
            @Valid @RequestBody AskRequest request) {
        return service.ask(clientId, slug, new AnswerService.AnswerRequest(
                request.question(), request.language(), request.contextSchema(), request.context(), request.snapshotId()));
    }

    public record AskRequest(
            @NotBlank @Size(max = 4000) String question,
            @Size(max = 35) String language,
            @Size(max = 120) String contextSchema,
            JsonNode context,
            UUID snapshotId) {}
}
