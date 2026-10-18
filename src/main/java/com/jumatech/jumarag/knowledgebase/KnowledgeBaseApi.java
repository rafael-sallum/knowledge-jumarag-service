package com.jumatech.jumarag.knowledgebase;

import com.jumatech.jumarag.security.OpenApiConfiguration;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/admin/knowledge-bases")
@SecurityRequirement(name = OpenApiConfiguration.ADMIN_SCHEME)
@ConditionalOnProperty(name = "app.runtime", havingValue = "api", matchIfMissing = true)
public final class KnowledgeBaseApi {
    private final KnowledgeBaseService service;

    public KnowledgeBaseApi(KnowledgeBaseService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Create a knowledge base")
    ResponseEntity<KnowledgeBase> create(@Valid @RequestBody CreateKnowledgeBase request) {
        KnowledgeBase created = service.create(
                request.slug(), request.name(), request.description(), request.defaultLocale());
        return ResponseEntity.created(URI.create("/v1/admin/knowledge-bases/" + created.id())).body(created);
    }

    @GetMapping
    List<KnowledgeBase> list() {
        return service.list();
    }

    @GetMapping("/{idOrSlug}")
    KnowledgeBase get(@PathVariable String idOrSlug) {
        return service.get(idOrSlug);
    }

    public record CreateKnowledgeBase(
            @NotBlank @Size(max = 120) String slug,
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Size(max = 4000) String description,
            @NotBlank @Size(max = 35) String defaultLocale) {}
}
