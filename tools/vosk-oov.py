#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Ktorych hasel gry model Vosk NIE ZNA - czyli czego uczen nigdy nie powie.

DLACZEGO TO ISTNIEJE. Gramatyka Voska nie jest lista napisow, tylko lista SLOW ze slownika
modelu: `UpdateGrammarFst` zamienia kazdy token na identyfikator przez
`model_->word_syms_->Find(token)` i token, ktorego w slowniku nie ma, PO CICHU wypada
(recognizer.cc ostrzega w logu natywnym i jedzie dalej). Haslo zlozone z samych takich tokenow
nie ma jak zostac rozpoznane - nie ma zadnego bledu, zadnego komunikatu, nic. Dla ucznia
wyglada to tak, ze akurat to jedno slowo "nie dziala".

Skrypt czyta hasla wprost z data/BuiltInSets.kt i normalizuje je DOKLADNIE tak jak
VoskGrammar.normalizeForGrammar w Kotlinie - jesli tamto sie zmieni, to musi zmienic sie tutaj.

UZYCIE:
    tools/vosk-oov.py                         # oba jezyki, modele z app/src/main/assets/vosk
    tools/vosk-oov.py --lista                 # same hasla, po jednym w linii (do wklejenia)
"""

import importlib.util
import os
import re
import sys

KORZEN = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ZESTAWY = os.path.join(KORZEN, "app/src/main/java/com/matura/app/data/BuiltInSets.kt")
MODELE = os.path.join(KORZEN, "app/src/main/assets/vosk")

# --- normalizacja: lustro VoskGrammar.normalizeForGrammar ---------------------------------
ROZDZIELACZE = re.compile(r"[,;/]")
DO_SPACJI = re.compile(r"[-‐-―'‘’`]+")
NIE_LITERA = re.compile(r"[^\w ]", re.UNICODE)
WIELE_SPACJI = re.compile(r"\s+")


def normalizuj(raw):
    s = DO_SPACJI.sub(" ", raw.lower())
    s = NIE_LITERA.sub(" ", s)
    return WIELE_SPACJI.sub(" ", s).strip()


def warianty(raw):
    out = []
    kandydaci = [raw] + (ROZDZIELACZE.split(raw) if ROZDZIELACZE.search(raw) else [])
    for czesc in kandydaci:
        n = normalizuj(czesc)
        if n and n not in out:
            out.append(n)
    return out


def slownik_modelu(kod):
    """Tablica symboli z Gr.fst - czytana tym samym kodem, ktory ja tam zostawil."""
    sciezka = os.path.join(os.path.dirname(os.path.abspath(__file__)), "vosk-slim.py")
    spec = importlib.util.spec_from_file_location("vosk_slim", sciezka)
    vs = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(vs)
    gr, _ = vs.sciezka_gr(os.path.join(MODELE, kod))
    _, _, symbole = vs.czytaj_symbole_wyjsciowe(gr)
    return {s.decode("utf-8", "replace") for s, _ in symbole}


def hasla():
    zrodlo = open(ZESTAWY, encoding="utf-8").read()
    pary = re.findall(r'Entry\("((?:[^"\\]|\\.)*)",\s*"((?:[^"\\]|\\.)*)"', zrodlo)
    return pary


def main(argv):
    tylko_lista = "--lista" in argv
    pary = hasla()
    if not tylko_lista:
        print("hasel w BuiltInSets: %d" % len(pary))
    for jezyk, kolumna, kod in (("angielski", 0, "en"), ("polski", 1, "pl")):
        leks = slownik_modelu(kod)
        strony = sorted({p[kolumna] for p in pary})
        # Haslo jest nieme dopiero wtedy, gdy ZADEN jego wariant nie ma kompletu tokenow:
        # "towarzyski, kontaktowy" wystarczy, ze da sie powiedziec jako "towarzyski".
        nieme = [s for s in strony
                 if not any(all(t in leks for t in w.split(" ")) for w in warianty(s))]
        if tylko_lista:
            for s in nieme:
                print(s)
            continue
        print("\n== %s ==  hasel: %d   slownik modelu: %d slow" % (jezyk, len(strony), len(leks)))
        print("   nie do powiedzenia: %d (%.2f%%)" % (len(nieme), 100.0 * len(nieme) / len(strony)))
        for s in nieme:
            print("     %s" % s)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
