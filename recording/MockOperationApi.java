package org.framework.core.recording;

import groovy.lang.GroovyObjectSupport;
import org.codehaus.groovy.runtime.InvokerHelper;
import org.framework.core.ResponseResolver;
import org.framework.properties.Context;
import org.framework.properties.MockResponse;

import java.util.List;

/**
 * What scripts see as {@code mockOperation}: the ReadyAPI calls on top of the operation from
 * the .vs.
 *
 * <pre>
 *   def response = mockOperation.getMockResponseByName("Response_123")   // null when missing
 *   if (response == null) {
 *       response = mockOperation.addNewMockResponse("Response_123")      // empty, not served yet
 *   }
 *   response.setBinaryData(context.liveResponseBytes)                    // served + saved to the .vs
 * </pre>
 *
 * <p>Anything else - {@code mockOperation.name}, {@code mockOperation.getRrList()} and so on - is
 * passed through to the operation object itself (Endpoint, StubOperation or TCPOperation), so
 * existing scripts behave as before.
 */
public class MockOperationApi extends GroovyObjectSupport {

    private final Object operation;
    private final ResponseResolver resolver;

    MockOperationApi(Object operation, ResponseResolver resolver) {
        this.operation = operation;
        this.resolver = resolver;
    }

    /**
     * The {@code mockOperation} to bind into a script: this wrapper for an operation from the
     * .vs, or the operation itself when there is no resolver (legacy routes).
     */
    public static Object of(Context context) {
        if (context == null || context.getMockOperation() == null) {
            return null;
        }
        if (context.getResponseResolver() == null) {
            return context.getMockOperation();
        }
        return new MockOperationApi(context.getMockOperation(), context.getResponseResolver());
    }

    /** The response with this name in any RRPair of the operation; null when there is none. */
    public MockResponse getMockResponseByName(String name) {
        return resolver.getMockResponseByName(operation, name);
    }

    /**
     * Adds an empty response with this name; returns the existing one when the name is already
     * taken. Fill it with {@code setBinaryData} or {@code setResponseContent} - that serves it and
     * saves it to the .vs.
     */
    public MockResponse addNewMockResponse(String name) {
        return resolver.addNewMockResponse(operation, name);
    }

    /** Every response of the operation, across its RRPairs. */
    public List<MockResponse> getMockResponses() {
        return resolver.getResponses(operation);
    }

    public int getMockResponseCount() {
        return resolver.getResponses(operation).size();
    }

    /** The operation object from the .vs (Endpoint, StubOperation or TCPOperation). */
    public Object getOperation() {
        return operation;
    }

    public String getName() {
        Object name = InvokerHelper.getProperty(operation, "name");
        return name == null ? null : name.toString();
    }

    // ---- everything else goes to the operation object ----

    public Object methodMissing(String name, Object args) {
        return InvokerHelper.invokeMethod(operation, name, args);
    }

    public Object propertyMissing(String name) {
        return InvokerHelper.getProperty(operation, name);
    }

    @Override
    public String toString() {
        return String.valueOf(operation);
    }
}
