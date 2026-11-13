package app.opsflow.orchestrator;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "run_step")
public class RunStep {
    public enum Status { PENDING, RUNNING, SUCCEEDED, FAILED }

    @Id
    private String id = UUID.randomUUID().toString();
    @Column(name = "run_id")
    @JsonIgnore
    private String runId;
    private int idx;
    private String type;
    private String label;
    /** The step exactly as the template had it when the run started, as JSON. */
    @JsonIgnore
    private String config;
    private String status = Status.PENDING.name();
    private int attempts;
    private String detail;
    private String reference;
    private String link;
    @Column(name = "started_at")
    private OffsetDateTime startedAt;
    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    protected RunStep() {}

    RunStep(String runId, int idx, String type, String label, String config) {
        this.runId = runId;
        this.idx = idx;
        this.type = type;
        this.label = label;
        this.config = config;
    }

    void attempt(String note) {
        attempts++;
        status = Status.RUNNING.name();
        if (startedAt == null) startedAt = OffsetDateTime.now();
        detail = note;
    }

    void progress(String note) {
        detail = note;
    }

    void succeed(String detail, String reference, String link) {
        status = Status.SUCCEEDED.name();
        this.detail = cut(detail);
        this.reference = reference;
        this.link = link;
        finishedAt = OffsetDateTime.now();
    }

    void fail(String detail) {
        status = Status.FAILED.name();
        this.detail = cut(detail);
        finishedAt = OffsetDateTime.now();
    }

    void reset() {
        status = Status.PENDING.name();
        attempts = 0;
        detail = null;
        startedAt = null;
        finishedAt = null;
    }

    private static String cut(String s) {
        return s == null || s.length() <= 1990 ? s : s.substring(0, 1990);
    }

    public String getId() { return id; }
    public String getRunId() { return runId; }
    public int getIdx() { return idx; }
    public String getType() { return type; }
    public String getLabel() { return label; }
    public String getConfig() { return config; }
    public String getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public String getDetail() { return detail; }
    public String getReference() { return reference; }
    public String getLink() { return link; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public OffsetDateTime getFinishedAt() { return finishedAt; }
}
