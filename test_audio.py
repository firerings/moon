"""Prueba la captura de audio del servidor con un parec falso (sin micrófono ni Termux):
micrófono mudo, /parar, recuperación de la bandera, Luna y apagado limpio.   python3 test_audio.py"""
import sys, os, types, threading, time, json, urllib.request, urllib.error, subprocess, shutil, tempfile, stat, glob
SRC = os.path.dirname(os.path.abspath(__file__))
TMP = tempfile.mkdtemp(prefix="moon_audio_")
D = os.path.join(TMP, "ProyectosTermux", "netguard")
shutil.copytree(SRC, D, ignore=shutil.ignore_patterns(".git", ".token", "config.sh", "logs", "apps_alias.json", "__pycache__", "app"))
os.environ["HOME"] = TMP; os.environ["MOON_LOGDIR"] = os.path.join(TMP, "MoonLogs")
os.makedirs(os.path.join(D, "logs"), exist_ok=True)
sys.path.insert(0, D); os.chdir(D)

# parec falso: modo "audio" (silencio continuo) o "mudo" (vivo pero sin entregar ni un byte)
BIN = os.path.join(TMP, "bin"); os.makedirs(BIN)
MODO = os.path.join(TMP, "modo")
def modo(m): open(MODO, "w").write(m)
modo("audio")
with open(os.path.join(BIN, "parec"), "w") as f:
    f.write("#!%s\nimport sys, time\nm = open(%r).read().strip()\nif m == 'mudo':\n    time.sleep(1000)\n"
            "o = sys.stdout.buffer\nwhile True:\n    o.write(bytes(3200)); o.flush(); time.sleep(0.05)\n" % (sys.executable, MODO))
os.chmod(os.path.join(BIN, "parec"), 0o755)
os.environ["PATH"] = BIN + os.pathsep + os.environ["PATH"]

class Rec:
    def __init__(self, *a, **k): pass
    def SetWords(self, x): pass
    def Reset(self): pass
    def AcceptWaveform(self, d): return False
    def PartialResult(self): return '{"partial": ""}'
    def Result(self): return '{"text": ""}'
    def FinalResult(self): return '{"text": ""}'
fake = types.ModuleType("vosk")
fake.Model = lambda p: object(); fake.KaldiRecognizer = Rec; fake.SetLogLevel = lambda x: None
sys.modules["vosk"] = fake
LLAMADAS = []
subprocess.run = lambda *a, **k: (LLAMADAS.append(a[0]) or types.SimpleNamespace(stdout="", returncode=1, stderr=""))

import importlib
threading.Thread(target=lambda: importlib.import_module("voz_servidor"), daemon=True).start()
for _ in range(100):
    try: urllib.request.urlopen("http://127.0.0.1:8765/", timeout=0.3)
    except urllib.error.HTTPError: break
    except Exception: time.sleep(0.1)
else: raise SystemExit("servidor no arranco")
time.sleep(0.3)
vs = sys.modules["voz_servidor"]
TOKEN = open(D + "/.token").read().strip()
vs.SIN_AUDIO = 1.0; vs.PAUSA_RESET = 0.05

def req(ruta, metodo="GET"):
    r = urllib.request.Request("http://127.0.0.1:8765" + ruta, method=metodo, data=(b"" if metodo == "POST" else None),
                               headers={"X-Moon-Token": TOKEN})
    return json.loads(urllib.request.urlopen(r, timeout=3).read())
def escuchando(): return req("/estado?desde=0")["escuchando"]
def espera(cond, tope=8.0):
    t = time.time()
    while time.time() - t < tope:
        if cond(): return True
        time.sleep(0.05)
    return False
def eventos(tipo):
    out = []
    for fn in glob.glob(os.path.join(TMP, "MoonLogs", "moon_*.jsonl")):
        out += [e for e in map(json.loads, open(fn, encoding="utf-8")) if e.get("tipo") == tipo]
    return out
fallos = 0
def ok(cond, msg):
    global fallos
    print("OK   " if cond else "FALLA", msg)
    if not cond: fallos += 1
