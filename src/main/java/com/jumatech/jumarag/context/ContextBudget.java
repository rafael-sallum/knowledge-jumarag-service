package com.jumatech.jumarag.context;

import com.jumatech.jumarag.retrieval.RetrievalHit;
import com.jumatech.jumarag.shared.web.DomainException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;

final class ContextBudget {
    private ContextBudget() {}

    static Selection select(List<RetrievalHit> candidates, int maxChunks, int maxCharacters, int maxTokens) {
        List<RetrievalHit> selected = new ArrayList<>();
        int characters = 0;
        for (RetrievalHit hit : candidates) {
            int nextCharacters = characters + hit.text().length();
            if (selected.size() >= maxChunks || nextCharacters > maxCharacters
                    || estimatedTokens(nextCharacters) > maxTokens) {
                break;
            }
            selected.add(hit);
            characters = nextCharacters;
        }
        if (!candidates.isEmpty() && selected.isEmpty()) {
            throw new DomainException("CONTEXT_CHUNK_EXCEEDS_BUDGET", HttpStatus.UNPROCESSABLE_ENTITY,
                    "A source chunk exceeds the configured context budget.");
        }
        return new Selection(List.copyOf(selected), characters, estimatedTokens(characters),
                selected.size() == candidates.size());
    }

    static int estimatedTokens(int characters) {
        return (characters + 3) / 4;
    }

    record Selection(List<RetrievalHit> chunks, int characters, int estimatedTokens, boolean complete) {}
}
