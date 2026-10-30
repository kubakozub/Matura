#!/usr/bin/env bash
#
# Odtwarza modele rozpoznawania offline w app/src/vosk-assets/.
#
# DLACZEGO MODELI NIE MA W REPOZYTORIUM. Odchudzone modele wazyly 107 MB — dziesiec
# razy wiecej niz caly kod i cala grafika razem. Nie sa tez nasze: to wagi z projektu
# Vosk (Alpha Cephei), ktore tylko przepuszczamy przez tools/vosk-slim.py. Zamiast
# trzymac w historii gita cudze 107 MB, trzymamy przepis: paczka upstreamu z
# przypietym skrotem + nasz skrypt + lista skrotow wyniku. Sprawdzone: wynik jest
# IDENTYCZNY BAJT W BAJT z tym, co bylo wczesniej w repozytorium (26 plikow).
#
# UZYCIE:
#     tools/vosk-fetch.sh            # pobierz, odchudz, sprawdz skroty
#     tools/vosk-fetch.sh --verify   # tylko sprawdz, czy to, co lezy, jest zgodne
#
# Potem paczka z glosem offline:
#     ./gradlew assembleDebug -Pvosk=true
#
# Bez tego kroku aplikacja buduje sie normalnie i dziala — VoskVoiceInput sam
# zglasza, ze nie ma czym rozpoznawac (maModel), a glos idzie przez silniki
# systemowe. Modele sa POLISA na telefony bez zadnej uslugi rozpoznawania.
set -euo pipefail

KORZEN="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CEL="$KORZEN/app/src/vosk-assets"
MANIFEST="$KORZEN/tools/vosk-models.sha256"
ROBOCZY="$KORZEN/build/vosk-pobrane"

# Przypiete wersje. Skrot liczony z paczki upstreamu — gdyby plik pod tym URL-em
# kiedykolwiek sie zmienil, pobranie zostanie odrzucone, a nie po cichu przyjete.
MODELE=(
  "pl|vosk-model-small-pl-0.22|c4cd16498ea544f446f9e9a55cbd602b71cfe5a2b6f2b0834d81e1b6fce15f0d"
  "en|vosk-model-small-en-us-0.15|30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"
)
BAZA="https://alphacephei.com/vosk/models"

sprawdz_manifest() {
  # Czyta liste "skrot  sciezka" i sprawdza kazdy plik. Zwraca 1, gdy czegos brakuje
  # albo skrot sie nie zgadza — wtedy wolajacy wie, ze trzeba pobierac od nowa.
  [ -d "$CEL" ] || return 1
  ( cd "$CEL" && grep -v '^#' "$MANIFEST" | grep -v '^[[:space:]]*$' | sha256sum --quiet --check - ) >/dev/null 2>&1
}

if sprawdz_manifest; then
  echo "Modele sa na miejscu i zgadzaja sie ze skrotami ($(grep -vc '^#' "$MANIFEST") plikow)."
  echo "Paczka z glosem offline: ./gradlew assembleDebug -Pvosk=true"
  exit 0
fi

if [ "${1:-}" = "--verify" ]; then
  echo "BLAD: modele w $CEL sa niezgodne z $MANIFEST albo ich nie ma." >&2
  echo "Uruchom bez --verify, zeby je odtworzyc." >&2
  exit 1
fi

command -v python3 >/dev/null || { echo "BLAD: potrzebny python3 (tools/vosk-slim.py)." >&2; exit 1; }
command -v unzip   >/dev/null || { echo "BLAD: potrzebny unzip." >&2; exit 1; }
POBIERACZ=""
command -v curl >/dev/null && POBIERACZ="curl"
[ -z "$POBIERACZ" ] && command -v wget >/dev/null && POBIERACZ="wget"
[ -z "$POBIERACZ" ] && { echo "BLAD: potrzebny curl albo wget." >&2; exit 1; }

mkdir -p "$ROBOCZY"
for wpis in "${MODELE[@]}"; do
  JEZYK="${wpis%%|*}"
  RESZTA="${wpis#*|}"
  NAZWA="${RESZTA%%|*}"
  SKROT="${RESZTA##*|}"
  ZIP="$ROBOCZY/$NAZWA.zip"

  if [ ! -f "$ZIP" ]; then
    echo "==> pobieram $NAZWA.zip"
    if [ "$POBIERACZ" = "curl" ]; then
      curl -fL --retry 3 -o "$ZIP.czesc" "$BAZA/$NAZWA.zip"
    else
      wget -q --tries=3 -O "$ZIP.czesc" "$BAZA/$NAZWA.zip"
    fi
    mv "$ZIP.czesc" "$ZIP"
  fi

  echo "==> sprawdzam skrot paczki $NAZWA.zip"
  echo "$SKROT  $ZIP" | sha256sum --check --quiet - || {
    echo "BLAD: $NAZWA.zip ma inny skrot niz przypiety. Nie rozpakowuje." >&2
    echo "      Usun $ZIP i spróbuj ponownie; jesli blad wraca, paczka upstreamu sie zmienila." >&2
    exit 1
  }

  echo "==> rozpakowuje i odchudzam -> $JEZYK"
  rm -rf "$ROBOCZY/$NAZWA"
  unzip -q "$ZIP" -d "$ROBOCZY"
  rm -rf "$CEL/vosk/$JEZYK"
  mkdir -p "$CEL/vosk"
  python3 "$KORZEN/tools/vosk-slim.py" "$ROBOCZY/$NAZWA" "$CEL/vosk/$JEZYK"
done

echo "==> sprawdzam skroty wyniku"
if sprawdz_manifest; then
  echo
  echo "GOTOWE. $(grep -vc '^#' "$MANIFEST") plikow zgodnych z $MANIFEST."
  echo "Paczka z glosem offline: ./gradlew assembleDebug -Pvosk=true"
else
  echo "BLAD: wynik nie zgadza sie z $MANIFEST." >&2
  ( cd "$CEL" && grep -v '^#' "$MANIFEST" | sha256sum --check - 2>&1 | grep -v ': OK$' | head -20 ) >&2
  exit 1
fi
