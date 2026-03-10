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

    def _post(self, path_and_query: str) -> str:
        """ POST with no body; returns trimmed response body. """
        resp = requests.post(self._base_url + path_and_query, timeout=10)
        resp.raise_for_status()
        return resp.text.strip()

    def _put(self, path_and_query: str, body: str) -> None:
        """ PUT with a plain-text body. """
        resp = requests.put(
            self._base_url + path_and_query,
            data=body.encode("utf-8"),
            headers={"Content-Type": "text/plain"},
            timeout=10,
        )
        resp.raise_for_status()

    # ── Environment setup ─────────────────────────────────────────────────────

    def create_environment(self) -> str:
        """ Creates a new execution environment; returns the server-assigned envKey. """
        return self._post("/createEnvironment")

    def set_metamodel(self, env_key: str, content: str) -> None:
        """ Uploads the metamodel (.kmeta source) to the environment. """
        self._put(f"/setMetamodel?envKey={env_key}", content)

    def set_model(self, env_key: str, content: str) -> None:
        """ Uploads the instance model (.kmodel source) to the environment. """
        self._put(f"/setModel?envKey={env_key}", content)

    def set_state_machine(self, env_key: str, attached_to: str, content: str) -> None:
        """
        Uploads a statemachine (.kstates source) and attaches it to the model object
        identified by attached_to (the instance ID used in the .kmodel file).
        """
        self._put(f"/setStateMachine?envKey={env_key}&attachedTo={attached_to}", content)

    def set_tick_delay(self, env_key: str, milliseconds: int) -> None:
        """ Sets the engine tick delay in milliseconds. Must be called before run_environment. """
        self._post(f"/setTickDelay?envKey={env_key}&milliseconds={milliseconds}")

    # ── Client registration ───────────────────────────────────────────────────

    def register_client_for_websocket(self, client_id: str, env_key: str) -> str:
        """ Registers a client for WebSocket access; returns the server-generated access key. """
        return self._post(f"/registerClientForWebSocket?clientId={client_id}&envKey={env_key}")

    def register_object_observer(self, env_key: str, client_id: str, object_id: str) -> None:
        """ Subscribes to property-change notifications for a specific model object instance. """
        self._post(f"/registerObjectObserver?envKey={env_key}&clientId={client_id}&objectId={object_id}")

    def register_domain_listener(self, env_key: str, client_id: str, domain: str) -> None:
        """ Subscribes to event notifications for a specific domain (e.g. "public"). """
        self._post(f"/registerDomainListener?envKey={env_key}&clientId={client_id}&domain={domain}")

    # ── Execution control ─────────────────────────────────────────────────────

    def run_environment(self, env_key: str) -> None:
        """ Activates the environment (creates the engine thread). Must precede start_environment. """
        self._post(f"/runEnvironment?envKey={env_key}")

    def start_environment(self, env_key: str) -> None:
        """ Starts the engine tick loop. Requires run_environment to have been called first. """
        self._post(f"/startEnvironment?envKey={env_key}")

    def stop_environment(self, env_key: str) -> None:
        """ Stops the engine and closes all open WebSocket sessions for this environment. """
        self._post(f"/stopEnvironment?envKey={env_key}")

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
        Each incoming JSON notification is forwarded to on_message on a background thread.

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
            on_message(message)

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

        # Block until the opening handshake completes (or timeout after 10 s)
        connected.wait(timeout=10)
        return app

    @staticmethod
    def send_event(ws: websocket.WebSocketApp, domain: str, event_name: str, payload: str) -> None:
        """
        Sends a domain event over an already-open WebSocket connection.

        ws         WebSocketApp returned by connect_websocket
        domain     event domain (e.g. "public")
        event_name event name (e.g. "movement detected")
        payload    optional string payload
        """
        # JSON built to match the format expected by the Karpfen runtime
        json_msg = json.dumps({
            "environmentKey": domain,
            "messageType": event_name,
            "payload": payload,
        })
        ws.send(json_msg)
