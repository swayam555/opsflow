CREATE SEQUENCE issue_seq START WITH 101;

CREATE TABLE issue (
    id               BIGINT        PRIMARY KEY,
    issue_key        VARCHAR(30)   NOT NULL UNIQUE,
    idempotency_key  VARCHAR(120)  UNIQUE,
    summary          VARCHAR(255)  NOT NULL,
    description      VARCHAR(4000),
    priority         VARCHAR(10)   NOT NULL,
    team             VARCHAR(60),
    ticket_key       VARCHAR(40),
    run_id           VARCHAR(36),
    status           VARCHAR(10)   NOT NULL,
    external_key     VARCHAR(40),
    external_url     VARCHAR(400),
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_issue_ticket ON issue (ticket_key);
CREATE INDEX ix_issue_created ON issue (created_at);
