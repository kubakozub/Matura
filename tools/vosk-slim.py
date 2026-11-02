#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Odchudza model Vosk do postaci, ktora wchodzi do APK.

DLACZEGO TO ISTNIEJE. Modele vosk-model-small-* wazą 68-92 MB, z czego 40-60 MB
to graph/Gr.fst - gotowy model jezykowy (n-gramy calego jezyka). Aplikacja pyta
o slowa Z PLANSZY, wiec kazdy Recognizer powstaje z GRAMATYKA:

    Recognizer(model, 16000f, '["kolano","plecy","[unk]"]')

W tym trybie Vosk buduje wlasny graf gramatyki i sklada go z graph/HCLr.fst
(vosk/src/recognizer.cc: UpdateGrammarFst -> LookaheadComposeFst(hcl_fst_, g_fst_)).
Z modelowego Gr.fst nie uzywa ani jednego luku - potrzebuje wylacznie jego TABLICY
SYMBOLI, bo tylko przez nia zamienia slowo z gramatyki na identyfikator
(recognizer.cc: model_->word_syms_->Find(token)), a identyfikatory musza byc te same,
ktorych uzywa HCLr.fst. Model.cc bierze te tablice tak:

    if (g_fst_ && g_fst_->OutputSymbols()) word_syms_ = g_fst_->OutputSymbols();

Dlatego zostawiamy Gr.fst z pelna tablica symboli i BEZ lukow (jeden stan koncowy,
zero przejsc). Plik dalej jest poprawnym FST-em, wiec Model wczytuje sie normalnie,
a z pobrania znika ~34 MB na jezyk.

CZEGO TEN SKRYPT CELOWO NIE ROBI. Nie przycina tablicy symboli do slownika
aplikacji, choc to oszczedziloby kolejne 5 MB (polski) / 2 MB (angielski). Uczen
moze zalozyc WLASNY zestaw (Repository.upsertSet) i wpisac w nim dowolne slowo;
slowo spoza tablicy symboli Vosk po cichu wyrzuca z gramatyki (recognizer.cc
ostrzega i pomija), wiec takiego slowa nie dalo by sie juz powiedziec nigdy.
Pelna tablica jest cena za to, ze glos dziala takze w zestawach wlasnych.

UZYCIE:
    tools/vosk-slim.py <katalog-modelu> <katalog-wynikowy>
    tools/vosk-slim.py --verify <katalog-wynikowy>

