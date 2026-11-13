package org.framework.utils;

import org.framework.constants.PathConstants;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Saves one response back into a service's .vs file - updating it when it exists, adding it
 * when it does not.
 *
 * <p>Used by Recording mode and by scripts that call {@code setBinaryData} /
 * {@code setResponseContent} / {@code addNewMockResponse}. The response is located the same way
 * the parser reads it:
 *
 * <pre>
 *   REST  Endpoint[method, path]   / RRPair[id] / ResponseSet  / Response[name]
 *   SOAP  StubOperation[name]      / RRPair[id] / ResponseSet  / Response[name]
 *   TCP   StubOperation[name]      / RRPair[id] / stubResponse / Response[name]
 * </pre>
 *
 * <p>Only that one Response element is touched; every other byte of the document is left as it
 * was (the same approach as {@link ExecutionModeXmlUpdater}). The file is written through a temp
 * file and swapped in, under {@link VsFileLock}.
 */
public final class VsResponseWriter {

    public enum Kind { REST, SOAP, TCP }

    private VsResponseWriter() {
    }

    /** Where the response lives in the .vs. */
    public static final class Target {
        final Kind kind;
        final String method;
        final String path;
        final String operationName;
        final String pairId;
        final String responseName;

        private Target(Kind kind, String method, String path, String operationName, String pairId,
                String responseName) {
            this.kind = kind;
            this.method = method;
            this.path = path;
            this.operationName = operationName;
            this.pairId = pairId;
            this.responseName = responseName;
        }

        public static Target rest(String method, String path, String pairId, String responseName) {
            return new Target(Kind.REST, method, path, null, pairId, responseName);
        }

        public static Target soap(String operationName, String pairId, String responseName) {
            return new Target(Kind.SOAP, null, null, operationName, pairId, responseName);
        }

        public static Target tcp(String operationName, String pairId, String responseName) {
            return new Target(Kind.TCP, null, null, operationName, pairId, responseName);
        }

        @Override
        public String toString() {
            String where = kind == Kind.REST ? method + " " + path : operationName;
            return kind + " " + where + " pair=" + pairId + " response='" + responseName + "'";
        }
    }

    /** What to write. A null field is left as it is in the file. */
    public static final class Content {
        Integer statusCode;
        String contentType;
        String header;
        String body;
        String footer;

        public Content statusCode(Integer value) { this.statusCode = value; return this; }
        public Content contentType(String value) { this.contentType = value; return this; }
        public Content header(String value) { this.header = value; return this; }
        public Content body(String value) { this.body = value; return this; }
        public Content footer(String value) { this.footer = value; return this; }
    }

    /** Outcome of a save. */
    public static final class Result {
        private final boolean written;
        private final boolean added;
        private final String xmlContent;
        private final String problem;

        private Result(boolean written, boolean added, String xmlContent, String problem) {
            this.written = written;
            this.added = added;
            this.xmlContent = xmlContent;
            this.problem = problem;
        }

        /** True when the file on disk was rewritten. */
        public boolean isWritten() { return written; }

        /** True when the Response element did not exist and was added. */
        public boolean isAdded() { return added; }

        /** The document as it now stands, or null when the file was not read. */
        public String getXmlContent() { return xmlContent; }

        /** Why nothing was written; null when the save worked or nothing needed changing. */
        public String getProblem() { return problem; }
    }

