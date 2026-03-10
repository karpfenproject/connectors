import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
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

    private void assertOk(HttpResponse<?> resp) {
        if (resp.statusCode() < 200 || resp.statusCode() >= 300)
            throw new RuntimeException("HTTP " + resp.statusCode() + " — " + resp.body());
    }

    // ── Environment setup ───────────────────────────────────────────────────

    /** Creates a new execution environment; returns the server-assigned envKey. */
    public String createEnvironment() throws Exception {
        return post("/createEnvironment");
    }

    /** Uploads the metamodel (.kmeta source) to the environment. */
    public void setMetamodel(String envKey, String content) throws Exception {
        put("/setMetamodel?envKey=" + envKey, content);
    }

    /** Uploads the instance model (.kmodel source) to the environment. */
    public void setModel(String envKey, String content) throws Exception {
        put("/setModel?envKey=" + envKey, content);
    }

    /**
     * Uploads a statemachine (.kstates source) and attaches it to the model object
     * identified by {@code attachedTo} (the instance ID used in the .kmodel file).
     */
    public void setStateMachine(String envKey, String attachedTo, String content) throws Exception {
        put("/setStateMachine?envKey=" + envKey + "&attachedTo=" + attachedTo, content);
    }

    /** Sets the engine tick delay in milliseconds. Must be called before runEnvironment. */
    public void setTickDelay(String envKey, int milliseconds) throws Exception {
        post("/setTickDelay?envKey=" + envKey + "&milliseconds=" + milliseconds);
    }

    // ── Client registration ─────────────────────────────────────────────────

    /** Registers a client for WebSocket access; returns the server-generated access key. */
    public String registerClientForWebSocket(String clientId, String envKey) throws Exception {
        return post("/registerClientForWebSocket?clientId=" + clientId + "&envKey=" + envKey);
    }

    /** Subscribes to property-change notifications for a specific model object instance. */
    public void registerObjectObserver(String envKey, String clientId, String objectId) throws Exception {
        post("/registerObjectObserver?envKey=" + envKey + "&clientId=" + clientId + "&objectId=" + objectId);
    }

    /** Subscribes to event notifications for a specific domain (e.g. "public"). */
    public void registerDomainListener(String envKey, String clientId, String domain) throws Exception {
        post("/registerDomainListener?envKey=" + envKey + "&clientId=" + clientId + "&domain=" + domain);
    }

    // ── Execution control ───────────────────────────────────────────────────

    /** Activates the environment (creates the engine thread). Must precede startEnvironment. */
    public void runEnvironment(String envKey) throws Exception {
        post("/runEnvironment?envKey=" + envKey);
    }

    /** Starts the engine tick loop. Requires runEnvironment to have been called first. */
    public void startEnvironment(String envKey) throws Exception {
        post("/startEnvironment?envKey=" + envKey);
    }

    /** Stops the engine and closes all open WebSocket sessions for this environment. */
    public void stopEnvironment(String envKey) throws Exception {
        post("/stopEnvironment?envKey=" + envKey);
    }

    // ── WebSocket ───────────────────────────────────────────────────────────

    /**
     * Opens a WebSocket connection to the runtime and authenticates it.
     * Each incoming JSON notification is forwarded to {@code onMessage} on a
     * background thread managed by the HttpClient.
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
     *
     * @param ws        WebSocket returned by {@link #connectWebSocket}
     * @param domain    event domain (e.g. {@code "public"})
     * @param eventName event name (e.g. {@code "movement detected"})
     * @param payload   optional string payload
     */
    public static void sendEvent(WebSocket ws, String domain, String eventName, String payload) {
        // JSON built manually to avoid external dependencies
        var json = """
                {"environmentKey":"%s","messageType":"%s","payload":"%s"}"""
                .formatted(domain, eventName, payload);
        ws.sendText(json, true);
    }
}
