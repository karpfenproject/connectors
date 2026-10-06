import java.net.{URI, URLEncoder}
import java.net.http.{HttpClient, HttpRequest, HttpResponse, WebSocket}
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture

/** Synchronous HTTP + WebSocket client for the Karpfen runtime server. */
class KarpfenConnector(host: String, port: Int):

  private val baseUrl = s"http://$host:$port"
  private val http = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .build()

  // ── HTTP helpers ──────────────────────────────────────────────────────────

  /** Builds "path?k1=v1&k2=v2", URL-encoding each value. */
  private def query(path: String, params: (String, Any)*): String =
    if params.isEmpty then path
    else path + params
      .map((k, v) => s"$k=${URLEncoder.encode(v.toString, StandardCharsets.UTF_8)}")
      .mkString("?", "&", "")

  /** GET; returns trimmed response body. */
  private def get(pathAndQuery: String): String =
    val req = HttpRequest.newBuilder()
      .uri(URI.create(baseUrl + pathAndQuery))
      .GET()
      .build()
    val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
    assertOk(resp)
    resp.body().strip()

  /** POST with no body; returns trimmed response body. */
  private def post(pathAndQuery: String): String =
    val req = HttpRequest.newBuilder()
      .uri(URI.create(baseUrl + pathAndQuery))
      .POST(HttpRequest.BodyPublishers.noBody())
      .build()
    val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
    assertOk(resp)
    resp.body().strip()

  /** PUT with a plain-text body. */
  private def put(pathAndQuery: String, body: String): Unit =
    val req = HttpRequest.newBuilder()
      .uri(URI.create(baseUrl + pathAndQuery))
      .header("Content-Type", "text/plain")
      .PUT(HttpRequest.BodyPublishers.ofString(body))
      .build()
    val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
    assertOk(resp)

  /** Throws with the server's error message (400 bad params, 409 lifecycle conflict, 500). */
  private def assertOk(resp: HttpResponse[?]): Unit =
    if resp.statusCode() < 200 || resp.statusCode() >= 300 then
      throw RuntimeException(s"HTTP ${resp.statusCode()} — ${resp.body()}")

  // ── Health ────────────────────────────────────────────────────────────────

  /** Returns true if the runtime server is reachable and reports {"status":"ok"}. */
  def health(): Boolean =
    try get("/health").replace(" ", "").contains("\"status\":\"ok\"")
    catch case _: Exception => false

  // ── Environment setup (only allowed before runEnvironment) ────────────────

  /** Creates a new execution environment; returns the server-assigned envKey. */
  def createEnvironment(): String = post("/createEnvironment")

  /** Uploads the metamodel (.kmeta source) to the environment. Must precede setModel. */
  def setMetamodel(envKey: String, content: String): Unit =
    put(query("/setMetamodel", "envKey" -> envKey), content)

  /** Uploads the instance model (.kmodel source) to the environment. */
  def setModel(envKey: String, content: String): Unit =
    put(query("/setModel", "envKey" -> envKey), content)

  /**
   * Uploads the event payload definitions (a .kmeta source, conventionally EVENTS.kmeta).
   * Each type is an event name; an event "setSpeed" is parsed against the type "setSpeed".
   * Optional — only needed for events that carry a payload. Call after setMetamodel if
   * payloads embed domain types.
   */
  def setEventDefinitions(envKey: String, content: String): Unit =
    put(query("/setEventDefinitions", "envKey" -> envKey), content)

  /**
   * Uploads a statemachine (.kstates source) and attaches it to the model object
   * identified by `attachedTo` (the instance ID used in the .kmodel file).
   */
  def setStateMachine(envKey: String, attachedTo: String, content: String): Unit =
    put(query("/setStateMachine", "envKey" -> envKey, "attachedTo" -> attachedTo), content)

  /** Sets the engine tick delay in milliseconds. */
  def setTickDelay(envKey: String, milliseconds: Int): Unit =
    post(query("/setTickDelay", "envKey" -> envKey, "milliseconds" -> milliseconds))

  /** Sets how long unconsumed events stay alive, in milliseconds (0 = forever). */
  def setEventTtl(envKey: String, ttlMs: Long): Unit =
    post(query("/setEventTtl", "envKey" -> envKey, "ttlMs" -> ttlMs))

  // ── Client registration ───────────────────────────────────────────────────

  /** Registers a client for WebSocket access; returns the server-generated access key. */
  def registerClientForWebSocket(clientId: String, envKey: String): String =
    post(query("/registerClientForWebSocket", "clientId" -> clientId, "envKey" -> envKey))

  /** Subscribes to objectChanged / objectDeleted notifications for a model object instance. */
  def registerObjectObserver(envKey: String, clientId: String, objectId: String): Unit =
    post(query("/registerObjectObserver", "envKey" -> envKey, "clientId" -> clientId, "objectId" -> objectId))

  /** Subscribes to domainEvent notifications for a specific domain (e.g. "public"). */
  def registerDomainListener(envKey: String, clientId: String, domain: String): Unit =
    post(query("/registerDomainListener", "envKey" -> envKey, "clientId" -> clientId, "domain" -> domain))

  // ── Execution control ─────────────────────────────────────────────────────

  /**
   * Activates the environment (creates the engine thread). Requires metamodel and model to be
   * set and must precede startEnvironment. Afterwards the environment is immutable.
   */
  def runEnvironment(envKey: String): Unit =
    post(query("/runEnvironment", "envKey" -> envKey))

  /** Starts the engine tick loop. Requires runEnvironment to have been called first. */
  def startEnvironment(envKey: String): Unit =
    post(query("/startEnvironment", "envKey" -> envKey))

  /** Stops the engine and closes all open WebSocket sessions for this environment. */
  def stopEnvironment(envKey: String): Unit =
    post(query("/stopEnvironment", "envKey" -> envKey))

  /**
   * Forces the running statemachine attached to `modelElement` into the given leaf state
   * and clears its pending events (manual resync). Requires an active environment.
   */
  def setActiveState(envKey: String, modelElement: String, state: String): Unit =
    post(query("/setActiveState", "envKey" -> envKey, "modelElement" -> modelElement, "state" -> state))

  // ── Observatory (read-only inspection) ────────────────────────────────────

  /** Lists active environments as raw JSON: [{"envKey":...,"modelElements":[...]}, ...]. */
  def listEnvironments(): String = get("/observatory/environments")

  /** Returns the .kstates source attached to `modelElement`. */
  def getStateMachineSource(envKey: String, modelElement: String): String =
    get(query("/observatory/statemachine", "envKey" -> envKey, "modelElement" -> modelElement))

  /**
   * Registers a client for observatory trace/state updates of `modelElement`
   * (`"*"` = all elements); returns an access key for connectWebSocket.
   */
  def registerObservatoryClient(clientId: String, envKey: String, modelElement: String): String =
    post(query("/observatory/registerClient",
      "clientId" -> clientId, "envKey" -> envKey, "modelElement" -> modelElement))

  // ── WebSocket ─────────────────────────────────────────────────────────────

  /**
   * Opens a WebSocket connection to the runtime and authenticates it.
   * Each incoming JSON notification ({"environmentKey","clientId","messageType","payload"},
   * where messageType is e.g. "objectChanged", "objectDeleted" or "domainEvent") is forwarded
   * to `onMessage` on a background thread managed by the HttpClient.
   *
   * @return the live WebSocket, which can be used to send events via KarpfenConnector.sendEvent
   */
  def connectWebSocket(clientId: String, envKey: String, accessKey: String,
                       onMessage: String => Unit): WebSocket =
    val wsUri = URI.create(baseUrl.replace("http://", "ws://") + "/ws")

    // Accumulates partial WebSocket text frames before delivering to the callback
    val buf = new StringBuilder

    val listener = new WebSocket.Listener:
      override def onOpen(ws: WebSocket): Unit =
        ws.request(1) // allow the first message to be delivered

      override def onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletableFuture[?] =
        buf.append(data)
        if last then
          onMessage(buf.toString())
          buf.setLength(0)
        ws.request(1) // request next message
        null

      override def onError(ws: WebSocket, error: Throwable): Unit =
        System.err.println(s"[WS error] ${error.getMessage}")

    // Blocks until the opening handshake completes
    val ws = http.newWebSocketBuilder()
      .buildAsync(wsUri, listener)
      .join()

    // Authenticate: the first message must be "clientId:envKey:accessKey"
    ws.sendText(s"$clientId:$envKey:$accessKey", true).join()
    ws

