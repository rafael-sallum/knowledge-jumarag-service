package com.jumatech.jumarag.context;

import com.jumatech.jumarag.knowledgebase.KnowledgeBase;
import com.jumatech.jumarag.evidence.EvidenceIntent;
import com.jumatech.jumarag.snapshot.KnowledgeSnapshot;

public record ContextPreparationRequest(
        KnowledgeBase knowledgeBase,
        KnowledgeSnapshot snapshot,
        String question,
        String language,
        EvidenceIntent evidenceIntent) {

    public ContextPreparationRequest {
        evidenceIntent = EvidenceIntent.defaulted(evidenceIntent);
    }

    public ContextPreparationRequest(
            KnowledgeBase knowledgeBase, KnowledgeSnapshot snapshot, String question, String language) {
        this(knowledgeBase, snapshot, question, language, EvidenceIntent.GENERAL);
    }
}
