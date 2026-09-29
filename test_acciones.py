# -*- coding: utf-8 -*-
"""Prueba del cerebro de Moon con Shizuku, TTS y microfono simulados.
Corre en Termux o en un PC:  python3 test_acciones.py   (no toca tu movil ni tus logs)"""
import json, os, shutil, sys, tempfile, time

tmp = tempfile.mkdtemp()
os.environ["MOON_LOGDIR"] = os.path.join(tmp, "MoonLogs")
AQUI = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, AQUI)
import acciones
acciones.DIR = tmp
shutil.copy(os.path.join(AQUI, "comandos.json"), tmp)
acciones.ESPERA_RESPUESTA = 0.4
acciones.ESPERA_ACK = 0.4

nlu = None
try:
    from nlu_np import NLU
    nlu = NLU(os.path.join(AQUI, "modelo_nlu.npz"))
except Exception as e:
    print("(sin NLU:", e, ")")

PKGS = ["com.whatsapp", "com.android.chrome", "com.miui.gallery", "com.android.camera",
        "com.google.android.youtube", "org.telegram.messenger", "com.termux", "com.example.videoplayer"]
S = {"dicho": [], "ui": [], "rish": [], "esc": False, "reinicios": 0, "bat": (50, False)}


def rish(cmd, timeout=10):
    S["rish"].append(cmd)
    if cmd.startswith("pm list"):
        return True, "\n".join("package:" + p for p in PKGS) + "\n"
    if cmd.startswith("monkey"):
        return True, "Events injected: 1"
    return True, ""


def reiniciar():
    S["reinicios"] += 1
    return "Reiniciando la conexión"


def iniciar(auto=False): S["esc"] = True
def parar(): S["esc"] = False


ACC = {"n": 0, "enviadas": [], "activa": False}


def enviar(a):
    ACC["n"] += 1
    ACC["enviadas"].append(a)
    return ACC["n"]


env = {"enviar": enviar, "app_activa": lambda: ACC["activa"], "rish": rish, "tts": lambda t: S["dicho"].append(t), "ui": lambda d: S["ui"].append(d),
       "reiniciar": reiniciar, "iniciar": iniciar, "parar": parar,
       "escuchando": lambda: S["esc"], "bateria": lambda: S["bat"]}
c = acciones.Cerebro(nlu, env)
acciones.COLA_TTS = 0
fallos = 0


def chequear(nombre, cond):
    global fallos
    print(("OK    " if cond else "FALLA ") + nombre)
    fallos += 0 if cond else 1


def dice(t):
    S["dicho"].clear(); S["rish"][:] = [x for x in S["rish"] if x.startswith("pm list")]
    c.procesar(t)
    return S["dicho"][-1] if S["dicho"] else None


# --- comandos.json ---
chequear("reiniciar (frase con 'reinici*')", dice("por favor reinicia la conexion") == "Reiniciando la conexión" and S["reinicios"] == 1)
chequear("hora", (dice("que hora es") or "").startswith(("Es la", "Son las")))
chequear("batería", dice("cuanta bateria queda") == "La batería está al 50 por ciento")
chequear("apagar linterna gana a 'linterna'", c._buscar_comando("apaga la linterna")["accion"] == "linterna_off")
chequear("desactivar ahorro gana a 'modo ahorro'", c._buscar_comando("desactiva el modo ahorro")["accion"] == "ahorro_off")
chequear("ahorro on/off", dice("activa el modo ahorro") == "Modo ahorro activado" and c.ahorro and
         dice("quita el modo ahorro") == "Modo ahorro desactivado" and not c.ahorro)
chequear("palabra suelta 'hora' en charla NO dispara", c._buscar_comando("a esa hora nos vemos") is None)

# --- abrir / cerrar apps ---
if nlu:
    chequear("abre whatsapp", dice("abre whatsapp") == "Abriendo whatsapp" and
             any("monkey -p com.whatsapp" in x for x in S["rish"]))
    chequear("abre chrome", dice("abre chrome") == "Abriendo chrome")
    chequear("cerrar app usa force-stop", dice("cierra whatsapp") == "Cerré whatsapp" and
             any("force-stop com.whatsapp" in x for x in S["rish"]))
    chequear("charla no responde", dice("hola como estas") is None)
    chequear("llamar aún no implementado", dice("llama a mi mama") == "Todavía no sé hacer eso")
r = c.resolver_app("la cámara", PKGS)
chequear("alias: 'la cámara' -> com.android.camera", r and r["pkg"] == "com.android.camera")
r = c.resolver_app("galeria", PKGS)
chequear("alias con varios candidatos: galería -> com.miui.gallery", r and r["pkg"] == "com.miui.gallery")
r = c.resolver_app("telegram", PKGS)
chequear("alias: telegram", r and r["pkg"] == "org.telegram.messenger")
r = c.resolver_app("video player", PKGS)
chequear("nombre derivado del paquete (fuzzy)", r is None or r["pkg"] == "com.example.videoplayer")
chequear("app inexistente", c.resolver_app("photoshop", PKGS) is None)
chequear("app no instalada avisa", (lambda: (c.procesar("abre netflix"), S["dicho"][-1])[1])().startswith("No encontré") if nlu else True)

