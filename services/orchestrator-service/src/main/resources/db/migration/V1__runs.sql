CREATE TABLE workflow_run (
    id               VARCHAR(36)   PRIMARY KEY,
    idempotency_key  VARCHAR(120)  UNIQUE,
    ticket_id        BIGINT,
    ticket_key       VARCHAR(40),
    title            VARCHAR(200),
    description      VARCHAR(5000),
    priority         VARCHAR(10),
    team             VARCHAR(60),
    workflow_id      VARCHAR(60)   NOT NULL,
    workflow_name    VARCHAR(120)  NOT NULL,
    run_trigger      VARCHAR(20)   NOT NULL,
    status           VARCHAR(16)   NOT NULL,
    attempt          INT           NOT NULL DEFAULT 1,
    current_step     INT           NOT NULL DEFAULT 0,
    warnings         INT           NOT NULL DEFAULT 0,
    error            VARCHAR(1000),
    reported         BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at      TIMESTAMP WITH TIME ZONE,
    version          BIGINT        NOT NULL DEFAULT 0
);
CREATE INDEX ix_run_ticket ON workflow_run (ticket_id);
CREATE INDEX ix_run_created ON workflow_run (created_at);
CREATE INDEX ix_run_status ON workflow_run (status);

CREATE TABLE run_step (
    id           VARCHAR(36)   PRIMARY KEY,
    run_id       VARCHAR(36)   NOT NULL REFERENCES workflow_run (id) ON DELETE CASCADE,
    idx          INT           NOT NULL,
    type         VARCHAR(20)   NOT NULL,
    label        VARCHAR(200)  NOT NULL,
    config       VARCHAR(4000) NOT NULL,
    status       VARCHAR(16)   NOT NULL,
    attempts     INT           NOT NULL DEFAULT 0,
    detail       VARCHAR(2000),
    reference    VARCHAR(60),
    link         VARCHAR(400),
    started_at   TIMESTAMP WITH TIME ZONE,
    finished_at  TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_run_step UNIQUE (run_id, idx)
);
