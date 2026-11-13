package app.opsflow.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "notification")
public class Notification {
    /** SENT: posted to Slack. LOGGED: recorded only (no Slack configured). FAILED: Slack refused or was unreachable. */
    public enum Status { SENT, LOGGED, FAILED }

    @Id
    private String id = UUID.randomUUID().toString();
    @Column(name = "idempotency_key")
    private String idempotencyKey;
    private String team;
    private String channel;
    private String message;
    @Column(name = "ticket_key")
    private String ticketKey;
    @Column(name = "run_id")
    private String runId;
    private String status;
    private String detail;
    private int attempts = 1;
    @Column(name = "created_at")
    private OffsetDateTime createdAt = OffsetDateTime.now();
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt = createdAt;

    protected Notification() {}

    public Notification(String key, String team, String channel, String message, String ticketKey, String runId) {
        this.idempotencyKey = key;
        this.team = team;
        this.channel = channel;
        this.message = message;
        this.ticketKey = ticketKey;
        this.runId = runId;
    }

    void delivered(Status s, String detail) {
        this.status = s.name();
        this.detail = detail;
        this.updatedAt = OffsetDateTime.now();
    }

    void retried() {
        attempts++;
    }

    public String getId() { return id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getTeam() { return team; }
    public String getChannel() { return channel; }
    public String getMessage() { return message; }
    public String getTicketKey() { return ticketKey; }
    public String getRunId() { return runId; }
    public String getStatus() { return status; }
    public String getDetail() { return detail; }
    public int getAttempts() { return attempts; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
