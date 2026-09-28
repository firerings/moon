import json, subprocess, threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs
from vosk import Model, KaldiRecognizer, SetLogLevel

SetLogLevel(-1)
MODEL = "/storage/emulated/0/Download/ProyectosTermux/Models/vosk-model-small-es-0.42"
PORT = 8765

E = {"escuchando": False, "parcial": "", "finales": [], "n": 0}
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

    def do_GET(self):
        u = urlparse(self.path)
        if u.path == "/estado":
            d = int(parse_qs(u.query).get("desde", ["0"])[0])
            with lock:
                self._j({"escuchando": E["escuchando"], "parcial": E["parcial"],
                         "total": E["n"],
                         "finales": [f for f in E["finales"] if f["n"] > d]})
        else:
            self._j({"error": "no existe"}, 404)

    def do_POST(self):
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
print("Listo. Servidor en 127.0.0.1:%d (Ctrl+C para salir)" % PORT, flush=True)
ThreadingHTTPServer(("127.0.0.1", PORT), H).serve_forever()