    public static Result save(String serviceName, String xmlPathHint, Target target, Content content)
            throws Exception {

        String unsafe = firstIllegalXmlChar(content, target.kind);
        if (unsafe != null) {
            return new Result(false, false, null, unsafe);
        }

        synchronized (VsFileLock.of(serviceName)) {

            Path file = resolveXmlFile(serviceName, xmlPathHint);
            if (file == null) {
                return new Result(false, false, null, "no .vs file found for " + serviceName);
            }

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            Document doc = factory.newDocumentBuilder().parse(file.toFile());

            Element operation = findOperation(doc.getDocumentElement(), target);
            if (operation == null) {
                return new Result(false, false, null, "operation not found in the .vs");
            }

            Element pair = findPair(operation, target);
            if (pair == null) {
                return new Result(false, false, null, "RRPair '" + target.pairId + "' not found in the .vs");
            }

            Element set = firstChild(pair, "ResponseSet", "stubResponse");
            if (set == null) {
                set = createLike(doc, pair, target.kind == Kind.TCP ? "stubResponse" : "ResponseSet");
                pair.appendChild(set);
            }

            boolean added = false;
            Element response = findResponse(set, target.responseName);
            if (response == null) {
                response = newResponse(doc, operation, set, target);
                added = true;
            }

            boolean changed = applyContent(doc, response, target.kind, content);

            String xml = serialize(doc);
            if (!changed && !added) {
                return new Result(false, false, xml, null);
            }

            writeAtomically(file, xml);
            return new Result(true, added, xml, null);
        }
    }

    // ---- locating ----

    private static Element findOperation(Element root, Target target) {
        String wanted = target.kind == Kind.REST ? "Endpoint" : "StubOperation";
        for (Element el : descendants(root, wanted)) {
            if (target.kind == Kind.REST) {
                if (trim(el.getAttribute("path")).equals(trim(target.path))
                        && trim(el.getAttribute("method")).equalsIgnoreCase(trim(target.method))) {
                    return el;
                }
            } else if (trim(el.getAttribute("name")).equals(trim(target.operationName))) {
                return el;
            }
        }
        return null;
    }

    private static Element findPair(Element operation, Target target) {
        List<Element> pairs = children(operation, "RRPair");
        String id = trim(target.pairId);
        if (!id.isEmpty()) {
            for (Element pair : pairs) {
                if (id.equals(trim(pair.getAttribute("id")))) {
                    return pair;
                }
            }
            return null;
        }
        // No id in the model: the pair that already holds the response, else the first pair.
        for (Element pair : pairs) {
            Element set = firstChild(pair, "ResponseSet", "stubResponse");
            if (set != null && findResponse(set, target.responseName) != null) {
                return pair;
            }
        }
        return pairs.isEmpty() ? null : pairs.get(0);
    }

    private static Element findResponse(Element set, String name) {
        for (Element response : children(set, "Response")) {
            if (trim(response.getAttribute("name")).equals(trim(name))) {
                return response;
            }
        }
        return null;
    }

    // ---- adding a response ----

    /**
     * A new Response element, shaped like the responses already in the file: a copy of a
     * sibling (or of any response in the operation) with the new name, an empty script and no
     * custom headers. Built from scratch only when the operation has no response at all.
     */
    private static Element newResponse(Document doc, Element operation, Element set, Target target) {

        List<Element> siblings = children(set, "Response");
        Element template = siblings.isEmpty() ? firstResponseIn(operation) : siblings.get(siblings.size() - 1);

        Element response;
        if (template != null) {
            response = (Element) template.cloneNode(true);
            for (Element headers : children(response, "CustomHeaders")) {
                removeWithIndent(response, headers);
            }
            for (Element script : children(response, "ResponseScript")) {
                for (Element text : children(script, "Script")) {
                    clear(text);
                }
            }
        } else {
            response = createLike(doc, set, "Response");
            response.setAttribute("contentType", target.kind == Kind.REST ? "application/json" : "");
            response.setAttribute("responseDelay", "0");
            response.setAttribute("statusCode", "200");
            if (target.kind == Kind.TCP) {
                response.appendChild(createLike(doc, set, "Header"));
            }
            response.appendChild(createLike(doc, set, "Body"));
            if (target.kind == Kind.TCP) {
                response.appendChild(createLike(doc, set, "Footer"));
            }
            Element script = createLike(doc, set, "ResponseScript");
            Element lang = createLike(doc, set, "ScriptLang");
            lang.setTextContent("Groovy");
            script.appendChild(lang);
            script.appendChild(createLike(doc, set, "Script"));
            response.appendChild(script);
        }
        response.setAttribute("name", target.responseName);

        if (siblings.isEmpty()) {
            String indent = indentBefore(set, "\n          ") + "  ";
            set.appendChild(doc.createTextNode(indent));
            set.appendChild(response);
            set.appendChild(doc.createTextNode(indentBefore(set, "\n          ")));
        } else {
            Element last = siblings.get(siblings.size() - 1);
            Node after = last.getNextSibling();
            set.insertBefore(doc.createTextNode(indentBefore(last, "\n            ")), after);
            set.insertBefore(response, after);
        }
        return response;
    }

