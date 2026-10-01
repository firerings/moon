# -*- coding: utf-8 -*-
"""Prueba del cerebro de Moon con Shizuku, TTS y microfono simulados.
Corre en Termux o en un PC:  python3 test_acciones.py   (no toca tu movil ni tus logs)"""
import json, os, shutil, sys, tempfile, threading, time

tmp = tempfile.mkdtemp()
os.environ["MOON_LOGDIR"] = os.path.join(tmp, "MoonLogs")
AQUI = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, AQUI)
import acciones
from cerebro import config as cfg      # las constantes viven en cerebro.config
cfg.DIR = tmp
shutil.copy(os.path.join(AQUI, "comandos.json"), tmp)
cfg.ESPERA_RESPUESTA = 0.4
cfg.ESPERA_ACK = 0.4
cfg.DECIR_LLAMANDO = True      # las pruebas viejas comprueban la frase; abajo hay una con el valor por defecto (False)

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


ACC = {"n": 0, "enviadas": [], "activa": False, "ult_abrir": 0, "modo_voz": "ok", "entregadas": set(),
       "pedidas": [], "resp": {}}      # pedidas: linterna/bateria/llamar; resp: tipo -> (ok, datos) que contesta la app simulada


def enviar(a):
    """Simula la app Moon: recoge lo que se le manda y confirma."""
    ACC["n"] += 1
    i = ACC["n"]
    if a["tipo"] == "decir":
        if ACC["modo_voz"] in ("ok", "falla"):
            ACC["entregadas"].add(i)
            if ACC["modo_voz"] == "ok":
                S["dicho"].append(a["texto"]); S["nativo"] += 1
            threading.Timer(ACC.get("demora", 0.02), lambda: c.resultado_app(i, ACC["modo_voz"] == "ok")).start()
        # modo "no_recoge": la app no la recibe nunca
    elif a["tipo"] in ("linterna", "bateria", "llamar"):
        ACC["pedidas"].append(a)
        ACC["entregadas"].add(i)
        ok, datos = ACC["resp"].get(a["tipo"], (True, None))
        threading.Timer(0.02, lambda: c.resultado_app(i, ok, datos)).start()
    else:
        ACC["enviadas"].append(a)
        ACC["ult_abrir"] = i
    return i


def tts(t):
    S["dicho"].append(t); S["tts"] += 1


S.update(nativo=0, tts=0)
PAUSAS = []
env = {"pausar_audio": PAUSAS.append, "enviar": enviar, "app_activa": lambda: ACC["activa"], "servida": lambda i: i in ACC["entregadas"],
       "cancelar": lambda i: None, "rish": rish, "tts": tts,
       "ui": lambda d: S["ui"].append(d),
       "reiniciar": reiniciar, "iniciar": iniciar, "parar": parar,
       "escuchando": lambda: S["esc"], "bateria": lambda: S["bat"]}
c = acciones.Cerebro(nlu, env)
cfg.COLA_TTS = 0
fallos = 0


def chequear(nombre, cond):
    global fallos
    print(("OK    " if cond else "FALLA ") + nombre)
    fallos += 0 if cond else 1


def asentar(cer=None, t=2.0):
    """La pregunta Sí/No se dice en otro hilo (para no tener el candado tomado): espera a que termine de
    decirse y quede armada, como lo vería quien habla."""
    cer = cer or c
    fin = time.time() + t
    while time.time() < fin:
        p = cer.pendiente
        if p is None or "t_arma" in p:
            return
        time.sleep(0.01)


def dice(t):
    S["dicho"].clear(); S["rish"][:] = [x for x in S["rish"] if x.startswith("pm list")]
    c.procesar(t)
    asentar()
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
    chequear("llamar sin contactos sincronizados avisa", (dice("llama a mi mama") or "").startswith("Todavía no tengo tus contactos"))
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
    c.resultado_app(ACC["ult_abrir"], True)
    time.sleep(0.6)
    chequear("con confirmación de la app no hay respaldo", not any(x.startswith("monkey") for x in S["rish"]))
    dice("abre chrome"); c.resultado_app(ACC["ult_abrir"], False)
    chequear("la app dice que no pudo -> respaldo con Shizuku", any("monkey -p com.android.chrome" in x for x in S["rish"]))
    dice("abre ajustes"); time.sleep(0.8)
    chequear("la app no responde -> respaldo con Shizuku", any("monkey -p com.android.settings" in x for x in S["rish"]))
    ACC["activa"] = False
    dice("abre calculadora")
    chequear("app Moon no conectada -> Shizuku directo", any("monkey -p com.miui.calculator" in x for x in S["rish"]))
