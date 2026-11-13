package app.opsflow.deployment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Plays out a deployment in the background: pull, checks, a rolling update over three instances, health checks.
 * {@link #failNext} makes the next N rollouts fail at the health check, to exercise failure handling end to end.
 * Rollouts that were in flight when the service stopped are resumed on start.
 */
@Component
public class Rollouts {
    private static final Logger log = LoggerFactory.getLogger(Rollouts.class);
    private final DeploymentRepository repo;
    private final long stepMillis;
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    final AtomicInteger failNext = new AtomicInteger();

    public Rollouts(DeploymentRepository repo, @Value("${opsflow.deploy.step-millis:900}") long stepMillis) {
        this.repo = repo;
        this.stepMillis = stepMillis;
    }

    @EventListener(ApplicationReadyEvent.class)
    void resumeInFlight() {
        for (Deployment d : repo.findByStatusIn(List.of(Deployment.Status.QUEUED.name(), Deployment.Status.RUNNING.name()))) {
            d.line("Deployment service restarted; resuming the rollout");
            repo.save(d);
            start(d.getId());
        }
    }

    void start(String id) {
        pool.submit(() -> {
            try {
                play(id);
            } catch (Exception e) {
                log.error("Rollout {} crashed", id, e);
                repo.findById(id).ifPresent(d -> {
                    if (!d.finished()) {
                        d.line("Rollout crashed: " + e.getClass().getSimpleName());
                        d.finish(false, "The rollout crashed inside the deployment service");
                        repo.save(d);
                    }
                });
            }
        });
    }

    private void play(String id) throws InterruptedException {
        boolean fail = failNext.getAndUpdate(n -> Math.max(0, n - 1)) > 0;
        Deployment d = repo.findById(id).orElseThrow();
        if (d.finished()) return;
        d.start();
        d.line("Pulling " + d.getService() + ":" + d.getVersion());
        d = repo.save(d);
        Thread.sleep(stepMillis);
        d.line("Pre-deploy checks passed (config, migrations, capacity)");
        d = repo.save(d);
        for (int i = 1; i <= 3; i++) {
            Thread.sleep(stepMillis / 2);
            if (fail && i == 2) {
                d.line("Instance 2/3 failed its health check (HTTP 503 on /health)");
                d.line("Rolling back to the previous version");
                d.finish(false, "Health check failed on instance 2 of 3; rolled back");
                repo.save(d);
                log.info("Deployment {} to {} FAILED (injected)", d.getKey(), d.getEnvironment());
                return;
            }
            d.line("Instance " + i + "/3 updated and healthy");
            d = repo.save(d);
        }
        Thread.sleep(stepMillis / 2);
        d.line("Rollout complete: " + d.getService() + " " + d.getVersion() + " is live on " + d.getEnvironment());
        d.finish(true, null);
        repo.save(d);
        log.info("Deployment {} to {} SUCCEEDED", d.getKey(), d.getEnvironment());
    }
}