    private static Element firstResponseIn(Element operation) {
        for (Element pair : children(operation, "RRPair")) {
            Element set = firstChild(pair, "ResponseSet", "stubResponse");
            if (set != null) {
                List<Element> responses = children(set, "Response");
                if (!responses.isEmpty()) {
                    return responses.get(0);
                }
            }
        }
        return null;
    }

    // ---- writing the values ----

    private static boolean applyContent(Document doc, Element response, Kind kind, Content content) {
        boolean changed = false;

        if (content.statusCode != null) {
            changed |= setAttribute(response, "statusCode", String.valueOf(content.statusCode));
        }
        if (content.contentType != null && !content.contentType.isBlank()) {
            changed |= setAttribute(response, "contentType", content.contentType);
        }

        if (kind == Kind.TCP) {
            if (content.header != null) {
                changed |= setText(doc, response, "Header", content.header, false);
            }
            if (content.body != null) {
                changed |= setText(doc, response, "Body", content.body, true);
            }
            if (content.footer != null) {
                changed |= setText(doc, response, "Footer", content.footer, false);
            }
        } else if (content.body != null) {
            changed |= setText(doc, response, "Body", content.body, true);
        }
        return changed;
    }

    private static boolean setAttribute(Element el, String name, String value) {
        if (value.equals(el.getAttribute(name))) {
            return false;
        }
        el.setAttribute(name, value);
        return true;
    }

    /** Sets a child's text, creating the child in Header, Body, Footer order when missing. */
    private static boolean setText(Document doc, Element response, String name, String value, boolean cdata) {
        Element child = firstChild(response, name);
        if (child == null) {
            child = createLike(doc, response, name);
            insertInOrder(doc, response, child, name);
        } else if (value.equals(child.getTextContent())) {
            return false;
        }
        clear(child);
        if (!value.isEmpty()) {
            child.appendChild(cdata ? doc.createCDATASection(value) : doc.createTextNode(value));
        }
        return true;
    }

    private static void insertInOrder(Document doc, Element response, Element child, String name) {
        Element anchor = null;
        if ("Header".equals(name)) {
            anchor = firstChild(response, "Body", "Footer", "ResponseScript", "CustomHeaders");
        } else if ("Body".equals(name)) {
            anchor = firstChild(response, "Footer", "ResponseScript", "CustomHeaders");
        } else if ("Footer".equals(name)) {
            anchor = firstChild(response, "ResponseScript", "CustomHeaders");
        }
        if (anchor == null) {
            response.appendChild(child);
            return;
        }
        response.insertBefore(child, anchor);
        response.insertBefore(doc.createTextNode(indentBefore(anchor, "\n              ")), anchor);
    }

