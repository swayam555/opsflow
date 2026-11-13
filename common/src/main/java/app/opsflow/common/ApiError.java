package app.opsflow.common;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/** The one error shape every Opsflow service returns. {@code fields} maps a request field to what is wrong with it. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(int status, String error, String message, Map<String, String> fields, String path, Instant timestamp) {
    public static ApiError of(int status, String error, String message, Map<String, String> fields, String path) {
        return new ApiError(status, error, message, fields == null || fields.isEmpty() ? null : fields, path, Instant.now());
    }
}
