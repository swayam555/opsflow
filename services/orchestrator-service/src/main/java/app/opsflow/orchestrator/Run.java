package app.opsflow.orchestrator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "workflow_run")
public class Run {
    public enum Status { RUNNING, COMPLETED, FAILED }

    @Id
    private String id = UUID.randomUUID().toString();
    @Column(name = "idempotency_key")
    private String idempotencyKey;
    @Column(name = "ticket_id")
    private Long ticketId;
    @Column(name = "ticket_key")
    private String ticketKey;
    private String title;
    private String description;
    private String priority;
    private String team;
    @Column(name = "workflow_id")
    private String workflowId;
    @Column(name = "workflow_name")
    private String workflowName;
    @Column(name = "run_trigger")
    private String trigger;
    private String status = Status.RUNNING.name();
    private int attempt = 1;
    @Column(name = "current_step")
    private int currentStep;
    private int warnings;
    private String error;
    private boolean reported;
    @Column(name = "created_at")
    private OffsetDateTime createdAt = OffsetDateTime.now();
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt = createdAt;
    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;
    @Version
    private long version;
    @Transient
    private List<RunStep> steps = List.of();

    protected Run() {}

    Run(String idempotencyKey, Long ticketId, String ticketKey, String title, String description, String priority, String team,
        Workflow w, String trigger) {
        this.idempotencyKey = idempotencyKey;
        this.ticketId = ticketId;
        this.ticketKey = ticketKey;
        this.title = title;
        this.description = description;
        this.priority = priority;
        this.team = team;
        this.workflowId = w.id();
        this.workflowName = w.name();
        this.trigger = trigger;
    }

    void at(int step) {
        currentStep = step;
        updatedAt = OffsetDateTime.now();
    }

    void warn() {
        warnings++;
    }

    void finish(Status s, String error) {
        status = s.name();
        this.error = error;
        finishedAt = OffsetDateTime.now();
        updatedAt = finishedAt;
        reported = ticketId == null; // runs without a ticket have nobody to tell
    }

    void retry() {
        attempt++;
        status = Status.RUNNING.name();
        error = null;
        finishedAt = null;
        reported = false;
        updatedAt = OffsetDateTime.now();
    }

    void markReported() {
        reported = true;
    }

    Run withSteps(List<RunStep> s) {
        this.steps = s;
        return this;
    }

    public String getId() { return id; }
    public Long getTicketId() { return ticketId; }
    public String getTicketKey() { return ticketKey; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getPriority() { return priority; }
    public String getTeam() { return team; }
    public String getWorkflowId() { return workflowId; }
    public String getWorkflowName() { return workflowName; }
    public String getTrigger() { return trigger; }
    public String getStatus() { return status; }
    public int getAttempt() { return attempt; }
    public int getCurrentStep() { return currentStep; }
    public int getWarnings() { return warnings; }
    public String getError() { return error; }
    public boolean isReported() { return reported; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public OffsetDateTime getFinishedAt() { return finishedAt; }
    public List<RunStep> getSteps() { return steps; }
}
