package app.opsflow.triage;

import java.util.List;

/**
 * The outcome of triage.
 *
 * @param source     "rules" or "llm"
 * @param confidence "high" when both priority and team were matched, "medium" when one was, "low" when neither
 * @param matched    the rule keywords that were found
 * @param note       why the LLM was not used, when it was configured but failed
 */
public record Verdict(String ticketId, String priority, String ownerTeam, String reason, String confidence,
                      String source, List<String> matched, String note) {
    Verdict withNote(String n) {
        return new Verdict(ticketId, priority, ownerTeam, reason, confidence, source, matched, n);
    }
}
