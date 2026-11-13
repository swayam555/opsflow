package app.opsflow.deployment;

import app.opsflow.common.ApiException;
import app.opsflow.common.PageView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
public class DeploymentController {
    public record DeployRequest(@NotBlank(message = "Environment is required") @Size(max = 40) String environment,
                                @Size(max = 80) @Pattern(regexp = "^$|^[a-zA-Z0-9][a-zA-Z0-9._-]*$", message = "Service names use letters, digits, '.', '_' and '-'") String service,
                                @Size(max = 60) String version,
                                @Size(max = 500) String reason,
                                @Size(max = 40) String ticketKey,
                                @Size(max = 36) String runId) {}

    public record LegacyDeploy(@NotBlank(message = "Target environment is required") String target) {}

    public record Chaos(@Min(value = 0, message = "Use 0 to 10") @Max(value = 10, message = "Use 0 to 10") int failNext) {}

    public record FreezeRequest(@NotBlank(message = "Say why the environment is frozen") @Size(max = 300) String reason) {}

    private final DeploymentRepository repo;
    private final FreezeRepository freezes;
    private final Rollouts rollouts;
    private final TransactionTemplate tx;
    private final List<String> environments;

    public DeploymentController(DeploymentRepository repo, FreezeRepository freezes, Rollouts rollouts, TransactionTemplate tx,
                                @Value("${opsflow.deploy.environments}") String environments) {
        this.repo = repo;
        this.freezes = freezes;
        this.rollouts = rollouts;
        this.tx = tx;
        this.environments = Arrays.stream(environments.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** Starts a deployment and returns at once (202); poll GET /deployments/{id} for the outcome. */
    @PostMapping("/deployments")
    public ResponseEntity<Deployment> deploy(@Valid @RequestBody DeployRequest r, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        if (key != null && !key.isBlank()) {
            var existing = repo.findByIdempotencyKey(key);
            if (existing.isPresent()) return ResponseEntity.ok(existing.get());
        }
        String env = r.environment().trim().toLowerCase(Locale.ROOT);
        if (!environments.contains(env)) {
            throw ApiException.invalid("environment", "Unknown environment '" + r.environment().trim() + "'. Use one of: " + String.join(", ", environments));
        }
        freezes.findById(env).ifPresent(f -> {
            throw ApiException.conflict("environment_frozen", env + " is frozen: " + f.getReason());
        });
        Deployment d;
        try {
            d = tx.execute(s -> repo.saveAndFlush(new Deployment("DEP-" + repo.nextNumber(), blank(key), env,
                    blank(r.service()) == null ? "core-api" : r.service().trim(),
                    blank(r.version()) == null ? "latest" : r.version().trim(),
                    blank(r.reason()), blank(r.ticketKey()), blank(r.runId()))));
        } catch (DataIntegrityViolationException race) {
            return ResponseEntity.ok(repo.findByIdempotencyKey(key).orElseThrow(() -> race));
        }
        rollouts.start(d.getId());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(d);
    }

    @PostMapping("/deploy")
    public ResponseEntity<Deployment> legacy(@Valid @RequestBody LegacyDeploy r) {
        return deploy(new DeployRequest(r.target(), null, null, "Requested through /deploy", null, null), null);
    }

    @GetMapping("/deployments/{idOrKey}")
    public Deployment one(@PathVariable String idOrKey) {
        return (idOrKey.startsWith("DEP-") ? repo.findByKey(idOrKey) : repo.findById(idOrKey))
                .orElseThrow(() -> ApiException.notFound("Deployment"));
    }

    @GetMapping("/deployments")
    public PageView<Deployment> list(@RequestParam(required = false) String environment, @RequestParam(required = false) String status,
                                     @RequestParam(required = false) String ticket,
                                     @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return PageView.of(repo.search(blank(environment), blank(status), blank(ticket),
                PageRequest.of(PageView.page(page), PageView.size(size), Sort.by(Sort.Direction.DESC, "createdAt"))), d -> d);
    }

    @GetMapping("/deployments/settings")
    public Map<String, Object> settings() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("environments", environments);
        m.put("frozen", freezes.findAll());
        m.put("failNext", rollouts.failNext.get());
        return m;
    }

    /** Fault injection: the next N rollouts fail their health check. */
    @PostMapping("/deployments/chaos")
    public Map<String, Object> chaos(@Valid @RequestBody Chaos c) {
        rollouts.failNext.set(c.failNext());
        return settings();
    }

    @PutMapping("/deployments/freezes/{environment}")
    public Map<String, Object> freeze(@PathVariable String environment, @Valid @RequestBody FreezeRequest r) {
        String env = environment.toLowerCase(Locale.ROOT);
        if (!environments.contains(env)) throw ApiException.notFound("Environment");
        freezes.save(new Freeze(env, r.reason().trim()));
        return settings();
    }

    @DeleteMapping("/deployments/freezes/{environment}")
    public Map<String, Object> unfreeze(@PathVariable String environment) {
        freezes.findById(environment.toLowerCase(Locale.ROOT)).ifPresent(freezes::delete);
        return settings();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
