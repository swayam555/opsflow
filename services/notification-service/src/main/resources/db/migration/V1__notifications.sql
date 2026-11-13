CREATE TABLE notification (
    id               VARCHAR(36)   PRIMARY KEY,
    idempotency_key  VARCHAR(120)  UNIQUE,
    team             VARCHAR(60)   NOT NULL,
    channel          VARCHAR(80)   NOT NULL,
    message          VARCHAR(2000) NOT NULL,
    ticket_key       VARCHAR(40),
    run_id           VARCHAR(36),
    status           VARCHAR(16)   NOT NULL,
    detail           VARCHAR(500),
    attempts         INT           NOT NULL DEFAULT 1,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_notification_created ON notification (created_at);
CREATE INDEX ix_notification_team ON notification (team);
CREATE INDEX ix_notification_ticket ON notification (ticket_key);
