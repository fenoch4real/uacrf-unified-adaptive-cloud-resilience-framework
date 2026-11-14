CREATE SCHEMA IF NOT EXISTS uacrf_knowledge;

CREATE TABLE uacrf_knowledge.knowledge_item (
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    item_id text NOT NULL,
    revision bigint NOT NULL CHECK (revision >= 1),
    withdrawn boolean NOT NULL DEFAULT false,
    PRIMARY KEY (tenant_id, environment_id, item_id)
);

CREATE TABLE uacrf_knowledge.knowledge_version (
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    item_id text NOT NULL,
    version text NOT NULL,
    title text NOT NULL,
    source_ref text,
    content text NOT NULL,
    classification text NOT NULL CHECK (classification IN ('PUBLIC', 'INTERNAL', 'RESTRICTED')),
    allowed_purposes text[] NOT NULL CHECK (cardinality(allowed_purposes) > 0),
    target_workload_ids text[] NOT NULL,
    review_deadline timestamptz NOT NULL,
    reason text NOT NULL,
    source_digest char(64) NOT NULL,
    state text NOT NULL CHECK (state IN ('CANDIDATE', 'APPROVED', 'REJECTED', 'SUPERSEDED', 'WITHDRAWN')),
    revision bigint NOT NULL CHECK (revision >= 1),
    proposed_by text NOT NULL,
    reviewed_by text,
    review_reason text,
    withdrawn_by text,
    withdrawal_reason text,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, environment_id, item_id, version),
    FOREIGN KEY (tenant_id, environment_id, item_id)
        REFERENCES uacrf_knowledge.knowledge_item (tenant_id, environment_id, item_id)
);

CREATE UNIQUE INDEX knowledge_one_candidate_per_item_idx
    ON uacrf_knowledge.knowledge_version (tenant_id, environment_id, item_id)
    WHERE state = 'CANDIDATE';
CREATE UNIQUE INDEX knowledge_one_approved_per_item_idx
    ON uacrf_knowledge.knowledge_version (tenant_id, environment_id, item_id)
    WHERE state = 'APPROVED';
CREATE INDEX knowledge_search_scope_idx
    ON uacrf_knowledge.knowledge_version
        (tenant_id, environment_id, classification, item_id, version)
    WHERE state = 'APPROVED';

CREATE FUNCTION uacrf_knowledge.protect_knowledge_item() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'knowledge items are retained and cannot be deleted' USING ERRCODE = '23514';
    END IF;
    IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
       OR NEW.environment_id IS DISTINCT FROM OLD.environment_id
       OR NEW.item_id IS DISTINCT FROM OLD.item_id
       OR NEW.revision <> OLD.revision + 1
       OR (OLD.withdrawn AND NOT NEW.withdrawn) THEN
        RAISE EXCEPTION 'invalid knowledge item revision or lifecycle change' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER knowledge_item_immutable_lifecycle
    BEFORE UPDATE OR DELETE ON uacrf_knowledge.knowledge_item
    FOR EACH ROW EXECUTE FUNCTION uacrf_knowledge.protect_knowledge_item();

CREATE FUNCTION uacrf_knowledge.protect_knowledge_version() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'knowledge versions are retained and cannot be deleted' USING ERRCODE = '23514';
    END IF;
    IF ROW(NEW.tenant_id, NEW.environment_id, NEW.item_id, NEW.version, NEW.title,
           NEW.source_ref, NEW.content, NEW.classification, NEW.allowed_purposes,
           NEW.target_workload_ids, NEW.review_deadline, NEW.reason, NEW.source_digest,
           NEW.proposed_by, NEW.created_at)
       IS DISTINCT FROM
       ROW(OLD.tenant_id, OLD.environment_id, OLD.item_id, OLD.version, OLD.title,
           OLD.source_ref, OLD.content, OLD.classification, OLD.allowed_purposes,
           OLD.target_workload_ids, OLD.review_deadline, OLD.reason, OLD.source_digest,
           OLD.proposed_by, OLD.created_at) THEN
        RAISE EXCEPTION 'knowledge version body is immutable' USING ERRCODE = '23514';
    END IF;
    IF NEW.revision <> OLD.revision + 1 THEN
        RAISE EXCEPTION 'knowledge revision must advance exactly once' USING ERRCODE = '23514';
    END IF;
    IF NOT (
        (OLD.state = 'CANDIDATE' AND NEW.state IN ('APPROVED', 'REJECTED'))
        OR (OLD.state = 'APPROVED' AND NEW.state IN ('SUPERSEDED', 'WITHDRAWN'))
    ) THEN
        RAISE EXCEPTION 'invalid knowledge lifecycle transition' USING ERRCODE = '23514';
    END IF;
    IF OLD.state = 'CANDIDATE' THEN
        IF NEW.reviewed_by IS NULL OR NEW.reviewed_by = NEW.proposed_by
           OR NEW.review_reason IS NULL OR btrim(NEW.review_reason) = ''
           OR NEW.withdrawn_by IS DISTINCT FROM OLD.withdrawn_by
           OR NEW.withdrawal_reason IS DISTINCT FROM OLD.withdrawal_reason
           OR OLD.review_deadline <= now() THEN
            RAISE EXCEPTION 'invalid or expired independent knowledge review' USING ERRCODE = '23514';
        END IF;
        IF NEW.state = 'APPROVED' AND EXISTS (
            SELECT 1 FROM uacrf_knowledge.knowledge_item
            WHERE tenant_id = OLD.tenant_id AND environment_id = OLD.environment_id
              AND item_id = OLD.item_id AND withdrawn = true
        ) THEN
            RAISE EXCEPTION 'withdrawn knowledge items cannot be approved again' USING ERRCODE = '23514';
        END IF;
    ELSE
        IF NEW.reviewed_by IS DISTINCT FROM OLD.reviewed_by
           OR NEW.review_reason IS DISTINCT FROM OLD.review_reason THEN
            RAISE EXCEPTION 'knowledge review record is immutable' USING ERRCODE = '23514';
        END IF;
        IF NEW.state = 'SUPERSEDED' AND (
            NEW.withdrawn_by IS DISTINCT FROM OLD.withdrawn_by
            OR NEW.withdrawal_reason IS DISTINCT FROM OLD.withdrawal_reason
        ) THEN
            RAISE EXCEPTION 'superseding cannot change withdrawal data' USING ERRCODE = '23514';
        END IF;
        IF NEW.state = 'WITHDRAWN' AND (
            NEW.withdrawn_by IS NULL OR NEW.withdrawal_reason IS NULL
            OR btrim(NEW.withdrawal_reason) = ''
        ) THEN
            RAISE EXCEPTION 'withdrawal requires an actor and reason' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER knowledge_version_immutable_lifecycle
    BEFORE UPDATE OR DELETE ON uacrf_knowledge.knowledge_version
    FOR EACH ROW EXECUTE FUNCTION uacrf_knowledge.protect_knowledge_version();

