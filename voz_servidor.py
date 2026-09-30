import json, subprocess, threading, os, re, secrets, time, queue, datetime, shutil, signal, select, atexit
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
    subprocess.run(["pkill", "parec"])      # restos de un servidor anterior que murió sin cerrar su captura
    subprocess.run(["pulseaudio", "--start", "--exit-idle-time=-1"])
    fuentes = subprocess.run(["pactl", "list", "short", "sources"],
                             capture_output=True, text=True).stdout
    if "OpenSL_ES_source" not in fuentes:
        subprocess.run(["pactl", "load-module", "module-sles-source"])


# ---------- captura de audio (parec) ----------
# Un parec que se cuelga no entrega bytes pero sigue vivo: por eso se lee con select y tiempo maximo,
# y todo parec que se lanza se cierra esperando a que suelte el microfono.
PAREC = ["parec", "--device=OpenSL_ES_source", "--rate=16000", "--channels=1",
         "--format=s16le", "--latency-msec=100"]
SIN_AUDIO = 4.0          # s sin un solo byte del microfono antes de dar la captura por colgada
REINTENTOS_AUDIO = 2     # veces que se relanza la captura antes de rendirse
PAUSA_RESET = 1.0        # s de espera tras reiniciar PulseAudio
AUDIO = {"p": None}      # parec de la escucha normal (lo cierra /parar)
HILO = {"t": None}       # hilo de la escucha normal
HIJOS = set()            # todos los parec vivos, para cerrarlos al apagar


def lanzar_parec():
    p = subprocess.Popen(PAREC, stdout=subprocess.PIPE)
    HIJOS.add(p)
    return p


def cerrar_parec(p):
    """Termina parec y espera a que suelte el microfono (terminate solo no espera)."""
    if p is None:
        return
    try:
        p.terminate()
        try:
            p.wait(timeout=2)
        except subprocess.TimeoutExpired:
            p.kill()
            p.wait(timeout=2)
    except Exception:
        pass
    HIJOS.discard(p)
    try:
        p.stdout.close()
    except Exception:
        pass


def leer_audio(p, tope):
    """Hasta 3200 bytes de parec. None si en `tope` s no llego nada; b"" si parec se cerro."""
    r, _, _ = select.select([p.stdout], [], [], tope)
    if not r:
        return None
    return os.read(p.stdout.fileno(), 3200)


def reiniciar_audio():
    """Ultimo recurso con el microfono colgado: reinicia PulseAudio y su fuente OpenSL."""
    subprocess.run(["pulseaudio", "-k"])
    time.sleep(PAUSA_RESET)
    preparar_audio()


def parar_escucha():
    """Para la escucha normal y cierra su parec, para no depender de que el hilo vuelva de read()."""
    parar.set()
    p = AUDIO["p"]
    if p is not None:
        try:
            p.terminate()
        except Exception:
            pass


def apagar(signum=None, frame=None):
    for p in list(HIJOS):
        try:
            p.terminate()
        except Exception:
            pass
    os._exit(0)


atexit.register(lambda: [p.terminate() for p in list(HIJOS)])
try:
    signal.signal(signal.SIGTERM, apagar)
except ValueError:
    pass                 # no estamos en el hilo principal (tests)


COLA = queue.Queue()
DIAG = {"arranque": time.time(), "audio_fallos": 0, "audio_ultimo_fallo": 0.0, "audio_ultimo_ok": 0.0}   # para /diagnostico
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
            out.append({k: a[k] for k in ("id", "tipo", "pkg", "nombre", "texto", "modo", "cid") if k in a})
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
    rec = None
    p = None
    try:
        rec = KaldiRecognizer(model, 16000)
        ult = time.time()
        mudo = False
        fallos = 0
        sin = time.time()
        while not parar.is_set():
            if p is None:
                p = lanzar_parec()
                AUDIO["p"] = p
                sin = time.time()
            data = leer_audio(p, 0.5)
            if data is None:                       # medio segundo sin audio
                if time.time() - sin > SIN_AUDIO:
                    fallos += 1
                    DIAG["audio_fallos"] += 1
                    DIAG["audio_ultimo_fallo"] = time.time()
                    acciones.log_evento({"tipo": "audio_sin_datos", "donde": "escucha", "intento": fallos})
                    cerrar_parec(p)
                    p = None
                    AUDIO["p"] = None
                    if fallos > REINTENTOS_AUDIO:
                        break
                    if fallos == REINTENTOS_AUDIO:
                        reiniciar_audio()
                continue
            if not data:                           # parec se cerro
                break
            sin = time.time()
            DIAG["audio_ultimo_ok"] = sin
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
    except Exception as e:
        acciones.log_evento({"tipo": "error", "donde": "escuchar", "detalle": repr(e)})
    finally:
        AUDIO["p"] = None
        cerrar_parec(p)
        with lock:
            if rec is not None:
                try:
                    agregar(json.loads(rec.FinalResult()).get("text", ""))
                except Exception:
                    pass
            E["escuchando"] = False
            E["parcial"] = ""


