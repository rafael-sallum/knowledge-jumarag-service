package com.jumatech.jumarag.embedding;

import com.jumatech.jumarag.shared.web.DomainException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public final class EmbeddingProfileResolver {
    private final EmbeddingRepository repository;
    private final EmbeddingGateway gateway;

    public EmbeddingProfileResolver(EmbeddingRepository repository, EmbeddingGateway gateway) {
        this.repository = repository;
        this.gateway = gateway;
    }

    public EmbeddingRepository.EmbeddingProfile requireCompatible(String profileName) {
        if (profileName == null || profileName.isBlank()) {
            throw new DomainException(
                    "EMBEDDING_PROFILE_REQUIRED", HttpStatus.BAD_REQUEST, "An embedding profile is required.");
        }
        EmbeddingRepository.EmbeddingProfile profile = repository.findProfileByName(profileName)
                .orElseThrow(() -> new DomainException(
                        "EMBEDDING_PROFILE_NOT_FOUND", HttpStatus.NOT_FOUND, "Embedding profile was not found."));
        if (profile.dimensions() != gateway.dimensions()
                || !profile.provider().equals(gateway.provider())
                || !profile.model().equals(gateway.model())
                || !profile.distanceMetric().equals("COSINE")) {
            throw new DomainException(
                    "EMBEDDING_PROFILE_INCOMPATIBLE",
                    HttpStatus.CONFLICT,
                    "The selected runtime embedding provider is incompatible with the requested profile.");
        }
        return profile;
    }
}
