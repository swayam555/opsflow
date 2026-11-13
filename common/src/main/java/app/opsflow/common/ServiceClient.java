package app.opsflow.common;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;

/**
 * JSON over HTTP to another Opsflow service. Every call carries the shared internal token, has a deadline,
 * and turns any failure into a {@link DownstreamException} that says whether a retry makes sense.
 */
public class ServiceClient {
    private final String name;
    private final RestClient rest;
    private final ObjectMapper json = new ObjectMapper();

    public ServiceClient(String name, String baseUrl, String internalToken, Duration timeout) {
        this.name = name;
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        this.rest = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader(InternalToken.HEADER, internalToken)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public String name() { return name; }

    public <T> T get(String path, Class<T> type, Object... uriVars) {
        return call(r -> r.get().uri(path, uriVars).retrieve().body(type));
    }

    public <T> T post(String path, Object body, Class<T> type, Map<String, String> headers, Object... uriVars) {
        return call(r -> {
            var spec = r.post().uri(path, uriVars).contentType(MediaType.APPLICATION_JSON);
            if (headers != null) headers.forEach(spec::header);
            if (body != null) spec.body(body);
            return spec.retrieve().body(type);
        });
    }

    public <T> T put(String path, Object body, Class<T> type, Object... uriVars) {
        return call(r -> r.put().uri(path, uriVars).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(type));
    }

    public <T> T delete(String path, Class<T> type, Object... uriVars) {
        return call(r -> r.delete().uri(path, uriVars).retrieve().body(type));
    }

    private <T> T call(Function<RestClient, T> fn) {
        try {
            return fn.apply(rest);
        } catch (RestClientResponseException e) {
            HttpStatusCode s = e.getStatusCode();
            boolean retry = s.is5xxServerError() || s.value() == 429;
            throw new DownstreamException(name, s.value(), retry, name + " answered " + s.value() + ": " + messageOf(e), e);
        } catch (ResourceAccessException e) {
            throw new DownstreamException(name, 0, true, name + " is unreachable (" + rootMessage(e) + ")", e);
        }
    }

    private String messageOf(RestClientResponseException e) {
        String body = e.getResponseBodyAsString();
        try {
            JsonNode n = json.readTree(body);
            if (n.hasNonNull("message")) return n.get("message").asString();
        } catch (RuntimeException ignored) {
            // not JSON: fall through
        }
        return body == null || body.isBlank() ? e.getStatusText() : body.length() > 200 ? body.substring(0, 200) : body;
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }
}
