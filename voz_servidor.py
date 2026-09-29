import json, subprocess, threading, os, re, secrets, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs
from vosk import Model, KaldiRecognizer, SetLogLevel

SetLogLevel(-1)
MODEL = "/storage/emulated/0/Download/ProyectosTermux/Models/vosk-model-small-es-0.42"
PORT = 8765
DIR = os.path.expanduser("~/ProyectosTermux/netguard")
TF = os.path.join(DIR, ".token")
if not os.path.exists(TF):
    open(TF, "w").write(secrets.token_hex(8))
    os.chmod(TF, 0o600)
TOKEN = open(TF).read().strip()


def pausa(n, d):
    try:
        return int(re.search(n + r"=(\d+)", open(os.path.join(DIR, "config.sh")).read()).group(1))
    except Exception:
        return d


def orden_vista():
    o = E["orden"]
    if not o:
        return None
    el = time.time() - o["t0"]
    a, r = pausa("PAUSA_AVION", 2), pausa("PAUSA_RED", 15)
    if el > a + r + 22:
        return None
    paso = 1 if el < a + 1 else (2 if el < a + 1 + r else 3)
    return {"nombre": "Reiniciar conexión", "paso": paso, "hecho": el > a + r + 6}

E = {"escuchando": False, "parcial": "", "finales": [], "n": 0, "orden": None}
lock = threading.Lock()
parar = threading.Event()


def preparar_audio():
    subprocess.run(["pulseaudio", "--start", "--exit-idle-time=-1"])
    fuentes = subprocess.run(["pactl", "list", "short", "sources"],
                             capture_output=True, text=True).stdout
    if "OpenSL_ES_source" not in fuentes:
        subprocess.run(["pactl", "load-module", "module-sles-source"])


def agregar(texto):
    if texto:
        E["n"] += 1
        E["finales"].append({"n": E["n"], "t": texto})
        E["finales"] = E["finales"][-50:]
        o = E["orden"]
        if "reinici" in texto.lower() and (not o or time.time() - o["t0"] > 60):
            E["orden"] = {"t0": time.time()}
            subprocess.Popen([DIR + "/reiniciar.sh"])


def escuchar():
    rec = KaldiRecognizer(model, 16000)
    p = subprocess.Popen(
        ["parec", "--device=OpenSL_ES_source", "--rate=16000", "--channels=1",
         "--format=s16le", "--latency-msec=100"], stdout=subprocess.PIPE)
    try:
        while not parar.is_set():
            data = p.stdout.read(3200)
            if not data:
                break
            if rec.AcceptWaveform(data):
                t = json.loads(rec.Result()).get("text", "")
                with lock:
                    E["parcial"] = ""
                    agregar(t)
            else:
                with lock:
                    E["parcial"] = json.loads(rec.PartialResult()).get("partial", "")
    finally:
        p.terminate()
        with lock:
            agregar(json.loads(rec.FinalResult()).get("text", ""))
            E["escuchando"] = False
            E["parcial"] = ""


class H(BaseHTTPRequestHandler):
    def _j(self, obj, code=200):
        b = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def _auth(self):
        if self.headers.get("X-Moon-Token") == TOKEN:
            return True
        self._j({"auth": False}, 401)
        return False

    def do_GET(self):
        if not self._auth():
            return
        u = urlparse(self.path)
        if u.path == "/estado":
            d = int(parse_qs(u.query).get("desde", ["0"])[0])
            with lock:
                self._j({"escuchando": E["escuchando"], "parcial": E["parcial"],
                         "total": E["n"],
                         "finales": [f for f in E["finales"] if f["n"] > d],
                         "orden": orden_vista()})
        else:
            self._j({"error": "no existe"}, 404)

    def do_POST(self):
        if not self._auth():
            return
        if self.path == "/escuchar":
            with lock:
                ya = E["escuchando"]
                if not ya:
                    E["escuchando"] = True
            if not ya:
                parar.clear()
                threading.Thread(target=escuchar, daemon=True).start()
            self._j({"ok": True})
        elif self.path == "/parar":
            parar.set()
            self._j({"ok": True})
        else:
            self._j({"error": "no existe"}, 404)

    def log_message(self, *a):
        pass


preparar_audio()
print("Cargando modelo...", flush=True)
model = Model(MODEL)
print("Token:", TOKEN, flush=True)
print("Listo. Servidor en 127.0.0.1:%d (Ctrl+C para salir)" % PORT, flush=True)
ThreadingHTTPServer(("127.0.0.1", PORT), H).serve_forever()
