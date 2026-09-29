# -*- coding: utf-8 -*-
"""
Modulo NLU (version numpy, sin PyTorch): INTENT CLASSIFICATION + SLOT FILLING.

Uso:
    from nlu import NLU
    modelo = NLU("modelo_nlu.npz")
    resultado = modelo.procesar("mandale a la pura que te amo")
    # {
    #   'intent': 'enviar_mensaje',
    #   'confianza': 0.97,
    #   'tokens': ['mandale', 'a', 'la', 'pura', 'que', 'te', 'amo'],
    #   'slots': ['O', 'O', 'B-CONTACTO', 'I-CONTACTO', 'O', 'B-MENSAJE', 'I-MENSAJE']
    # }
"""
import json

import numpy as np

PAD_TOKEN = "<pad>"
UNK_TOKEN = "<unk>"
CALL_INTENT_KEYWORDS = {"llama", "llamame", "llamá", "llamen", "marca", "llamada", "llamar"}
CANAL_TOKENS = {"whatsapp"}


_PUNT = str.maketrans({c: " " for c in "¿?¡!,.;:\"()"})


def tokenize(texto):
    return texto.lower().translate(_PUNT).split()


def _sin_acentos(t):
    import unicodedata
    return "".join(c for c in unicodedata.normalize("NFKD", t) if not unicodedata.combining(c))


def _es_verbode_llamada(tokens):
    return any(token in CALL_INTENT_KEYWORDS for token in tokens)


def _normalizar_tags_bio(tags):
    """Corrige etiquetas I-* que no tienen un bloque B-* válido anterior."""
    normalizados = []
    tipo_actual = None

    for tag in tags:
        if tag.startswith("B-"):
            tipo_actual = tag[2:]
            normalizados.append(tag)
        elif tag.startswith("I-"):
            tipo = tag[2:]
            if tipo_actual != tipo:
                normalizados.append(f"B-{tipo}")
                tipo_actual = tipo
            else:
                normalizados.append(tag)
        else:
            normalizados.append(tag)
            tipo_actual = None

    return normalizados


def _extraer_entidades_slots(tokens, tags):
    """Convierte bloques BIO contiguos en entidades de texto estructuradas."""
    entidades = {"app": [], "contacto": [], "mensaje": [], "canal": []}
    tipo_actual = None
    tokens_actuales = []

    def guardar_entidad():
        if tipo_actual and tokens_actuales:
            entidades[tipo_actual].append(" ".join(tokens_actuales))

    for token, tag in zip(tokens, tags):
        if tag.startswith("B-"):
            guardar_entidad()
            tipo_actual = tag[2:].lower()
            tokens_actuales = [token]
        elif tag.startswith("I-") and tipo_actual == tag[2:].lower():
            tokens_actuales.append(token)
        else:
            guardar_entidad()
            tipo_actual = None
            tokens_actuales = []

    guardar_entidad()
    return entidades


def _sigmoid(x):
    return 1.0 / (1.0 + np.exp(-np.clip(x, -30.0, 30.0)))


def _lstm_dir(x, w_ih, w_hh, b, reverso=False):
    """Una direccion de una LSTM de PyTorch (orden de puertas: i, f, g, o)."""
    n, h = x.shape[0], w_hh.shape[1]
    hs = np.zeros((n, h), dtype=np.float32)
    ht = np.zeros(h, dtype=np.float32)
    ct = np.zeros(h, dtype=np.float32)
    pasos = range(n - 1, -1, -1) if reverso else range(n)
    with np.errstate(over="ignore"):
        for t in pasos:
            g = w_ih @ x[t] + w_hh @ ht + b
            i, f, c_, o = g[:h], g[h:2 * h], g[2 * h:3 * h], g[3 * h:]
            ct = _sigmoid(f) * ct + _sigmoid(i) * np.tanh(c_)
            ht = _sigmoid(o) * np.tanh(ct)
            hs[t] = ht
    return hs, ht


def _softmax(v):
    e = np.exp(v - v.max())
    return e / e.sum()


