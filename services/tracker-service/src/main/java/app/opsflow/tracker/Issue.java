package app.opsflow.tracker;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "issue")
public class Issue {
    public enum Status { OPEN, DONE }

    @Id
    private Long id;
    @Column(name = "issue_key")
    private String key;
    @Column(name = "idempotency_key")
    private String idempotencyKey;
    private String summary;
    private String description;
    /** P1, P2 or P3, like the ticket. */
    private String priority;
    private String team;
    @Column(name = "ticket_key")
    private String ticketKey;
    @Column(name = "run_id")
    private String runId;
    private String status = Status.OPEN.name();
    @Column(name = "external_key")
    private String externalKey;
    @Column(name = "external_url")
    private String externalUrl;
    @Column(name = "created_at")
    private OffsetDateTime createdAt = OffsetDateTime.now();
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt = createdAt;

    protected Issue() {}

    Issue(long id, String key, String idempotencyKey, String summary, String description, String priority, String team, String ticketKey, String runId) {
        this.id = id;
        this.key = key;
        this.idempotencyKey = idempotencyKey;
        this.summary = summary;
        this.description = description;
        this.priority = priority;
        this.team = team;
        this.ticketKey = ticketKey;
        this.runId = runId;
    }

    void linkExternal(String key, String url) {
        this.externalKey = key;
        this.externalUrl = url;
    }

    void setStatus(Status s) {
        this.status = s.name();
        this.updatedAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public String getKey() { return key; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getSummary() { return summary; }
    public String getDescription() { return description; }
    public String getPriority() { return priority; }
    public String getTeam() { return team; }
    public String getTicketKey() { return ticketKey; }
    public String getRunId() { return runId; }
    public String getStatus() { return status; }
    public String getExternalKey() { return externalKey; }
    public String getExternalUrl() { return externalUrl; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
