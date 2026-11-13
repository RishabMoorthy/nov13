package org.framework.core;

import com.sun.net.httpserver.Headers;
import org.common.db.config.ConfigLoader;
import org.framework.properties.Context;
import org.framework.properties.MockRequest;
import org.framework.properties.MockResponse;
import org.framework.utils.Logger;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Calls the destination server for the two modes that route traffic to it.
 *
 * <p>The distinction that matters to Failover is between <em>the server answered</em> and
 * <em>the call never completed</em>. Any HTTP status - including 500 - is an answer and is
 * returned as-is; only a transport failure (connection refused, timeout, DNS, reset, TLS)
 * counts as a failed call, and only that makes Failover serve the mock response instead.
 *
 * <p>Failover uses shorter timeouts than Live Invocation by default, because a caller waiting
 * out the full Live Invocation read timeout defeats the point of having a fallback. All four
 * values can be overridden in config.properties.
 */
public class LiveInvocation {

    /**
     * Reported as the response name and source in the request logs, for every outcome of a
     * live call - answered, truncated, or failed. One name keeps the logs simple: whenever
     * the mode routed to the destination server, the log says so.
     */
    public static final String LIVE_RESPONSE_NAME = "Live Response";
    private static final String LIVE_SOURCE = "live";

    private static final int DEFAULT_LIVE_CONNECT_SECONDS = 30;
    private static final int DEFAULT_LIVE_REQUEST_SECONDS = 120;
    private static final int DEFAULT_FAILOVER_CONNECT_SECONDS = 10;
    private static final int DEFAULT_FAILOVER_REQUEST_SECONDS = 30;

    /**
     * The outcome of one live call.
     *
     * <p>{@link #isLiveAnswered()} is the only thing Failover needs: true means the
     * destination server produced an HTTP response and {@link #getResponse()} carries it.
     */
    public static final class LiveResult {

        private final MockResponse response;
        private final boolean liveAnswered;
        private final String failureReason;

        private LiveResult(MockResponse response, boolean liveAnswered, String failureReason) {
            this.response = response;
            this.liveAnswered = liveAnswered;
            this.failureReason = failureReason;
        }

        static LiveResult answered(MockResponse response) {
            return new LiveResult(response, true, null);
        }

        static LiveResult failed(MockResponse response, String reason) {
            return new LiveResult(response, false, reason);
        }

        /** True when the destination server returned an HTTP response, whatever its status. */
        public boolean isLiveAnswered() {
            return liveAnswered;
        }

        /** The live response, or - when the call failed and the mode is not Failover - an error response. */
        public MockResponse getResponse() {
            return response;
        }

        /** Why the call failed; null when it did not. */
        public String getFailureReason() {
            return failureReason;
        }
    }

    /**
     * Existing entry point: Live Invocation semantics, where a failed call is surfaced to the
     * caller as a 500 rather than falling back.
     */
    public MockResponse getLiveResponse(Context context, MockRequest mockRequest, MockResponse resp) {
        return invoke(context, mockRequest, resp, false).getResponse();
    }

