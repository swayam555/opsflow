package app.opsflow.orchestrator;

import app.opsflow.common.ApiException;
import app.opsflow.common.PageView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
public class RunController {
    public record StartRequest(Long ticketId, @Size(max = 40) String ticketKey, @Size(max = 200) String title,
                               @Size(max = 5000) String description, @Size(max = 10) String priority, @Size(max = 60) String team,
                               @Size(max = 60) String workflow, @Size(max = 20) String trigger) {}

    private final RunRepository runs;
    private final StepRepository steps;
    private final WorkflowCatalog catalog;
    private final Engine engine;
    private final TransactionTemplate tx;
    private final ObjectMapper json = new ObjectMapper();

    public RunController(RunRepository runs, StepRepository steps, WorkflowCatalog catalog, Engine engine, TransactionTemplate tx) {
        this.runs = runs;
        this.steps = steps;
        this.catalog = catalog;
        this.engine = engine;
        this.tx = tx;
    }

    /** Starts a run. Without "workflow" the template is picked from priority and team. Returns at once; poll GET /runs/{id}. */
    @PostMapping("/runs")
    public ResponseEntity<Run> start(@Valid @RequestBody StartRequest r, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        if (key != null && !key.isBlank()) {
            var existing = runs.findByIdempotencyKey(key);
            if (existing.isPresent()) return ResponseEntity.ok(withSteps(existing.get()));
        }
        Workflow w;
        if (r.workflow() != null && !r.workflow().isBlank()) {
            w = catalog.get(r.workflow().trim()).orElseThrow(() -> ApiException.invalid("workflow", "There is no workflow called '" + r.workflow().trim() + "'"));
            if (!w.valid()) throw ApiException.conflict("workflow_invalid", "Workflow '" + w.id() + "' has errors: " + String.join("; ", w.errors()));
        } else {
            w = catalog.select(r.priority(), r.team())
                    .orElseThrow(() -> ApiException.conflict("no_workflow", "No workflow matches and the default '" + catalog.defaultId() + "' is missing or broken"));
        }
        Run run;
        try {
            run = tx.execute(s -> {
                Run created = runs.saveAndFlush(new Run(blank(key), r.ticketId(), blank(r.ticketKey()), blank(r.title()), blank(r.description()),
                        blank(r.priority()), blank(r.team()), w, r.trigger() == null || r.trigger().isBlank() ? "manual" : r.trigger()));
                int i = 0;
                for (Map<String, Object> step : w.steps()) {
                    steps.save(new RunStep(created.getId(), i++, String.valueOf(step.get("type")), Workflow.label(step), json.writeValueAsString(step)));
                }
                return created;
            });
        } catch (DataIntegrityViolationException race) {
            return ResponseEntity.ok(withSteps(runs.findByIdempotencyKey(key).orElseThrow(() -> race)));
        }
        engine.start(run.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(withSteps(run));
    }

    /** The original orchestrator entry point: run a named template with no ticket attached. */
    @PostMapping("/orchestrate/start")
    public ResponseEntity<Run> legacy(@RequestParam String workflow) {
        return start(new StartRequest(null, null, "Manual run of " + workflow, null, "P3", null, workflow, "manual"), null);
    }

    @GetMapping("/runs/{id}")
    public Run one(@PathVariable String id) {
        return withSteps(runs.findById(id).orElseThrow(() -> ApiException.notFound("Run")));
    }

    @GetMapping("/runs")
    public PageView<Run> list(@RequestParam(required = false) Long ticketId, @RequestParam(required = false) String status,
                              @RequestParam(required = false) String workflow,
                              @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        var p = runs.search(ticketId, blank(status), blank(workflow),
                PageRequest.of(PageView.page(page), PageView.size(size), Sort.by(Sort.Direction.DESC, "createdAt")));
        Map<String, List<RunStep>> byRun = p.isEmpty() ? Map.of()
                : steps.findByRunIdInOrderByIdx(p.getContent().stream().map(Run::getId).toList()).stream()
                .collect(Collectors.groupingBy(RunStep::getRunId, LinkedHashMap::new, Collectors.toCollection(ArrayList::new)));
        return PageView.of(p, r -> r.withSteps(byRun.getOrDefault(r.getId(), List.of())));
    }

    @GetMapping("/runs/stats")
    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String s : List.of("RUNNING", "COMPLETED", "FAILED")) m.put(s, 0L);
        for (Object[] row : runs.countByStatus()) m.put(String.valueOf(row[0]), row[1]);
        return m;
    }

    /** Re-runs a FAILED run from the step that failed. Earlier steps are not repeated. */
    @PostMapping("/runs/{id}/retry")
    public Run retry(@PathVariable String id) {
        Run run = tx.execute(s -> {
            Run r = runs.findById(id).orElseThrow(() -> ApiException.notFound("Run"));
            if (!Run.Status.FAILED.name().equals(r.getStatus())) {
                throw ApiException.conflict("not_failed", "Only a failed run can be retried; this one is " + r.getStatus().toLowerCase());
            }
            for (RunStep st : steps.findByRunIdOrderByIdx(id)) {
                if (st.getIdx() >= r.getCurrentStep()) {
                    st.reset();
                    steps.save(st);
                }
            }
            r.retry();
            return runs.save(r);
        });
        engine.start(run.getId());
        return withSteps(run);
    }

    private Run withSteps(Run r) {
        return r.withSteps(steps.findByRunIdOrderByIdx(r.getId()));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
