import json, subprocess, threading, os, re, secrets, time, queue
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs
from vosk import Model, KaldiRecognizer, SetLogLevel
import acciones

SetLogLevel(-1)
MODEL = "/storage/emulated/0/Download/ProyectosTermux/Models/vosk-model-small-es-0.42"
PORT = 8765
DIR = os.path.expanduser("~/ProyectosTermux/netguard")
TF = os.path.join(DIR, ".token")
if not os.path.exists(TF):
    open(TF, "w").write(secrets.token_hex(8))
    os.chmod(TF, 0o600)
TOKEN = open(TF).read().strip()
ACT = os.path.join(DIR, "logs", "actividad.jsonl")
os.makedirs(os.path.dirname(ACT), exist_ok=True)


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


def guardar(reg):
    """Añade un registro al historial de actividad (una línea JSON)."""
    reg["t"] = round(time.time(), 1)
    try:
        if os.path.exists(ACT) and os.path.getsize(ACT) > 100000:
            with open(ACT) as f:
                cola = f.readlines()[-200:]
            with open(ACT, "w") as f:
                f.writelines(cola)
        with open(ACT, "a") as f:
            f.write(json.dumps(reg, ensure_ascii=False) + "\n")
    except OSError:
        pass


def leer_actividad(limite=40):
    try:
        with open(ACT) as f:
            lineas = f.readlines()[-limite:]
    except OSError:
        return []
    out = []
    for ln in lineas:
        try:
            out.append(json.loads(ln))
        except ValueError:
            pass
    return out


def marca_actividad():
    """Cambia cuando el historial cambia (también si lo escribe un script bash)."""
    try:
        return os.stat(ACT).st_mtime_ns
    except OSError:
        return 0


_shz = {"t": 0, "ok": False}


def shizuku_activo():
    if time.time() - _shz["t"] < 10:
        return _shz["ok"]
    try:
        r = subprocess.run(["./rish", "-c", "id"], cwd=os.path.expanduser("~"),
                           capture_output=True, text=True, timeout=5,
                           env={**os.environ, "RISH_APPLICATION_ID": "com.termux"})
        _shz["ok"] = r.returncode == 0 and "uid=" in r.stdout
    except Exception:
        _shz["ok"] = False
    _shz["t"] = time.time()
    return _shz["ok"]


def modelo_corto():
    m = re.search(r"-([a-z]+)-([\d.]+)$", os.path.basename(MODEL))
    return "%s %s" % (m.group(1), m.group(2)) if m else os.path.basename(MODEL)


E = {"escuchando": False, "parcial": "", "finales": [], "n": 0, "orden": None}
lock = threading.Lock()
parar = threading.Event()


def preparar_audio():
    subprocess.run(["pulseaudio", "--start", "--exit-idle-time=-1"])
    fuentes = subprocess.run(["pactl", "list", "short", "sources"],
                             capture_output=True, text=True).stdout
    if "OpenSL_ES_source" not in fuentes:
        subprocess.run(["pactl", "load-module", "module-sles-source"])


COLA = queue.Queue()
ACC = {"sid": secrets.token_hex(3), "n": 0, "items": []}   # acciones que la app Moon ejecuta
VISTO = {"t": 0.0}                                          # ultima vez que la app pregunto /estado


def enviar_accion(a):
    with lock:
        ACC["n"] += 1
        a = {**a, "id": ACC["n"], "t": time.time(), "ack": False}
        ACC["items"] = ACC["items"][-19:] + [a]
        return a["id"]


def app_activa():
    return time.time() - VISTO["t"] < 3


def marcar_ack(i):
    with lock:
        for a in ACC["items"]:
            if a["id"] == i:
                a["ack"] = True


def acciones_pendientes():
    ahora = time.time()
    return [{k: a[k] for k in ("id", "tipo", "pkg", "nombre") if k in a}
            for a in ACC["items"] if not a["ack"] and ahora - a["t"] < 20]


def agregar(texto):
    if texto:
        E["n"] += 1
        E["finales"].append({"n": E["n"], "t": texto})
        E["finales"] = E["finales"][-50:]
        guardar({"tipo": "texto", "texto": texto})
        COLA.put(texto)


def reiniciar_conexion():
    with lock:
        o = E["orden"]
        if o and time.time() - o["t0"] <= 60:
            return "Ya estoy reiniciando la conexión"
        E["orden"] = {"t0": time.time()}
    guardar({"tipo": "orden", "nombre": "Reiniciar conexión", "via": "voz"})
    subprocess.Popen([DIR + "/reiniciar.sh"])
    return "Reiniciando la conexión"


def trabajador():
    """Procesa las frases fuera del hilo de audio (hablar puede tardar segundos)."""
    while True:
        t = COLA.get()
        try:
            CEREBRO.procesar(t)
        except Exception as e:
            acciones.log_evento({"tipo": "error", "donde": "procesar", "texto": t, "detalle": str(e)})


