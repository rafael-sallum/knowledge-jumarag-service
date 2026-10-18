ALTER TABLE knowledge_source
    ADD COLUMN source_classification varchar(80) NOT NULL DEFAULT 'UNSPECIFIED',
    ADD COLUMN metadata jsonb NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE knowledge_source
    ADD CONSTRAINT knowledge_source_classification_check
        CHECK (source_classification ~ '^[A-Z][A-Z0-9_]{0,79}$');

ALTER TABLE source_version
    DROP CONSTRAINT source_version_status_check;

ALTER TABLE source_version
    ADD COLUMN title varchar(500),
    ADD COLUMN language varchar(35),
    ADD COLUMN normalized_content text,
    ADD COLUMN original_filename varchar(255),
    ADD COLUMN metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN failure_code varchar(80),
    ADD COLUMN processed_at timestamptz,
    ADD CONSTRAINT source_version_status_check
        CHECK (status IN ('REGISTERED', 'PROCESSING', 'READY', 'FAILED', 'DISABLED'));

ALTER TABLE document_chunk
    ADD COLUMN checksum char(64),
    ADD CONSTRAINT document_chunk_checksum_format
        CHECK (checksum IS NULL OR checksum ~ '^[0-9a-f]{64}$');

CREATE TABLE api_client (
    id uuid PRIMARY KEY,
    name varchar(200) NOT NULL UNIQUE,
    key_prefix varchar(32) NOT NULL UNIQUE,
    secret_hash bytea NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL,
    disabled_at timestamptz,
    CONSTRAINT api_client_prefix_format CHECK (key_prefix ~ '^[A-Za-z0-9_-]{12,32}$'),
    CONSTRAINT api_client_secret_hash_size CHECK (octet_length(secret_hash) = 32),
    CONSTRAINT api_client_disabled_state CHECK (
        (enabled = true AND disabled_at IS NULL)
        OR (enabled = false AND disabled_at IS NOT NULL)
    )
);

CREATE INDEX api_client_enabled_prefix_idx ON api_client (key_prefix) WHERE enabled = true;

CREATE TABLE knowledge_base_grant (
    api_client_id uuid NOT NULL REFERENCES api_client(id) ON DELETE CASCADE,
    knowledge_base_id uuid NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
    permission varchar(32) NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (api_client_id, knowledge_base_id, permission),
    CONSTRAINT knowledge_base_grant_permission_check CHECK (permission = 'RETRIEVE')
);

CREATE INDEX knowledge_base_grant_knowledge_base_idx
    ON knowledge_base_grant (knowledge_base_id, permission);

CREATE FUNCTION prevent_source_version_content_update()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF ROW(
        NEW.source_id, NEW.version_label, NEW.checksum, NEW.media_type, NEW.byte_size,
        NEW.effective_from, NEW.effective_to, NEW.title, NEW.language,
        NEW.normalized_content, NEW.original_filename, NEW.metadata, NEW.created_at
    ) IS DISTINCT FROM ROW(
        OLD.source_id, OLD.version_label, OLD.checksum, OLD.media_type, OLD.byte_size,
        OLD.effective_from, OLD.effective_to, OLD.title, OLD.language,
        OLD.normalized_content, OLD.original_filename, OLD.metadata, OLD.created_at
    ) THEN
        RAISE EXCEPTION 'source version content is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER source_version_content_immutable
BEFORE UPDATE ON source_version
FOR EACH ROW EXECUTE FUNCTION prevent_source_version_content_update();
