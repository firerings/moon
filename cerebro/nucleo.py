# -*- coding: utf-8 -*-
"""Cerebro de Moon: interpreta lo que oye Vosk y ejecuta acciones.

Flujo de cada frase:
  1. Si Moon esta esperando un si/no, la frase se interpreta como respuesta.
  2. comandos.json (frases fijas, editable) -> accion.
  3. Si no hay comando, el NLU (nlu_np.py) decide la intencion y la entidad.
  4. La accion se ejecuta (con confirmacion si hace falta), se responde por voz
     (termux-tts-speak) y todo queda en un log por dia en Download/MoonLogs.

Las acciones viven en mixins (apps.py, sistema.py...). Una skill nueva = un mixin con su NOMBRES y su
`_acciones_<skill>()`, y añadirlo abajo en `class Cerebro` y en `self.acciones`.
"""
import difflib
import json
import os
import subprocess
import threading
import time

from . import config as cfg
from . import apps, sistema, llamadas
from .util import norm, log_evento
from .voz import VozMixin
from .dialogo import DialogoMixin
from .apps import AppsMixin
from .sistema import SistemaMixin
from .llamadas import LlamadasMixin

NOMBRES = {**apps.NOMBRES, **sistema.NOMBRES, **llamadas.NOMBRES}


def _ejecutar_rish(cmd, timeout=10):
    try:
        r = subprocess.run(["./rish", "-c", cmd], cwd=cfg.HOME, capture_output=True, text=True,
                           timeout=timeout, env={**os.environ, "RISH_APPLICATION_ID": "com.termux"})
        return r.returncode == 0, (r.stdout or "") + (r.stderr or "")
    except Exception as e:
        return False, str(e)


def _tts_termux(texto):
    subprocess.run(["termux-tts-speak", "-l", "es", texto], timeout=12)


