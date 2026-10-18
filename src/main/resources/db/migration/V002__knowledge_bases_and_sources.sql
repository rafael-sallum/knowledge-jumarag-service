CREATE TABLE knowledge_base (
    id uuid PRIMARY KEY,
    slug varchar(120) NOT NULL UNIQUE,
    name varchar(200) NOT NULL,
    description text NOT NULL,
    default_locale varchar(35) NOT NULL,
    status varchar(20) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT knowledge_base_slug_format CHECK (slug ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'),
    CONSTRAINT knowledge_base_status_check CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE TABLE knowledge_source (
    id uuid PRIMARY KEY,
    knowledge_base_id uuid NOT NULL REFERENCES knowledge_base(id) ON DELETE RESTRICT,
    source_type varchar(20) NOT NULL,
    logical_name varchar(240) NOT NULL,
    canonical_uri text,
    enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT knowledge_source_type_check CHECK (source_type IN ('TEXT', 'FILE', 'PDF', 'URL', 'SITEMAP')),
    CONSTRAINT knowledge_source_name_unique UNIQUE (knowledge_base_id, logical_name)
);

CREATE INDEX knowledge_source_knowledge_base_idx
    ON knowledge_source (knowledge_base_id, enabled);

CREATE TABLE source_version (
    id uuid PRIMARY KEY,
    source_id uuid NOT NULL REFERENCES knowledge_source(id) ON DELETE RESTRICT,
    version_label varchar(120),
    checksum char(64) NOT NULL,
    media_type varchar(160) NOT NULL,
    byte_size bigint NOT NULL,
    effective_from date,
    effective_to date,
    status varchar(24) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT source_version_checksum_format CHECK (checksum ~ '^[0-9a-f]{64}$'),
    CONSTRAINT source_version_byte_size_check CHECK (byte_size >= 0),
    CONSTRAINT source_version_dates_check CHECK (effective_to IS NULL OR effective_from IS NULL OR effective_to >= effective_from),
    CONSTRAINT source_version_status_check CHECK (status IN ('REGISTERED', 'READY', 'FAILED', 'DISABLED')),
    CONSTRAINT source_version_checksum_unique UNIQUE (source_id, checksum)
);

CREATE INDEX source_version_source_idx ON source_version (source_id, created_at DESC);
