package app.opsflow.ticket;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

@Entity
@Table(name = "ticket")
public class Ticket {
    /**
     * NEW (waiting for triage) → TRIAGED → IN_PROGRESS (workflow running) → ACTIONED (workflow done) or FAILED.
     * RESOLVED is set by a person; reopening goes back to TRIAGED.
     */
    public enum Status { NEW, TRIAGED, IN_PROGRESS, ACTIONED, FAILED, RESOLVED }

    @Id
    private Long id;
    @Column(name = "ticket_key")
    private String key;
    private String title;
    private String description;
    private String reporter;
    private String status = Status.NEW.name();
    private String priority;
    @Column(name = "owner_team")
    private String ownerTeam;
    @Column(name = "priority_overridden")
    private boolean priorityOverridden;
    @Column(name = "team_overridden")
    private boolean teamOverridden;
    @Column(name = "triage_reason")
    private String triageReason;
    @Column(name = "triage_source")
    private String triageSource;
    @Column(name = "triage_confidence")
    private String triageConfidence;
    @Column(name = "triage_matched")
    private String triageMatched;
    @Column(name = "triage_note")
    private String triageNote;
    @Column(name = "requested_workflow")
    private String requestedWorkflow;
    @Column(name = "workflow_id")
    private String workflowId;
    @Column(name = "workflow_name")
    private String workflowName;
    @Column(name = "current_run_id")
    private String currentRunId;
    @Column(name = "run_status")
    private String runStatus;
    @Column(name = "run_error")
    private String runError;
    @Column(name = "last_report")
    @JsonIgnore
    private String lastReport;
    @Column(name = "created_by")
    private String createdBy;
    @Column(name = "created_at")
    private OffsetDateTime createdAt = OffsetDateTime.now();
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt = createdAt;
    @Column(name = "triaged_at")
    private OffsetDateTime triagedAt;
    @Column(name = "actioned_at")
    private OffsetDateTime actionedAt;
    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;
    @Version
    private long version;

    protected Ticket() {}

    Ticket(long id, String title, String description, String reporter, String priority, String workflow, String createdBy) {
        this.id = id;
        this.key = "OPS-" + id;
        this.title = title;
        this.description = description;
        this.reporter = reporter;
        if (priority != null) {
            this.priority = priority;
            this.priorityOverridden = true;
        }
        this.requestedWorkflow = workflow;
        this.createdBy = createdBy;
    }

    void touch() {
        updatedAt = OffsetDateTime.now();
    }

    void triaged(String priority, String team, String reason, String source, String confidence, List<String> matched, String note) {
        if (!priorityOverridden) this.priority = priority;
        if (!teamOverridden) this.ownerTeam = team;
        this.triageReason = cut(reason, 600);
        this.triageSource = source;
        this.triageConfidence = confidence;
        this.triageMatched = matched == null || matched.isEmpty() ? null : cut(String.join(",", matched), 600);
        this.triageNote = cut(note, 300);
        this.triagedAt = OffsetDateTime.now();
        if (Status.NEW.name().equals(status)) status = Status.TRIAGED.name();
        touch();
    }

    void runStarted(String runId, String workflowId, String workflowName) {
        currentRunId = runId;
        this.workflowId = workflowId;
        this.workflowName = workflowName;
        runStatus = "RUNNING";
        runError = null;
        if (!Status.RESOLVED.name().equals(status)) status = Status.IN_PROGRESS.name();
        touch();
    }

    void runFinished(String runStatus, String error) {
        this.runStatus = runStatus;
        this.runError = cut(error, 1000);
        if (!Status.RESOLVED.name().equals(status)) {
            status = "COMPLETED".equals(runStatus) ? Status.ACTIONED.name() : Status.FAILED.name();
        }
        if ("COMPLETED".equals(runStatus)) actionedAt = OffsetDateTime.now();
        touch();
    }

    void setPriority(String p) {
        priority = p;
        priorityOverridden = true;
        touch();
    }

    void setTeam(String t) {
        ownerTeam = t;
        teamOverridden = true;
        touch();
    }

    void resolve() {
        status = Status.RESOLVED.name();
        resolvedAt = OffsetDateTime.now();
        touch();
    }

    void reopen() {
        status = triagedAt == null ? Status.NEW.name() : Status.TRIAGED.name();
        resolvedAt = null;
        touch();
    }

    void reported(String marker) {
        lastReport = marker;
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    public Long getId() { return id; }
    public String getKey() { return key; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getReporter() { return reporter; }
    public String getStatus() { return status; }
    public String getPriority() { return priority; }
    public String getOwnerTeam() { return ownerTeam; }
    public boolean isPriorityOverridden() { return priorityOverridden; }
    public boolean isTeamOverridden() { return teamOverridden; }
    public String getTriageReason() { return triageReason; }
    public String getTriageSource() { return triageSource; }
    public String getTriageConfidence() { return triageConfidence; }
    public List<String> getTriageMatched() { return triageMatched == null ? List.of() : Arrays.asList(triageMatched.split(",")); }
    public String getTriageNote() { return triageNote; }
    public String getRequestedWorkflow() { return requestedWorkflow; }
    public String getWorkflowId() { return workflowId; }
    public String getWorkflowName() { return workflowName; }
    public String getCurrentRunId() { return currentRunId; }
    public String getRunStatus() { return runStatus; }
    public String getRunError() { return runError; }
    @JsonIgnore
    public String getLastReport() { return lastReport; }
    public String getCreatedBy() { return createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public OffsetDateTime getTriagedAt() { return triagedAt; }
    public OffsetDateTime getActionedAt() { return actionedAt; }
    public OffsetDateTime getResolvedAt() { return resolvedAt; }
    public long getVersion() { return version; }
}
