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
    static final String LIGHT_ID  = "light";               // lightbulb instance ID in world.kmodel
    static final int    TICK_MS    = 1000;                 // engine tick delay
    static final String MODELS_DIR = "../example_models/"; // relative to the java/ working directory

    public static void main(String[] args) throws Exception {
        var connector = new KarpfenConnector(HOST, PORT);

        // ── Load model artefacts from disk ───────────────────────────────────
        var metamodel    = Files.readString(Path.of(MODELS_DIR + "domain.kmeta"));
        var model        = Files.readString(Path.of(MODELS_DIR + "world.kmodel"));
        var statemachine = Files.readString(Path.of(MODELS_DIR + "lightbulb.kstates"));

        System.out.println("Connecting to Karpfen runtime at " + HOST + ":" + PORT + " …");

        // ── 1. Create a new execution environment ────────────────────────────
        var envKey = connector.createEnvironment();
        System.out.println("Environment: " + envKey);

        // ── 2. Register the three model artefacts ────────────────────────────
        connector.setMetamodel(envKey, metamodel);
        connector.setModel(envKey, model);
        connector.setStateMachine(envKey, LIGHT_ID, statemachine);
        System.out.println("Models registered.");

        // ── 3. Configure engine timing ───────────────────────────────────────
        connector.setTickDelay(envKey, TICK_MS);

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

        // ── 7. Keyboard loop — press Enter to fire a "movement detected" event
        System.out.println("Press ENTER to send 'movement detected'  (Ctrl+C to quit).");
        var scanner = new Scanner(System.in);
        while (scanner.hasNextLine()) {
            scanner.nextLine(); // block until Enter is pressed
            KarpfenConnector.sendEvent(ws, "public", "movement detected", "");
            System.out.println("[EVENT SENT] movement detected → public");
        }
    }
}
