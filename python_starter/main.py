from karpfen_connector import KarpfenConnector

""" Minimal Karpfen client: lightbulb statemachine driven by keyboard events. """

# ── Configuration ─────────────────────────────────────────────────────────────
HOST       = "127.0.0.1"
PORT       = 8080
CLIENT_ID  = "python-client"
LIGHT_ID   = "light"                # lightbulb instance ID in world.kmodel
TICK_MS    = 1000                   # engine tick delay
MODELS_DIR = "../example_models/"   # relative to the python_starter/ working directory


def main():
    connector = KarpfenConnector(HOST, PORT)

    # ── Load model artefacts from disk ────────────────────────────────────────
    with open(MODELS_DIR + "domain.kmeta") as f:
        metamodel = f.read()
    with open(MODELS_DIR + "world.kmodel") as f:
        model = f.read()
    with open(MODELS_DIR + "lightbulb.kstates") as f:
        statemachine = f.read()

    print(f"Connecting to Karpfen runtime at {HOST}:{PORT} …")

    # ── 1. Create a new execution environment ─────────────────────────────────
    env_key = connector.create_environment()
    print(f"Environment: {env_key}")

    # ── 2. Register the three model artefacts ─────────────────────────────────
    connector.set_metamodel(env_key, metamodel)
    connector.set_model(env_key, model)
    connector.set_state_machine(env_key, LIGHT_ID, statemachine)
    print("Models registered.")

    # ── 3. Configure engine timing ────────────────────────────────────────────
    connector.set_tick_delay(env_key, TICK_MS)

    # ── 4. Register client and subscribe to object-change notifications ───────
    access_key = connector.register_client_for_websocket(CLIENT_ID, env_key)
    # Request notifications whenever the "light" object's properties change
    connector.register_object_observer(env_key, CLIENT_ID, LIGHT_ID)

    # ── 5. Activate and start the engine ──────────────────────────────────────
    connector.run_environment(env_key)
    connector.start_environment(env_key)
    print(f"Environment started (tick every {TICK_MS} ms).")

    # ── 6. Connect WebSocket — background thread prints object changes ─────────
    # connect_websocket authenticates the session and delivers messages
    # to the callback on a background thread.
    ws = connector.connect_websocket(
        CLIENT_ID, env_key, access_key,
        lambda message: print(f"[OBSERVATION] {message}"),
    )
    print("WebSocket connected.\n")

    # ── 7. Keyboard loop — press Enter to fire a "movement detected" event ─────
    print("Press ENTER to send 'movement detected'  (Ctrl+C to quit).")
    try:
        while True:
            input()  # block until Enter is pressed
            KarpfenConnector.send_event(ws, "public", "movement detected", "")
            print("[EVENT SENT] movement detected → public")
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
