package org.framework.properties;

import groovy.lang.GroovyObjectSupport;
import org.framework.core.AbstractService;
import org.framework.utils.GlobalProperties;

import java.util.HashMap;
import java.util.Map;

public class Context extends GroovyObjectSupport {
    public AbstractService mockService;

    public void setMockService(AbstractService mockService) {
        this.mockService = mockService;
    }

    /**
     * The operation currently being dispatched. Bound into Groovy scripts as
     * "mockOperation". Since the migration this is a com.stubio Endpoint or
     * StubOperation for REST/SOAP, and still a BaseRoute for legacy TCP - hence
     * Object rather than a single type.
     */
    public Object getMockOperation() {
        return mockOperation;
    }

    public void setMockOperation(Object mockOperation) {
        this.mockOperation = mockOperation;
    }

    private Object mockOperation;

    // ---- per-request state for Recording mode (Java side only; not script properties) ----

    /** The resolver that answered this request - lets scripts reach the operation's responses. */
    private org.framework.core.ResponseResolver responseResolver;

    /** The response an RRPair claimed; null when nothing matched. */
    private MockResponse matchedResponse;

    /** True when no RRPair matched and the defaultRR response answered instead. */
    private boolean matchedByDefault;

    /** Set when Recording mode got a live reply; finished after the On Response script. */
    private org.framework.core.recording.Recording recording;

    public org.framework.core.ResponseResolver getResponseResolver() {
        return responseResolver;
    }

    public void setResponseResolver(org.framework.core.ResponseResolver responseResolver) {
        this.responseResolver = responseResolver;
    }

    public MockResponse getMatchedResponse() {
        return matchedResponse;
    }

    public boolean isMatchedByDefault() {
        return matchedByDefault;
    }

    public void setMatchedResponse(MockResponse matchedResponse, boolean byDefault) {
        this.matchedResponse = matchedResponse;
        this.matchedByDefault = byDefault;
    }

    public org.framework.core.recording.Recording getRecording() {
        return recording;
    }

    public void setRecording(org.framework.core.recording.Recording recording) {
        this.recording = recording;
    }

    private final Map<String, Object> dynamicProperties = new HashMap<>();

    /**
     * The {name} segments of the matched endpoint path. They are also in the
     * dynamic properties; this copy lets a data source read ${path.name}
     * without mistaking a property of the same name for a path variable.
     */
    private final Map<String, String> pathParameters = new HashMap<>();

    public Map<String, String> getPathParameters() {
        return pathParameters;
    }

    public Map<String, Object> getRespTimeProperties() {
        return respTimeProperties;
    }

    private final Map<String, Object> respTimeProperties = new HashMap<>();

    public Context(AbstractService service) {
        super();
        this.mockService = service;
    }

    public Context(Context context) {
        super();
        this.mockService = context.mockService;
    }

    public AbstractService getMockService() {
        return mockService;
    }

    /**
     * The server-wide global property store, shared by every virtual service.
     * Groovy scripts receive it as "globalProperties" from GroovyScriptExecutor;
     * this accessor is for Java callers that hold a Context but no binding.
     */
    public GlobalProperties getGlobalProperties() {
        return GlobalProperties.getInstance();
    }

    // Handle dynamic properties: Groovy will use this method for context.name =
    // value
    @Override
    public void setProperty(String name, Object value) {
        if (!"mockService".equals(name)) {
            dynamicProperties.put(name, value);
            // System.out.println("Setting property: " + name + " = " + value);
        }
    }

    public void setPropertyValue(String name, Object value) {
        if (!"mockService".equals(name)) {
            dynamicProperties.put(name, value);
            // System.out.println("Setting property: " + name + " = " + value);
        }
    }

    public String getPropertyValue(String name) {

        if (!"mockService".equals(name)) {
            return dynamicProperties.get(name).toString();
            // System.out.println("Setting property: " + name + " = " + value);
        }
        return name;
    }

    // Handle dynamic properties: Groovy will use this method for context.name
    @Override
    public Object getProperty(String name) {
        if (!"mockService".equals(name)) {
            System.out.println("getting property: " + name + " = " + name);
            return dynamicProperties.get(name);
        }

        return mockService;
    }

    @Override
    public Object invokeMethod(String name, Object args) {
        return this.getMetaClass().invokeMethod(this, name, args);
    }

    public Map<String, Object> getDynamicProperties() {
        return dynamicProperties;
    }
}

