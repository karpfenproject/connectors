import json
import sys
import threading

import requests
import websocket

""" Synchronous HTTP + WebSocket client for the Karpfen runtime server. """


class KarpfenConnector:

    def __init__(self, host: str, port: int):
        self._base_url = f"http://{host}:{port}"

    # ── HTTP helpers ──────────────────────────────────────────────────────────

    @staticmethod
    def _check(resp: requests.Response) -> None:
        """ Raises with the server's error message (400 bad params, 409 lifecycle conflict, 500). """
        if not resp.ok:
            raise RuntimeError(f"HTTP {resp.status_code} — {resp.text.strip()}")

    def _get(self, path: str, params: dict | None = None) -> str:
        """ GET; returns trimmed response body. """
        resp = requests.get(self._base_url + path, params=params, timeout=10)
        self._check(resp)
        return resp.text.strip()

    def _post(self, path: str, params: dict | None = None) -> str:
        """ POST with no body; returns trimmed response body. Query params are URL-encoded. """
        resp = requests.post(self._base_url + path, params=params, timeout=10)
        self._check(resp)
        return resp.text.strip()

    def _put(self, path: str, params: dict, body: str) -> None:
        """ PUT with a plain-text body. """
        resp = requests.put(
            self._base_url + path,
            params=params,
            data=body.encode("utf-8"),
            headers={"Content-Type": "text/plain"},
            timeout=10,
        )
        self._check(resp)

    # ── Health ────────────────────────────────────────────────────────────────

    def health(self) -> bool:
        """ Returns True if the runtime server is reachable and reports {"status":"ok"}. """
        try:
            return json.loads(self._get("/health")).get("status") == "ok"
        except (requests.RequestException, RuntimeError, ValueError):
            return False

    # ── Environment setup (only allowed before run_environment) ───────────────

    def create_environment(self) -> str:
        """ Creates a new execution environment; returns the server-assigned envKey. """
        return self._post("/createEnvironment")

    def set_metamodel(self, env_key: str, content: str) -> None:
        """ Uploads the metamodel (.kmeta source) to the environment. Must precede set_model. """
        self._put("/setMetamodel", {"envKey": env_key}, content)

    def set_model(self, env_key: str, content: str) -> None:
        """ Uploads the instance model (.kmodel source) to the environment. """
        self._put("/setModel", {"envKey": env_key}, content)

    def set_event_definitions(self, env_key: str, content: str) -> None:
        """
        Uploads the event payload definitions (a .kmeta source, conventionally EVENTS.kmeta).
        Each type is an event name; an event "setSpeed" is parsed against the type "setSpeed".
        Optional — only needed for events that carry a payload. Call after set_metamodel if
        payloads embed domain types.
        """
        self._put("/setEventDefinitions", {"envKey": env_key}, content)

    def set_state_machine(self, env_key: str, attached_to: str, content: str) -> None:
        """
        Uploads a statemachine (.kstates source) and attaches it to the model object
        identified by attached_to (the instance ID used in the .kmodel file).
        """
        self._put("/setStateMachine", {"envKey": env_key, "attachedTo": attached_to}, content)

    def set_tick_delay(self, env_key: str, milliseconds: int) -> None:
        """ Sets the engine tick delay in milliseconds. """
        self._post("/setTickDelay", {"envKey": env_key, "milliseconds": milliseconds})

    def set_event_ttl(self, env_key: str, ttl_ms: int) -> None:
        """ Sets how long unconsumed events stay alive, in milliseconds (0 = forever). """
        self._post("/setEventTtl", {"envKey": env_key, "ttlMs": ttl_ms})

    # ── Client registration ───────────────────────────────────────────────────

    def register_client_for_websocket(self, client_id: str, env_key: str) -> str:
        """ Registers a client for WebSocket access; returns the server-generated access key. """
        return self._post("/registerClientForWebSocket", {"clientId": client_id, "envKey": env_key})

    def register_object_observer(self, env_key: str, client_id: str, object_id: str) -> None:
        """ Subscribes to objectChanged / objectDeleted notifications for a model object instance. """
        self._post("/registerObjectObserver", {"envKey": env_key, "clientId": client_id, "objectId": object_id})

    def register_domain_listener(self, env_key: str, client_id: str, domain: str) -> None:
        """ Subscribes to domainEvent notifications for a specific domain (e.g. "public"). """
        self._post("/registerDomainListener", {"envKey": env_key, "clientId": client_id, "domain": domain})

    # ── Execution control ─────────────────────────────────────────────────────

    def run_environment(self, env_key: str) -> None:
        """
        Activates the environment (creates the engine thread). Requires metamodel and model to be
        set and must precede start_environment. Afterwards the environment is immutable.
        """
        self._post("/runEnvironment", {"envKey": env_key})

    def start_environment(self, env_key: str) -> None:
        """ Starts the engine tick loop. Requires run_environment to have been called first. """
        self._post("/startEnvironment", {"envKey": env_key})

    def stop_environment(self, env_key: str) -> None:
        """ Stops the engine and closes all open WebSocket sessions for this environment. """
        self._post("/stopEnvironment", {"envKey": env_key})

    def set_active_state(self, env_key: str, model_element: str, state: str) -> None:
        """
        Forces the running statemachine attached to model_element into the given leaf state and
        clears its pending events (manual resync). Requires an active environment.
        """
        self._post("/setActiveState", {"envKey": env_key, "modelElement": model_element, "state": state})

    # ── Observatory (read-only inspection) ────────────────────────────────────

    def list_environments(self) -> list[dict]:
        """ Lists active environments: [{"envKey": ..., "modelElements": [...]}, ...]. """
        return json.loads(self._get("/observatory/environments"))

    def get_state_machine_source(self, env_key: str, model_element: str) -> str:
        """ Returns the .kstates source attached to model_element. """
        return self._get("/observatory/statemachine", {"envKey": env_key, "modelElement": model_element})

    def register_observatory_client(self, client_id: str, env_key: str, model_element: str) -> str:
        """
        Registers a client for observatory trace/state updates of model_element ("*" = all
        elements); returns an access key for connect_websocket.
        """
        return self._post(
            "/observatory/registerClient",
            {"clientId": client_id, "envKey": env_key, "modelElement": model_element},
        )

    # ── WebSocket ─────────────────────────────────────────────────────────────

    def connect_websocket(
        self,
        client_id: str,
        env_key: str,
        access_key: str,
        on_message,
    ) -> websocket.WebSocketApp:
        """
        Opens a WebSocket connection to the runtime and authenticates it.
        Each incoming notification is forwarded to on_message on a background thread as a dict
        {"environmentKey", "clientId", "messageType", "payload"}, where messageType is e.g.
        "objectChanged", "objectDeleted" or "domainEvent".

        Returns the live WebSocketApp, which can be used to send events via
        KarpfenConnector.send_event.
        """
        ws_url = self._base_url.replace("http://", "ws://") + "/ws"
        connected = threading.Event()

        def _on_open(ws):
            # Authenticate: the first message must be "clientId:envKey:accessKey"
            ws.send(f"{client_id}:{env_key}:{access_key}")
            connected.set()

        def _on_message(ws, message):
            try:
                on_message(json.loads(message))
            except ValueError:
                print(f"[WS] Non-JSON message: {message}", file=sys.stderr)

        def _on_error(ws, error):
            print(f"[WS error] {error}", file=sys.stderr)

        app = websocket.WebSocketApp(
            ws_url,
            on_open=_on_open,
            on_message=_on_message,
            on_error=_on_error,
        )

        # Run on a background thread so the main thread stays free for keyboard input
        thread = threading.Thread(target=app.run_forever, daemon=True)
        thread.start()

        # Block until the opening handshake completes
        if not connected.wait(timeout=10):
            raise TimeoutError(f"WebSocket connection to {ws_url} timed out")
        return app

    @staticmethod
    def send_event(
        ws: websocket.WebSocketApp,
        domain: str,
        event_name: str,
        payload: str | dict = "",
        payload_format: str | None = None,
    ) -> None:
        """
        Sends a domain event over an already-open WebSocket connection.

        ws             WebSocketApp returned by connect_websocket
        domain         event domain (e.g. "public"), matched by EVENT("domain", ...) conditions
        event_name     event name (e.g. "movement detected"); also the payload type in EVENTS.kmeta
        payload        optional payload: "" (none), a dict (sent as JSON), a JSON object string,
                       or a kmodel `make object` block
        payload_format optional "none", "json" or "kmodel"; guessed by the runtime when omitted
        """
        if isinstance(payload, dict):
            payload = json.dumps(payload)
        msg = {
            "environmentKey": domain,
            "messageType": event_name,
            "payload": payload,
        }
        if payload_format is not None:
            msg["payloadFormat"] = payload_format
        ws.send(json.dumps(msg))