class Cerebro(VozMixin, DialogoMixin, AppsMixin, SistemaMixin, LlamadasMixin):
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
        self.n_ordenes = 0           # órdenes terminadas (la sesión «un comando por Luna» se cierra al subir)
        self.t_orden = 0.0
        self._abiertas = {}
        self._habla = {}
        self.bateria_preguntada = False
        self.ahorro = self._cargar_estado().get("ahorro", False)
        self.comandos = self._cargar_comandos()
        self.acciones = {}
        for skill in (self._acciones_apps, self._acciones_sistema, self._acciones_llamadas):
            self.acciones.update(skill())

    # ---------- configuracion ----------
    def _cargar_comandos(self):
        try:
            with open(os.path.join(cfg.DIR, "comandos.json"), encoding="utf-8") as f:
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

    # ---------- entrada principal ----------
    def procesar(self, texto):
        tn = norm(texto)
        if not tn:
            return
        t0 = time.time()
        reg = {"id": os.urandom(4).hex(), "tipo": "frase", "texto": texto, "norm": tn}
        with self._lock:
            if self.pendiente:
                return self._responder_pendiente(tn, reg)
            if tn in cfg.FRASES_CIERRE:                      # «ya está», «gracias»...: solo si es la frase entera
                reg.update(via="cierre", accion="parar")
                return self._ejecutar("parar", {}, False, reg, t0)
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
            contacto = self._regla_llamada(tn)
            if contacto:
                reg.update(via="regla", entidades={"contacto": contacto})
                return self._ejecutar("llamar", {"contacto": contacto}, False, reg, t0)
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
            if r["confianza"] < cfg.UMBRAL_INTENT:
                reg.update(ok=False, motivo="baja_confianza")
                log_evento(reg)
                return self.hablar("No te entendí bien")
            if it == "llamar":
                contacto = ent.get("contacto") or r.get("contacto_candidato") or ""
                return self._ejecutar("llamar", {"contacto": contacto}, False, reg, t0)
            if it in ("abrir_app", "cerrar_app"):
                if not ent.get("app"):
                    reg.update(ok=False, motivo="sin_app")
                    log_evento(reg)
                    return self.hablar("¿Qué app?")
                return self._ejecutar(it, {"app": ent["app"]}, False, reg, t0)
            reg.update(ok=False, motivo="no_implementado")
            log_evento(reg)
            return self.hablar("Todavía no sé hacer eso")


    def probar(self, texto):
        """Qué haría Moon con esta frase, SIN ejecutar nada ni hablar (caja «Probar frase» de la app)."""
        tn = norm(texto)
        out = {"norm": tn, "via": "ninguna", "accion": "", "nombre": "", "entidades": {}, "detalle": ""}
        if not tn:
            return out
        cmd = self._buscar_comando(tn)
        if cmd:
            return self._decision(out, "comando", cmd["accion"], {}, cmd["confirmar"])
        regla = self._regla_app(tn)
        if regla:
            accion, app, verbo = regla
            r = self.resolver_app(app, self.apps_instaladas())
            if verbo != "ahora" or r:
                out = self._decision(out, "regla", accion, {"app": app}, False)
                out["detalle"] = ("App: %s (%.2f)" % (r["nombre"], r["score"])) if r else "No encuentro esa app"
                return out
        contacto = self._regla_llamada(tn)
        if contacto:
            return self._decision_llamada(out, "regla", contacto)
        if not self.nlu:
            out["detalle"] = "Sin modelo NLU"
            return out
        r = self.nlu.procesar(texto)
        ent = {k: r[k] for k in ("app", "contacto", "mensaje", "canal") if k in r}
        out.update(via="nlu", intent=r["intent"], confianza=r["confianza"], entidades=ent)
        it = r["intent"]
        if it == "conversar":
            out["detalle"] = "Charla: no hace nada"
        elif it == "salir":
            out = self._decision(out, "nlu", "parar", {}, False)
        elif r["confianza"] < cfg.UMBRAL_INTENT:
            out["detalle"] = "Confianza baja: diría «No te entendí bien»"
        elif it == "llamar":
            out = self._decision_llamada(out, "nlu", ent.get("contacto") or r.get("contacto_candidato") or "")
        elif it in ("abrir_app", "cerrar_app"):
            ap = self.resolver_app(ent.get("app", ""), self.apps_instaladas()) if ent.get("app") else None
            out = self._decision(out, "nlu", it, {"app": ent.get("app", "")}, False)
            out["detalle"] = ("App: %s (%.2f)" % (ap["nombre"], ap["score"])) if ap else "No encuentro esa app"
        else:
            out["detalle"] = "Todavía no sé hacer eso"
        return out

    def _decision(self, out, via, accion, ent, confirmar):
        out.update(via=via, accion=accion, nombre=NOMBRES.get(accion, accion), entidades=ent)
        out["detalle"] = "Pediría confirmación" if confirmar else "Lo ejecutaría"
        return out

    def _decision_llamada(self, out, via, contacto):
        out = self._decision(out, via, "llamar", {"contacto": contacto}, False)
        if not self._contactos():
            out["detalle"] = "Todavía no hay contactos sincronizados"
            return out
        c = self.buscar_contactos(contacto)
        out["detalle"] = ("Preguntaría por: " + ", ".join("%s (%.2f)" % (self._nombre_corto(x), x["score"]) for x in c)) \
            if c else "No encuentro ese contacto"
        return out

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
            self.env["ui"]({"tipo": "orden", "nombre": NOMBRES.get(accion, accion), "via": "voz",
                            "texto": reg.get("texto", ""), "frase_id": reg.get("id", ""),
                            "dur_ms": reg.get("ms", 0)})
        self.hablar(resp)
        self.fin_orden()
        return resp


    def iniciar(self):
        threading.Thread(target=self.vigilar_bateria, daemon=True).start()
        log_evento({"tipo": "inicio", "ahorro": self.ahorro, "comandos": len(self.comandos),
                    "nlu": bool(self.nlu)})
