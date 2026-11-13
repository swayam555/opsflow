package app.opsflow.triage;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Keyword rules read from YAML. Keywords match as whole words or phrases, so "ci" never matches inside "decision". */
public final class TriageRules {
    record Level(String level, String reason, List<Keyword> keywords) {}
    record Team(String team, List<Keyword> keywords) {}
    record Keyword(String word, Pattern pattern) {}

    private final List<Level> priorities;
    private final String defaultPriority;
    private final String defaultPriorityReason;
    private final List<Team> teams;
    private final String defaultTeam;

    private TriageRules(List<Level> priorities, String defaultPriority, String defaultPriorityReason, List<Team> teams, String defaultTeam) {
        this.priorities = priorities;
        this.defaultPriority = defaultPriority;
        this.defaultPriorityReason = defaultPriorityReason;
        this.teams = teams;
        this.defaultTeam = defaultTeam;
    }

    @SuppressWarnings("unchecked")
    public static TriageRules load(InputStream in) throws IOException {
        Map<String, Object> root = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
        if (root == null) throw new IOException("rules file is empty");
        List<Level> levels = new ArrayList<>();
        for (Map<String, Object> p : (List<Map<String, Object>>) req(root, "priorities")) {
            levels.add(new Level(str(p, "level"), str(p, "reason"), keywords(p)));
        }
        List<Team> teams = new ArrayList<>();
        for (Map<String, Object> t : (List<Map<String, Object>>) req(root, "teams")) {
            teams.add(new Team(str(t, "team"), keywords(t)));
        }
        if (levels.isEmpty() || teams.isEmpty()) throw new IOException("rules need at least one priority and one team");
        return new TriageRules(levels, str(root, "default-priority"), String.valueOf(root.getOrDefault("default-priority-reason", "no rule matched")),
                teams, str(root, "default-team"));
    }

    private static Object req(Map<String, Object> m, String key) throws IOException {
        Object v = m.get(key);
        if (v == null) throw new IOException("rules file is missing '" + key + "'");
        return v;
    }

    private static String str(Map<String, Object> m, String key) throws IOException {
        Object v = req(m, key);
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) throw new IOException("'" + key + "' is empty");
        return s;
    }

    @SuppressWarnings("unchecked")
    private static List<Keyword> keywords(Map<String, Object> m) throws IOException {
        List<Keyword> out = new ArrayList<>();
        for (Object o : (List<Object>) req(m, "keywords")) {
            String w = String.valueOf(o).toLowerCase(Locale.ROOT).trim();
            if (w.isEmpty()) continue;
            String body = Pattern.quote(w).replace(" ", "\\E\\s+\\Q");
            out.add(new Keyword(w, Pattern.compile("(?<![\\p{L}\\p{N}])" + body + "(?![\\p{L}\\p{N}])")));
        }
        return out;
    }

    public Verdict classify(String ticketId, String title, String description) {
        String text = ((title == null ? "" : title) + "\n" + (description == null ? "" : description)).toLowerCase(Locale.ROOT);
        List<String> matched = new ArrayList<>();

        String priority = defaultPriority;
        String priorityWhy = defaultPriorityReason;
        boolean priorityHit = false;
        for (Level l : priorities) {
            List<String> hits = hits(l.keywords(), text);
            if (!hits.isEmpty()) {
                priority = l.level();
                priorityWhy = l.reason() + " (" + quoted(hits) + ")";
                matched.addAll(hits);
                priorityHit = true;
                break;
            }
        }

        String team = defaultTeam;
        List<String> teamHits = List.of();
        for (Team t : teams) {
            List<String> hits = hits(t.keywords(), text);
            if (hits.size() > teamHits.size()) {
                team = t.team();
                teamHits = hits;
            }
        }
        matched.addAll(teamHits);
        String teamWhy = teamHits.isEmpty() ? "no team keywords, so " + defaultTeam + " picks it up" : team + " from " + quoted(teamHits);

        String confidence = priorityHit && !teamHits.isEmpty() ? "high" : priorityHit || !teamHits.isEmpty() ? "medium" : "low";
        String reason = priority + ": " + priorityWhy + ". Owner: " + teamWhy + ".";
        return new Verdict(ticketId, priority, team, reason, confidence, "rules", matched, null);
    }

    private static List<String> hits(List<Keyword> kws, String text) {
        List<String> out = new ArrayList<>();
        for (Keyword k : kws) {
            Matcher m = k.pattern().matcher(text);
            if (m.find()) out.add(k.word());
        }
        return out;
    }

    private static String quoted(List<String> words) {
        return String.join(", ", words.stream().map(w -> "\"" + w + "\"").toList());
    }

    public List<String> priorityLevels() {
        List<String> out = new ArrayList<>(priorities.stream().map(Level::level).toList());
        if (!out.contains(defaultPriority)) out.add(defaultPriority);
        return out;
    }

    public List<String> teamNames() {
        List<String> out = new ArrayList<>(teams.stream().map(Team::team).toList());
        if (!out.contains(defaultTeam)) out.add(defaultTeam);
        return out;
    }

    /** A plain view of the rules for GET /triage/rules and the console. */
    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("priorities", priorities.stream().map(l -> Map.of("level", l.level(), "reason", l.reason(),
                "keywords", l.keywords().stream().map(Keyword::word).toList())).toList());
        m.put("defaultPriority", defaultPriority);
        m.put("teams", teams.stream().map(t -> Map.of("team", t.team(), "keywords", t.keywords().stream().map(Keyword::word).toList())).toList());
        m.put("defaultTeam", defaultTeam);
        return m;
    }
}