os.remove(os.path.join(tmp, "apps_app.json"))

# --- reglas 'abre X' sin modelo (NLU apagado) ---
c2 = acciones.Cerebro(None, env)
ACC["activa"] = True
json.dump({"apps": [{"n": n, "p": p} for n, p in CAT]}, open(os.path.join(tmp, "apps_app.json"), "w", encoding="utf-8"))
def dice2(t):
    S["dicho"].clear(); c2.procesar(t); return S["dicho"][-1] if S["dicho"] else None
chequear("sin modelo: 'abre whatsapp' funciona", dice2("abre whatsapp") == "Abriendo WhatsApp")
chequear("sin modelo: 'abrir whatsapp'", dice2("abrir whatsapp") == "Abriendo WhatsApp")
chequear("sin modelo: 'por favor abre chrome'", dice2("por favor abre chrome") == "Abriendo Chrome")
chequear("Vosk oye 'ahora whatsapp' -> abre", dice2("ahora whatsapp") == "Abriendo WhatsApp")
chequear("'ahora hablamos' no hace nada", dice2("ahora hablamos") is None)
chequear("app inexistente avisa", dice2("abre photoshop").startswith("No encontré"))
chequear("sin modelo: cierra usa Shizuku", dice2("cierra chrome") == "Cerré Chrome")
os.remove(os.path.join(tmp, "apps_app.json"))
ACC["activa"] = False

# --- voz: la app Moon (rápida) o Termux (lenta) ---
ACC["activa"] = True; ACC["modo_voz"] = "ok"; S["nativo"] = S["tts"] = 0
c.hablar("Prueba")
chequear("voz: app conectada -> habla la app, no Termux", S["nativo"] == 1 and S["tts"] == 0)
ACC["modo_voz"] = "falla"; S["nativo"] = S["tts"] = 0
c.hablar("Prueba dos")
chequear("voz: la app no tiene voz -> respaldo Termux", S["tts"] == 1)
ACC["modo_voz"] = "no_recoge"; cfg.ESPERA_ENTREGA = 0.3; S["tts"] = 0
t0 = time.time(); c.hablar("Prueba tres")
chequear("voz: la app no recoge la frase -> Termux en menos de 1 s", S["tts"] == 1 and time.time() - t0 < 1)
ACC["activa"] = False; ACC["modo_voz"] = "ok"; S["nativo"] = S["tts"] = 0
c.hablar("Prueba cuatro")
chequear("voz: app desconectada -> Termux directo", S["tts"] == 1 and S["nativo"] == 0)

# --- si / no ---
S["dicho"].clear(); S["bat"] = (15, False); c.revisar_bateria(S["bat"]); asentar()
chequear("batería baja: pregunta y abre el micrófono", "modo ahorro" in S["dicho"][-1] and S["esc"] and c.pendiente)
c.procesar("sí, claro")
chequear("respuesta 'sí' activa ahorro y cierra el micrófono", c.ahorro and not S["esc"] and not c.pendiente and S["dicho"][-1] == "Modo ahorro activado")
c.ahorro = False; c.bateria_preguntada = False; c.revisar_bateria((15, False)); asentar()
c.procesar("negativo")
chequear("respuesta 'negativo' no activa nada", not c.ahorro and S["dicho"][-1] == "Está bien, sigo normal")
c.revisar_bateria((14, False)); asentar()
chequear("no vuelve a preguntar en la misma descarga", not c.pendiente)
c.revisar_bateria((50, False)); c.revisar_bateria((15, False)); asentar()
c.procesar("mmm quizas"); c.procesar("bueno")
chequear("respuesta ininteligible dos veces -> cancela", not c.pendiente and S["dicho"][-1] == "No te entendí, lo dejo así")
c.bateria_preguntada = False; c.revisar_bateria((15, False)); time.sleep(0.8); asentar()
chequear("sin respuesta -> expira y cierra micrófono", not c.pendiente and not S["esc"] and S["dicho"][-1].startswith("No te escuché"))
c.revisar_bateria((15, True)); asentar()
chequear("cargando -> no pregunta", not c.pendiente)

