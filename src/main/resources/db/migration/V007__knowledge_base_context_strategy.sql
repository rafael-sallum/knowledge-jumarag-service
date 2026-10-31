ALTER TABLE knowledge_base
    ADD COLUMN context_strategy varchar(24);

UPDATE knowledge_base
SET context_strategy = 'RAG';

ALTER TABLE knowledge_base
    ALTER COLUMN context_strategy SET NOT NULL,
    ALTER COLUMN context_strategy SET DEFAULT 'AUTO';

ALTER TABLE knowledge_base
    ADD CONSTRAINT knowledge_base_context_strategy_check
        CHECK (context_strategy IN ('RAG', 'LONG_CONTEXT', 'HYBRID', 'AUTO'));
