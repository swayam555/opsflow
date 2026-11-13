package app.opsflow.common;

/**
 * A call to another service failed. {@code retryable} is true when trying again later can help
 * (service unreachable, timed out or answered 5xx) and false when the request itself was refused (4xx).
 */
public class DownstreamException extends RuntimeException {
    private final String service;
    private final int status;
    private final boolean retryable;

    public DownstreamException(String service, int status, boolean retryable, String message, Throwable cause) {
        super(message, cause);
        this.service = service;
        this.status = status;
        this.retryable = retryable;
    }

    public String service() { return service; }
    /** HTTP status the other service answered with, or 0 when it could not be reached. */
    public int status() { return status; }
    public boolean retryable() { return retryable; }
}
