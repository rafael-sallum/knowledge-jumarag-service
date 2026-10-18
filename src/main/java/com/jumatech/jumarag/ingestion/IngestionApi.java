package com.jumatech.jumarag.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/v1/admin/knowledge-bases/{knowledgeBaseId}/sources")
@SecurityRequirement(name = "AdminBearer")
final class IngestionApi {

    private final IngestionService ingestionService;

    IngestionApi(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping(path = "/text", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Ingest controlled text",
            description = "Normalizes and checksums content, then atomically creates chunks and fake embeddings. Repeated identical content for the same logical source is idempotent.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Source version is ready or an idempotent version was returned"),
        @ApiResponse(responseCode = "401", description = "Missing or invalid administrator credential"),
        @ApiResponse(responseCode = "409", description = "Logical source identity conflict"),
        @ApiResponse(responseCode = "413", description = "Content exceeds the configured limit"),
        @ApiResponse(responseCode = "422", description = "Ingestion failed without publishing partial content")
    })
    ResponseEntity<IngestionService.IngestionResult> ingestText(
            @PathVariable UUID knowledgeBaseId,
            @Valid @org.springframework.web.bind.annotation.RequestBody TextIngestionRequest request) {
        return ResponseEntity.ok(ingestionService.ingestText(
                knowledgeBaseId,
                request.logicalName(),
                request.sourceClassification(),
                request.language(),
                request.title(),
                request.content(),
                request.metadata()));
    }

    @PostMapping(path = "/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "Ingest a UTF-8 TXT or Markdown file",
            description = "The uploaded file is bounded, validated and consumed without using the supplied filename as a filesystem path.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(
                    mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
                    schema = @Schema(implementation = FileUploadOpenApi.class)))
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Source version is ready or an idempotent version was returned"),
        @ApiResponse(responseCode = "401", description = "Missing or invalid administrator credential"),
        @ApiResponse(responseCode = "409", description = "Logical source identity conflict"),
        @ApiResponse(responseCode = "413", description = "File exceeds the configured limit"),
        @ApiResponse(responseCode = "422", description = "Unsupported or invalid file content")
    })
    ResponseEntity<IngestionService.IngestionResult> ingestFile(
            @PathVariable UUID knowledgeBaseId,
            @RequestParam @NotBlank String logicalName,
            @RequestParam(defaultValue = "CONTROLLED_SOURCE") String sourceClassification,
            @RequestParam @NotBlank String language,
            @RequestParam(required = false) String title,
            @RequestParam(required = false, defaultValue = "{}") String metadata,
            @RequestPart("file") MultipartFile file) {
        return ResponseEntity.ok(ingestionService.ingestFile(
                knowledgeBaseId,
                logicalName,
                sourceClassification,
                language,
                title,
                ingestionService.parseMetadata(metadata),
                file));
    }

    record TextIngestionRequest(
            @NotBlank @Size(max = 240) String logicalName,
            @NotBlank @Size(max = 80) String sourceClassification,
            @NotBlank @Size(max = 35) String language,
            @Size(max = 500) String title,
            @NotBlank String content,
            JsonNode metadata) {}

    @SuppressWarnings("unused")
    private record FileUploadOpenApi(
            String logicalName,
            String sourceClassification,
            String language,
            String title,
            String metadata,
            @Schema(type = "string", format = "binary") byte[] file) {}
}
