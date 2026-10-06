import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Synchronous HTTP + WebSocket client for the Karpfen runtime server. */
public class KarpfenConnector {

    private final String baseUrl;  // e.g. "http://127.0.0.1:8080"
    private final HttpClient http;

    public KarpfenConnector(String host, int port) {
        this.baseUrl = "http://" + host + ":" + port;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // ── HTTP helpers ────────────────────────────────────────────────────────

    /** Builds "path?k1=v1&k2=v2" from alternating key/value pairs, URL-encoding each value. */
    private static String query(String path, Object... keyValues) {
        var sb = new StringBuilder(path);
        for (int i = 0; i < keyValues.length; i += 2) {
            sb.append(i == 0 ? '?' : '&')
              .append(keyValues[i])
              .append('=')
              .append(URLEncoder.encode(String.valueOf(keyValues[i + 1]), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /** GET; returns trimmed response body. */
    private String get(String pathAndQuery) throws Exception {
        var req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + pathAndQuery))
                .GET()
                .build();
        var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertOk(resp);
        return resp.body().strip();
    }

    /** POST with no body; returns trimmed response body. */
    private String post(String pathAndQuery) throws Exception {
        var req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + pathAndQuery))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertOk(resp);
        return resp.body().strip();
    }

    /** PUT with a plain-text body. */
    private void put(String pathAndQuery, String body) throws Exception {
        var req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + pathAndQuery))
                .header("Content-Type", "text/plain")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();
        var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertOk(resp);
    }

    /** Throws with the server's error message (400 bad params, 409 lifecycle conflict, 500). */
    private void assertOk(HttpResponse<?> resp) {
        if (resp.statusCode() < 200 || resp.statusCode() >= 300)
            throw new RuntimeException("HTTP " + resp.statusCode() + " — " + resp.body());
    }

    // ── Health ──────────────────────────────────────────────────────────────

    /** Returns true if the runtime server is reachable and reports {"status":"ok"}. */
    public boolean health() {
        try {
            return get("/health").replace(" ", "").contains("\"status\":\"ok\"");
        } catch (Exception e) {
            return false;
        }
    }

    // ── Environment setup (only allowed before runEnvironment) ──────────────

    /** Creates a new execution environment; returns the server-assigned envKey. */
    public String createEnvironment() throws Exception {
        return post("/createEnvironment");
    }

    /** Uploads the metamodel (.kmeta source) to the environment. Must precede setModel. */
    public void setMetamodel(String envKey, String content) throws Exception {
        put(query("/setMetamodel", "envKey", envKey), content);
    }

    /** Uploads the instance model (.kmodel source) to the environment. */
    public void setModel(String envKey, String content) throws Exception {
        put(query("/setModel", "envKey", envKey), content);
    }

    /**
     * Uploads the event payload definitions (a .kmeta source, conventionally EVENTS.kmeta).
     * Each type is an event name; an event "setSpeed" is parsed against the type "setSpeed".
     * Optional — only needed for events that carry a payload. Call after setMetamodel if
     * payloads embed domain types.
     */
    public void setEventDefinitions(String envKey, String content) throws Exception {
        put(query("/setEventDefinitions", "envKey", envKey), content);
    }

    /**
     * Uploads a statemachine (.kstates source) and attaches it to the model object
     * identified by {@code attachedTo} (the instance ID used in the .kmodel file).
     */
    public void setStateMachine(String envKey, String attachedTo, String content) throws Exception {
        put(query("/setStateMachine", "envKey", envKey, "attachedTo", attachedTo), content);
    }

    /** Sets the engine tick delay in milliseconds. */
    public void setTickDelay(String envKey, int milliseconds) throws Exception {
        post(query("/setTickDelay", "envKey", envKey, "milliseconds", milliseconds));
    }

    /** Sets how long unconsumed events stay alive, in milliseconds (0 = forever). */
    public void setEventTtl(String envKey, long ttlMs) throws Exception {
        post(query("/setEventTtl", "envKey", envKey, "ttlMs", ttlMs));
    }

    // ── Client registration ─────────────────────────────────────────────────

    /** Registers a client for WebSocket access; returns the server-generated access key. */
    public String registerClientForWebSocket(String clientId, String envKey) throws Exception {
        return post(query("/registerClientForWebSocket", "clientId", clientId, "envKey", envKey));
    }

    /** Subscribes to objectChanged / objectDeleted notifications for a model object instance. */
    public void registerObjectObserver(String envKey, String clientId, String objectId) throws Exception {
        post(query("/registerObjectObserver", "envKey", envKey, "clientId", clientId, "objectId", objectId));
    }

    /** Subscribes to domainEvent notifications for a specific domain (e.g. "public"). */
    public void registerDomainListener(String envKey, String clientId, String domain) throws Exception {
        post(query("/registerDomainListener", "envKey", envKey, "clientId", clientId, "domain", domain));
    }

    // ── Execution control ───────────────────────────────────────────────────

    /**
     * Activates the environment (creates the engine thread). Requires metamodel and model to be
     * set and must precede startEnvironment. Afterwards the environment is immutable.
     */
    public void runEnvironment(String envKey) throws Exception {
        post(query("/runEnvironment", "envKey", envKey));
    }

    /** Starts the engine tick loop. Requires runEnvironment to have been called first. */
    public void startEnvironment(String envKey) throws Exception {
        post(query("/startEnvironment", "envKey", envKey));
    }

    /** Stops the engine and closes all open WebSocket sessions for this environment. */
    public void stopEnvironment(String envKey) throws Exception {
        post(query("/stopEnvironment", "envKey", envKey));
    }

    /**
     * Forces the running statemachine attached to {@code modelElement} into the given leaf state
     * and clears its pending events (manual resync). Requires an active environment.
     */
    public void setActiveState(String envKey, String modelElement, String state) throws Exception {
        post(query("/setActiveState", "envKey", envKey, "modelElement", modelElement, "state", state));
    }

    // ── Observatory (read-only inspection) ──────────────────────────────────

    /** Lists active environments as raw JSON: [{"envKey":...,"modelElements":[...]}, ...]. */
    public String listEnvironments() throws Exception {
        return get("/observatory/environments");
    }

    /** Returns the .kstates source attached to {@code modelElement}. */
    public String getStateMachineSource(String envKey, String modelElement) throws Exception {
        return get(query("/observatory/statemachine", "envKey", envKey, "modelElement", modelElement));
    }

    /**
     * Registers a client for observatory trace/state updates of {@code modelElement}
     * ({@code "*"} = all elements); returns an access key for {@link #connectWebSocket}.
     */
    public String registerObservatoryClient(String clientId, String envKey, String modelElement) throws Exception {
        return post(query("/observatory/registerClient",
                "clientId", clientId, "envKey", envKey, "modelElement", modelElement));
    }

    // ── WebSocket ───────────────────────────────────────────────────────────

    /**
     * Opens a WebSocket connection to the runtime and authenticates it.
     * Each incoming JSON notification ({"environmentKey","clientId","messageType","payload"},
     * where messageType is e.g. "objectChanged", "objectDeleted" or "domainEvent") is forwarded
     * to {@code onMessage} on a background thread managed by the HttpClient.
     *
     * @return the live WebSocket, which can be used to send events via {@link #sendEvent}
     */
    public WebSocket connectWebSocket(String clientId, String envKey, String accessKey,
                                      Consumer<String> onMessage) {
        var wsUri = URI.create(baseUrl.replace("http://", "ws://") + "/ws");

        // Accumulates partial WebSocket text frames before delivering to the callback
        var buf = new StringBuilder();

        var listener = new WebSocket.Listener() {
            @Override
            public void onOpen(WebSocket ws) {
                ws.request(1); // allow the first message to be delivered
            }

            @Override
            public CompletableFuture<?> onText(WebSocket ws, CharSequence data, boolean last) {
                buf.append(data);
                if (last) {
                    onMessage.accept(buf.toString());
                    buf.setLength(0);
                }
                ws.request(1); // request next message
                return null;
            }

            @Override
            public void onError(WebSocket ws, Throwable error) {
                System.err.println("[WS error] " + error.getMessage());
            }
        };

        // Blocks until the opening handshake completes
        var ws = http.newWebSocketBuilder()
                .buildAsync(wsUri, listener)
                .join();

        // Authenticate: the first message must be "clientId:envKey:accessKey"
        ws.sendText(clientId + ":" + envKey + ":" + accessKey, true).join();
        return ws;
    }

    /**
     * Sends a domain event over an already-open WebSocket connection.
     * The payload format is guessed by the runtime (empty → none, "{" → json, "make object" → kmodel).
     *
     * @param ws        WebSocket returned by {@link #connectWebSocket}
     * @param domain    event domain (e.g. {@code "public"}), matched by EVENT("domain", ...) conditions
     * @param eventName event name (e.g. {@code "movement detected"}); also the payload type in EVENTS.kmeta
     * @param payload   optional payload: {@code ""} (none), a JSON object string, or a kmodel
     *                  {@code make object} block
     */
    public static void sendEvent(WebSocket ws, String domain, String eventName, String payload) {
        sendEvent(ws, domain, eventName, payload, null);
    }

    /**
     * Sends a domain event with an explicit payload format.
     *
     * @param payloadFormat {@code "none"}, {@code "json"}, {@code "kmodel"}, or {@code null} to let
     *                      the runtime guess
     */
    public static void sendEvent(WebSocket ws, String domain, String eventName, String payload,
                                 String payloadFormat) {
        // JSON built manually to avoid external dependencies
        var json = new StringBuilder()
                .append("{\"environmentKey\":").append(jsonString(domain))
                .append(",\"messageType\":").append(jsonString(eventName))
                .append(",\"payload\":").append(jsonString(payload == null ? "" : payload));
        if (payloadFormat != null)
            json.append(",\"payloadFormat\":").append(jsonString(payloadFormat));
        json.append('}');
        ws.sendText(json.toString(), true).join();
    }

    /** Encodes {@code s} as a quoted JSON string literal. */
    private static String jsonString(String s) {
        var sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}