# --- abrir apps con la app Moon (sin Shizuku) ---
CAT = [("WhatsApp", "com.whatsapp"), ("Cámara", "com.android.camera"), ("Galería", "com.miui.gallery"),
       ("Chrome", "com.android.chrome"), ("Ajustes", "com.android.settings"),
       ("Google Play Store", "com.android.vending"), ("Calculadora", "com.miui.calculator")]
json.dump({"apps": [{"n": n, "p": p} for n, p in CAT]}, open(os.path.join(tmp, "apps_app.json"), "w", encoding="utf-8"))
chequear("catálogo de la app: etiqueta exacta 'cámara'", c.resolver_app("la cámara", c.apps_instaladas())["pkg"] == "com.android.camera")
chequear("catálogo: 'play store' -> Google Play Store", c.resolver_app("play store", c.apps_instaladas())["pkg"] == "com.android.vending")
if nlu:
    ACC["activa"] = True
    d = dice("abre whatsapp")
    chequear("app conectada: manda la orden a la app y NO usa Shizuku",
             d == "Abriendo WhatsApp" and ACC["enviadas"][-1] == {"tipo": "abrir", "pkg": "com.whatsapp", "nombre": "WhatsApp"}
             and not any(x.startswith("monkey") for x in S["rish"]))
    c.resultado_app(ACC["n"], True)
    time.sleep(0.6)
    chequear("con confirmación de la app no hay respaldo", not any(x.startswith("monkey") for x in S["rish"]))
    dice("abre chrome"); c.resultado_app(ACC["n"], False)
    chequear("la app dice que no pudo -> respaldo con Shizuku", any("monkey -p com.android.chrome" in x for x in S["rish"]))
    dice("abre ajustes"); time.sleep(0.8)
    chequear("la app no responde -> respaldo con Shizuku", any("monkey -p com.android.settings" in x for x in S["rish"]))
    ACC["activa"] = False
    dice("abre calculadora")
    chequear("app Moon no conectada -> Shizuku directo", any("monkey -p com.miui.calculator" in x for x in S["rish"]))
os.remove(os.path.join(tmp, "apps_app.json"))

# --- si / no ---
S["dicho"].clear(); S["bat"] = (15, False); c.revisar_bateria(S["bat"])
chequear("batería baja: pregunta y abre el micrófono", "modo ahorro" in S["dicho"][-1] and S["esc"] and c.pendiente)
c.procesar("sí, claro")
chequear("respuesta 'sí' activa ahorro y cierra el micrófono", c.ahorro and not S["esc"] and not c.pendiente and S["dicho"][-1] == "Modo ahorro activado")
c.ahorro = False; c.bateria_preguntada = False; c.revisar_bateria((15, False))
c.procesar("negativo")
chequear("respuesta 'negativo' no activa nada", not c.ahorro and S["dicho"][-1] == "Está bien, sigo normal")
c.revisar_bateria((14, False))
chequear("no vuelve a preguntar en la misma descarga", not c.pendiente)
c.revisar_bateria((50, False)); c.revisar_bateria((15, False))
c.procesar("mmm quizas"); c.procesar("bueno")
chequear("respuesta ininteligible dos veces -> cancela", not c.pendiente and S["dicho"][-1] == "No te entendí, lo dejo así")
c.bateria_preguntada = False; c.revisar_bateria((15, False)); time.sleep(0.8)
chequear("sin respuesta -> expira y cierra micrófono", not c.pendiente and not S["esc"] and S["dicho"][-1].startswith("No te escuché"))
c.revisar_bateria((15, True))
chequear("cargando -> no pregunta", not c.pendiente)

# --- ahorro y silencio ---
c.ahorro = False
chequear("límite de silencio: normal sin límite, ahorro 20 s", c.limite_silencio() is None and (setattr(c, "ahorro", True) or c.limite_silencio() == 20))

# --- logs ---
dia = os.listdir(os.environ["MOON_LOGDIR"])
lineas = [json.loads(l) for l in open(os.path.join(os.environ["MOON_LOGDIR"], dia[0]), encoding="utf-8")]
chequear("log por día: un archivo moon_AAAA-MM-DD.jsonl", len(dia) == 1 and dia[0].startswith("moon_") and dia[0].endswith(".jsonl"))
chequear("log guarda texto crudo, intención, confianza y resultado",
         any(l.get("via") == "nlu" and "confianza" in l and "ms" in l for l in lineas) if nlu else True)
print("\n%d líneas de log" % len(lineas), "| FALLOS:", fallos)
shutil.rmtree(tmp, ignore_errors=True)
sys.exit(1 if fallos else 0)