class NLU:
    def __init__(self, ruta_modelo="modelo_nlu.npz"):
        z = np.load(ruta_modelo, allow_pickle=False)
        meta = json.loads(str(z["meta"]))
        self.word2idx = meta["word2idx"]
        self.intent2idx = meta["intent2idx"]
        self.slot2idx = meta["slot2idx"]
        self.idx2intent = {v: k for k, v in self.intent2idx.items()}
        self.idx2slot = {v: k for k, v in self.slot2idx.items()}
        self.w = {k: z[k] for k in z.files if k != "meta"}

    def _forward(self, ids):
        w = self.w
        x = w["embedding.weight"][ids]
        bf = w["lstm.bias_ih_l0"] + w["lstm.bias_hh_l0"]
        br = w["lstm.bias_ih_l0_reverse"] + w["lstm.bias_hh_l0_reverse"]
        hf, h_fwd = _lstm_dir(x, w["lstm.weight_ih_l0"], w["lstm.weight_hh_l0"], bf)
        hb, h_bwd = _lstm_dir(x, w["lstm.weight_ih_l0_reverse"], w["lstm.weight_hh_l0_reverse"], br, True)
        oracion = np.concatenate([h_fwd, h_bwd])
        intent_logits = w["intent_head.weight"] @ oracion + w["intent_head.bias"]
        por_token = np.concatenate([hf, hb], axis=1)
        slot_logits = por_token @ w["slot_head.weight"].T + w["slot_head.bias"]
        return intent_logits, slot_logits

    def procesar(self, texto):
        tokens = tokenize(texto)
        if not tokens:
            return {"intent": "conversar", "confianza": 0.0, "tokens": [], "slots": []}

        ids = [self.word2idx.get(t, self.word2idx.get(_sin_acentos(t), self.word2idx[UNK_TOKEN])) for t in tokens]
        intent_logits, slot_logits = self._forward(np.array(ids))
        intent_probs = _softmax(intent_logits)
        intent_idx = int(np.argmax(intent_probs))
        intent_conf = float(intent_probs[intent_idx])
        slot_tags = [self.idx2slot[int(i)] for i in np.argmax(slot_logits, axis=1)]
        slot_tags = _normalizar_tags_bio(slot_tags)

        result = {
            "intent": self.idx2intent[intent_idx],
            "confianza": round(intent_conf, 4),
            "tokens": tokens,
            "slots": slot_tags,
        }

        entidades = _extraer_entidades_slots(tokens, slot_tags)
        for tipo, valores in entidades.items():
            if valores:
                result[tipo] = valores[0]

        canales = []
        canal_actual = []
        for token, tag in zip(tokens, slot_tags):
            if tag == "B-CANAL":
                if canal_actual:
                    canales.append(" ".join(canal_actual))
                canal_actual = [token]
            elif tag == "I-CANAL" and canal_actual:
                canal_actual.append(token)
            elif canal_actual:
                canales.append(" ".join(canal_actual))
                canal_actual = []
        if canal_actual:
            canales.append(" ".join(canal_actual))

        # Fallback sencillo para canales conocidos: permite reconocer
        # WhatsApp aunque el predictor de slots no lo etiquete correctamente.
        if not canales:
            canales = [token for token in tokens if token in CANAL_TOKENS]
        if canales:
            result["canal"] = canales[0]
            if "mensaje" in result:
                mensaje_tokens = result["mensaje"].split()
                canal = result["canal"]
                indice_canal = next(
                    (i for i, token in enumerate(mensaje_tokens) if token == canal),
                    None,
                )
                if indice_canal is not None:
                    inicio = indice_canal - 1 if indice_canal > 0 and mensaje_tokens[indice_canal - 1] == "por" else indice_canal
                    result["mensaje"] = " ".join(
                        mensaje_tokens[:inicio] + mensaje_tokens[indice_canal + 1:]
                    ).strip()

        # Fallback de llamada: si la frase claramente contiene un verbo de
        # llamada y el modelo no está muy seguro, preferimos llamar.
        if result["intent"] != "llamar" and result["confianza"] < 0.85 and _es_verbode_llamada(tokens):
            result["intent"] = "llamar"
            result["confianza"] = round(max(result["confianza"], 0.65), 4)

        # Si la intención es 'llamar', tratar el resto de la frase como
        # candidato de contacto (priorizar contacto sobre mensaje).
        if result["intent"] == "llamar":
            # buscar 'a' o 'al' como inicio del contacto
            start = None
            for i, t in enumerate(tokens):
                if t in ("a", "al"):
                    start = i + 1
                    break
            if start is None:
                # si no hay 'a', tomar desde el segundo token (tras verbo)
                start = 1 if len(tokens) > 1 else 0

            # construir candidato y sobrescribir slots para reflejar contacto
            candidato_tokens = tokens[start:]
            if candidato_tokens:
                contacto_texto = " ".join(candidato_tokens)
                new_slots = ["O"] * len(tokens)
                new_slots[start] = "B-CONTACTO"
                for j in range(start + 1, len(tokens)):
                    new_slots[j] = "I-CONTACTO"
                result["slots"] = new_slots
                result["contacto_candidato"] = contacto_texto
                result["contacto"] = contacto_texto

        return result


if __name__ == "__main__":
    nlu = NLU("modelo_nlu.npz")
    for texto in ["abre chrome", "mandale a la pura que te amo",
                  "llama a mi mama", "hola como estas", "salir"]:
        print(texto, "->", nlu.procesar(texto))
