package app.opsflow.orchestrator;

import java.util.List;
import java.util.Map;

/**
 * A workflow template. {@code errors} is non-empty when the file is broken; such a template is listed but never run.
 * Empty {@code priorities} or {@code teams} match any value.
 */
public record Workflow(String id, String name, String description, int order, List<String> priorities, List<String> teams,
                       List<Map<String, Object>> steps, List<String> errors, String source) {
    public boolean valid() {
        return errors.isEmpty();
    }

    public boolean matches(String priority, String team) {
        return (priorities.isEmpty() || priorities.contains(priority)) && (teams.isEmpty() || teams.contains(team));
    }

    static String label(Map<String, Object> step) {
        Object l = step.get("label");
        if (l != null && !String.valueOf(l).isBlank()) return String.valueOf(l);
        String type = String.valueOf(step.get("type"));
        return switch (type) {
            case "notify" -> step.get("channel") != null ? "Notify " + step.get("channel") : "Notify the owning team";
            case "jira", "issue" -> "Open a tracker issue";
            case "deployment", "deploy" -> "Deploy to " + step.get("environment");
            case "log" -> "Record a note";
            case "complete" -> "Complete";
            default -> type;
        };
    }
}
