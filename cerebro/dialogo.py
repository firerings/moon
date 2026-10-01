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
    def preguntar(self, pregunta, si, no, espera=None, extra=None, elegir=None, descartar=None, nadie=None):
        """si/no: funciones que devuelven el texto a decir. `extra` (opcional) son datos para la tarjeta de la
        app (por ejemplo {"tipo": "llamar", "cid": "12", "nombre": "La Pura"}); nunca llevan datos privados.
        Solo para la tarjeta con lista de opciones (toques, no voz):
          elegir    {cid: funcion}: «Sí» tocado en la fila de ese cid.
          descartar funcion(cid): «No» tocado en la fila de ese cid (quita solo esa opción).
          nadie     funcion: «No llamar a nadie» (cierra todo sin pasar al siguiente)."""
        p = {"si": si, "no": no, "intentos": 0, "auto": False, "espera": espera or cfg.ESPERA_RESPUESTA,
             "id": os.urandom(3).hex(), "texto": pregunta, "extra": dict(extra or {}),
             "elegir": dict(elegir or {}), "descartar": descartar, "nadie": nadie, "visible": False}
        with self._lock:
            self.pendiente = p
        log_evento({"tipo": "pregunta", "texto": pregunta})
        # La pregunta se dice en otro hilo: así no se queda el candado (`self._lock`) tomado mientras Moon habla
        # y un toque en ✓ se resuelve al instante en vez de esperar a que termine la frase.
        threading.Thread(target=self._decir_pregunta, args=(p,), daemon=True).start()

    def _decir_pregunta(self, p):
        """Dice la pregunta; la tarjeta sale justo cuando empieza la voz (`visible`), no antes."""
        def ver():
            p["visible"] = True
        try:
            self.hablar(p["texto"], ver)
        finally:
            p["visible"] = True
        if self.pendiente is not p:              # ya la respondieron (toque) mientras hablaba
            return
        if not self.env["escuchando"]():
            p["auto"] = True
            self.env["iniciar"](True)
        if self.pendiente is p:
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
        if not p or not p.get("visible", True):
            return None
        restante = max(0.0, p["espera"] - (time.time() - p.get("t_arma", time.time())))
        return {"id": p["id"], "texto": p["texto"], "restante": round(restante, 1),
                "espera": p["espera"], "extra": p["extra"]}

    def responder_toque(self, r, pid=None, cid=None):
        """Respuesta tocada en la tarjeta ('si' / 'no' / 'nadie'; con `cid` si se tocó la fila de una opción).
        Se resuelve en segundo plano para que la petición HTTP vuelva al instante. False si ya no hay pregunta
        (expiró, se respondió por voz) o es otra distinta, o si el cid no es una opción de esta pregunta."""
        p = self.pendiente
        if r not in ("si", "no", "nadie") or not p or (pid and p["id"] != pid):
            return False
        if cid is not None:
            cid = str(cid)[:40]
            if r == "si" and cid not in p["elegir"]:
                return False
            if r == "no" and not p["descartar"]:
                return False
        if r == "nadie" and not p["nadie"]:
            r = "no"
        threading.Thread(target=self._resolver_toque, args=(p, r, cid), daemon=True).start()
        return True

    def _resolver_toque(self, p, r, cid=None):
        rr = "no" if r == "nadie" else r
        reg = {"id": os.urandom(4).hex(), "tipo": "frase", "texto": "(toque) " + ("sí" if rr == "si" else "no"),
               "norm": rr}
        with self._lock:
            if self.pendiente is not p:          # mientras tanto respondió por voz o expiró
                return
            if r == "si" and cid in p["elegir"]:
                p["si"] = p["elegir"][cid]
            elif r == "no" and cid and p["descartar"]:
                p["no"] = lambda f=p["descartar"], c=cid: f(c)
            elif r == "nadie":
                p["no"] = p["nadie"]
            reg.update(via="toque", respuesta=rr)
            self._decidir(p, rr, reg)

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
                self.fin_orden()
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
                self.fin_orden()
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
        self.fin_orden()
        self._cerrar_auto(p)

    def _cerrar_auto(self, p):
        if p.get("auto"):
            self.env["parar"]()
