import java.net.http.WebSocket
import java.nio.file.{Files, Path}
import java.util.Scanner

/** Minimal Karpfen client: lightbulb statemachine driven by keyboard events. */
@main def run(): Unit =

  // ── Configuration ─────────────────────────────────────────────────────────
  val Host      = "127.0.0.1"
  val Port      = 8080
  val ClientId  = "scala-client"
  val LightId   = "light"               // lightbulb instance ID in world.kmodel
  val TickMs    = 1000                  // engine tick delay
  val ModelsDir = "../example_models/"  // relative to the scala_starter/ working directory

  val connector = KarpfenConnector(Host, Port)

  // ── Load model artefacts from disk ────────────────────────────────────────
  val metamodel    = Files.readString(Path.of(ModelsDir + "domain.kmeta"))
  val model        = Files.readString(Path.of(ModelsDir + "world.kmodel"))
  val statemachine = Files.readString(Path.of(ModelsDir + "lightbulb.kstates"))

  println(s"Connecting to Karpfen runtime at $Host:$Port …")

  // ── 1. Create a new execution environment ─────────────────────────────────
  val envKey = connector.createEnvironment()
  println(s"Environment: $envKey")

  // ── 2. Register the three model artefacts ─────────────────────────────────
  connector.setMetamodel(envKey, metamodel)
  connector.setModel(envKey, model)
  connector.setStateMachine(envKey, LightId, statemachine)
  println("Models registered.")

  // ── 3. Configure engine timing ────────────────────────────────────────────
  connector.setTickDelay(envKey, TickMs)

  // ── 4. Register client and subscribe to object-change notifications ───────
  val accessKey = connector.registerClientForWebSocket(ClientId, envKey)
  // Request notifications whenever the "light" object's properties change
  connector.registerObjectObserver(envKey, ClientId, LightId)

  // ── 5. Activate and start the engine ──────────────────────────────────────
  connector.runEnvironment(envKey)
  connector.startEnvironment(envKey)
  println(s"Environment started (tick every $TickMs ms).")

  // ── 6. Connect WebSocket — background thread prints object changes ─────────
  // connectWebSocket authenticates the session and delivers messages
  // to the callback on an HttpClient background thread.
  val ws: WebSocket = connector.connectWebSocket(ClientId, envKey, accessKey,
    message => println(s"[OBSERVATION] $message"))
  println("WebSocket connected.\n")

  // ── 7. Keyboard loop — press Enter to fire a "movement detected" event ─────
  println("Press ENTER to send 'movement detected'  (Ctrl+C to quit).")
  val scanner = Scanner(System.in)
  while scanner.hasNextLine() do
    scanner.nextLine() // block until Enter is pressed
    KarpfenConnector.sendEvent(ws, "public", "movement detected", "")
    println("[EVENT SENT] movement detected → public")
