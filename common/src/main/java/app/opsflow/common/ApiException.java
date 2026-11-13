package app.opsflow.common;

import org.springframework.http.HttpStatus;

import java.util.Map;

/** An error with an HTTP status, a stable machine code and a message that is safe to show. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final Map<String, String> fields;

    public ApiException(HttpStatus status, String code, String message, Map<String, String> fields) {
        super(message);
        this.status = status;
        this.code = code;
        this.fields = fields;
    }

    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, "not_found", what + " not found", null);
    }

    public static ApiException invalid(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", message, field == null ? null : Map.of(field, message));
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message, null);
    }

    public static ApiException unavailable(String code, String message) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, code, message, null);
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public Map<String, String> fields() { return fields; }
}