# --- ahorro y silencio ---
c.ahorro = False
chequear("límite de silencio: sin límite ni en ahorro", c.limite_silencio() is None and (setattr(c, "ahorro", True) or c.limite_silencio() is None))

# --- sesión «un comando por Luna»: fin de orden y frases de cierre ---
c.ahorro = False; c.bateria_preguntada = False; c.pendiente = None
n0 = c.n_ordenes
dice("que hora es")
chequear("una orden ejecutada marca el fin de orden", c.n_ordenes > n0 and time.time() - c.t_orden < 2)
S["esc"] = True; c.procesar("gracias")
chequear("«gracias» (frase entera) cierra la escucha", not S["esc"])
S["esc"] = True; c.procesar("ya está")
chequear("«ya está» cierra la escucha", not S["esc"])
S["esc"] = True; dice("que hora es gracias")
chequear("«gracias» dentro de otra frase no cierra: ejecuta la orden", S["esc"])
S["esc"] = False; S["bat"] = (15, False); c.revisar_bateria(S["bat"])
n1 = c.n_ordenes; c.procesar("no")
chequear("responder una pregunta marca el fin de orden", c.n_ordenes > n1 and not c.pendiente)
c.bateria_preguntada = False; c.revisar_bateria((50, False)); S["esc"] = False; asentar()

# --- linterna y batería por la app Moon (respaldo: Termux) ---
import subprocess
cfg.ESPERA_ORDEN_APP = 0.6
termux = []
_run = subprocess.run
subprocess.run = lambda cmd, *a, **k: termux.append(cmd) or type("R", (), {"stdout": "", "returncode": 0})()
ACC["activa"] = True
chequear("linterna: la app la enciende y Termux no interviene",
         dice("enciende la linterna") == "Linterna encendida"
         and ACC["pedidas"][-1] == {"tipo": "linterna", "modo": "on"} and not termux)
chequear("linterna: apagar", dice("apaga la linterna") == "Linterna apagada" and ACC["pedidas"][-1]["modo"] == "off")
ACC["resp"]["linterna"] = (False, None)
chequear("linterna: si la app no puede, respalda Termux", dice("enciende la linterna") == "Linterna encendida" and termux == [["termux-torch", "on"]])
ACC["activa"] = False
termux.clear()
chequear("linterna: app desconectada -> Termux", dice("apaga la linterna") == "Linterna apagada" and termux == [["termux-torch", "off"]])
ACC["activa"] = True
ACC["resp"]["bateria"] = (True, {"pct": 77, "carg": True})
chequear("batería: la lee la app", c._estado_bateria() == (77, True))
ACC["resp"]["bateria"] = (False, None)
termux.clear()
subprocess.run = lambda cmd, *a, **k: termux.append(cmd) or type("R", (), {"stdout": '{"percentage": 41, "status": "DISCHARGING"}', "returncode": 0})()
chequear("batería: sin respuesta de la app, lee Termux", c._estado_bateria() == (41, False) and termux == [["termux-battery-status"]])
subprocess.run = _run
ACC["resp"].clear()
ACC["activa"] = False

# --- llamadas por voz ---
chequear("llamada: reglas de la frase", c._regla_llamada("llama a daniel lopez") == "daniel lopez"
         and c._regla_llamada("por favor llama al jefe") == "jefe" and c._regla_llamada("me llama mi mama") is None
         and c._regla_llamada("llama") is None)
