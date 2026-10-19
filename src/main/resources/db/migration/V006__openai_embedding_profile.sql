INSERT INTO embedding_profile (
    id, name, provider, model, dimensions, distance_metric, created_at
) VALUES (
    '00000000-0000-4000-8000-000000003153',
    'openai-text-embedding-3-small-1536',
    'OPENAI',
    'text-embedding-3-small',
    1536,
    'COSINE',
    CURRENT_TIMESTAMP
);