Wynik ma trafic do app/src/main/assets/vosk/<pl|en>/.
"""

import os
import shutil
import struct
import sys

# Naglowek OpenFst i tablicy symboli - liczby sa z fst/lib/fst.h (kFstMagicNumber)
# i fst/lib/symbol-table.h (kSymbolTableMagicNumber). Bez sprawdzenia obu nie
# wiadomo, czy czytamy model Vosk, czy dowolny inny plik o tej nazwie.
FST_MAGIC = 2125659606
SYMTAB_MAGIC = 0x7EB2FB74

# Bity FstHeader::flags. Tablica wejsciowa nie jest nam do niczego potrzebna
# (Vosk pyta wylacznie o OutputSymbols), wiec w wyniku zapalamy tylko drugi.
FLAG_ISYMBOLS = 0x01
FLAG_OSYMBOLS = 0x02

# Wersja formatu VectorFst (fst/lib/vector-fst.h: kFileVersion). Wersja z pliku
# zrodlowego sie NIE nadaje: modele Vosk sa zapisane jako ConstFst, a my piszemy
# VectorFst i jego czytnik sprawdza wlasny zakres wersji.
VECTOR_FST_VERSION = 2

# Pliki, bez ktorych Model sie nie zlozy (sciezki z vosk/src/model.cc, uklad
# "z katalogiem graph"). Kolejnosc jak w model.cc, zeby dalo sie porownac wzrokiem.
# Czego tu NIE MA i dlaczego: rescore/ i rnnlm/ (przeliczanie hipotez calymi
# zdaniami - przy gramatyce z kilkunastu slow nie ma czego przeliczac),
# graph/words.txt (czytane tylko wtedy, gdy Gr.fst nie ma tablicy symboli - a ma).
WYMAGANE = (
    "am/final.mdl",
    "conf/model.conf",
    "conf/mfcc.conf",
    "ivector/final.ie",
    "ivector/final.dubm",
    "ivector/final.mat",
    "ivector/global_cmvn.stats",
    "ivector/online_cmvn.conf",
    "ivector/splice.conf",
    "graph/HCLr.fst",
    "graph/disambig_tid.int",
    # Znaczniki granic slow. Bez nich Vosk nie odda czasow ani pewnosci pojedynczych
    # slow, a na tym stoi bramka pewnosci w VoskVoiceInput (SetWords/SetMaxAlternatives).
    "graph/phones/word_boundary.int",
)


class Czytnik:
    """Strumien little-endian nad plikiem FST - same typy, ktore wystepuja w naglowku."""

    def __init__(self, dane):
        self.dane = dane
        self.poz = 0

    def _we(self, ile):
        if self.poz + ile > len(self.dane):
            raise ValueError("plik urwany na bajcie %d" % self.poz)
        out = self.dane[self.poz:self.poz + ile]
        self.poz += ile
        return out

    def i32(self):
        return struct.unpack("<i", self._we(4))[0]

    def i64(self):
        return struct.unpack("<q", self._we(8))[0]

    def u64(self):
        return struct.unpack("<Q", self._we(8))[0]

    def napis(self):
        return self._we(self.i32())


def _zapisz_napis(b):
    return struct.pack("<i", len(b)) + b


def czytaj_tablice_symboli(c):
    """Zwraca (nazwa, available_key, [(symbol, id)]) spod biezacej pozycji."""
    magia = c.i32()
    if magia != SYMTAB_MAGIC:
        raise ValueError("oczekiwano tablicy symboli, jest 0x%08x" % (magia & 0xFFFFFFFF))
    nazwa = c.napis()
    dostepny_klucz = c.i64()
    ile = c.i64()
    symbole = []
    for _ in range(ile):
        sym = c.napis()
        symbole.append((sym, c.i64()))
    return nazwa, dostepny_klucz, symbole


def czytaj_symbole_wyjsciowe(sciezka):
    """Wyluskuje tablice symboli WYJSCIOWYCH z Gr.fst (to jedyne, co nam z niego zostaje)."""
    with open(sciezka, "rb") as f:
        c = Czytnik(f.read())
    if c.i32() != FST_MAGIC:
        raise ValueError("%s nie jest plikiem FST" % sciezka)
    c.napis()          # fsttype
    c.napis()          # arctype
    c.i32()            # version
    flagi = c.i32()
    c.u64()            # properties
    c.i64()            # start
    c.i64()            # numstates
    c.i64()            # numarcs
    if flagi & FLAG_ISYMBOLS:
        czytaj_tablice_symboli(c)
    if not flagi & FLAG_OSYMBOLS:
        raise ValueError("%s nie ma tablicy symboli wyjsciowych - model nie nadaje sie "
                         "do trybu gramatyki" % sciezka)
    return czytaj_tablice_symboli(c)


def zbuduj_zaslepke(nazwa, dostepny_klucz, symbole):
    """VectorFst: jeden stan koncowy, zero lukow, pelna tablica symboli wyjsciowych."""
    buf = bytearray()
    buf += struct.pack("<i", FST_MAGIC)
    buf += _zapisz_napis(b"vector")
    buf += _zapisz_napis(b"standard")
    buf += struct.pack("<i", VECTOR_FST_VERSION)
    buf += struct.pack("<i", FLAG_OSYMBOLS)
    # properties = 0 znaczy "nic nie wiadomo" (same bity "znane" sa wyzerowane),
    # wiec czytnik policzy sobie wlasnosci sam zamiast zaufac naglowkowi.
    buf += struct.pack("<Q", 0)
    buf += struct.pack("<q", 0)   # start
    buf += struct.pack("<q", 1)   # numstates
    buf += struct.pack("<q", 0)   # numarcs
    buf += struct.pack("<i", SYMTAB_MAGIC)
    buf += _zapisz_napis(nazwa)
    buf += struct.pack("<q", dostepny_klucz)
    buf += struct.pack("<q", len(symbole))
    for sym, klucz in symbole:
        buf += _zapisz_napis(sym) + struct.pack("<q", klucz)
    # Cialo VectorFst: dla kazdego stanu waga koncowa (float) i liczba lukow (int64).
    # 0.0 to One() w polpierscieniu tropikalnym, czyli stan jest koncowy - FST
    # przyjmuje wylacznie pusty ciag i nigdy nie da zadnego slowa.
    buf += struct.pack("<f", 0.0) + struct.pack("<q", 0)
    return bytes(buf)


def sciezka_gr(katalog):
    """Vosk zna dwa uklady modelu (z katalogiem graph/ i plaski) - patrz model.cc."""
    for rel in ("graph/Gr.fst", "Gr.fst"):
        p = os.path.join(katalog, rel)
        if os.path.exists(p):
            return p, rel
    raise SystemExit("brak Gr.fst w %s - to nie jest model Vosk" % katalog)


def odchudz(zrodlo, cel):
    gr, gr_rel = sciezka_gr(zrodlo)
    plaski = gr_rel == "Gr.fst"
    nazwa, dostepny_klucz, symbole = czytaj_symbole_wyjsciowe(gr)
    zaslepka = zbuduj_zaslepke(nazwa, dostepny_klucz, symbole)

    if os.path.exists(cel):
        shutil.rmtree(cel)
    skopiowane = 0
    for rel in WYMAGANE:
        z = os.path.join(zrodlo, rel)
        if not os.path.exists(z) and plaski:
            z = os.path.join(zrodlo, os.path.basename(rel))
        if not os.path.exists(z):
            # Brak pliku nieobowiazkowego nie jest bledem tylko wtedy, gdy model go
            # nie ma w ogole; brak final.mdl jest bledem zawsze. Rozstrzyga o tym
            # ponizsze sprawdzenie na koncu, tu tylko notujemy.
            continue
        d = os.path.join(cel, rel)
        os.makedirs(os.path.dirname(d), exist_ok=True)
        shutil.copy2(z, d)
        skopiowane += 1

    d_gr = os.path.join(cel, "graph", "Gr.fst")
    os.makedirs(os.path.dirname(d_gr), exist_ok=True)
    with open(d_gr, "wb") as f:
        f.write(zaslepka)

    for musi in ("am/final.mdl", "conf/model.conf", "graph/HCLr.fst"):
        if not os.path.exists(os.path.join(cel, musi)):
            raise SystemExit("model wynikowy nie ma %s - Vosk go nie wczyta" % musi)

    # Kontrola pozytywna: zaslepke odczytujemy tym samym czytnikiem i sprawdzamy,
    # ze tablica symboli przezyla zapis. Bez tego "0 bledow" znaczyloby tylko tyle,
    # ze skrypt nie rzucil wyjatku.
    _, _, po = czytaj_symbole_wyjsciowe(d_gr)
    if len(po) != len(symbole):
        raise SystemExit("zaslepka zgubila symbole: %d -> %d" % (len(symbole), len(po)))

    przed = rozmiar(zrodlo)
    potem = rozmiar(cel)
    print("%s -> %s" % (zrodlo, cel))
    print("  symbole slownika : %d" % len(symbole))
    print("  Gr.fst           : %s -> %s" % (mb(os.path.getsize(gr)), mb(len(zaslepka))))
    print("  plikow           : %d + Gr.fst" % skopiowane)
    print("  model razem      : %s -> %s  (oszczednosc %s)" % (mb(przed), mb(potem), mb(przed - potem)))
    return potem


def rozmiar(katalog):
    suma = 0
    for korzen, _, pliki in os.walk(katalog):
        for p in pliki:
            suma += os.path.getsize(os.path.join(korzen, p))
    return suma


def mb(b):
    return "%.1f MB" % (b / 1e6)


def main(argv):
    if len(argv) == 3 and argv[1] == "--verify":
        gr, _ = sciezka_gr(argv[2])
        nazwa, _, symbole = czytaj_symbole_wyjsciowe(gr)
        print("%s: %d symboli, Gr.fst %s, model %s"
              % (argv[2], len(symbole), mb(os.path.getsize(gr)), mb(rozmiar(argv[2]))))
        return 0
    if len(argv) != 3:
        print(__doc__)
        return 2
    odchudz(argv[1], argv[2])
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
