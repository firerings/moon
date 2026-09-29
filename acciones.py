# -*- coding: utf-8 -*-
"""Cerebro de Moon: interpreta lo que oye Vosk y ejecuta acciones.

Flujo de cada frase:
  1. Si Moon esta esperando un si/no, la frase se interpreta como respuesta.
  2. comandos.json (frases fijas, editable) -> accion.
  3. Si no hay comando, el NLU (nlu_np.py) decide la intencion y la entidad.
  4. La accion se ejecuta (con confirmacion si hace falta), se responde por voz
     (termux-tts-speak) y todo queda en un log por dia en Download/MoonLogs.
"""
import datetime
import difflib
import json
import os
import subprocess
import threading
import time
import unicodedata
import re

HOME = os.path.expanduser("~")
DIR = os.path.dirname(os.path.abspath(__file__))
LOGDIR = os.environ.get("MOON_LOGDIR", "/storage/emulated/0/Download/MoonLogs")

UMBRAL_INTENT = 0.55        # confianza minima del NLU
ESPERA_RESPUESTA = 15       # segundos que Moon espera un si/no
SILENCIO_AUTO = 20          # cierre por silencio: escucha abierta por Moon o modo ahorro
COLA_TTS = 0.9              # segundos de "oido apagado" tras hablar (evita oirse a si mismo)
ESPERA_ENTREGA = 1.5        # segundos para que la app Moon recoja una frase a decir
ESPERA_ACK = 4              # segundos que se espera a que la app Moon confirme
BATERIA_BAJA = 20           # % a partir del cual pregunta por el modo ahorro
BATERIA_OK = 35             # % a partir del cual vuelve a poder preguntar
REVISION_BATERIA = 120      # cada cuantos segundos mira la bateria

_log_lock = threading.Lock()


def norm(t):
    """minusculas, sin acentos ni signos."""
    t = unicodedata.normalize("NFKD", (t or "").lower())
    t = "".join(c for c in t if not unicodedata.combining(c))
    return " ".join(re.sub(r"[^a-z0-9 ]+", " ", t).split())


def log_evento(reg):
    """Una linea JSON por evento en Download/MoonLogs/moon_AAAA-MM-DD.jsonl"""
    ahora = datetime.datetime.now()
    reg = {"ts": ahora.strftime("%H:%M:%S"), **reg}
    try:
        with _log_lock:
            os.makedirs(LOGDIR, exist_ok=True)
            ruta = os.path.join(LOGDIR, "moon_%s.jsonl" % ahora.strftime("%Y-%m-%d"))
            with open(ruta, "a", encoding="utf-8") as f:
                f.write(json.dumps(reg, ensure_ascii=False) + "\n")
    except OSError:
        pass


SI_T = {"si", "afirmativo", "claro", "dale", "ok", "okay", "correcto", "adelante",
        "hazlo", "vale", "exacto", "activalo", "yes"}
NO_T = {"no", "negativo", "cancela", "cancelar", "nada", "nunca"}


def sino(tn):
    t = set(tn.split())
    s, n = bool(t & SI_T), bool(t & NO_T)
    if s and not n:
        return "si"
    if n and not s:
        return "no"
    return None


