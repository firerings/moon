"""Prueba los endpoints /detalle, /corregir y /alias sin Vosk ni Termux (copia el proyecto a una carpeta temporal)."""
import sys, os, types, threading, time, json, urllib.request, urllib.error, subprocess, shutil, tempfile
SRC = os.path.dirname(os.path.abspath(__file__))
TMP = tempfile.mkdtemp(prefix="moon_test_")
D = os.path.join(TMP, "ProyectosTermux", "netguard")
shutil.copytree(SRC, D, ignore=shutil.ignore_patterns(".git", ".token", "config.sh", "logs", "apps_alias.json", "__pycache__", "app"))
os.environ["HOME"] = TMP; os.environ["MOON_LOGDIR"] = os.path.join(TMP, "MoonLogs")
os.makedirs(os.path.join(D, "logs"), exist_ok=True)
open(os.path.join(D, "logs", "actividad.jsonl"), "w", encoding="utf-8").write(
    '{"t":1700000000.0,"tipo":"orden","nombre":"Reiniciar conexión","via":"SMS autorizado"}\n')
sys.path.insert(0, D); os.chdir(D)
fake = types.ModuleType("vosk")
fake.Model = lambda p: object(); fake.KaldiRecognizer = lambda *a, **k: object(); fake.SetLogLevel = lambda x: None
sys.modules["vosk"] = fake
subprocess.run = lambda *a, **k: types.SimpleNamespace(stdout="", returncode=1, stderr="")
import importlib
threading.Thread(target=lambda: importlib.import_module("voz_servidor"), daemon=True).start()
for _ in range(100):
    try:
        urllib.request.urlopen("http://127.0.0.1:8765/", timeout=0.3)
    except urllib.error.HTTPError: break
    except Exception: time.sleep(0.1)
else: raise SystemExit("servidor no arranco")
vs = sys.modules["voz_servidor"]; ac = sys.modules["acciones"]
TOKEN = open(D + "/.token").read().strip()

