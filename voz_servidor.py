import json, subprocess, threading, os, re, secrets, time, queue, datetime, shutil
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
os.makedirs(os.path.join(DIR, "logs"), exist_ok=True)      # servidor.log y netguard.log siguen aqui (llevan token y numero)


def _dir_logs():
    """Historial y correcciones van a Download/MoonLogs (acciones.LOGDIR); si no se puede escribir, a logs/."""
    try:
        os.makedirs(acciones.LOGDIR, exist_ok=True)
        return acciones.LOGDIR
    except OSError:
        return os.path.join(DIR, "logs")


LOGS = _dir_logs()
ACT = os.path.join(LOGS, "actividad.jsonl")
FB = os.path.join(LOGS, "correcciones.jsonl")


def _migrar(nombre, destino):
    """Si el archivo esta en el sitio antiguo (logs/) y en el nuevo no existe, lo copia y deja el viejo como .migrado."""
    viejo = os.path.join(DIR, "logs", nombre)
    if os.path.abspath(viejo) == os.path.abspath(destino) or not os.path.exists(viejo) or os.path.exists(destino):
        return
    try:
        shutil.copyfile(viejo, destino)
        os.rename(viejo, viejo + ".migrado")
    except OSError:
        pass


_migrar("actividad.jsonl", ACT)
_migrar("correcciones.jsonl", FB)


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
    reg.setdefault("id", secrets.token_hex(4))
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


def id_de(it):
    """Id estable del registro; los que escribe un script bash no traen uno."""
    return it.get("id") or "t%d" % int(float(it.get("t", 0)) * 10)


def escribir_fb(reg):
    try:
        with open(FB, "a", encoding="utf-8") as f:
            f.write(json.dumps(reg, ensure_ascii=False) + "\n")
        return True
    except OSError:
        return False


def leer_correcciones(limite=800):
    """{id: ultimo registro de feedback}."""
    try:
        with open(FB, encoding="utf-8") as f:
            lineas = f.readlines()[-limite:]
    except OSError:
        return {}
    out = {}
    for ln in lineas:
        try:
            r = json.loads(ln)
            i = r["id"]
        except (ValueError, KeyError):
            continue
        out[i] = r if "fb" in r else {**out.get(i, {}), **r}   # un fb nuevo reemplaza; un alias se suma
    return out


def leer_actividad(limite=40):
    try:
        with open(ACT) as f:
            lineas = f.readlines()[-limite:]
    except OSError:
        return []
    fb = leer_correcciones()
    out = []
    for ln in lineas:
        try:
            it = json.loads(ln)
        except ValueError:
            continue
        it["id"] = id_de(it)
        r = fb.get(it["id"])
        if r:
            it["fb"] = r.get("fb")
            if r.get("dije"):
                it["dije"] = r["dije"]
            if r.get("alias"):
                it["alias"] = r["alias"]
                it["alias_nombre"] = r.get("alias_nombre", "")
        out.append(it)
    return out


def _epoch_evento(ev, dia):
    if "t" in ev:
        return float(ev["t"])
    try:
        h = datetime.datetime.strptime(ev.get("ts", ""), "%H:%M:%S").time()
        return datetime.datetime.combine(dia, h).timestamp()
    except ValueError:
        return 0.0


def buscar_evento(it):
    """Evento de la frase en el log diario (intencion, confianza, via, resultado)."""
    t = float(it.get("t", 0))
    dia = datetime.datetime.fromtimestamp(t).date()
    ruta = os.path.join(acciones.LOGDIR, "moon_%s.jsonl" % dia.strftime("%Y-%m-%d"))
    try:
        with open(ruta, encoding="utf-8") as f:
            lineas = f.readlines()[-600:]
    except OSError:
        return None
    fid, texto = it.get("frase_id"), it.get("texto", "")
    mejor = None
    for ln in lineas:
        try:
            ev = json.loads(ln)
        except ValueError:
            continue
        if ev.get("tipo") != "frase":
            continue
        if fid and ev.get("id") == fid:
            return ev
        if not fid and texto and ev.get("texto") == texto:
            d = abs(_epoch_evento(ev, dia) - t)
            if d <= 30 and (mejor is None or d < mejor[0]):
                mejor = (d, ev)
    return mejor[1] if mejor else None


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
    out = []
    for a in ACC["items"]:
        if not a["ack"] and ahora - a["t"] < 20:
            a["servida"] = True
            out.append({k: a[k] for k in ("id", "tipo", "pkg", "nombre", "texto") if k in a})
    return out


def accion_servida(i):
    with lock:
        return any(a["id"] == i and a.get("servida") for a in ACC["items"])


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


# ---------- palabra «Luna» ----------
# La app avisa cada segundo (GET /luna?on=1) mientras el interruptor esta encendido; sin avisos durante
# 6 s el vigilante se apaga solo. Usa un reconocedor aparte que solo entiende «luna» (barato) y se
# aparta mientras hay una escucha normal o Moon esta hablando.
LUNA = {"visto": 0.0, "n": 0, "t": 0.0, "hilo": False}
UMBRAL_LUNA = 0.6