def escuchar(auto=False):
    rec = KaldiRecognizer(model, 16000)
    p = subprocess.Popen(
        ["parec", "--device=OpenSL_ES_source", "--rate=16000", "--channels=1",
         "--format=s16le", "--latency-msec=100"], stdout=subprocess.PIPE)
    ult = time.time()
    mudo = False
    try:
        while not parar.is_set():
            data = p.stdout.read(3200)
            if not data:
                break
            if CEREBRO.callando():
                mudo = True
                ult = time.time()
                continue
            if mudo:
                rec.Reset()
                mudo = False
            lim = CEREBRO.limite_silencio(auto)
            if lim and time.time() - ult > lim:
                break
            if rec.AcceptWaveform(data):
                t = json.loads(rec.Result()).get("text", "")
                with lock:
                    E["parcial"] = ""
                    agregar(t)
                if t:
                    ult = time.time()
            else:
                par = json.loads(rec.PartialResult()).get("partial", "")
                with lock:
                    E["parcial"] = par
                if par:
                    ult = time.time()
    finally:
        p.terminate()
        with lock:
            agregar(json.loads(rec.FinalResult()).get("text", ""))
            E["escuchando"] = False
            E["parcial"] = ""


def iniciar_escucha(auto=False):
    with lock:
        ya = E["escuchando"]
        if not ya:
            E["escuchando"] = True
    if not ya:
        parar.clear()
        threading.Thread(target=escuchar, args=(auto,), daemon=True).start()


def esta_escuchando():
    return E["escuchando"]


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
            VISTO["t"] = time.time()
            with lock:
                self._j({"escuchando": E["escuchando"], "parcial": E["parcial"],
                         "total": E["n"], "act": marca_actividad(),
                         "finales": [f for f in E["finales"] if f["n"] > d],
                         "orden": orden_vista(), "ahorro": CEREBRO.ahorro,
                         "sid": ACC["sid"], "acciones": acciones_pendientes()})
        elif u.path == "/actividad":
            self._j({"items": leer_actividad()})
        elif u.path == "/sistema":
            self._j({"shizuku": shizuku_activo(), "modelo": modelo_corto()})
        else:
            self._j({"error": "no existe"}, 404)

    def do_POST(self):
        if not self._auth():
            return
        u = urlparse(self.path)
        if self.path == "/escuchar":
            iniciar_escucha()
            self._j({"ok": True})
        elif u.path == "/apps":
            n = int(self.headers.get("Content-Length", 0) or 0)
            if n <= 0 or n > 1000000:
                return self._j({"error": "tamano"}, 413)
            try:
                lista = [{"n": str(x["n"]), "p": str(x["p"])} for x in json.loads(self.rfile.read(n))]
                with open(os.path.join(DIR, "apps_app.json"), "w", encoding="utf-8") as f:
                    json.dump({"t": time.time(), "apps": lista}, f, ensure_ascii=False)
                acciones.log_evento({"tipo": "apps_sincronizadas", "n": len(lista)})
                self._j({"ok": True, "n": len(lista)})
            except (ValueError, KeyError, TypeError, OSError):
                self._j({"error": "formato"}, 400)
        elif u.path == "/ack":
            q = parse_qs(u.query)
            try:
                i, ok = int(q["id"][0]), q.get("ok", ["0"])[0] == "1"
            except (KeyError, ValueError):
                return self._j({"error": "parametros"}, 400)
            marcar_ack(i)
            CEREBRO.resultado_app(i, ok)
            self._j({"ok": True})
        elif self.path == "/parar":
            parar.set()
            self._j({"ok": True})
        elif self.path == "/limpiar":
            try:
                open(ACT, "w").close()
            except OSError:
                pass
            self._j({"ok": True})
        else:
            self._j({"error": "no existe"}, 404)

    def log_message(self, *a):
        pass


preparar_audio()
print("Cargando modelo...", flush=True)
model = Model(MODEL)
try:
    from nlu_np import NLU
    NLU_MODELO = NLU(os.path.join(DIR, "modelo_nlu.npz"))
except Exception as e:
    print("Sin NLU (solo comandos.json):", e, flush=True)
    NLU_MODELO = None
CEREBRO = acciones.Cerebro(NLU_MODELO, {
    "ui": guardar, "reiniciar": reiniciar_conexion, "iniciar": iniciar_escucha,
    "enviar": enviar_accion, "app_activa": app_activa,
    "parar": parar.set, "escuchando": esta_escuchando})
CEREBRO.iniciar()
threading.Thread(target=trabajador, daemon=True).start()
print("Token:", TOKEN, flush=True)
print("Listo. Servidor en 127.0.0.1:%d (Ctrl+C para salir)" % PORT, flush=True)
ThreadingHTTPServer(("127.0.0.1", PORT), H).serve_forever()
