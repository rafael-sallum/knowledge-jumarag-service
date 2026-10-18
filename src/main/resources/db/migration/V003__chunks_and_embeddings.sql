CREATE TABLE document_chunk (
    id uuid PRIMARY KEY,
    source_version_id uuid NOT NULL REFERENCES source_version(id) ON DELETE RESTRICT,
    chunk_index integer NOT NULL,
    text text NOT NULL,
    language varchar(35) NOT NULL,
    heading_path text,
    page_start integer,
    page_end integer,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL,
    CONSTRAINT document_chunk_index_check CHECK (chunk_index >= 0),
    CONSTRAINT document_chunk_text_check CHECK (length(btrim(text)) > 0),
    CONSTRAINT document_chunk_pages_check CHECK (
        (page_start IS NULL AND page_end IS NULL)
        OR (page_start IS NOT NULL AND page_start > 0 AND (page_end IS NULL OR page_end >= page_start))
    ),
    CONSTRAINT document_chunk_index_unique UNIQUE (source_version_id, chunk_index)
);

CREATE INDEX document_chunk_source_version_idx ON document_chunk (source_version_id);
CREATE INDEX document_chunk_language_idx ON document_chunk (language);
CREATE INDEX document_chunk_metadata_gin_idx ON document_chunk USING gin (metadata);

CREATE TABLE embedding_profile (
    id uuid PRIMARY KEY,
    name varchar(120) NOT NULL UNIQUE,
    provider varchar(80) NOT NULL,
    model varchar(160) NOT NULL,
    dimensions integer NOT NULL,
    distance_metric varchar(24) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT embedding_profile_dimensions_check CHECK (dimensions = 1536),
    CONSTRAINT embedding_profile_distance_check CHECK (distance_metric = 'COSINE')
);

CREATE TABLE chunk_embedding (
    chunk_id uuid NOT NULL REFERENCES document_chunk(id) ON DELETE CASCADE,
    embedding_profile_id uuid NOT NULL REFERENCES embedding_profile(id) ON DELETE RESTRICT,
    embedding vector(1536) NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (chunk_id, embedding_profile_id)
);

INSERT INTO embedding_profile (
    id, name, provider, model, dimensions, distance_metric, created_at
) VALUES (
    '00000000-0000-4000-8000-000000001536',
    'phase0-fake-1536',
    'FAKE',
    'deterministic-token-hash-v1',
    1536,
    'COSINE',
    CURRENT_TIMESTAMP
);