chequear("llamada: sin contactos sincronizados avisa", (dice("llama a daniel") or "").startswith("Todavía no tengo tus contactos"))
with open(os.path.join(tmp, "contactos.json"), "w", encoding="utf-8") as f:
    json.dump({"t": time.time(), "contactos": [
        {"i": "1", "n": "Daniel Lopez"}, {"i": "2", "n": "H - Daniela Perez 😀"}, {"i": "3", "n": "Beth"},
        {"i": "4", "n": "Hugo Mamani"}, {"i": "5", "n": "Jessica"}, {"i": "6", "n": "Yesica Rojas"}]}, f)
chequear("contactos: palabras útiles sin prefijos ni emojis", [x["p"] for x in c._contactos()][1] == ["daniela", "perez"])
chequear("contactos: parecido de sonido (h muda, b/v, ll/y)", c.buscar_contactos("ugo")[0]["i"] == "4"
         and c.buscar_contactos("bet")[0]["i"] == "3" and c.buscar_contactos("llesica")[0]["i"] == "6"
         and c.buscar_contactos("jesica")[0]["i"] == "5")
chequear("contactos: nada parecido -> vacío", c.buscar_contactos("zzzzqx") == [])
chequear("llamada: desconocido", dice("llama a zzzzqx") == "No encontré a zzzzqx en tus contactos")
ACC["activa"] = True
ACC["pedidas"].clear()
chequear("llamada: pregunta antes de marcar", dice("llama a daniel") == "¿Llamo a Daniel Lopez?" and c.pendiente and not ACC["pedidas"])
chequear("llamada: 'no' pasa al siguiente parecido y sigue escuchando",
         dice("no") == "¿Llamo a Daniela Perez?" and c.pendiente and S["esc"])
chequear("llamada: 'sí' marca por id (el número no sale del móvil)",
         dice("si") == "Llamando a Daniela Perez" and ACC["pedidas"][-1] == {"tipo": "llamar", "cid": "2", "nombre": "Daniela Perez"}
         and not c.pendiente and not S["esc"])
ACC["pedidas"].clear()
dice("llama a bet"); dice("si")
chequear("llamada: nombre exacto no crea alias", not os.path.exists(os.path.join(tmp, "contactos_alias.json")))
dice("llama a beto"); dice("si")
chequear("llamada: un parecido aceptado guarda el alias", c.buscar_contactos("beto")[0]["via"] == "alias"
         and json.load(open(os.path.join(tmp, "contactos_alias.json")))["beto"] == "3")
json.dump({"mi suegro": "4"}, open(os.path.join(tmp, "contactos_alias.json"), "w"))
chequear("llamada: alias («mi suegro»)", c.buscar_contactos("a mi suegro")[0]["i"] == "4")
ACC["pedidas"].clear()
dice("llama a daniel"); dice("no"); dice("no")
chequear("llamada: si dice que no a todos, no llama", S["dicho"][-1] == "Está bien, no llamo a nadie" and not ACC["pedidas"] and not c.pendiente)
ACC["resp"]["llamar"] = (False, None)
dice("llama a hugo")
chequear("llamada: la app no puede marcar (permiso)", dice("si").startswith("No pude llamar a Hugo Mamani"))
ACC["resp"].clear()
cfg_ok = acciones.guardar_ajuste("confirmar_llamadas", False)
chequear("llamada: sin confirmación y nombre exacto único, marca directo",
         cfg_ok and dice("llama a hugo") == "Llamando a Hugo Mamani" and not c.pendiente)
chequear("llamada: sin confirmación pero con duda, pregunta igual", dice("llama a daniel") == "¿Llamo a Daniel Lopez?" and c.pendiente)
dice("no"); dice("no")
acciones.guardar_ajuste("confirmar_llamadas", True)
ACC["activa"] = False
chequear("llamada: app cerrada avisa", (dice("llama a hugo") == "¿Llamo a Hugo Mamani?") and dice("si") == "La app Moon no está abierta, no puedo marcar")
chequear("ajustes: valor desconocido o de tipo malo se rechaza", not acciones.guardar_ajuste("otra", True) and not acciones.guardar_ajuste("confirmar_llamadas", "no"))