def _es_luna(res):
    palabras = (res.get("text") or "").split()
    if "luna" not in palabras or len(palabras) > 2:
        return False
    return any(w.get("word") == "luna" and w.get("conf", 1.0) >= UMBRAL_LUNA for w in (res.get("result") or []))


def _sesion_luna(rec):
    p = subprocess.Popen(
        ["parec", "--device=OpenSL_ES_source", "--rate=16000", "--channels=1",
         "--format=s16le", "--latency-msec=100"], stdout=subprocess.PIPE)
    try:
        rec.Reset()
        while time.time() - LUNA["visto"] < 6 and not E["escuchando"] and not CEREBRO.callando():
            data = p.stdout.read(3200)
            if not data:
                break
            if rec.AcceptWaveform(data):
                res = json.loads(rec.Result())
                if _es_luna(res) and time.time() - LUNA["t"] > 3:
                    LUNA["t"] = time.time()
                    LUNA["n"] += 1
                    acciones.log_evento({"tipo": "luna_oida"})
                    return
    finally:
        p.terminate()


def vigilar_luna():
    try:
        try:
            rec = KaldiRecognizer(model, 16000, '["luna", "[unk]"]')
        except Exception:
            rec = KaldiRecognizer(model, 16000)
        rec.SetWords(True)
        while time.time() - LUNA["visto"] < 6:
            if E["escuchando"] or CEREBRO.callando():
                time.sleep(0.3)
                continue
            _sesion_luna(rec)
            time.sleep(0.2)
    except Exception as e:
        acciones.log_evento({"tipo": "error", "donde": "luna", "detalle": str(e)})
    finally:
        with lock:
            LUNA["hilo"] = False


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
        elif u.path == "/detalle":
            i = parse_qs(u.query).get("id", [""])[0]
            it = next((x for x in leer_actividad(200) if x["id"] == i), None)
            self._j({"evento": buscar_evento(it) if it else None})
        elif u.path == "/luna":
            if parse_qs(u.query).get("on", ["0"])[0] == "1":
                LUNA["visto"] = time.time()
                with lock:
                    nuevo = not LUNA["hilo"]
                    LUNA["hilo"] = True
                if nuevo:
                    threading.Thread(target=vigilar_luna, daemon=True).start()
            else:
                LUNA["visto"] = 0.0
            self._j({"n": LUNA["n"], "activo": LUNA["hilo"]})
        elif u.path == "/sistema":
            self._j({"shizuku": shizuku_activo(), "modelo": modelo_corto()})
        else:
            self._j({"error": "no existe"}, 404)

    def _cuerpo_json(self, maximo=4000):
        n = int(self.headers.get("Content-Length", 0) or 0)
        if n <= 0 or n > maximo:
            return None
        try:
            d = json.loads(self.rfile.read(n))
            return d if isinstance(d, dict) else None
        except ValueError:
            return None

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
        elif u.path == "/corregir":
            d = self._cuerpo_json()
            try:
                i, fb = str(d["id"])[:40], d["fb"]
                oido, dije = str(d.get("oido", ""))[:300], str(d.get("dije", "")).strip()[:300]
            except (TypeError, KeyError):
                return self._j({"error": "formato"}, 400)
            if fb not in ("ok", "corr") or not i or (fb == "corr" and not dije):
                return self._j({"error": "parametros"}, 400)
            reg = {"id": i, "fb": fb, "oido": oido, "dije": dije, "t": round(time.time(), 1)}
            if not escribir_fb(reg):
                return self._j({"error": "disco"}, 500)
            acciones.log_evento({"tipo": "correccion", "frase_id": i, "fb": fb, "oido": oido, "dije": dije})
            sug = CEREBRO.sugerir_alias(oido, dije) if fb == "corr" else None
            self._j({"ok": True, "sugerencia": sug})
        elif u.path == "/alias":
            d = self._cuerpo_json()
            try:
                alias, pkg = str(d["alias"]), str(d["pkg"])
            except (TypeError, KeyError):
                return self._j({"error": "formato"}, 400)
            fid, nombre = str(d.get("id", ""))[:40], str(d.get("nombre", ""))[:80]
            ok = CEREBRO.guardar_alias(alias, pkg)
            if ok:
                acciones.log_evento({"tipo": "alias_guardado", "alias": alias, "pkg": pkg, "frase_id": fid})
                if fid:
                    escribir_fb({"id": fid, "alias": alias, "alias_pkg": pkg, "alias_nombre": nombre,
                                 "t": round(time.time(), 1)})
            self._j({"ok": ok})
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
    acciones.log_evento({"tipo": "error", "donde": "nlu", "detalle": repr(e)})
    NLU_MODELO = None
CEREBRO = acciones.Cerebro(NLU_MODELO, {
    "ui": guardar, "reiniciar": reiniciar_conexion, "iniciar": iniciar_escucha,
    "enviar": enviar_accion, "app_activa": app_activa,
    "servida": accion_servida, "cancelar": marcar_ack,
    "parar": parar.set, "escuchando": esta_escuchando})
CEREBRO.iniciar()
threading.Thread(target=trabajador, daemon=True).start()
print("Token:", TOKEN, flush=True)
print("Listo. Servidor en 127.0.0.1:%d (Ctrl+C para salir)" % PORT, flush=True)
ThreadingHTTPServer(("127.0.0.1", PORT), H).serve_forever()