    /**
     * @param failoverMode true to apply Failover behaviour: shorter timeouts, and a failed call
     *                     reported through {@link LiveResult#isLiveAnswered()} with no fabricated
     *                     error response and no partial body
     */
    public LiveResult invoke(Context context,
            MockRequest mockRequest,
            MockResponse resp,
            boolean failoverMode) {

        ByteArrayOutputStream buffer = new ByteArrayOutputStream(); // for partial data
        String endpoint = null;

        // The live result is built into a SEPARATE MockResponse, never into the one the
        // RRPair chain matched.
        //
        // The matched response is the object cached in PairRuntime.responses at deploy
        // time, and it is reused for every request for the life of the service. Writing
        // the live status, body, headers and name into it renamed it to "Live Response"
        // permanently, so the next time Failover looked the mock up by name it found
        // nothing and served a 404 - and, when the name did still match, it served the
        // previous live body while reporting it as the mock. Both faults disappeared on
        // restart, because that re-parsed the responses from the .vs.
        //
        // Seed the copy from the matched response so the mock's headers and script stay
        // available to the live path, then mutate only the copy.
        MockResponse target = copyOf(resp);

        try {
            String host = context.getMockService().getConfig().getRouteEndpoint();

            String requestURI = mockRequest.getHttpRequest().getRequestURI();
            String queryString = mockRequest.getHttpRequest().getQueryString();

            endpoint = host + requestURI;
            if (queryString != null && !queryString.isEmpty()) {
                endpoint += "?" + queryString;
            }

            Logger.getInstance().info("Endpoint: " + endpoint);

            Duration connectTimeout = Duration.ofSeconds(failoverMode
                    ? seconds("executionmode.failover.connectTimeoutSeconds", DEFAULT_FAILOVER_CONNECT_SECONDS)
                    : seconds("executionmode.live.connectTimeoutSeconds", DEFAULT_LIVE_CONNECT_SECONDS));

            Duration requestTimeout = Duration.ofSeconds(failoverMode
                    ? seconds("executionmode.failover.requestTimeoutSeconds", DEFAULT_FAILOVER_REQUEST_SECONDS)
                    : seconds("executionmode.live.requestTimeoutSeconds", DEFAULT_LIVE_REQUEST_SECONDS));

            // HTTP CLIENT (force HTTP/1.1)
            HttpClient client = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(connectTimeout)
                    .build();

            // BUILD REQUEST
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(requestTimeout);

            String method = mockRequest.getMethod();
            String content = mockRequest.getRequestContent();

            if ("POST".equalsIgnoreCase(method) ||
                    "PUT".equalsIgnoreCase(method) ||
                    "PATCH".equalsIgnoreCase(method)) {

                requestBuilder.method(method,
                        HttpRequest.BodyPublishers.ofString(
                                content != null ? content : "",
                                StandardCharsets.UTF_8
                        ));
            } else {
                requestBuilder.GET();
            }

            //COPY SAFE HEADERS
            for (Map.Entry<String, List<String>> entry : mockRequest.getRequestHeaders().entrySet()) {
                String key = entry.getKey();

                if (key == null) continue;

                // Hop-by-hop headers belong to the client's own connection, and Java's HTTP
                // client refuses to send them (an HTTP/2 "Upgrade" from the caller made every
                // live call fail with "restricted header name").
                if (key.equalsIgnoreCase("host") ||
                        key.equalsIgnoreCase("content-length") ||
                        key.equalsIgnoreCase("connection") ||
                        key.equalsIgnoreCase("accept-encoding") ||
                        key.equalsIgnoreCase("upgrade") ||
                        key.equalsIgnoreCase("http2-settings") ||
                        key.equalsIgnoreCase("expect") ||
                        key.equalsIgnoreCase("keep-alive") ||
                        key.equalsIgnoreCase("te") ||
                        key.equalsIgnoreCase("trailer") ||
                        key.equalsIgnoreCase("transfer-encoding") ||
                        key.equalsIgnoreCase("proxy-connection")) {
                    continue;
                }

                for (String value : entry.getValue()) {
                    requestBuilder.header(key, value);
                }
            }

            long start = System.currentTimeMillis();

            // STREAMING RESPONSE
            HttpResponse<InputStream> response = client.send(
                    requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofInputStream()
            );

            long end = System.currentTimeMillis();
            Logger.getInstance().info("Response Time: " + (end - start) + " ms");
            target.setStatusCode(response.statusCode());

            // READ RESPONSE IN CHUNKS
            InputStream is = response.body();
            byte[] chunk = new byte[8192];
            int bytesRead;

            while ((bytesRead = is.read(chunk)) != -1) {
                buffer.write(chunk, 0, bytesRead);

                // optional progress log
                if (buffer.size() % (50 * 1024) < 8192) {
                    Logger.getInstance().info("Downloaded bytes: " + buffer.size());
                }
            }

            target.setResponseBytes(buffer.toByteArray());

            // HEADERS
            Headers respHeaders = target.getHeaders();
            if (respHeaders == null) {
                respHeaders = new Headers();
                target.setHeaders(respHeaders);
            } else {
                respHeaders.clear();
            }

            for (Map.Entry<String, List<String>> entry : response.headers().map().entrySet()) {
                respHeaders.put(entry.getKey(), entry.getValue());
            }

            // The body, status and headers now all belong to the live server, so relabel
            // the response object. The request logs key off these two fields:
            // MockResponse derives "source" from a name containing "live", and
            // getServiceLogs reports the name as the response that was served. Without
            // this the log would still show the mock response that was matched and then
            // discarded.
            target.setName(LIVE_RESPONSE_NAME);
            target.setSource(LIVE_SOURCE);

            // The server answered. Any status code is an answer, so Failover does not
            // second-guess a 500 that the destination genuinely returned.
            return LiveResult.answered(target);

        } catch (Exception e) {

            String reason = e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage());

            // In Failover a failed live call is the expected path, not a fault - it is
            // precisely what the mode exists to handle. Logging a full stack trace for
            // normal behaviour buries the real errors and trains people to ignore the
            // error log, so Failover gets one WARN line. Live Invocation keeps the trace,
            // because there a failed call really is an error the caller needs diagnosed.
            if (failoverMode) {
                Logger.getInstance().warn("Live call failed"
                        + (endpoint == null ? "" : " for " + endpoint)
                        + " (" + reason + ") - Failover will serve the mock response.");
            } else {
                Logger.getInstance().error("Live call failed"
                        + (endpoint == null ? "" : " for " + endpoint), e);
            }

            if (failoverMode) {
                // Nothing usable is handed back: a truncated body is worse than the mock
                // response the caller is about to fall back to.
                if (buffer.size() > 0) {
                    Logger.getInstance().info("Discarding " + buffer.size()
                            + " partial bytes; Failover will serve the mock response.");
                }
                return LiveResult.failed(null, reason);
            }

            // Live Invocation: unchanged behaviour - return whatever arrived, else a 500.
            if (buffer.size() > 0) {
                Logger.getInstance().info("Returning partial response. Bytes received: " + buffer.size());
                target.setResponseBytes(buffer.toByteArray());
                // Truncated, but still the live server's content - log it as live. The log
                // line above already records that it was partial.
                target.setName(LIVE_RESPONSE_NAME);
                target.setSource(LIVE_SOURCE);
                return LiveResult.failed(target, reason);
            }

            // Named the same as a successful live call: the request was routed live, and that
            // is what the log should show. MockResponse derives source from the name, so this
            // is reported as source=live too.
            return LiveResult.failed(new MockResponse(
                    LIVE_RESPONSE_NAME,
                    500,
                    ("Error invoking live service: " + e.getMessage()).getBytes(StandardCharsets.UTF_8),
                    null
            ), reason);
        }
    }

    private static int seconds(String key, int fallback) {
        try {
            String raw = ConfigLoader.getProperty(key);
            if (raw != null && !raw.isBlank()) {
                int parsed = Integer.parseInt(raw.trim());
                if (parsed > 0) {
                    return parsed;
                }
            }
        } catch (Exception ignored) {
            // fall through to the default
        }
        return fallback;
    }

    /**
     * A detached copy of the matched mock response, for the live path to fill in.
     *
     * <p>The matched response is shared and cached for the life of the service, so it
     * must never be mutated. A null {@code resp} (the TCP path has no matched response)
     * yields an empty container.
     */
    private static MockResponse copyOf(MockResponse resp) {
        if (resp == null) {
            return new MockResponse("live_response", 200, new byte[0], null);
        }
        byte[] bytes = resp.getResponseBytes();
        // The headers are copied too. Sharing the Headers object meant the live call below
        // cleared the cached mock response's headers and filled them with the live server's
        // (Content-Length, Date, ...), which then went out with every later mock reply.
        Headers headers = null;
        if (resp.getHeaders() != null) {
            headers = new Headers();
            for (Map.Entry<String, List<String>> entry : resp.getHeaders().entrySet()) {
                headers.put(entry.getKey(), new java.util.ArrayList<>(entry.getValue()));
            }
        }
        MockResponse copy = new MockResponse(
                resp.getName(),
                headers,
                resp.getStatusCode(),
                bytes == null ? new byte[0] : bytes.clone(),
                resp.getScript(),
                resp.getOperationName(),
                resp.getServiceName());
        copy.setSource(resp.getSource());
        return copy;
    }
}

