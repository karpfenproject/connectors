import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Scanner;

/** Minimal Karpfen client: lightbulb statemachine driven by keyboard events. */
public class Main {

    // ── Configuration ────────────────────────────────────────────────────────
    static final String HOST       = "127.0.0.1";
    static final int    PORT       = 8080;
    static final String CLIENT_ID  = "java-client";
    static final String LIGHT_ID   = "light";              // lightbulb instance ID in world.kmodel
    static final int    TICK_MS    = 1000;                 // engine tick delay
    static final int    EVENT_TTL  = 5000;                 // unconsumed events expire after 5 s
    static final double DURATION   = 3;                    // default light duration (ticks), sent as event payload
    static final String MODELS_DIR = "../example_models/"; // relative to the java_starter/ working directory

    public static void main(String[] args) throws Exception {
        var connector = new KarpfenConnector(HOST, PORT);

        // ── Load model artefacts from disk ───────────────────────────────────
        var metamodel    = Files.readString(Path.of(MODELS_DIR + "domain.kmeta"));
        var model        = Files.readString(Path.of(MODELS_DIR + "world.kmodel"));
        var statemachine = Files.readString(Path.of(MODELS_DIR + "lightbulb.kstates"));
        var eventDefs    = Files.readString(Path.of(MODELS_DIR + "EVENTS.kmeta"));

        System.out.println("Connecting to Karpfen runtime at " + HOST + ":" + PORT + " …");
        if (!connector.health()) {
            System.err.println("Karpfen runtime not reachable at " + HOST + ":" + PORT + " — is the server running?");
            System.exit(1);
        }

        // ── 1. Create a new execution environment ────────────────────────────
        var envKey = connector.createEnvironment();
        System.out.println("Environment: " + envKey);

        // ── 2. Register the model artefacts (metamodel first) ────────────────
        connector.setMetamodel(envKey, metamodel);
        connector.setModel(envKey, model);
        connector.setEventDefinitions(envKey, eventDefs);
        connector.setStateMachine(envKey, LIGHT_ID, statemachine);
        System.out.println("Models registered.");

        // ── 3. Configure engine timing ───────────────────────────────────────
        connector.setTickDelay(envKey, TICK_MS);
        connector.setEventTtl(envKey, EVENT_TTL);

        // ── 4. Register client and subscribe to object-change notifications ──
        var accessKey = connector.registerClientForWebSocket(CLIENT_ID, envKey);
        // Request notifications whenever the "light" object's properties change
        connector.registerObjectObserver(envKey, CLIENT_ID, LIGHT_ID);

        // ── 5. Activate and start the engine ─────────────────────────────────
        connector.runEnvironment(envKey);
        connector.startEnvironment(envKey);
        System.out.println("Environment started (tick every " + TICK_MS + " ms).");

        // ── 6. Connect WebSocket — background thread prints object changes ────
        // connectWebSocket authenticates the session and delivers messages
        // to the callback on an HttpClient background thread.
        WebSocket ws = connector.connectWebSocket(CLIENT_ID, envKey, accessKey,
                message -> System.out.println("[OBSERVATION] " + message));
        System.out.println("WebSocket connected.\n");

        // Stop the environment on exit (Ctrl+C or end of input)
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                connector.stopEnvironment(envKey);
                System.out.println("Environment stopped.");
            } catch (Exception e) {
                System.err.println("Could not stop environment: " + e.getMessage());
            }
        }));

        // ── 7. Keyboard loop — press Enter to fire a "movement detected" event
        // The event carries a JSON payload, parsed against the "movement detected" type in EVENTS.kmeta.
        System.out.println("Press ENTER to send 'movement detected' (light on for " + DURATION + " ticks),");
        System.out.println("or type a number + ENTER for a custom duration  (Ctrl+C to quit).");
        var scanner = new Scanner(System.in);
        while (scanner.hasNextLine()) {
            var line = scanner.nextLine().strip(); // block until Enter is pressed
            double duration;
            try {
                duration = line.isEmpty() ? DURATION : Double.parseDouble(line);
            } catch (NumberFormatException e) {
                System.out.println("Not a number: '" + line + "'");
                continue;
            }
            KarpfenConnector.sendEvent(ws, "public", "movement detected", "{\"duration\": " + duration + "}");
            System.out.println("[EVENT SENT] movement detected → public (duration=" + duration + ")");
        }
    }
}