def limpio(): return espera(lambda: not vs.HIJOS and not escuchando(), 6)

ok(["pkill", "parec"] in LLAMADAS, "al arrancar se cierran los parec huerfanos de un servidor anterior")

# 1. escucha normal y /parar
req("/escuchar", "POST"); espera(lambda: vs.AUDIO["p"] is not None)
p = vs.AUDIO["p"]
ok(escuchando() and p is not None and p.poll() is None, "con audio: escucha y su parec esta vivo")
req("/parar", "POST")
ok(espera(lambda: not escuchando(), 3), "/parar apaga la bandera")
ok(espera(lambda: p.poll() is not None, 3), "/parar cierra el parec")

# 2. microfono mudo: el vigilante reintenta, reinicia PulseAudio y se rinde limpiando la bandera
modo("mudo"); LLAMADAS.clear()
req("/escuchar", "POST")
ok(escuchando(), "microfono mudo: arranca como escuchando")
ok(espera(lambda: not escuchando(), 10), "microfono mudo: la bandera se limpia sola (antes quedaba colgada)")
ok(len(eventos("audio_sin_datos")) >= 3, "se registra audio_sin_datos en cada intento (%d)" % len(eventos("audio_sin_datos")))
ok(["pulseaudio", "-k"] in LLAMADAS, "en el ultimo intento se reinicia PulseAudio")
ok(limpio(), "no queda ningun parec vivo")

# 3. microfono mudo + /parar (el hilo estaba bloqueado esperando audio)
req("/escuchar", "POST"); time.sleep(0.3); p = vs.AUDIO["p"]
t0 = time.time(); req("/parar", "POST")
ok(espera(lambda: not escuchando(), 3) and time.time() - t0 < 2.5, "mudo + /parar: se libera rapido (%.1f s)" % (time.time() - t0))
ok(p is not None and espera(lambda: p.poll() is not None, 3), "mudo + /parar: el parec muere")

# 4. fallo antes del bucle: la bandera no se queda puesta
modo("audio")
orig = vs.KaldiRecognizer
def roto(*a, **k): raise RuntimeError("fallo al crear el reconocedor")
vs.KaldiRecognizer = roto
req("/escuchar", "POST")
ok(espera(lambda: not escuchando(), 3), "si falla al arrancar, la bandera se limpia")
ok(any("reconocedor" in e.get("detalle", "") for e in eventos("error")), "el fallo queda en el log")
vs.KaldiRecognizer = orig

# 5. bandera puesta sin hilo: /escuchar la recupera
vs.E["escuchando"] = True; vs.HILO["t"] = None
req("/escuchar", "POST")
ok(espera(lambda: vs.AUDIO["p"] is not None), "bandera huerfana: /escuchar arranca una escucha real")
req("/parar", "POST"); ok(limpio(), "y se puede parar")

# 6. Luna vigila y cede el microfono a la escucha normal
req("/luna?on=1")
ok(espera(lambda: len(vs.HIJOS) == 1), "Luna activa abre su parec")
req("/escuchar", "POST")
def solo_escucha():
    req("/luna?on=1")
    return escuchando() and len(vs.HIJOS) == 1 and vs.AUDIO["p"] in vs.HIJOS
ok(espera(solo_escucha, 4), "al escuchar, Luna suelta su parec y queda uno solo")
req("/parar", "POST"); req("/luna?on=0")
ok(limpio(), "al apagar Luna y parar no queda nada")

# 7. apagado limpio (SIGTERM): no deja parec huerfanos
req("/escuchar", "POST"); espera(lambda: vs.AUDIO["p"] is not None); p = vs.AUDIO["p"]
salidas = []; real = os._exit; os._exit = lambda c: salidas.append(c)
vs.apagar(15, None); os._exit = real
ok(salidas == [0] and espera(lambda: p.poll() is not None, 3), "apagar() cierra los parec y sale")

print("\nFALLOS:", fallos)
for q in list(vs.HIJOS): q.kill()
sys.exit(1 if fallos else 0)
