package app.opsflow.ticket;

import app.opsflow.common.DownstreamException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Does pending jobs: triage, starting workflows, closing tracker issues. Runs right after a change ({@link #kick()})
 * and every couple of seconds. A job whose service is down is retried with backoff (2 s doubling up to 1 min);
 * a job the service refuses is marked FAILED and noted on the ticket.
 */
@Component
public class Dispatcher {
    private static final Logger log = LoggerFactory.getLogger(Dispatcher.class);

    private final JobRepository jobs;
    private final Tickets tickets;
    private final TicketRepository ticketRepo;
    private final Services services;
    private final long baseBackoff;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> Thread.ofVirtual().name("jobs").unstarted(r));
    private final AtomicBoolean scheduled = new AtomicBoolean();

    public Dispatcher(JobRepository jobs, Tickets tickets, TicketRepository ticketRepo, Services services,
                      @Value("${opsflow.jobs.base-backoff-millis:2000}") long baseBackoff) {
        this.jobs = jobs;
        this.tickets = tickets;
        this.ticketRepo = ticketRepo;
        this.services = services;
        this.baseBackoff = baseBackoff;
    }

    /** Ask for a pass soon. Several kicks while a pass is queued collapse into one. */
    public void kick() {
        if (scheduled.compareAndSet(false, true)) {
            worker.submit(() -> {
                scheduled.set(false);
                drain();
            });
        }
    }

    @Scheduled(fixedDelayString = "${opsflow.jobs.sweep-millis:2000}", initialDelay = 2000)
    void sweep() {
        kick();
    }

    private void drain() {
        for (int round = 0; round < 50; round++) {
            var due = jobs.findTop20ByStatusAndNextAtBeforeOrderByIdAsc(Job.Status.PENDING.name(), OffsetDateTime.now().plusNanos(1_000_000));
            if (due.isEmpty()) return;
            boolean progressed = false;
            for (Job job : due) progressed |= process(job);
            if (!progressed) return;
        }
    }

    private boolean process(Job job) {
        try {
            switch (Job.Type.valueOf(job.getType())) {
                case TRIAGE -> triage(job);
                case START_WORKFLOW -> start(job);
                case CLOSE_ISSUES -> close(job);
            }
            job.done();
            jobs.save(job);
            return true;
        } catch (DownstreamException e) {
            if (e.retryable()) {
                long wait = Math.min(60_000, baseBackoff * (1L << Math.min(job.getAttempts(), 5)));
                job.failedFor(e.getMessage(), Duration.ofMillis(wait));
                log.warn("Job {} ({} for ticket {}) will retry in {} ms: {}", job.getId(), job.getType(), job.getTicketId(), wait, e.getMessage());
            } else {
                job.giveUp(e.getMessage());
                tickets.jobGaveUp(job, e.getMessage());
            }
            save(job);
            return false;
        } catch (RuntimeException e) {
            log.error("Job {} crashed", job.getId(), e);
            job.failedFor("Unexpected error: " + e.getClass().getSimpleName(), Duration.ofSeconds(30));
            save(job);
            return false;
        }
    }

    private void save(Job job) {
        try {
            jobs.save(job);
        } catch (OptimisticLockingFailureException ignored) {
            // someone pressed "retry now" at the same moment; the next pass sees their version
        }
    }

    private void triage(Job job) {
        Ticket t = ticketRepo.findById(job.getTicketId()).orElseThrow();
        Map<String, Object> body = new HashMap<>();
        body.put("ticketId", String.valueOf(t.getId()));
        body.put("title", t.getTitle());
        body.put("description", t.getDescription());
        JsonNode v = services.triage.post("/triage", body, JsonNode.class, null);
        tickets.applyTriage(job, v);
    }

    private void start(Job job) {
        Ticket t = ticketRepo.findById(job.getTicketId()).orElseThrow();
        if (Ticket.Status.RESOLVED.name().equals(t.getStatus())) return; // resolved while waiting: nothing to do
        Map<String, Object> body = new HashMap<>();
        body.put("ticketId", t.getId());
        body.put("ticketKey", t.getKey());
        body.put("title", t.getTitle());
        body.put("description", t.getDescription());
        body.put("priority", t.getPriority());
        body.put("team", t.getOwnerTeam());
        body.put("workflow", job.getWorkflow());
        body.put("trigger", job.getTrigger());
        JsonNode run = services.orchestrator.post("/runs", body, JsonNode.class, Map.of("Idempotency-Key", "ticket-job-" + job.getId()));
        tickets.applyRunStarted(job, run);
    }

    private void close(Job job) {
        Ticket t = ticketRepo.findById(job.getTicketId()).orElseThrow();
        JsonNode res = services.tracker.post("/issues/close-for-ticket", Map.of("ticketKey", t.getKey(), "note", "Resolved in Opsflow"), JsonNode.class, null);
        tickets.issuesClosed(job, res);
    }
}
