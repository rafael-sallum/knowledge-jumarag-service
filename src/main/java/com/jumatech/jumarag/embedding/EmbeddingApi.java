package com.jumatech.jumarag.embedding;

import com.jumatech.jumarag.security.OpenApiConfiguration;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/admin/chunks")
@SecurityRequirement(name = OpenApiConfiguration.ADMIN_SCHEME)
@ConditionalOnProperty(name = "app.runtime", havingValue = "api", matchIfMissing = true)
public final class EmbeddingApi {
    private final EmbeddingService service;

    public EmbeddingApi(EmbeddingService service) {
        this.service = service;
    }

    @PostMapping("/{chunkId}/embedding")
    EmbeddingService.EmbeddedChunk embed(@PathVariable UUID chunkId) {
        return service.embed(chunkId);
    }
}
