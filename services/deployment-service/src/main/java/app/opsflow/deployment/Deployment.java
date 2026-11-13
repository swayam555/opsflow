package app.opsflow.deployment;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "deployment")
public class Deployment {
    public enum Status { QUEUED, RUNNING, SUCCEEDED, FAILED }

    @Id
    private String id = UUID.randomUUID().toString();
    @Column(name = "deploy_key")
    private String key;
    @Column(name = "idempotency_key")
    private String idempotencyKey;
    private String environment;
    private String service;
    private String version;
    private String reason;
    @Column(name = "ticket_key")
    private String ticketKey;
    @Column(name = "run_id")
    private String runId;
    private String status = Status.QUEUED.name();
    @Column(name = "failure_reason")
    private String failureReason;
    private String log = "";
    @Column(name = "created_at")
    private OffsetDateTime createdAt = OffsetDateTime.now();
    @Column(name = "started_at")
    private OffsetDateTime startedAt;
    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;
    @Version
    @Column(name = "version_lock")
    @JsonIgnore
    private long versionLock;

    protected Deployment() {}

    Deployment(String key, String idempotencyKey, String environment, String service, String version, String reason, String ticketKey, String runId) {
        this.key = key;
        this.idempotencyKey = idempotencyKey;
        this.environment = environment;
        this.service = service;
        this.version = version;
        this.reason = reason;
        this.ticketKey = ticketKey;
        this.runId = runId;
        line("Queued " + service + " " + version + " for " + environment);
    }

    void line(String text) {
        String l = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS) + "  " + text + "\n";
        log = (log + l).length() > 7900 ? log : log + l;
    }

    void start() {
        status = Status.RUNNING.name();
        startedAt = OffsetDateTime.now();
    }

    void finish(boolean ok, String why) {
        status = (ok ? Status.SUCCEEDED : Status.FAILED).name();
        failureReason = ok ? null : why;
        finishedAt = OffsetDateTime.now();
    }

    public boolean finished() {
        return Status.SUCCEEDED.name().equals(status) || Status.FAILED.name().equals(status);
    }

    public String getId() { return id; }
    public String getKey() { return key; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getEnvironment() { return environment; }
    public String getService() { return service; }
    public String getVersion() { return version; }
    public String getReason() { return reason; }
    public String getTicketKey() { return ticketKey; }
    public String getRunId() { return runId; }
    public String getStatus() { return status; }
    public String getFailureReason() { return failureReason; }
    public String getLog() { return log; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public OffsetDateTime getFinishedAt() { return finishedAt; }
}
