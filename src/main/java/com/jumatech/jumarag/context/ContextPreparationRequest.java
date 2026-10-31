package com.jumatech.jumarag.context;

import com.jumatech.jumarag.knowledgebase.KnowledgeBase;
import com.jumatech.jumarag.snapshot.KnowledgeSnapshot;

public record ContextPreparationRequest(
        KnowledgeBase knowledgeBase,
        KnowledgeSnapshot snapshot,
        String question,
        String language) {}
