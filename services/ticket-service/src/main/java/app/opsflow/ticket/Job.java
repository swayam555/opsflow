package app.opsflow.ticket;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * Work that needs another service, written in the same transaction as the change that caused it,
 * then done in the background and retried with backoff while that service is down.
 */
@Entity
@Table(name = "job")
public class Job {
    public enum Type { TRIAGE, START_WORKFLOW, CLOSE_ISSUES }
    public enum Status { PENDING, DONE, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "ticket_id")
    private Long ticketId;
    private String type;
    /** START_WORKFLOW: the template to use (null picks one). */
    private String workflow;
    /** TRIAGE: start a workflow afterwards. */
    private boolean rerun = true;
    @Column(name = "job_trigger")
    private String trigger;
    private String status = Status.PENDING.name();
    private int attempts;
    @Column(name = "next_at")
    private OffsetDateTime nextAt = OffsetDateTime.now();
    @Column(name = "last_error")
    private String lastError;
    @Column(name = "created_at")
    private OffsetDateTime createdAt = OffsetDateTime.now();
    @Version
    private long version;

    protected Job() {}

    Job(Long ticketId, Type type, String workflow, boolean rerun, String trigger) {
        this.ticketId = ticketId;
        this.type = type.name();
        this.workflow = workflow;
        this.rerun = rerun;
        this.trigger = trigger;
    }

    void done() {
        status = Status.DONE.name();
        lastError = null;
    }

    void failedFor(String error, Duration wait) {
        attempts++;
        lastError = error.length() > 600 ? error.substring(0, 600) : error;
        nextAt = OffsetDateTime.now().plus(wait);
    }

    void giveUp(String error) {
        attempts++;
        status = Status.FAILED.name();
        lastError = error.length() > 600 ? error.substring(0, 600) : error;
    }

    void dueNow() {
        nextAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public Long getTicketId() { return ticketId; }
    public String getType() { return type; }
    public String getWorkflow() { return workflow; }
    public boolean isRerun() { return rerun; }
    public String getTrigger() { return trigger; }
    public String getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public OffsetDateTime getNextAt() { return nextAt; }
    public String getLastError() { return lastError; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
