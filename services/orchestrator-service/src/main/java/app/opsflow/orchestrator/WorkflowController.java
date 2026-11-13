package app.opsflow.orchestrator;

import app.opsflow.common.ApiException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class WorkflowController {
    private final WorkflowCatalog catalog;

    public WorkflowController(WorkflowCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/workflows")
    public List<Map<String, Object>> all() {
        return catalog.all().stream().map(this::view).toList();
    }

    @GetMapping("/workflows/select")
    public Map<String, Object> select(@RequestParam(required = false) String priority, @RequestParam(required = false) String team) {
        Workflow w = catalog.select(priority, team).orElseThrow(() -> ApiException.notFound("Matching workflow"));
        return Map.of("id", w.id(), "name", w.name());
    }

    @GetMapping("/workflows/{id}")
    public Map<String, Object> one(@PathVariable String id) {
        return view(catalog.get(id).orElseThrow(() -> ApiException.notFound("Workflow")));
    }

    private Map<String, Object> view(Workflow w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", w.id());
        m.put("name", w.name());
        m.put("description", w.description());
        m.put("order", w.order());
        m.put("priorities", w.priorities());
        m.put("teams", w.teams());
        m.put("isDefault", w.id().equals(catalog.defaultId()));
        m.put("valid", w.valid());
        m.put("errors", w.errors());
        m.put("source", w.source());
        m.put("steps", w.steps().stream().map(s -> {
            Map<String, Object> v = new LinkedHashMap<>(s);
            v.put("label", Workflow.label(s));
            return v;
        }).toList());
        return m;
    }
}
