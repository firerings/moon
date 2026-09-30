# -*- coding: utf-8 -*-
"""Preguntas de sí/no: Moon pregunta por voz y la siguiente frase se interpreta como respuesta."""
import threading

from . import config as cfg
from .util import log_evento

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



class DialogoMixin:
    # ---------- preguntas si/no ----------
    def preguntar(self, pregunta, si, no, espera=None):
        """si/no: funciones que devuelven el texto a decir."""
        p = {"si": si, "no": no, "intentos": 0, "auto": False, "espera": espera or cfg.ESPERA_RESPUESTA}
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
            if self.pendiente is None:
                self._cerrar_auto(p)
            elif p.get("auto"):
                self.pendiente["auto"] = True      # la respuesta abrio otra pregunta: hereda el cierre de la escucha

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
