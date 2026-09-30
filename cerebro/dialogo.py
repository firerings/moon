# -*- coding: utf-8 -*-
"""Preguntas de sí/no: Moon pregunta por voz y la siguiente frase se interpreta como respuesta."""
import os
import threading
import time

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
    def preguntar(self, pregunta, si, no, espera=None, extra=None):
        """si/no: funciones que devuelven el texto a decir. `extra` (opcional) son datos para la tarjeta de la
        app (por ejemplo {"tipo": "llamar", "cid": "12", "nombre": "La Pura"}); nunca llevan datos privados."""
        p = {"si": si, "no": no, "intentos": 0, "auto": False, "espera": espera or cfg.ESPERA_RESPUESTA,
             "id": os.urandom(3).hex(), "texto": pregunta, "extra": dict(extra or {})}
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
        p["t_arma"] = time.time()
        p["timer"] = threading.Timer(p["espera"], self._expirar, args=(p,))
        p["timer"].daemon = True
        p["timer"].start()

    def _cerrar_pregunta(self, p):
        if p.get("timer"):
            p["timer"].cancel()
        if self.pendiente is p:
            self.pendiente = None

    def vista_pregunta(self):
        """Lo que la app necesita para dibujar la tarjeta Sí/No, o None si no hay pregunta abierta.
        Se lee sin tomar el candado: /estado se consulta cada 350 ms y no debe esperar a que Moon termine de hablar."""
        p = self.pendiente
        if not p:
            return None
        restante = max(0.0, p["espera"] - (time.time() - p.get("t_arma", time.time())))
        return {"id": p["id"], "texto": p["texto"], "restante": round(restante, 1),
                "espera": p["espera"], "extra": p["extra"]}

    def responder_toque(self, r, pid=None):
        """Respuesta tocada en la tarjeta ('si' / 'no'). Se resuelve en segundo plano para que la petición HTTP
        vuelva al instante. False si ya no hay pregunta (expiró, se respondió por voz) o es otra distinta."""
        p = self.pendiente
        if r not in ("si", "no") or not p or (pid and p["id"] != pid):
            return False
        threading.Thread(target=self._resolver_toque, args=(p, r), daemon=True).start()
        return True

    def _resolver_toque(self, p, r):
        reg = {"id": os.urandom(4).hex(), "tipo": "frase", "texto": "(toque) " + ("sí" if r == "si" else "no"),
               "norm": r}
        with self._lock:
            if self.pendiente is not p:          # mientras tanto respondió por voz o expiró
                return
            reg.update(via="toque", respuesta=r)
            self._decidir(p, r, reg)

    def _responder_pendiente(self, tn, reg):
        p = self.pendiente
        r = sino(tn)
        reg.update(via="respuesta", respuesta=r)
        return self._decidir(p, r, reg)

    def _decidir(self, p, r, reg):
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
