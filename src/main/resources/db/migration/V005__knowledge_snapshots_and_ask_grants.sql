CREATE TABLE knowledge_snapshot (
    id uuid PRIMARY KEY,
    knowledge_base_id uuid NOT NULL REFERENCES knowledge_base(id) ON DELETE RESTRICT,
    name varchar(200) NOT NULL,
    status varchar(20) NOT NULL,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL,
    sealed_at timestamptz,
    activated_at timestamptz,
    CONSTRAINT knowledge_snapshot_status_check CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    CONSTRAINT knowledge_snapshot_name_check CHECK (length(btrim(name)) > 0),
    CONSTRAINT knowledge_snapshot_name_unique UNIQUE (knowledge_base_id, name),
    CONSTRAINT knowledge_snapshot_id_kb_unique UNIQUE (id, knowledge_base_id),
    CONSTRAINT knowledge_snapshot_activation_check CHECK (
        (status = 'DRAFT' AND activated_at IS NULL)
        OR (status IN ('ACTIVE', 'RETIRED') AND activated_at IS NOT NULL AND sealed_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX knowledge_snapshot_one_active_per_kb_idx
    ON knowledge_snapshot (knowledge_base_id)
    WHERE status = 'ACTIVE';

CREATE INDEX knowledge_snapshot_kb_created_idx
    ON knowledge_snapshot (knowledge_base_id, created_at DESC, id);

ALTER TABLE knowledge_source
    ADD CONSTRAINT knowledge_source_id_kb_unique UNIQUE (id, knowledge_base_id);

ALTER TABLE source_version
    ADD CONSTRAINT source_version_id_source_unique UNIQUE (id, source_id);

CREATE TABLE knowledge_snapshot_item (
    snapshot_id uuid NOT NULL,
    knowledge_base_id uuid NOT NULL,
    knowledge_source_id uuid NOT NULL,
    source_version_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (snapshot_id, knowledge_source_id),
    CONSTRAINT knowledge_snapshot_item_version_unique UNIQUE (snapshot_id, source_version_id),
    CONSTRAINT knowledge_snapshot_item_snapshot_fk
        FOREIGN KEY (snapshot_id, knowledge_base_id)
        REFERENCES knowledge_snapshot(id, knowledge_base_id) ON DELETE RESTRICT,
    CONSTRAINT knowledge_snapshot_item_source_fk
        FOREIGN KEY (knowledge_source_id, knowledge_base_id)
        REFERENCES knowledge_source(id, knowledge_base_id) ON DELETE RESTRICT,
    CONSTRAINT knowledge_snapshot_item_version_fk
        FOREIGN KEY (source_version_id, knowledge_source_id)
        REFERENCES source_version(id, source_id) ON DELETE RESTRICT
);

CREATE INDEX knowledge_snapshot_item_version_idx
    ON knowledge_snapshot_item (source_version_id, snapshot_id);

CREATE FUNCTION prevent_knowledge_snapshot_identity_update()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF ROW(NEW.id, NEW.knowledge_base_id, NEW.name, NEW.metadata, NEW.created_at)
       IS DISTINCT FROM
       ROW(OLD.id, OLD.knowledge_base_id, OLD.name, OLD.metadata, OLD.created_at) THEN
        RAISE EXCEPTION 'knowledge snapshot identity is immutable' USING ERRCODE = '23514';
    END IF;
    IF OLD.sealed_at IS NOT NULL AND NEW.sealed_at IS DISTINCT FROM OLD.sealed_at THEN
        RAISE EXCEPTION 'knowledge snapshot seal is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER knowledge_snapshot_identity_immutable
BEFORE UPDATE ON knowledge_snapshot
FOR EACH ROW EXECUTE FUNCTION prevent_knowledge_snapshot_identity_update();

CREATE FUNCTION prevent_knowledge_snapshot_item_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF EXISTS (
            SELECT 1 FROM knowledge_snapshot
            WHERE id = NEW.snapshot_id AND sealed_at IS NOT NULL
        ) THEN
            RAISE EXCEPTION 'knowledge snapshot items are sealed' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'knowledge snapshot items are immutable' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER knowledge_snapshot_item_immutable
BEFORE INSERT OR UPDATE OR DELETE ON knowledge_snapshot_item
FOR EACH ROW EXECUTE FUNCTION prevent_knowledge_snapshot_item_mutation();

ALTER TABLE knowledge_base_grant
    DROP CONSTRAINT knowledge_base_grant_permission_check;

ALTER TABLE knowledge_base_grant
    ADD CONSTRAINT knowledge_base_grant_permission_check
        CHECK (permission IN ('RETRIEVE', 'ASK'));
