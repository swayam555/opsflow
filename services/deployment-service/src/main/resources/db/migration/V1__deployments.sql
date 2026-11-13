CREATE SEQUENCE deployment_seq START WITH 1001;

CREATE TABLE deployment (
    id               VARCHAR(36)   PRIMARY KEY,
    deploy_key       VARCHAR(20)   NOT NULL UNIQUE,
    idempotency_key  VARCHAR(120)  UNIQUE,
    environment      VARCHAR(40)   NOT NULL,
    service          VARCHAR(80)   NOT NULL,
    version          VARCHAR(60)   NOT NULL,
    reason           VARCHAR(500),
    ticket_key       VARCHAR(40),
    run_id           VARCHAR(36),
    status           VARCHAR(16)   NOT NULL,
    failure_reason   VARCHAR(500),
    log              VARCHAR(8000) NOT NULL DEFAULT '',
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at       TIMESTAMP WITH TIME ZONE,
    finished_at      TIMESTAMP WITH TIME ZONE,
    version_lock     BIGINT        NOT NULL DEFAULT 0
);
CREATE INDEX ix_deployment_created ON deployment (created_at);
CREATE INDEX ix_deployment_status ON deployment (status);

CREATE TABLE environment_freeze (
    environment  VARCHAR(40)  PRIMARY KEY,
    reason       VARCHAR(300) NOT NULL,
    frozen_at    TIMESTAMP WITH TIME ZONE NOT NULL
);