CREATE TABLE uacrf_knowledge.retrieval (
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    retrieval_id text NOT NULL,
    case_id text,
    case_binding text NOT NULL CHECK (case_binding = 'NOT_VERIFIED'),
    purpose text NOT NULL,
    max_classification text NOT NULL CHECK (max_classification IN ('PUBLIC', 'INTERNAL', 'RESTRICTED')),
    query_text text NOT NULL,
    workload_id text,
    manifest_digest char(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, environment_id, retrieval_id)
);

CREATE TABLE uacrf_knowledge.retrieval_result (
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    retrieval_id text NOT NULL,
    result_order integer NOT NULL CHECK (result_order >= 0),
    item_id text NOT NULL,
    version text NOT NULL,
    chunk_index integer NOT NULL CHECK (chunk_index >= 0),
    start_offset integer NOT NULL CHECK (start_offset >= 0),
    end_offset integer NOT NULL CHECK (end_offset > start_offset),
    source_digest char(64) NOT NULL,
    excerpt_digest char(64) NOT NULL,
    allowed_purposes text[] NOT NULL,
    classification text NOT NULL CHECK (classification IN ('PUBLIC', 'INTERNAL', 'RESTRICTED')),
    score integer NOT NULL CHECK (score > 0),
    excerpt text NOT NULL,
    PRIMARY KEY (tenant_id, environment_id, retrieval_id, result_order),
    FOREIGN KEY (tenant_id, environment_id, retrieval_id)
        REFERENCES uacrf_knowledge.retrieval (tenant_id, environment_id, retrieval_id),
    FOREIGN KEY (tenant_id, environment_id, item_id, version)
        REFERENCES uacrf_knowledge.knowledge_version (tenant_id, environment_id, item_id, version)
);

CREATE FUNCTION uacrf_knowledge.protect_retrieval() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'retrieval manifests are immutable and retained' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER retrieval_immutable
    BEFORE UPDATE OR DELETE ON uacrf_knowledge.retrieval
    FOR EACH ROW EXECUTE FUNCTION uacrf_knowledge.protect_retrieval();
CREATE TRIGGER retrieval_result_immutable
    BEFORE UPDATE OR DELETE ON uacrf_knowledge.retrieval_result
    FOR EACH ROW EXECUTE FUNCTION uacrf_knowledge.protect_retrieval();

CREATE TABLE uacrf_knowledge.outbox_event (
    event_id uuid PRIMARY KEY,
    tenant_id text NOT NULL,
    environment_id text NOT NULL,
    aggregate_type text NOT NULL,
    aggregate_id text NOT NULL,
    aggregate_sequence bigint NOT NULL,
    event_type text NOT NULL CHECK (event_type IN (
        'uacrf.s08.knowledge.review.changed.v1',
        'uacrf.s08.retrieval.retained.v1'
    )),
    schema_version integer NOT NULL DEFAULT 1,
    payload jsonb NOT NULL,
    payload_digest char(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    UNIQUE (tenant_id, environment_id, aggregate_type, aggregate_id, aggregate_sequence)
);

CREATE FUNCTION uacrf_knowledge.protect_outbox_event() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'knowledge outbox records are retained' USING ERRCODE = '23514';
    END IF;
    IF ROW(NEW.event_id, NEW.tenant_id, NEW.environment_id, NEW.aggregate_type, NEW.aggregate_id,
           NEW.aggregate_sequence, NEW.event_type, NEW.schema_version, NEW.payload, NEW.payload_digest,
           NEW.created_at)
       IS DISTINCT FROM
       ROW(OLD.event_id, OLD.tenant_id, OLD.environment_id, OLD.aggregate_type, OLD.aggregate_id,
           OLD.aggregate_sequence, OLD.event_type, OLD.schema_version, OLD.payload, OLD.payload_digest,
           OLD.created_at)
       OR (OLD.published_at IS NOT NULL AND NEW.published_at IS DISTINCT FROM OLD.published_at) THEN
        RAISE EXCEPTION 'knowledge outbox facts are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER outbox_event_immutable
    BEFORE UPDATE OR DELETE ON uacrf_knowledge.outbox_event
    FOR EACH ROW EXECUTE FUNCTION uacrf_knowledge.protect_outbox_event();