def req(path, metodo="GET", cuerpo=None, token=TOKEN):
    r = urllib.request.Request("http://127.0.0.1:8765" + path, method=metodo,
        data=(json.dumps(cuerpo).encode() if cuerpo is not None else (b"" if metodo == "POST" else None)),
        headers={"X-Moon-Token": token, "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(r, timeout=3) as x: return x.status, json.loads(x.read())
    except urllib.error.HTTPError as e: return e.code, json.loads(e.read() or b"{}")

fallos = 0
def chk(nombre, cond):
    global fallos
    print(("OK    " if cond else "FALLA ") + nombre); fallos += (not cond)

# datos de prueba: una transcripcion + su evento en el log diario
vs.guardar({"tipo": "texto", "texto": "abre de negra"})
ac.log_evento({"id": "f1", "tipo": "frase", "texto": "abre de negra", "via": "regla", "ok": False, "motivo": "app_no_encontrada", "ms": 12})
vs.guardar({"tipo": "orden", "nombre": "Decir la hora", "via": "voz", "texto": "ahora es", "frase_id": "f2", "dur_ms": 40})
ac.log_evento({"id": "f2", "tipo": "frase", "texto": "ahora es", "via": "nlu", "intent": "abrir_app", "confianza": 0.71})

s, j = req("/actividad"); items = j["items"]
chk("actividad: todos los items traen id", all(it.get("id") for it in items))
it_txt = next(i for i in items if i.get("texto") == "abre de negra" and i["tipo"] == "texto")
it_ord = next(i for i in items if i.get("nombre") == "Decir la hora")
it_sms = next((i for i in items if i.get("via") == "SMS autorizado"), None)
chk("migracion: el historial viejo de logs/ pasa a MoonLogs", it_sms is not None and os.path.exists(os.path.join(TMP, "MoonLogs", "actividad.jsonl")))
chk("migracion: el archivo viejo queda como .migrado", os.path.exists(os.path.join(D, "logs", "actividad.jsonl.migrado")) and not os.path.exists(os.path.join(D, "logs", "actividad.jsonl")))
chk("entrada sin id (script bash) recibe un id estable", bool(it_sms and it_sms["id"].startswith("t")))
chk("actividad: la orden lleva dur_ms y texto", it_ord.get("dur_ms") == 40 and it_ord.get("texto") == "ahora es")

s, j = req("/detalle?id=" + it_txt["id"])
chk("detalle por texto+hora encuentra el evento", (j["evento"] or {}).get("via") == "regla" and j["evento"]["motivo"] == "app_no_encontrada")
s, j = req("/detalle?id=" + it_ord["id"])
chk("detalle por frase_id encuentra el evento del NLU", (j["evento"] or {}).get("intent") == "abrir_app")
s, j = req("/detalle?id=nope"); chk("detalle de id desconocido -> evento null", s == 200 and j["evento"] is None)

s, j = req("/corregir", "POST", {"id": it_txt["id"], "fb": "corr", "oido": "abre de negra", "dije": "abre telegram"})
chk("corregir devuelve alias sugerido para Telegram", s == 200 and (j.get("sugerencia") or {}).get("pkg") == "org.telegram.messenger")
sug = j["sugerencia"]; print("      sugerencia:", sug)
s, j = req("/actividad"); it = next(i for i in j["items"] if i["id"] == it_txt["id"])
chk("actividad refleja la correccion", it.get("fb") == "corr" and it.get("dije") == "abre telegram")

before = ac.Cerebro.resolver_app(vs.CEREBRO, "abre negra".split(" ", 1)[1], vs.CEREBRO.apps_instaladas())
s, j = req("/alias", "POST", {"id": it_txt["id"], "alias": sug["alias"], "pkg": sug["pkg"], "nombre": sug["nombre"]})
chk("guardar alias", s == 200 and j["ok"] is True)
r = vs.CEREBRO.resolver_app(sug["alias"], vs.CEREBRO.apps_instaladas())
chk("tras guardar, el alias resuelve a Telegram al instante", r and r["pkg"] == "org.telegram.messenger" and r["score"] == 1.0)
chk("apps_alias.json es JSON valido", isinstance(json.load(open(D + "/apps_alias.json")), dict))

s, j = req("/actividad"); it = next(i for i in j["items"] if i["id"] == it_txt["id"])
chk("actividad muestra el alias guardado junto a la correccion", it.get("alias") == sug["alias"] and it.get("alias_nombre") == "Telegram" and it.get("dije") == "abre telegram")
s, j = req("/alias", "POST", {"alias": "loquesea", "pkg": "com.no.existe"}); chk("alias con paquete inexistente se rechaza", j["ok"] is False)
s, j = req("/corregir", "POST", {"id": it_ord["id"], "fb": "ok"}); chk("marcar correcto (ok) sin sugerencia", s == 200 and j["sugerencia"] is None)
s, j = req("/corregir", "POST", {"id": "x", "fb": "corr", "oido": "a", "dije": ""}); chk("correccion vacia -> 400", s == 400)
s, j = req("/corregir", "POST", {"fb": "ok"}); chk("sin id -> 400", s == 400)
s, j = req("/corregir", "POST", None); chk("sin cuerpo -> 400", s == 400)
s, j = req("/actividad", token="malo"); chk("sin token valido -> 401", s == 401)
# ya lo entiende: no propone alias repetido
s, j = req("/corregir", "POST", {"id": it_txt["id"], "fb": "corr", "oido": "abre negra", "dije": "abre telegram"})
chk("si ya lo entendia, no repite la sugerencia", s == 200 and j["sugerencia"] is None)
s, j = req("/actividad"); it = next(i for i in j["items"] if i["id"] == it_txt["id"])
chk("una correccion nueva borra el alias de la anterior", it.get("dije") == "abre telegram" and "alias" not in it)
chk("actividad y correcciones estan en MoonLogs", all(os.path.exists(os.path.join(TMP, "MoonLogs", n)) for n in ("actividad.jsonl", "correcciones.jsonl")))
chk("ya no se crean en la carpeta del proyecto", not os.path.exists(os.path.join(D, "logs", "correcciones.jsonl")))
lin = [json.loads(l) for l in open(os.path.join(TMP, "MoonLogs", os.listdir(os.path.join(TMP, "MoonLogs"))[0]))]
chk("el log diario guarda correcciones y alias", any(e["tipo"] == "correccion" for e in lin) and any(e["tipo"] == "alias_guardado" for e in lin))
print("\nFALLOS:", fallos)
shutil.rmtree(TMP, ignore_errors=True)
os._exit(1 if fallos else 0)
