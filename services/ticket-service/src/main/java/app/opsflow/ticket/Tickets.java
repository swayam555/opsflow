package app.opsflow.ticket;

import app.opsflow.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Ticket changes. Every change writes an audit event in the same transaction; work for other services becomes a job. */
@Service
public class Tickets {
    private static final Logger log = LoggerFactory.getLogger(Tickets.class);

    private final TicketRepository tickets;
    private final EventRepository events;
    private final JobRepository jobs;

    public Tickets(TicketRepository tickets, EventRepository events, JobRepository jobs) {
        this.tickets = tickets;
        this.events = events;
        this.jobs = jobs;
    }

    Ticket find(String idOrKey) {
        String k = idOrKey.trim().toUpperCase(Locale.ROOT);
        if (k.matches("\\d{1,18}")) return tickets.findById(Long.parseLong(k)).orElseThrow(() -> ApiException.notFound("Ticket"));
        return tickets.findByKey(k).orElseThrow(() -> ApiException.notFound("Ticket"));
    }

    void event(Ticket t, String type, String message, String actor) {
        events.save(new TicketEvent(t.getId(), type, message, actor));
    }

    @Transactional
    public Ticket create(String title, String description, String reporter, String priority, String workflow, String actor) {
        Ticket t = tickets.save(new Ticket(tickets.nextNumber(), title, description, reporter, priority, workflow, actor));
        event(t, "CREATED", "Ticket created" + (reporter == null ? "" : " for " + reporter)
                + (priority == null ? "" : ", priority set to " + priority + " by hand")
                + (workflow == null ? "" : ", workflow '" + workflow + "' requested"), actor);
        jobs.save(new Job(t.getId(), Job.Type.TRIAGE, workflow, true, "auto"));
        return t;
    }

    @Transactional
    public Ticket update(String idOrKey, String priority, String team, String status, String actor) {
        Ticket t = find(idOrKey);
        List<String> changes = new ArrayList<>();
        if (priority != null && !priority.equals(t.getPriority())) {
            changes.add("priority " + (t.getPriority() == null ? "(none)" : t.getPriority()) + " → " + priority);
            t.setPriority(priority);
        }
        if (team != null && !team.equals(t.getOwnerTeam())) {
            changes.add("owner " + (t.getOwnerTeam() == null ? "(none)" : t.getOwnerTeam()) + " → " + team);
            t.setTeam(team);
        }
        if (!changes.isEmpty()) event(t, "CHANGED", "Changed " + String.join(", ", changes), actor);
        if ("RESOLVED".equals(status)) {
            if (Ticket.Status.RESOLVED.name().equals(t.getStatus())) throw ApiException.conflict("already_resolved", "This ticket is already resolved");
            t.resolve();
            event(t, "RESOLVED", "Ticket resolved; open tracker issues will be closed", actor);
            jobs.save(new Job(t.getId(), Job.Type.CLOSE_ISSUES, null, false, "auto"));
        } else if ("REOPENED".equals(status)) {
            if (!Ticket.Status.RESOLVED.name().equals(t.getStatus())) throw ApiException.conflict("not_resolved", "Only a resolved ticket can be reopened");
            t.reopen();
            event(t, "REOPENED", "Ticket reopened", actor);
        }
        return tickets.save(t);
    }

    @Transactional
    public TicketEvent note(String idOrKey, String text, String actor) {
        Ticket t = find(idOrKey);
        t.touch();
        tickets.save(t);
        return events.save(new TicketEvent(t.getId(), "NOTE", text, actor));
    }

    @Transactional
    public Job retriage(String idOrKey, boolean rerun, String actor) {
        Ticket t = find(idOrKey);
        busy(t);
        event(t, "RETRIAGE_REQUESTED", "Triage requested again" + (rerun ? ", then a new workflow run" : ""), actor);
        return jobs.save(new Job(t.getId(), Job.Type.TRIAGE, null, rerun, "manual"));
    }

    @Transactional
    public Job runWorkflow(String idOrKey, String workflow, String actor) {
        Ticket t = find(idOrKey);
        if (t.getTriagedAt() == null) throw ApiException.conflict("not_triaged", "Wait for triage to finish before starting a workflow");
        if (Ticket.Status.RESOLVED.name().equals(t.getStatus())) throw ApiException.conflict("resolved", "Reopen the ticket before running a workflow");
        busy(t);
        if ("RUNNING".equals(t.getRunStatus())) throw ApiException.conflict("run_in_progress", "A workflow is already running for this ticket");
        event(t, "RUN_REQUESTED", "Workflow run requested" + (workflow == null ? " (template picked automatically)" : ": " + workflow), actor);
        return jobs.save(new Job(t.getId(), Job.Type.START_WORKFLOW, workflow, false, "manual"));
    }

    private void busy(Ticket t) {
        if (!jobs.findByTicketIdAndStatus(t.getId(), Job.Status.PENDING.name()).isEmpty()) {
            throw ApiException.conflict("work_pending", "This ticket still has work waiting (see the System page); try again when it is done");
        }
    }

