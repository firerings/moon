# -*- coding: utf-8 -*-
"""Acciones del propio móvil: hora, batería, linterna, modo ahorro, reinicio de conexión, dejar de escuchar."""
import datetime
import json
import os
import subprocess
import time

from . import config as cfg
from .util import log_evento

NOMBRES = {
    "hora": "Decir la hora", "bateria": "Estado de la batería", "linterna_on": "Encender linterna",
    "linterna_off": "Apagar linterna", "ahorro_on": "Activar modo ahorro",
    "ahorro_off": "Desactivar modo ahorro", "parar": "Dejar de escuchar", "reiniciar": "Reiniciar conexión",
}


class SistemaMixin:
    def _acciones_sistema(self):
        return {
            "hora": self._a_hora, "bateria": self._a_bateria,
            "linterna_on": lambda a: self._linterna("on"),
            "linterna_off": lambda a: self._linterna("off"),
            "ahorro_on": lambda a: self._set_ahorro(True),
            "ahorro_off": lambda a: self._set_ahorro(False),
            "parar": self._a_parar,
            "reiniciar": lambda a: self.env["reiniciar"](),
        }

    # ---------- estado guardado ----------
    def _cargar_estado(self):
        try:
            with open(os.path.join(cfg.DIR, "estado_moon.json")) as f:
                return json.load(f)
        except Exception:
            return {}

    def _guardar_estado(self):
        try:
            with open(os.path.join(cfg.DIR, "estado_moon.json"), "w") as f:
                json.dump({"ahorro": self.ahorro}, f)
        except OSError:
            pass


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

    # ---------- bateria: pregunta por el modo ahorro ----------
    def revisar_bateria(self, st):
        """st = (porcentaje, cargando) o None. Pregunta una vez por descarga."""
        if not st:
            return
        pct, carg = st
        if carg or pct >= cfg.BATERIA_OK:
            self.bateria_preguntada = False
            return
        if pct <= cfg.BATERIA_BAJA and not self.ahorro and not self.bateria_preguntada and not self.pendiente:
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
            time.sleep(cfg.REVISION_BATERIA)
