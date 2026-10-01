# -*- coding: utf-8 -*-
"""Hablar: voz de la app Moon si está conectada, si no termux-tts-speak; y control de silencio."""
import threading
import time

from . import config as cfg
from .util import log_evento


class VozMixin:
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
                self._mudo_hasta = time.time() + cfg.COLA_TTS
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
            fin = time.time() + cfg.ESPERA_ENTREGA
            while not env["servida"](i):
                if time.time() > fin:            # la app no la recogio: que hable Termux
                    env["cancelar"](i)
                    return False
                time.sleep(0.05)
            h["ev"].wait(min(15.0, 2.0 + 0.09 * len(texto)))   # hasta que termine de hablar
            return h["ok"] is not False
        finally:
            self._habla.pop(i, None)

    def fin_orden(self):
        """Una orden (o su pregunta) terminó y Moon ya habló: la sesión «un comando por Luna» puede cerrarse."""
        self.n_ordenes += 1
        self.t_orden = time.time()

    def callando(self):
        """El bucle de audio descarta lo que oye mientras Moon habla."""
        return self._mudo or time.time() < self._mudo_hasta

    def limite_silencio(self, auto=False):
        """Segundos de silencio tras los que se cierra la escucha (None = sin limite)."""
        return cfg.SILENCIO_AUTO if (auto or self.ahorro) else None
