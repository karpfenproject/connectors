import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse, WebSocket}
import java.time.Duration
import java.util.concurrent.CompletableFuture

/** Synchronous HTTP + WebSocket client for the Karpfen runtime server. */
class KarpfenConnector(host: String, port: Int):

  private val baseUrl = s"http://$host:$port"
  private val http = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .build()

  // ── HTTP helpers ──────────────────────────────────────────────────────────

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

  private def assertOk(resp: HttpResponse[?]): Unit =
    if resp.statusCode() < 200 || resp.statusCode() >= 300 then
      throw RuntimeException(s"HTTP ${resp.statusCode()} — ${resp.body()}")

  // ── Environment setup ─────────────────────────────────────────────────────

  /** Creates a new execution environment; returns the server-assigned envKey. */
  def createEnvironment(): String = post("/createEnvironment")

  /** Uploads the metamodel (.kmeta source) to the environment. */
  def setMetamodel(envKey: String, content: String): Unit =
    put(s"/setMetamodel?envKey=$envKey", content)

  /** Uploads the instance model (.kmodel source) to the environment. */
  def setModel(envKey: String, content: String): Unit =
    put(s"/setModel?envKey=$envKey", content)

  /**
   * Uploads a statemachine (.kstates source) and attaches it to the model object
   * identified by `attachedTo` (the instance ID used in the .kmodel file).
   */
  def setStateMachine(envKey: String, attachedTo: String, content: String): Unit =
    put(s"/setStateMachine?envKey=$envKey&attachedTo=$attachedTo", content)

  /** Sets the engine tick delay in milliseconds. Must be called before runEnvironment. */
  def setTickDelay(envKey: String, milliseconds: Int): Unit =
    post(s"/setTickDelay?envKey=$envKey&milliseconds=$milliseconds")

  // ── Client registration ───────────────────────────────────────────────────

  /** Registers a client for WebSocket access; returns the server-generated access key. */
  def registerClientForWebSocket(clientId: String, envKey: String): String =
    post(s"/registerClientForWebSocket?clientId=$clientId&envKey=$envKey")

  /** Subscribes to property-change notifications for a specific model object instance. */
  def registerObjectObserver(envKey: String, clientId: String, objectId: String): Unit =
    post(s"/registerObjectObserver?envKey=$envKey&clientId=$clientId&objectId=$objectId")

  /** Subscribes to event notifications for a specific domain (e.g. "public"). */
  def registerDomainListener(envKey: String, clientId: String, domain: String): Unit =
    post(s"/registerDomainListener?envKey=$envKey&clientId=$clientId&domain=$domain")

  // ── Execution control ─────────────────────────────────────────────────────

  /** Activates the environment (creates the engine thread). Must precede startEnvironment. */
  def runEnvironment(envKey: String): Unit =
    post(s"/runEnvironment?envKey=$envKey")

  /** Starts the engine tick loop. Requires runEnvironment to have been called first. */
  def startEnvironment(envKey: String): Unit =
    post(s"/startEnvironment?envKey=$envKey")

  /** Stops the engine and closes all open WebSocket sessions for this environment. */
  def stopEnvironment(envKey: String): Unit =
    post(s"/stopEnvironment?envKey=$envKey")

  // ── WebSocket ─────────────────────────────────────────────────────────────

  /**
   * Opens a WebSocket connection to the runtime and authenticates it.
   * Each incoming JSON notification is forwarded to `onMessage` on a
   * background thread managed by the HttpClient.
   *
   * @return the live WebSocket, which can be used to send events via KarpfenConnector.sendEvent
   */
  def connectWebSocket(
      clientId: String,
      envKey: String,
      accessKey: String,
      onMessage: String => Unit
  ): WebSocket =
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
   * @param ws        WebSocket returned by connectWebSocket
   * @param domain    event domain (e.g. "public")
   * @param eventName event name (e.g. "movement detected")
   * @param payload   optional string payload
   */
  def sendEvent(ws: WebSocket, domain: String, eventName: String, payload: String): Unit =
    // JSON built manually to avoid external dependencies
    val json = s"""{"environmentKey":"$domain","messageType":"$eventName","payload":"$payload"}"""
    ws.sendText(json, true)
