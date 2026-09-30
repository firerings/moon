# -*- coding: utf-8 -*-
"""Compatibilidad: el cerebro ahora vive en el paquete `cerebro/` (nucleo, apps, sistema, dialogo, voz).
Este módulo solo reexporta lo que usan voz_servidor.py y los tests. Las constantes se cambian en
`cerebro.config` (cambiarlas aquí no tendría efecto)."""
from cerebro.config import LOGDIR
from cerebro.util import norm, log_evento, leer_ajustes, guardar_ajuste
from cerebro.dialogo import sino
from cerebro.nucleo import Cerebro