def _hilo_vivo():
    t = HILO["t"]
    return t is not None and (t.ident is None or t.is_alive())


def iniciar_escucha(auto=False):
    with lock:
        ya = E["escuchando"] and _hilo_vivo()      # si la bandera quedo puesta sin hilo, se recupera sola
        if not ya:
            E["escuchando"] = True
            parar.clear()
            t = threading.Thread(target=escuchar, args=(auto,), daemon=True)
            HILO["t"] = t
    if not ya:
        t.start()


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
    p = lanzar_parec()
    try:
        rec.Reset()
        while time.time() - LUNA["visto"] < 6 and not E["escuchando"] and not CEREBRO.callando():
            data = leer_audio(p, 0.5)
            if data is None:
                continue
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
        cerrar_parec(p)


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


def _resumen_archivo(nombre, clave):
    """(cantidad, segundos desde que se sincronizó) de apps_app.json / contactos.json; (0, None) si no existe."""
    try:
        with open(os.path.join(DIR, nombre), encoding="utf-8") as f:
            d = json.load(f)
        return len(d[clave]), round(time.time() - float(d.get("t", 0)))
    except (OSError, ValueError, KeyError, TypeError):
        return 0, None


def diagnostico():
    ahora = time.time()
    na, ta = _resumen_archivo("apps_app.json", "apps")
    nc, tc = _resumen_archivo("contactos.json", "contactos")

    def hace(t):
        return round(ahora - t) if t else None
    return {"shizuku": shizuku_activo(), "modelo": modelo_corto(), "nlu": NLU_MODELO is not None,
            "comandos": len(CEREBRO.comandos), "escuchando": E["escuchando"], "luna": LUNA["hilo"],
            "ahorro": CEREBRO.ahorro, "apps": na, "apps_hace": ta, "contactos": nc, "contactos_hace": tc,
            "audio_fallos": DIAG["audio_fallos"], "audio_fallo_hace": hace(DIAG["audio_ultimo_fallo"]),
            "audio_ok_hace": hace(DIAG["audio_ultimo_ok"]), "activo_hace": round(ahora - DIAG["arranque"]),
            "ajustes": acciones.leer_ajustes()}


class H(BaseHTTPRequestHandler):
    def _j(self, obj, code=200):
        b = json.dumps(obj, ensure_ascii=False, default=str).encode()
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
        elif u.path == "/diagnostico":
            self._j(diagnostico())
        elif u.path == "/ajustes":
            self._j(acciones.leer_ajustes())
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
        elif u.path == "/contactos":
            n = int(self.headers.get("Content-Length", 0) or 0)
            if n <= 0 or n > 2000000:
                return self._j({"error": "tamano"}, 413)
            try:
                lista = [{"i": str(x["i"]), "n": str(x["n"])[:80]} for x in json.loads(self.rfile.read(n))]
                ruta = os.path.join(DIR, "contactos.json")
                with open(ruta + ".tmp", "w", encoding="utf-8") as f:
                    json.dump({"t": time.time(), "contactos": lista}, f, ensure_ascii=False)
                os.replace(ruta + ".tmp", ruta)
                acciones.log_evento({"tipo": "contactos_sincronizados", "n": len(lista)})
                self._j({"ok": True, "n": len(lista)})
            except (ValueError, KeyError, TypeError, OSError):
                self._j({"error": "formato"}, 400)
        elif u.path == "/probar":
            d = self._cuerpo_json(2000)
            texto = str((d or {}).get("texto", "")).strip()[:300]
            if not texto:
                return self._j({"error": "formato"}, 400)
            try:
                self._j({"ok": True, **CEREBRO.probar(texto)})
            except Exception as e:
                self._j({"ok": False, "error": repr(e)}, 500)
        elif u.path == "/ajustes":
            d = self._cuerpo_json(500) or {}
            self._j({"ok": acciones.guardar_ajuste(str(d.get("clave", "")), d.get("valor"))})
        elif u.path == "/ack":
            q = parse_qs(u.query)
            try:
                i, ok = int(q["id"][0]), q.get("ok", ["0"])[0] == "1"
            except (KeyError, ValueError):
                return self._j({"error": "parametros"}, 400)
            datos = None
            if "pct" in q:                                   # la bateria que lee la app
                try:
                    datos = {"pct": int(q["pct"][0]), "carg": q.get("carg", ["0"])[0] == "1"}
                except ValueError:
                    datos = None
            marcar_ack(i)
            CEREBRO.resultado_app(i, ok, datos)
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
            parar_escucha()
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
    "parar": parar_escucha, "escuchando": esta_escuchando})
CEREBRO.iniciar()
threading.Thread(target=trabajador, daemon=True).start()
print("Token:", TOKEN, flush=True)
print("Listo. Servidor en 127.0.0.1:%d (Ctrl+C para salir)" % PORT, flush=True)
ThreadingHTTPServer(("127.0.0.1", PORT), H).serve_forever()