    /**
     * Characters XML 1.0 cannot hold. Writing them would leave a .vs file that no longer parses,
     * so the save is refused instead and the response stays updated in memory only. For TCP a
     * carriage return is refused too: the parser would turn it into a line feed on the next load
     * and change the bytes sent.
     */
    private static String firstIllegalXmlChar(Content content, Kind kind) {
        for (String value : new String[] { content.header, content.body, content.footer }) {
            if (value == null) {
                continue;
            }
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                boolean control = c < 0x20 && c != '\t' && c != '\n' && c != '\r';
                if (control || c == '￾' || c == '￿' || (kind == Kind.TCP && c == '\r')) {
                    return String.format("the reply contains character 0x%02X at position %d, which a .vs"
                            + " (XML) file cannot hold", (int) c, i);
                }
            }
        }
        return null;
    }

    // ---- file handling (same conventions as ExecutionModeXmlUpdater) ----

    private static Path resolveXmlFile(String serviceName, String xmlPathHint) {
        Path conventional = Paths.get(PathConstants.VS_XML_DIRECTORY, serviceName + PathConstants.VS_FILE_EXTENSION);
        if (Files.exists(conventional)) {
            return conventional;
        }
        if (xmlPathHint != null && !xmlPathHint.isBlank()) {
            try {
                Path hinted = xmlPathHint.startsWith("file:")
                        ? Paths.get(URI.create(xmlPathHint))
                        : Paths.get(xmlPathHint);
                if (Files.exists(hinted)) {
                    return hinted;
                }
            } catch (Exception ignored) {
                // unusable hint - treated as no file
            }
        }
        return null;
    }

    private static void writeAtomically(Path file, String xml) throws Exception {
        Path directory = file.toAbsolutePath().getParent();
        Path temp = Files.createTempFile(directory, "record-", PathConstants.VS_FILE_EXTENSION);
        try {
            Files.writeString(temp, xml);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicNotSupported) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static String serialize(Document doc) throws Exception {
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        StringWriter out = new StringWriter();
        transformer.transform(new DOMSource(doc), new StreamResult(out));
        String xml = out.toString();
        // The serializer puts the root element on the declaration's line; keep it on its own.
        int end = xml.startsWith("<?xml") ? xml.indexOf("?>") + 2 : -1;
        if (end > 1 && end < xml.length() && xml.charAt(end) != '\n') {
            xml = xml.substring(0, end) + "\n" + xml.substring(end);
        }
        return xml;
    }

    // ---- DOM helpers ----

    private static List<Element> children(Element parent, String localName) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element el && localName.equals(el.getLocalName())) {
                out.add(el);
            }
        }
        return out;
    }

    private static Element firstChild(Element parent, String... localNames) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element el) {
                for (String name : localNames) {
                    if (name.equals(el.getLocalName())) {
                        return el;
                    }
                }
            }
        }
        return null;
    }

    private static List<Element> descendants(Element root, String localName) {
        List<Element> out = new ArrayList<>();
        collect(root, localName, out);
        return out;
    }

    private static void collect(Element el, String localName, List<Element> out) {
        for (Node n = el.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element child) {
                if (localName.equals(child.getLocalName())) {
                    out.add(child);
                }
                collect(child, localName, out);
            }
        }
    }

    /** Creates an element carrying the same namespace and prefix as {@code like}. */
    private static Element createLike(Document doc, Element like, String localName) {
        String namespace = like.getNamespaceURI();
        String prefix = like.getPrefix();
        if (namespace == null) {
            return doc.createElement(localName);
        }
        return doc.createElementNS(namespace, prefix == null || prefix.isBlank() ? localName : prefix + ":" + localName);
    }

    private static void clear(Element el) {
        while (el.getFirstChild() != null) {
            el.removeChild(el.getFirstChild());
        }
    }

    private static void removeWithIndent(Element parent, Element child) {
        Node previous = child.getPreviousSibling();
        if (previous != null && previous.getNodeType() == Node.TEXT_NODE && previous.getNodeValue().isBlank()) {
            parent.removeChild(previous);
        }
        parent.removeChild(child);
    }

    private static String indentBefore(Node el, String fallback) {
        Node previous = el.getPreviousSibling();
        if (previous != null && previous.getNodeType() == Node.TEXT_NODE
                && previous.getNodeValue() != null && previous.getNodeValue().isBlank()
                && previous.getNodeValue().contains("\n")) {
            String text = previous.getNodeValue();
            return text.substring(text.lastIndexOf('\n'));
        }
        return fallback;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
