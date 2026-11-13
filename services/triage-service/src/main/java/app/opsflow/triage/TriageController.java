package app.opsflow.triage;

import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/triage")
public class TriageController {
    public record TriageRequest(@Size(max = 64) String ticketId,
                                @Size(max = 200, message = "Title can be at most 200 characters") String title,
                                @Size(max = 5000, message = "Description can be at most 5000 characters") String description) {
        @AssertTrue(message = "Give a title or a description")
        public boolean isTitleOrDescription() {
            return (title != null && !title.isBlank()) || (description != null && !description.isBlank());
        }
    }

    private final LlmTriage llm;
    private final String rulesFile;
    private TriageRules rules;

    public TriageController(LlmTriage llm, @Value("${opsflow.triage.rules-file:}") String rulesFile) {
        this.llm = llm;
        this.rulesFile = rulesFile;
    }

    @PostConstruct
    void loadRules() throws IOException {
        try (InputStream in = rulesFile == null || rulesFile.isBlank()
                ? new ClassPathResource("triage-rules.yml").getInputStream()
                : Files.newInputStream(Path.of(rulesFile))) {
            rules = TriageRules.load(in);
        }
    }

    /**
     * Classifies a ticket. {@code mode=rules} skips the LLM even when one is configured (used for live previews).
     */
    @PostMapping
    public Verdict classify(@Valid @RequestBody TriageRequest r, @RequestParam(defaultValue = "auto") String mode) {
        Verdict byRules = rules.classify(r.ticketId(), r.title(), r.description());
        if (!llm.enabled() || "rules".equals(mode)) return byRules;
        String[] failure = new String[1];
        return llm.classify(r.ticketId(), r.title(), r.description(), rules, failure)
                .map(v -> new Verdict(v.ticketId(), v.priority(), v.ownerTeam(), v.reason(), v.confidence(), v.source(), byRules.matched(), null))
                .orElseGet(() -> byRules.withNote("AI triage was skipped: " + failure[0] + ". Rules were used instead."));
    }

    @GetMapping("/rules")
    public Map<String, Object> rules() {
        Map<String, Object> m = new LinkedHashMap<>(rules.describe());
        m.put("llm", llm.enabled() ? Map.of("enabled", true, "model", llm.model()) : Map.of("enabled", false));
        return m;
    }

    /** Kept for scripts that poll the old path; Spring's /actuator/health is the real check. */
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