# --- tarjeta Sí/No en pantalla (la app la dibuja con /estado y contesta con /responder) ---
def esperar(cond, t=3.0):
    fin = time.time() + t
    while time.time() < fin:
        if cond():
            return True
        time.sleep(0.02)
    return False


cfg.ESPERA_RESPUESTA = 5
ACC["activa"] = True
ACC["pedidas"].clear()
chequear("tarjeta: sin pregunta abierta no hay tarjeta ni se acepta un toque", c.vista_pregunta() is None and not c.responder_toque("si"))
dice("llama a daniel")
v = c.vista_pregunta()
chequear("tarjeta: la pregunta trae texto, contacto y opción k de n",
         bool(v) and v["texto"] == "¿Llamo a Daniel Lopez?" and v["extra"]["tipo"] == "llamar" and v["extra"]["cid"] == "1"
         and v["extra"]["k"] == 1 and v["extra"]["n"] >= 2 and 0 < v["restante"] <= 5)
chequear("tarjeta: el número no viaja en la pregunta", all(k in ("tipo", "cid", "nombre", "k", "n", "opciones") for k in v["extra"])
         and all(set(o) == {"cid", "nombre"} for o in v["extra"]["opciones"]))
chequear("tarjeta: un id de otra pregunta se rechaza", not c.responder_toque("si", "zzzz") and c.pendiente is not None)
chequear("tarjeta: 'No' tocado pasa al siguiente parecido",
         c.responder_toque("no", v["id"]) and esperar(lambda: (c.vista_pregunta() or {}).get("texto") == "¿Llamo a Daniela Perez?"))
v2 = c.vista_pregunta()
chequear("tarjeta: la pregunta nueva tiene otro id y k=2", v2["id"] != v["id"] and v2["extra"]["k"] == 2)
chequear("tarjeta: 'Sí' tocado marca por id y cierra la pregunta",
         c.responder_toque("si", v2["id"]) and esperar(lambda: ACC["pedidas"] and ACC["pedidas"][-1]["cid"] == "2")
         and esperar(lambda: c.pendiente is None and not S["esc"]))
chequear("tarjeta: respondió por voz y luego toca -> se ignora", (dice("llama a daniel") and dice("si")) and not c.responder_toque("si"))

# ---- tarjeta con lista de opciones: Sí/No por fila y «No llamar a nadie» ----
ACC["pedidas"].clear()
dice("llama a daniel")
v = c.vista_pregunta()
ops = v["extra"]["opciones"]
chequear("lista: trae los parecidos desde el actual, primero el de la pregunta",
         len(ops) >= 2 and ops[0]["cid"] == v["extra"]["cid"] and len(ops) <= 4)
chequear("lista: Sí en un cid que no es opción se rechaza", not c.responder_toque("si", v["id"], "zzz") and c.pendiente is not None)
chequear("lista: Sí en la fila 2 llama a ese contacto y cierra",
         c.responder_toque("si", v["id"], ops[1]["cid"]) and esperar(lambda: ACC["pedidas"] and ACC["pedidas"][-1]["cid"] == ops[1]["cid"])
         and esperar(lambda: c.pendiente is None and not S["esc"]))
ACC["pedidas"].clear()
dice("llama a daniel")
v = c.vista_pregunta()
ops = v["extra"]["opciones"]
chequear("lista: ✕ en una fila la quita y la pregunta se rearma sin ella",
         c.responder_toque("no", v["id"], ops[0]["cid"])
         and esperar(lambda: (c.vista_pregunta() or {}).get("id") not in (None, v["id"]))
         and ops[0]["cid"] not in [o["cid"] for o in c.vista_pregunta()["extra"]["opciones"]]
         and not ACC["pedidas"])
v = c.vista_pregunta()
chequear("lista: «No llamar a nadie» cierra todo sin llamar ni proponer el siguiente",
         c.responder_toque("nadie", v["id"]) and esperar(lambda: c.pendiente is None and not S["esc"]) and not ACC["pedidas"])