object KarpfenConnector:

  /**
   * Sends a domain event over an already-open WebSocket connection.
   *
   * @param ws            WebSocket returned by connectWebSocket
   * @param domain        event domain (e.g. "public"), matched by EVENT("domain", ...) conditions
   * @param eventName     event name (e.g. "movement detected"); also the payload type in EVENTS.kmeta
   * @param payload       optional payload: "" (none), a JSON object string, or a kmodel
   *                      `make object` block
   * @param payloadFormat Some("none" | "json" | "kmodel"), or None to let the runtime guess
   *                      (empty → none, "{" → json, "make object" → kmodel)
   */
  def sendEvent(ws: WebSocket, domain: String, eventName: String, payload: String = "",
                payloadFormat: Option[String] = None): Unit =
    // JSON built manually to avoid external dependencies
    val fields = Seq(
      "environmentKey" -> domain,
      "messageType"    -> eventName,
      "payload"        -> payload
    ) ++ payloadFormat.map("payloadFormat" -> _)
    val json = fields.map((k, v) => s"\"$k\":${jsonString(v)}").mkString("{", ",", "}")
    ws.sendText(json, true).join()

  /** Encodes `s` as a quoted JSON string literal. */
  private def jsonString(s: String): String =
    val sb = new StringBuilder("\"")
    s.foreach {
      case '"'           => sb.append("\\\"")
      case '\\'          => sb.append("\\\\")
      case '\n'          => sb.append("\\n")
      case '\r'          => sb.append("\\r")
      case '\t'          => sb.append("\\t")
      case c if c < 0x20 => sb.append("\\u%04x".format(c.toInt))
      case c             => sb.append(c)
    }
    sb.append('"').toString