    // ---- results coming back from other services ----

    @Transactional
    public void applyTriage(Job job, JsonNode v) {
        Ticket t = tickets.findById(job.getTicketId()).orElseThrow();
        List<String> matched = new ArrayList<>();
        v.path("matched").forEach(m -> matched.add(m.asString()));
        String before = t.getPriority() + "/" + t.getOwnerTeam();
        t.triaged(v.path("priority").asString(), v.path("ownerTeam").asString(), v.path("reason").asString(null),
                v.path("source").asString("rules"), v.path("confidence").asString(null), matched, v.path("note").asString(null));
        String how = "llm".equals(t.getTriageSource()) ? "AI triage" : "Rule triage";
        String msg = how + ": " + t.getPriority() + (t.isPriorityOverridden() ? " (kept, set by hand)" : "")
                + ", owner " + t.getOwnerTeam() + (t.isTeamOverridden() ? " (kept, set by hand)" : "")
                + ". " + (t.getTriageReason() == null ? "" : t.getTriageReason());
        if (t.getTriageNote() != null) msg += " " + t.getTriageNote();
        event(t, job.getTrigger().equals("auto") ? "TRIAGED" : "RETRIAGED", msg.trim(), "triage-service");
        tickets.save(t);
        if (job.isRerun() && !Ticket.Status.RESOLVED.name().equals(t.getStatus())) {
            jobs.save(new Job(t.getId(), Job.Type.START_WORKFLOW, job.getTrigger().equals("auto") ? t.getRequestedWorkflow() : null, false, job.getTrigger()));
        }
        log.info("{} triaged {} → {}", t.getKey(), before, t.getPriority() + "/" + t.getOwnerTeam());
    }

    @Transactional
    public void applyRunStarted(Job job, JsonNode run) {
        Ticket t = tickets.findById(job.getTicketId()).orElseThrow();
        t.runStarted(run.path("id").asString(), run.path("workflowId").asString(), run.path("workflowName").asString());
        event(t, "WORKFLOW_STARTED", "Workflow '" + t.getWorkflowName() + "' started (" + run.path("steps").size() + " steps)", "orchestrator");
        tickets.save(t);
    }

    @Transactional
    public void runFinished(String runId, Map<String, Object> body) {
        Long ticketId = body.get("ticketId") instanceof Number n ? n.longValue() : null;
        if (ticketId == null) throw ApiException.invalid("ticketId", "ticketId is required");
        Ticket t = tickets.findById(ticketId).orElseThrow(() -> ApiException.notFound("Ticket"));
        String status = String.valueOf(body.get("status"));
        String marker = runId + ":" + body.get("attempt") + ":" + status;
        if (marker.equals(t.getLastReport())) return; // the same report again
        String error = body.get("error") == null || String.valueOf(body.get("error")).isBlank() ? null : String.valueOf(body.get("error"));
        int warnings = body.get("warnings") instanceof Number w ? w.intValue() : 0;
        String name = String.valueOf(body.getOrDefault("workflowName", body.get("workflowId")));
        if (runId.equals(t.getCurrentRunId())) {
            t.runFinished(status, error);
        }
        t.reported(marker);
        if ("COMPLETED".equals(status)) {
            event(t, "WORKFLOW_COMPLETED", "Workflow '" + name + "' completed" + (warnings > 0 ? " with " + warnings + " failed optional step(s)" : ""), "orchestrator");
        } else {
            event(t, "WORKFLOW_FAILED", "Workflow '" + name + "' failed. " + (error == null ? "" : error), "orchestrator");
        }
        tickets.save(t);
    }

    @Transactional
    public void runRetried(String runId, String actor) {
        tickets.findByCurrentRunId(runId).ifPresent(t -> {
            t.runStarted(runId, t.getWorkflowId(), t.getWorkflowName());
            event(t, "WORKFLOW_RETRIED", "Failed workflow retried from the step that failed", actor);
            tickets.save(t);
        });
    }

    @Transactional
    public void jobGaveUp(Job job, String error) {
        tickets.findById(job.getTicketId()).ifPresent(t -> {
            event(t, "JOB_FAILED", switch (Job.Type.valueOf(job.getType())) {
                case TRIAGE -> "Triage could not be done: ";
                case START_WORKFLOW -> "The workflow could not be started: ";
                case CLOSE_ISSUES -> "Tracker issues could not be closed: ";
            } + error, "system");
            t.touch();
            tickets.save(t);
        });
    }

    @Transactional
    public void issuesClosed(Job job, JsonNode res) {
        tickets.findById(job.getTicketId()).ifPresent(t -> {
            List<String> keys = new ArrayList<>();
            res.path("closed").forEach(k -> keys.add(k.asString()));
            if (!keys.isEmpty()) event(t, "ISSUES_CLOSED", "Closed tracker issue(s) " + String.join(", ", keys), "tracker");
        });
    }
}