# Apps: nombre hablado -> paquetes posibles (se usa el primero instalado).
# Se puede ampliar sin tocar codigo con apps_alias.json ({"nombre": ["paquete", ...]}).
ALIAS_BASE = {
    "camara": ["com.android.camera", "com.android.camera2", "com.google.android.GoogleCamera"],
    "galeria": ["com.miui.gallery", "com.google.android.apps.photos", "com.android.gallery3d"],
    "fotos": ["com.google.android.apps.photos", "com.miui.gallery"],
    "ajustes": ["com.android.settings"],
    "configuracion": ["com.android.settings"],
    "calculadora": ["com.miui.calculator", "com.google.android.calculator", "com.android.calculator2"],
    "reloj": ["com.android.deskclock", "com.google.android.deskclock"],
    "alarma": ["com.android.deskclock", "com.google.android.deskclock"],
    "contactos": ["com.android.contacts", "com.google.android.contacts"],
    "telefono": ["com.android.contacts", "com.google.android.dialer", "com.android.dialer"],
    "mensajes": ["com.google.android.apps.messaging", "com.android.mms"],
    "sms": ["com.google.android.apps.messaging", "com.android.mms"],
    "chrome": ["com.android.chrome"],
    "navegador": ["com.android.chrome", "com.mi.globalbrowser", "com.android.browser"],
    "whatsapp": ["com.whatsapp"],
    "youtube": ["com.google.android.youtube"],
    "telegram": ["org.telegram.messenger"],
    "facebook": ["com.facebook.katana"],
    "instagram": ["com.instagram.android"],
    "tiktok": ["com.zhiliaoapp.musically", "com.ss.android.ugc.trill"],
    "spotify": ["com.spotify.music"],
    "netflix": ["com.netflix.mediaclient"],
    "mapas": ["com.google.android.apps.maps"],
    "maps": ["com.google.android.apps.maps"],
    "gmail": ["com.google.android.gm"],
    "correo": ["com.google.android.gm"],
    "drive": ["com.google.android.apps.docs"],
    "play store": ["com.android.vending"],
    "tienda": ["com.android.vending"],
    "archivos": ["com.mi.android.globalFileexplorer", "com.google.android.documentsui", "com.android.fileexplorer"],
    "termux": ["com.termux"],
    "moon": ["com.exclusivo.moon"],
    "shizuku": ["moe.shizuku.privileged.api"],
}
RELLENO = {"el", "la", "los", "las", "mi", "mis", "un", "una", "app", "aplicacion",
           "de", "del", "por", "favor"}
GENERICOS = {"com", "org", "net", "android", "google", "apps", "app", "apk", "mobile", "www",
             "io", "me", "co", "de", "es", "mi", "miui", "xiaomi", "samsung", "system",
             "service", "services", "provider", "providers", "client"}

VERBOS_ABRIR = {"abre", "abrir", "abri", "abra", "abrime", "abrelo", "abrela", "lanza", "lanzar",
                "inicia", "iniciar", "ejecuta", "ejecutar", "ahora"}   # "ahora": Vosk oye asi "abre"
VERBOS_CERRAR = {"cierra", "cerrar", "cerra", "cierre", "termina", "terminar"}

NOMBRES = {
    "abrir_app": "Abrir app", "cerrar_app": "Cerrar app", "hora": "Decir la hora",
    "bateria": "Estado de la batería", "linterna_on": "Encender linterna",
    "linterna_off": "Apagar linterna", "ahorro_on": "Activar modo ahorro",
    "ahorro_off": "Desactivar modo ahorro", "parar": "Dejar de escuchar",
    "actualizar_apps": "Actualizar lista de apps", "reiniciar": "Reiniciar conexión",
}


def _ejecutar_rish(cmd, timeout=10):
    try:
        r = subprocess.run(["./rish", "-c", cmd], cwd=HOME, capture_output=True, text=True,
                           timeout=timeout, env={**os.environ, "RISH_APPLICATION_ID": "com.termux"})
        return r.returncode == 0, (r.stdout or "") + (r.stderr or "")
    except Exception as e:
        return False, str(e)


def _tts_termux(texto):
    subprocess.run(["termux-tts-speak", "-l", "es", texto], timeout=40)