# ---- llamada silenciosa + Luna en pausa mientras el teléfono toma el micrófono ----
cfg.DECIR_LLAMANDO = False; ACC["activa"] = True; ACC["pedidas"].clear(); PAUSAS.clear()
dice("llama a daniel"); S["dicho"].clear(); dice("si")
chequear("llamada silenciosa: marca y no dice «Llamando a...» (la pantalla de llamada lo cortaba)",
         ACC["pedidas"] and ACC["pedidas"][-1]["cid"] == "1" and not any(x.startswith("Llamando") for x in S["dicho"]))
chequear("llamada: Luna se pausa antes de marcar y no se libera si la llamada salió", PAUSAS and PAUSAS[0] == cfg.PAUSA_AUDIO_LLAMADA and PAUSAS[-1] != 0)
ACC["resp"]["llamar"] = (False, None); PAUSAS.clear()
dice("llama a daniel"); dice("si")
chequear("llamada que falla: Luna vuelve a oír (pausa a 0) y el error se dice", PAUSAS and PAUSAS[-1] == 0 and S["dicho"] and S["dicho"][-1].startswith("No pude llamar"))
ACC["resp"].pop("llamar", None); cfg.DECIR_LLAMANDO = True

# ---- rapidez: el toque ✓ no espera a que Moon termine de decir la pregunta, y la tarjeta sale con la voz ----
ACC["pedidas"].clear(); ACC["demora"] = 1.5          # la pregunta tarda 1,5 s en decirse
t0 = time.time(); c.procesar("llama a daniel")
chequear("rapidez: procesar() vuelve al instante, sin esperar a que se diga la pregunta", time.time() - t0 < 0.5)
chequear("rapidez: la tarjeta sale cuando empieza la voz, no antes ni mucho después",
         esperar(lambda: c.vista_pregunta() is not None, 1.0) and S["dicho"] and S["dicho"][-1] == "¿Llamo a Daniel Lopez?")
v = c.vista_pregunta(); t1 = time.time()
c.responder_toque("si", v["id"])
chequear("rapidez: ✓ mientras Moon aún habla marca al instante",
         esperar(lambda: ACC["pedidas"] and ACC["pedidas"][-1]["cid"] == "1", 1.0) and time.time() - t1 < 1.0)
ACC["demora"] = 0.02
esperar(lambda: c.pendiente is None and not S["esc"])

cfg.ESPERA_RESPUESTA = 0.4
ACC["activa"] = False

# --- probar frase: decide sin ejecutar ---
ACC["activa"] = True
ACC["pedidas"].clear(); S["dicho"].clear()
pr = c.probar("enciende la linterna")
chequear("probar: comando", pr["via"] == "comando" and pr["accion"] == "linterna_on" and not ACC["pedidas"] and not S["dicho"])
pr = c.probar("llama a daniel")
chequear("probar: llamada muestra candidatos sin preguntar", pr["accion"] == "llamar" and "Daniel Lopez" in pr["detalle"] and not c.pendiente and not S["dicho"])
chequear("probar: abrir app", c.probar("abre whatsapp")["accion"] == "abrir_app" and c.probar("")["via"] == "ninguna")
ACC["activa"] = False

# --- logs ---
dia = os.listdir(os.environ["MOON_LOGDIR"])
lineas = [json.loads(l) for l in open(os.path.join(os.environ["MOON_LOGDIR"], dia[0]), encoding="utf-8")]
chequear("log por día: un archivo moon_AAAA-MM-DD.jsonl", len(dia) == 1 and dia[0].startswith("moon_") and dia[0].endswith(".jsonl"))
chequear("log guarda texto crudo, vía, acción, resultado y duración",
         any(l.get("via") == "regla" and l.get("ok") and "ms" in l and l.get("texto") for l in lineas))
chequear("log del NLU guarda intención y confianza",
         any(l.get("via") == "nlu" and "confianza" in l and "intent" in l for l in lineas) if nlu else True)
print("\n%d líneas de log" % len(lineas), "| FALLOS:", fallos)
shutil.rmtree(tmp, ignore_errors=True)
sys.exit(1 if fallos else 0)
