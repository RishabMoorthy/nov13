package org.framework.core.recording;

import org.framework.core.ResponseResolver;
import org.framework.properties.Context;
import org.framework.properties.MockResponse;
import org.framework.utils.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recording mode for one request.
 *
 * <p>The flow, the same for REST, SOAP and TCP:
 * <ol>
 *   <li>The RRPair chain picks the matching response, as in every mode.</li>
 *   <li>The live server is called and its reply is returned to the client.</li>
 *   <li>{@link #capture} keeps the live reply and hands it to the On Response script:
 *       <pre>
 *   context.executionMode        "Recording"
 *   context.matchedResponseName  the response an RRPair matched; null when none did
 *                                (an answer from the defaultRR response does not count)
 *   context.liveResponse         the live reply as text
 *   context.liveResponseBytes    the live reply as exact bytes (use these for TCP)
 *   context.liveStatusCode       the live HTTP status (200 for TCP)
 *   context.liveHeaders          the live HTTP headers (empty for TCP)
 *   mockOperation                getMockResponseByName(name), addNewMockResponse(name)
 *       </pre></li>
 *   <li>The On Response script runs. It may create a response, store the reply somewhere else,
 *       or set {@code context.skipRecordingUpdate = true}.</li>
 *   <li>{@link #finish} then updates the matched response with the live reply, in memory and in
 *       the .vs - unless nothing matched or the script asked to skip.</li>
 * </ol>
 *
 * <p>A live call that failed is never recorded: the caller gets the failure, as in Live
 * Invocation, and the stored response is left alone.
 */
public final class Recording {

    /** Set to true in the On Response script to keep StubServer from updating the response. */
    public static final String SKIP_FLAG = "skipRecordingUpdate";

    private final ResponseResolver resolver;
    private final Object operation;
    private final MockResponse matched;
    private final MockResponse live;
    private final String liveText;
    private final byte[] liveBytes;

    private Recording(ResponseResolver resolver, Object operation, MockResponse matched, MockResponse live,
            String liveText, byte[] liveBytes) {
        this.resolver = resolver;
        this.operation = operation;
        this.matched = matched;
        this.live = live;
        this.liveText = liveText;
        this.liveBytes = liveBytes;
    }

    /**
     * Keeps the live reply for {@link #finish} and exposes it to the On Response script.
     *
     * @param liveText  the reply body as text (decompressed and decoded)
     * @param liveBytes the reply body as bytes, in the form that is stored
     */
    public static void capture(Context context, ResponseResolver resolver, Object operation, MockResponse live,
            String liveText, byte[] liveBytes) {

        MockResponse matched = context.isMatchedByDefault() ? null : context.getMatchedResponse();
        context.setRecording(new Recording(resolver, operation, matched, live, liveText, liveBytes));

        Map<String, Object> vars = context.getDynamicProperties();
        vars.put("matchedResponseName", matched == null ? null : matched.getName());
        vars.put("liveResponse", liveText);
        vars.put("liveResponseBytes", liveBytes);
        vars.put("liveStatusCode", live.getStatusCode());
        vars.put("liveHeaders", headersOf(live));
        vars.remove(SKIP_FLAG);
    }

    /**
     * Updates the matched response with the live reply, after the On Response script. Called by
     * the REST, SOAP and TCP services for every request; does nothing outside Recording mode.
     * Never throws - the reply has already been sent.
     */
    public static void finish(Context context) {
        if (context == null || context.getRecording() == null) {
            return;
        }
        Recording recording = context.getRecording();
        context.setRecording(null);

        String service = context.getMockService() == null ? "" : context.getMockService().getName();
        try {
            if (isTrue(context.getDynamicProperties().get(SKIP_FLAG))) {
                Logger.getInstance().info("[Recording] " + service
                        + ": On Response script set skipRecordingUpdate; no response updated.");
                return;
            }
            if (recording.matched == null) {
                Logger.getInstance().info("[Recording] " + service
                        + ": no RRPair matched this request; nothing updated."
                        + " (An On Response script can create a response with mockOperation.addNewMockResponse.)");
                return;
            }
            recording.resolver.recordLiveReply(recording.operation, recording.matched, recording.live,
                    recording.liveText, recording.liveBytes);
        } catch (Exception e) {
            Logger.getInstance().error("[Recording] " + service + ": could not record the live reply: "
                    + e.getMessage());
        }
    }

    private static boolean isTrue(Object value) {
        return value instanceof Boolean b ? b : value != null && "true".equalsIgnoreCase(value.toString().trim());
    }

    private static Map<String, String> headersOf(MockResponse live) {
        Map<String, String> out = new LinkedHashMap<>();
        if (live.getHeaders() != null) {
            for (Map.Entry<String, List<String>> e : live.getHeaders().entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    out.put(e.getKey(), String.join(",", e.getValue()));
                }
            }
        }
        return out;
    }
}
