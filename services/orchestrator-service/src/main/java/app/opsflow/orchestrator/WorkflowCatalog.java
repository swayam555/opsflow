package app.opsflow.orchestrator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Loads workflow templates from classpath:workflows/*.yml and, optionally, a folder of your own (a file with the
 * same id replaces the built-in one). Broken files are kept with their errors so they show up in the console.
 */
@Component
public class WorkflowCatalog {
    private static final Logger log = LoggerFactory.getLogger(WorkflowCatalog.class);
    static final Set<String> TYPES = Set.of("notify", "jira", "issue", "deployment", "deploy", "log", "complete");

    private final Map<String, Workflow> byId = new LinkedHashMap<>();
    private final String defaultId;

    public WorkflowCatalog(@Value("${opsflow.orchestrator.workflow-dir:}") String dir,
                           @Value("${opsflow.orchestrator.default-workflow:incident-basic}") String defaultId) throws IOException {
        this.defaultId = defaultId;
        for (Resource r : new PathMatchingResourcePatternResolver().getResources("classpath:workflows/*.yml")) {
            try (InputStream in = r.getInputStream()) {
                add(parse(in, "built-in " + r.getFilename()));
            }
        }
        if (dir != null && !dir.isBlank() && Files.isDirectory(Path.of(dir))) {
            try (Stream<Path> files = Files.list(Path.of(dir))) {
                for (Path p : files.filter(f -> f.toString().endsWith(".yml") || f.toString().endsWith(".yaml")).sorted().toList()) {
                    try (InputStream in = Files.newInputStream(p)) {
                        add(parse(in, p.getFileName().toString()));
                    }
                }
            }
        }
        byId.values().stream().filter(w -> !w.valid()).forEach(w -> log.warn("Workflow {} ({}) is invalid: {}", w.id(), w.source(), w.errors()));
        if (get(defaultId).filter(Workflow::valid).isEmpty()) {
            log.warn("The default workflow '{}' is missing or invalid; tickets that match no template will fail to start", defaultId);
        }
    }

    private void add(Workflow w) {
        byId.put(w.id(), w);
    }

    @SuppressWarnings("unchecked")
    static Workflow parse(InputStream in, String source) {
        List<String> errors = new ArrayList<>();
        Map<String, Object> root;
        try {
            Object o = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
            root = o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            if (!(o instanceof Map)) errors.add("The file is not a YAML mapping");
        } catch (RuntimeException e) {
            root = Map.of();
            errors.add("Not valid YAML: " + e.getMessage().lines().findFirst().orElse(""));
        }
        String id = str(root.get("id"));
        if (id == null) {
            id = source.replaceAll("^built-in ", "").replaceAll("\\.ya?ml$", "");
            errors.add("Missing 'id'");
        } else if (!id.matches("[a-z0-9][a-z0-9-]{0,59}")) {
            errors.add("'id' must be lower-case letters, digits and dashes");
        }
        String name = Optional.ofNullable(str(root.get("name"))).orElse(id);
        int order = root.get("order") instanceof Number n ? n.intValue() : 500;
        Map<String, Object> match = root.get("match") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        List<String> priorities = strings(match.get("priorities"));
        List<String> teams = strings(match.get("teams"));

        List<Map<String, Object>> steps = new ArrayList<>();
        if (!(root.get("steps") instanceof List<?> list) || list.isEmpty()) {
            errors.add("'steps' must be a non-empty list");
        } else {
            int i = 1;
            for (Object s : list) {
                if (!(s instanceof Map<?, ?> step)) {
                    errors.add("Step " + i + " is not a mapping");
                    i++;
                    continue;
                }
                Map<String, Object> st = new LinkedHashMap<>((Map<String, Object>) step);
                String type = str(st.get("type"));
                if (type == null || !TYPES.contains(type)) {
                    errors.add("Step " + i + ": unknown type '" + type + "' (use notify, jira, deployment, log or complete)");
                } else if ((type.equals("deployment") || type.equals("deploy")) && str(st.get("environment")) == null) {
                    errors.add("Step " + i + ": a deployment needs an 'environment'");
                } else if ((type.equals("notify") || type.equals("log")) && str(st.get("message")) == null) {
                    errors.add("Step " + i + ": a " + type + " step needs a 'message'");
                }
                Object retries = st.get("retries");
                if (retries != null && !(retries instanceof Integer r && r >= 0 && r <= 5)) {
                    errors.add("Step " + i + ": 'retries' must be a whole number from 0 to 5");
                }
                steps.add(st);
                i++;
            }
        }
        return new Workflow(id, name, Optional.ofNullable(str(root.get("description"))).orElse(""), order, priorities, teams,
                List.copyOf(steps), List.copyOf(errors), source);
    }

    private static String str(Object o) {
        return o == null || String.valueOf(o).isBlank() ? null : String.valueOf(o).trim();
    }

    private static List<String> strings(Object o) {
        if (o instanceof List<?> l) return l.stream().map(String::valueOf).map(String::trim).toList();
        if (o != null && !String.valueOf(o).isBlank()) return List.of(String.valueOf(o).trim());
        return List.of();
    }

    public List<Workflow> all() {
        return byId.values().stream().sorted(Comparator.comparingInt(Workflow::order).thenComparing(Workflow::id)).toList();
    }

    public Optional<Workflow> get(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** The first valid template (by order) that matches, else the default. */
    public Optional<Workflow> select(String priority, String team) {
        return all().stream().filter(Workflow::valid).filter(w -> !w.id().equals(defaultId))
                .filter(w -> w.matches(priority, team)).findFirst()
                .or(() -> get(defaultId).filter(Workflow::valid));
    }

    public String defaultId() {
        return defaultId;
    }
}
