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
  val EventTtl  = 5000                  // unconsumed events expire after 5 s
  val Duration  = 3.0                   // default light duration (ticks), sent as event payload
  val ModelsDir = "../example_models/"  // relative to the scala_starter/ working directory

  val connector = KarpfenConnector(Host, Port)

  // ── Load model artefacts from disk ────────────────────────────────────────
  val metamodel    = Files.readString(Path.of(ModelsDir + "domain.kmeta"))
  val model        = Files.readString(Path.of(ModelsDir + "world.kmodel"))
  val statemachine = Files.readString(Path.of(ModelsDir + "lightbulb.kstates"))
  val eventDefs    = Files.readString(Path.of(ModelsDir + "EVENTS.kmeta"))

  println(s"Connecting to Karpfen runtime at $Host:$Port …")
  if !connector.health() then
    System.err.println(s"Karpfen runtime not reachable at $Host:$Port — is the server running?")
    sys.exit(1)

  // ── 1. Create a new execution environment ─────────────────────────────────
  val envKey = connector.createEnvironment()
  println(s"Environment: $envKey")

  // ── 2. Register the model artefacts (metamodel first) ─────────────────────
  connector.setMetamodel(envKey, metamodel)
  connector.setModel(envKey, model)
  connector.setEventDefinitions(envKey, eventDefs)
  connector.setStateMachine(envKey, LightId, statemachine)
  println("Models registered.")

  // ── 3. Configure engine timing ────────────────────────────────────────────
  connector.setTickDelay(envKey, TickMs)
  connector.setEventTtl(envKey, EventTtl)

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

  // Stop the environment on exit (Ctrl+C or end of input)
  sys.addShutdownHook {
    try
      connector.stopEnvironment(envKey)
      println("Environment stopped.")
    catch case e: Exception => System.err.println(s"Could not stop environment: ${e.getMessage}")
  }

  // ── 7. Keyboard loop — press Enter to fire a "movement detected" event ─────
  // The event carries a JSON payload, parsed against the "movement detected" type in EVENTS.kmeta.
  println(s"Press ENTER to send 'movement detected' (light on for $Duration ticks),")
  println("or type a number + ENTER for a custom duration  (Ctrl+C to quit).")
  val scanner = Scanner(System.in)
  while scanner.hasNextLine() do
    val line = scanner.nextLine().strip() // block until Enter is pressed
    (if line.isEmpty then Some(Duration) else line.toDoubleOption) match
      case Some(duration) =>
        KarpfenConnector.sendEvent(ws, "public", "movement detected", s"""{"duration": $duration}""")
        println(s"[EVENT SENT] movement detected → public (duration=$duration)")
      case None =>
        println(s"Not a number: '$line'")
