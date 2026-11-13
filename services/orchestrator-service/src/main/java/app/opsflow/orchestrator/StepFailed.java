package app.opsflow.orchestrator;

/** A step that ran and failed in a way retrying the same call cannot fix (e.g. the deployment rolled back). */
class StepFailed extends RuntimeException {
    StepFailed(String message) {
        super(message);
    }
}