class Cerebro:
    """env (todo opcional salvo iniciar/parar/escuchando/reiniciar/ui):
       rish(cmd, timeout)->(ok, salida)   tts(texto)   ui(dict)   reiniciar()->str|None
       iniciar(auto=False)   parar()   escuchando()->bool"""

    def __init__(self, nlu, env):
        self.nlu = nlu
        self.env = env
        self.rish = env.get("rish", _ejecutar_rish)
        self.tts = env.get("tts", _tts_termux)
        self._lock = threading.RLock()
        self._tts_lock = threading.Lock()
        self._mudo = False
        self._mudo_hasta = 0.0
        self.pendiente = None
        self._abiertas = {}
        self._habla = {}
        self.bateria_preguntada = False
        self.ahorro = self._cargar_estado().get("ahorro", False)
        self.comandos = self._cargar_comandos()
        self.acciones = {
            "abrir_app": self._a_abrir_app, "cerrar_app": self._a_cerrar_app,
            "hora": self._a_hora, "bateria": self._a_bateria,
            "linterna_on": lambda a: self._linterna("on"),
            "linterna_off": lambda a: self._linterna("off"),
            "ahorro_on": lambda a: self._set_ahorro(True),
            "ahorro_off": lambda a: self._set_ahorro(False),
            "parar": self._a_parar, "actualizar_apps": self._a_actualizar_apps,
            "reiniciar": lambda a: env["reiniciar"](),
        }

    # ---------- configuracion y estado ----------
    def _cargar_comandos(self):
        try:
            with open(os.path.join(DIR, "comandos.json"), encoding="utf-8") as f:
                out = []
                for c in json.load(f)["comandos"]:
                    frases = []
                    for x in c["frases"]:
                        partes = [norm(p) + ("*" if p.endswith("*") else "") for p in x.split()]
                        frases.append(" ".join(p for p in partes if p.strip("*")))
                    out.append({"accion": c["accion"], "confirmar": c.get("confirmar", False),
                                "frases": frases})
                return out
        except Exception as e:
            log_evento({"tipo": "error", "donde": "comandos.json", "detalle": str(e)})
            return []

    def _cargar_estado(self):
        try:
            with open(os.path.join(DIR, "estado_moon.json")) as f:
                return json.load(f)
        except Exception:
            return {}

    def _guardar_estado(self):
        try:
            with open(os.path.join(DIR, "estado_moon.json"), "w") as f:
                json.dump({"ahorro": self.ahorro}, f)
        except OSError:
            pass

    # ---------- voz ----------
    def hablar(self, texto):
        """Voz de la app Moon (motor de Android ya arrancado, casi instantanea) si esta conectada;
        si no, termux-tts-speak (tarda varios segundos en arrancar)."""
        if not texto:
            return
        with self._tts_lock:
            self._mudo = True
            via, error = "app", None
            try:
                if not self._decir_app(texto):
                    via = "termux"
                    self.tts(texto)
            except Exception as e:
                error = str(e)
            finally:
                self._mudo_hasta = time.time() + COLA_TTS
                self._mudo = False
        log_evento({"tipo": "habla", "texto": texto, "via": via, **({"error": error} if error else {})})

    def _decir_app(self, texto):
        env = self.env
        if not (env.get("app_activa") and env["app_activa"]() and env.get("enviar")):
            return False
        i = env["enviar"]({"tipo": "decir", "texto": texto})
        h = {"ev": threading.Event(), "ok": None}
        self._habla[i] = h
        try:
            fin = time.time() + ESPERA_ENTREGA
            while not env["servida"](i):
                if time.time() > fin:            # la app no la recogio: que hable Termux
                    env["cancelar"](i)
                    return False
                time.sleep(0.05)
            h["ev"].wait(min(15.0, 2.0 + 0.09 * len(texto)))   # hasta que termine de hablar
            return h["ok"] is not False
        finally:
            self._habla.pop(i, None)

    def callando(self):
        """El bucle de audio descarta lo que oye mientras Moon habla."""
        return self._mudo or time.time() < self._mudo_hasta

    def limite_silencio(self, auto=False):
        """Segundos de silencio tras los que se cierra la escucha (None = sin limite)."""
        return SILENCIO_AUTO if (auto or self.ahorro) else None

    # ---------- entrada principal ----------
    def procesar(self, texto):
        tn = norm(texto)
        if not tn:
            return
        t0 = time.time()
        reg = {"tipo": "frase", "texto": texto, "norm": tn}
        with self._lock:
            if self.pendiente:
                return self._responder_pendiente(tn, reg)
            cmd = self._buscar_comando(tn)
            if cmd:
                reg.update(via="comando", accion=cmd["accion"])
                return self._ejecutar(cmd["accion"], {}, cmd["confirmar"], reg, t0)
            regla = self._regla_app(tn)
            if regla:
                accion, app, verbo = regla
                if verbo != "ahora" or self.resolver_app(app, self.apps_instaladas()):
                    reg.update(via="regla", entidades={"app": app})
                    return self._ejecutar(accion, {"app": app}, False, reg, t0)
            if not self.nlu:
                reg.update(via="ninguna", motivo="sin_nlu")
                return log_evento(reg)
            r = self.nlu.procesar(texto)
            ent = {k: r[k] for k in ("app", "contacto", "mensaje", "canal") if k in r}
            reg.update(via="nlu", intent=r["intent"], confianza=r["confianza"], entidades=ent)
            it = r["intent"]
            if it == "conversar":
                reg["motivo"] = "charla"
                return log_evento(reg)
            if it == "salir":
                return self._ejecutar("parar", {}, False, reg, t0)
            if r["confianza"] < UMBRAL_INTENT:
                reg.update(ok=False, motivo="baja_confianza")
                log_evento(reg)
                return self.hablar("No te entendí bien")
            if it in ("abrir_app", "cerrar_app"):
                if not ent.get("app"):
                    reg.update(ok=False, motivo="sin_app")
                    log_evento(reg)
                    return self.hablar("¿Qué app?")
                return self._ejecutar(it, {"app": ent["app"]}, False, reg, t0)
            reg.update(ok=False, motivo="no_implementado")
            log_evento(reg)
            return self.hablar("Todavía no sé hacer eso")

    @staticmethod
    def _regla_app(tn):
        """'abre X' / 'cierra X' sin necesitar el modelo. Devuelve (accion, app, verbo) o None."""
        toks = tn.split()
        for i, t in enumerate(toks[:3]):
            for accion, verbos in (("abrir_app", VERBOS_ABRIR), ("cerrar_app", VERBOS_CERRAR)):
                if t in verbos:
                    resto = [w for w in toks[i + 1:] if w not in RELLENO][:4]
                    if resto and len(toks) - i - 1 <= 6:
                        return accion, " ".join(resto), t
        return None

    def _buscar_comando(self, tn):
        toks = tn.split()
        mejor = None
        for c in self.comandos:
            for fr in c["frases"]:
                ft = fr.split()
                if self._contiene(toks, ft):
                    peso = len(fr)
                    if not mejor or peso > mejor[0]:
                        mejor = (peso, c)
        if mejor:
            return mejor[1]
        # tolerancia a errores del STT: frase entera casi igual
        for c in self.comandos:
            for fr in c["frases"]:
                if "*" not in fr and difflib.SequenceMatcher(None, tn, fr).ratio() >= 0.88:
                    return c
        return None

    @staticmethod
    def _contiene(toks, ft):
        n = len(ft)
        for i in range(len(toks) - n + 1):
            if all((a.startswith(b[:-1]) if b.endswith("*") else a == b)
                   for a, b in zip(toks[i:i + n], ft)):
                return True
        return False

    # ---------- ejecucion ----------
    def _ejecutar(self, accion, args, confirmar, reg, t0):
        fn = self.acciones.get(accion)
        if not fn:
            reg.update(ok=False, motivo="sin_accion")
            log_evento(reg)
            return self.hablar("Todavía no sé hacer eso")
        if confirmar:
            reg["confirma"] = True
            log_evento(reg)
            return self.preguntar("¿%s?" % NOMBRES.get(accion, accion),
                                  lambda: self._correr(accion, fn, args, {"tipo": "frase", "via": "confirmado"}, time.time()),
                                  lambda: "Cancelado")
        return self._correr(accion, fn, args, reg, t0)

    def _correr(self, accion, fn, args, reg, t0):
        try:
            resp = fn(args)
            reg["ok"] = True
        except Exception as e:
            resp, reg["ok"], reg["error"] = "Algo falló", False, str(e)
        reg.update(accion=accion, args=args, ms=int((time.time() - t0) * 1000))
        log_evento(reg)
        if accion != "reiniciar":
            self.env["ui"]({"tipo": "orden", "nombre": NOMBRES.get(accion, accion), "via": "voz"})
        self.hablar(resp)
        return resp

    # ---------- preguntas si/no ----------
    def preguntar(self, pregunta, si, no, espera=None):
        """si/no: funciones que devuelven el texto a decir."""
        p = {"si": si, "no": no, "intentos": 0, "auto": False, "espera": espera or ESPERA_RESPUESTA}
        with self._lock:
            self.pendiente = p
        log_evento({"tipo": "pregunta", "texto": pregunta})
        self.hablar(pregunta)
        if not self.env["escuchando"]():
            p["auto"] = True
            self.env["iniciar"](True)
        self._armar(p)

    def _armar(self, p):
        if p.get("timer"):
            p["timer"].cancel()
        p["timer"] = threading.Timer(p["espera"], self._expirar, args=(p,))
        p["timer"].daemon = True
        p["timer"].start()

    def _cerrar_pregunta(self, p):
        if p.get("timer"):
            p["timer"].cancel()
        if self.pendiente is p:
            self.pendiente = None

    def _responder_pendiente(self, tn, reg):
        p = self.pendiente
        r = sino(tn)
        reg.update(via="respuesta", respuesta=r)
        if r is None:
            p["intentos"] += 1
            if p["intentos"] >= 2:
                self._cerrar_pregunta(p)
                reg.update(ok=False, motivo="respuesta_no_entendida")
                log_evento(reg)
                self.hablar("No te entendí, lo dejo así")
                return self._cerrar_auto(p)
            log_evento(reg)
            self.hablar("¿Sí o no?")
            return self._armar(p)
        self._cerrar_pregunta(p)
        log_evento(reg)
        try:
            self.hablar((p["si"] if r == "si" else p["no"])())
        finally:
            self._cerrar_auto(p)

    def _expirar(self, p):
        with self._lock:
            if self.pendiente is not p:
                return
            self.pendiente = None
        log_evento({"tipo": "pregunta_expirada"})
        self.hablar("No te escuché, lo dejo así")
        self._cerrar_auto(p)

    def _cerrar_auto(self, p):
        if p.get("auto"):
            self.env["parar"]()

    # ---------- acciones ----------
    def _a_hora(self, a):
        n = datetime.datetime.now()
        h, m = n.hour % 12 or 12, n.minute
        s = "Es la una" if h == 1 else "Son las %d" % h
        return s + (" en punto" if m == 0 else " y media" if m == 30 else " y %d" % m)

    def _estado_bateria(self):
        try:
            r = subprocess.run(["termux-battery-status"], capture_output=True, text=True, timeout=10)
            d = json.loads(r.stdout)
            return int(d["percentage"]), str(d.get("status", "")).upper() == "CHARGING"
        except Exception:
            return None

    def _a_bateria(self, a):
        st = self.env.get("bateria", self._estado_bateria)()
        if not st:
            return "No pude leer la batería"
        pct, carg = st
        return "La batería está al %d por ciento%s" % (pct, " y está cargando" if carg else "")

    def _linterna(self, modo):
        subprocess.run(["termux-torch", modo], timeout=10)
        return "Linterna encendida" if modo == "on" else "Linterna apagada"

    def _set_ahorro(self, on):
        self.ahorro = on
        self._guardar_estado()
        return "Modo ahorro activado" if on else "Modo ahorro desactivado"

    def _a_parar(self, a):
        self.env["parar"]()
        return None

    def _catalogo_app(self):
        """{paquete: etiqueta} que la app Moon envia (nombres reales, como en tu lanzador)."""
        try:
            with open(os.path.join(DIR, "apps_app.json"), encoding="utf-8") as f:
                return {x["p"]: x["n"] for x in json.load(f)["apps"]}
        except Exception:
            return {}

    def apps_instaladas(self, forzar=False):
        cat = self._catalogo_app()
        if cat:
            return sorted(cat)
        ruta = os.path.join(DIR, "apps_cache.json")
        try:
            with open(ruta) as f:
                cache = json.load(f)
        except Exception:
            cache = None
        if cache and cache.get("pkgs") and not forzar and time.time() - cache.get("t", 0) < 12 * 3600:
            return cache["pkgs"]
        ok, out = self.rish("pm list packages", 20)
        pkgs = sorted({l.strip()[8:] for l in out.splitlines() if l.strip().startswith("package:")}) if ok else []
        if pkgs:
            try:
                with open(ruta, "w") as f:
                    json.dump({"t": time.time(), "pkgs": pkgs}, f)
            except OSError:
                pass
            return pkgs
        return cache["pkgs"] if cache and cache.get("pkgs") else []

    def _a_actualizar_apps(self, a):
        n = len(self.apps_instaladas(True))
        return "Encontré %d aplicaciones" % n if n else "No pude leer las aplicaciones"

    def _alias(self):
        alias = dict(ALIAS_BASE)
        try:
            with open(os.path.join(DIR, "apps_alias.json"), encoding="utf-8") as f:
                for k, v in json.load(f).items():
                    alias[norm(k)] = [v] if isinstance(v, str) else list(v)
        except Exception:
            pass
        return alias

    def resolver_app(self, texto, pkgs):
        """Orden: etiqueta exacta (catalogo de la app) > alias > etiqueta parecida > nombre del paquete."""
        q = " ".join(w for w in norm(texto).split() if w not in RELLENO)
        if not q:
            return None
        cat = self._catalogo_app()
        etq = {p: norm(n) for p, n in cat.items()}
        for p, n in etq.items():
            if n == q:
                return {"pkg": p, "nombre": cat[p], "score": 1.0}
        inst, alias = set(pkgs), self._alias()
        k, score = None, 0.0
        if q in alias:
            k, score = q, 1.0
        else:
            m = difflib.get_close_matches(q, list(alias), n=1, cutoff=0.8)
            if m:
                k, score = m[0], difflib.SequenceMatcher(None, q, m[0]).ratio()
        if k:
            for p in alias[k]:
                if p in inst:
                    return {"pkg": p, "nombre": cat.get(p, k), "score": round(score, 2)}
        mejor = None
        for p, n in etq.items():
            s = difflib.SequenceMatcher(None, q, n).ratio()
            if len(n) >= 4 and (n in q or q in n):
                s = max(s, 0.85)
            if s >= 0.75 and (not mejor or s > mejor[0] or (s == mejor[0] and len(n) < len(mejor[2]))):
                mejor = (s, p, n)
        if mejor:
            return {"pkg": mejor[1], "nombre": cat[mejor[1]], "score": round(mejor[0], 2)}
        for p in pkgs:
            for n in [s for s in p.lower().split(".") if s not in GENERICOS and len(s) > 2]:
                s = 1.0 if q == n else difflib.SequenceMatcher(None, q, n).ratio()
                if len(n) >= 4 and (n in q or q in n) and s < 0.85:
                    s = 0.85
                if s >= 0.75 and (not mejor or s > mejor[0] or (s == mejor[0] and len(p) < len(mejor[1]))):
                    mejor = (s, p, n)
        return {"pkg": mejor[1], "nombre": mejor[2], "score": round(mejor[0], 2)} if mejor else None

    def _app_de(self, args):
        pkgs = self.apps_instaladas()
        if not pkgs:
            return None, "Todavía no tengo la lista de aplicaciones"
        r = self.resolver_app(args.get("app", ""), pkgs)
        if not r:
            return None, "No encontré la aplicación %s" % args.get("app", "")
        return r, None

    def _lanzar_shizuku(self, r):
        ok, out = self.rish("monkey -p %s -c android.intent.category.LAUNCHER 1" % r["pkg"], 15)
        return ok and "No activities found" not in out and "aborted" not in out

    def _a_abrir_app(self, a):
        """Camino normal: la app Moon abre la app (no necesita Shizuku).
        Respaldo: Shizuku, si la app Moon no esta conectada o no confirma."""
        r, err = self._app_de(a)
        if err:
            return err
        if self.env.get("app_activa") and self.env["app_activa"]():
            i = self.env["enviar"]({"tipo": "abrir", "pkg": r["pkg"], "nombre": r["nombre"]})
            self._abiertas[i] = r
            t = threading.Timer(ESPERA_ACK, self._sin_ack, args=(i,))
            t.daemon = True
            t.start()
            return "Abriendo %s" % r["nombre"]
        return ("Abriendo %s" % r["nombre"]) if self._lanzar_shizuku(r) else "No pude abrir %s" % r["nombre"]

    def resultado_app(self, i, ok):
        """La app Moon confirma (ok) o dice que no pudo (abrir una app / terminar de hablar)."""
        h = self._habla.get(i)
        if h is not None:
            h["ok"] = bool(ok)
            h["ev"].set()
            return
        r = self._abiertas.pop(i, None)
        log_evento({"tipo": "ack_app", "id": i, "ok": bool(ok), "app": r and r["nombre"]})
        if r and not ok:
            self._respaldo(r)

    def _sin_ack(self, i):
        r = self._abiertas.pop(i, None)
        if r:
            log_evento({"tipo": "ack_app", "id": i, "ok": False, "motivo": "sin_respuesta"})
            self._respaldo(r)

    def _respaldo(self, r):
        if not self._lanzar_shizuku(r):
            self.hablar("No pude abrir %s" % r["nombre"])

    def _a_cerrar_app(self, a):
        r, err = self._app_de(a)
        if err:
            return err
        ok, _ = self.rish("am force-stop %s" % r["pkg"], 10)
        return ("Cerré %s" % r["nombre"]) if ok else "No pude cerrar %s" % r["nombre"]

    # ---------- bateria: pregunta por el modo ahorro ----------
    def revisar_bateria(self, st):
        """st = (porcentaje, cargando) o None. Pregunta una vez por descarga."""
        if not st:
            return
        pct, carg = st
        if carg or pct >= BATERIA_OK:
            self.bateria_preguntada = False
            return
        if pct <= BATERIA_BAJA and not self.ahorro and not self.bateria_preguntada and not self.pendiente:
            self.bateria_preguntada = True
            self.preguntar("La batería está al %d por ciento. ¿Quieres activar el modo ahorro?" % pct,
                           lambda: self._set_ahorro(True), lambda: "Está bien, sigo normal")

    def vigilar_bateria(self):
        fuente = self.env.get("bateria", self._estado_bateria)
        while True:
            try:
                self.revisar_bateria(fuente())
            except Exception as e:
                log_evento({"tipo": "error", "donde": "bateria", "detalle": str(e)})
            time.sleep(REVISION_BATERIA)

    def iniciar(self):
        threading.Thread(target=self.vigilar_bateria, daemon=True).start()
        log_evento({"tipo": "inicio", "ahorro": self.ahorro, "comandos": len(self.comandos),
                    "nlu": bool(self.nlu)})
