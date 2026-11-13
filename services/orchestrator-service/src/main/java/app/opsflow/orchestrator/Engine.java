package app.opsflow.orchestrator;

import app.opsflow.common.DownstreamException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Drives runs. Each run executes on its own virtual thread, one step at a time. A step that fails for a reason a
 * retry can fix (a service down, a 5xx) is retried with exponential backoff up to its {@code retries}; a refusal
 * (4xx) or a failed deployment fails it at once. {@code continueOnError} lets the run go on past a failed step.
 * Runs still RUNNING when the service starts are resumed from their current step; the outcome of every finished
 * run is reported to ticket-service until it is accepted.
 */
@Component
public class Engine {
    private static final Logger log = LoggerFactory.getLogger(Engine.class);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final RunRepository runs;
    private final StepRepository steps;
    private final StepRunner runner;
    private final Clients clients;
    private final long retryBaseMillis;
    private final ObjectMapper json = new ObjectMapper();
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<String> active = ConcurrentHashMap.newKeySet();

    public Engine(RunRepository runs, StepRepository steps, StepRunner runner, Clients clients,
                  @Value("${opsflow.orchestrator.retry-base-millis:1000}") long retryBaseMillis) {
        this.runs = runs;
        this.steps = steps;
        this.runner = runner;
        this.clients = clients;
        this.retryBaseMillis = retryBaseMillis;
    }

    @EventListener(ApplicationReadyEvent.class)
    void resume() {
        for (Run r : runs.findByStatus(Run.Status.RUNNING.name())) {
            log.info("Resuming run {} ({}) at step {}", r.getId(), r.getWorkflowId(), r.getCurrentStep() + 1);
            start(r.getId());
        }
    }

    void start(String runId) {
        pool.submit(() -> drive(runId));
    }

    boolean isActive(String runId) {
        return active.contains(runId);
    }

    void drive(String runId) {
        if (!active.add(runId)) return;
        try {
            Run run = runs.findById(runId).orElseThrow();
            if (!Run.Status.RUNNING.name().equals(run.getStatus())) return;
            String failure = null;
            for (RunStep step : steps.findByRunIdOrderByIdx(runId)) {
                if (!RunStep.Status.PENDING.name().equals(step.getStatus()) && !RunStep.Status.RUNNING.name().equals(step.getStatus())) continue;
                run.at(step.getIdx());
                run = runs.save(run);
                Map<String, Object> cfg = json.readValue(step.getConfig(), MAP);
                RunStep done = execute(run, step, cfg);
                if (!RunStep.Status.SUCCEEDED.name().equals(done.getStatus())) {
                    if (Boolean.TRUE.equals(cfg.get("continueOnError"))) {
                        run.warn();
                        run = runs.save(run);
                        continue;
                    }
                    failure = "Step " + (step.getIdx() + 1) + " (" + step.getLabel() + ") failed: " + done.getDetail();
                    break;
                }
            }
            run.finish(failure == null ? Run.Status.COMPLETED : Run.Status.FAILED, failure);
            run = runs.save(run);
            log.info("Run {} for {} {}{}", run.getId(), run.getTicketKey(), run.getStatus(), failure == null ? "" : ": " + failure);
            report(run);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Run {} crashed; it stays RUNNING and resumes on the next start", runId, e);
        } finally {
            active.remove(runId);
        }
    }

    /** Runs one step to its end and returns it as saved (SUCCEEDED or FAILED). */
    private RunStep execute(Run run, RunStep step, Map<String, Object> cfg) throws InterruptedException {
        int retries = cfg.get("retries") instanceof Integer r ? r : 2;
        for (int attempt = 1; ; attempt++) {
            step.attempt(attempt == 1 ? "Running" : "Attempt " + attempt);
            RunStep current = steps.save(step);
            try {
                var out = runner.run(run, current, cfg, note -> {
                    current.progress(note);
                    steps.save(current);
                });
                current.succeed(out.detail(), out.reference(), out.link());
                return steps.save(current);
            } catch (StepFailed e) {
                current.fail(e.getMessage());
                return steps.save(current);
            } catch (DownstreamException e) {
                if (!e.retryable() || attempt > retries) {
                    current.fail(e.getMessage() + (e.retryable() ? " (gave up after " + attempt + " attempts)" : ""));
                    return steps.save(current);
                }
                long wait = Math.min(30_000, retryBaseMillis * (1L << (attempt - 1)));
                current.progress(e.getMessage() + "; retrying in " + Math.max(1, wait / 1000) + " s");
                step = steps.save(current);
                Thread.sleep(wait);
            }
        }
    }

    /** Tells ticket-service how a run ended. Failures are left for the sweep. */
    void report(Run run) {
        if (run.isReported() || run.getTicketId() == null) return;
        try {
            clients.tickets.post("/internal/runs/{id}/finished", Map.of(
                    "runId", run.getId(), "ticketId", run.getTicketId(), "workflowId", run.getWorkflowId(),
                    "workflowName", run.getWorkflowName(), "status", run.getStatus(), "warnings", run.getWarnings(),
                    "error", run.getError() == null ? "" : run.getError(), "attempt", run.getAttempt()), Void.class, null, run.getId());
            Run fresh = runs.findById(run.getId()).orElseThrow();
            if (fresh.getStatus().equals(run.getStatus()) && fresh.getAttempt() == run.getAttempt()) {
                fresh.markReported();
                runs.save(fresh);
            }
        } catch (DownstreamException e) {
            log.warn("Could not report run {} to ticket-service yet: {}", run.getId(), e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${opsflow.orchestrator.report-sweep-millis:5000}", initialDelay = 5000)
    void sweepReports() {
        for (Run r : runs.findTop50ByReportedFalseAndStatusInOrderByFinishedAtAsc(List.of(Run.Status.COMPLETED.name(), Run.Status.FAILED.name()))) {
            report(r);
        }
    }
}
