# Stan prac: wstrzymane 20 listopada 2026

Ten katalog jest tymczasowy. Zawiera wszystko, czego potrzeba, żeby podjąć pracę od miejsca,
w którym została wstrzymana. Przed wydaniem należy go usunąć.

## Co jest gotowe i scalone na tej gałęzi (901 testów zielonych)

- Kod wersji sklepowej 0.9.18 połączony z gałęzią `master`.
- Tryb klasyczny: poprawna odpowiedź pokazywana na planszy, pomylone słowa wracają w tej samej
  rundzie, runda ćwiczeniowa ma koniec, ekran wyniku na cały ekran z listą słów do powtórki,
  sześć poprawek po recenzji.
- „Dzisiejsze 5 minut": trasa dnia jest zamrożona (nie dokłada nowych słów przy każdym wejściu),
  runda sama przechodzi do następnego kroku.
- Logika grywalizacji (bez ekranów): punkty nauki, 13 etapów, cel dnia, seria z dniami wolnymi,
  odznaki, pomocnik odliczania do matury, migracja zapisu do schematu 4.
- Dopasowanie odpowiedzi: apostrofy, ukośniki, hasła o wspólnym tłumaczeniu.
- Reguły rozpoznawania głosu: słownikiem jest cały zestaw, jedna wypowiedź daje jedno zaliczenie
  (logika jest gotowa, ale ekran gry jeszcze z niej nie korzysta).
- Android 16: kategoria gry, pion nie jest wymaganym sprzętem, zmiany konfiguracji bez restartu,
  ciemne paski systemowe, jedno repozytorium danych na proces, wpis dla lektora systemowego.
- Plakietki mnożnika pokazują x2, x3 i x5.

## Co zostało przerwane w połowie

1. **Edytor i lista zestawów** (`zestawy-edytor.patch`, nakłada się na commit `46b7ef5`):
   niedokończony etap. Zlecenie: `notatki/zlecenia/zestawy-edytor-platforma.js`, część SPEC_EDITOR.
2. **Logika punktów: poprawki po recenzji.** Recenzent skończył, poprawki nie zostały naniesione.
   Lista uwag: `notatki/raport-res-review-ledger.txt`.
3. **Tryby PRAWDA/FAŁSZ, ABCD, NA CZAS**: etap nie zdążył niczego zmienić.
   Zlecenie: `notatki/zlecenia/dopasowanie-tryby-glos.js`, część SPEC_MODES.

## Co jeszcze nie zostało zaczęte

- Ekrany grywalizacji: karta gracza w menu, nagrody na ekranie wyniku, ekran odznak, cel dnia
  i data matury w opcjach. Projekt: `notatki/projekt-grywalizacji.md`.
- Podłączenie nowych reguł głosu do ekranu gry oraz drobne poprawki tego ekranu
  (`notatki/audyt/res-audit-playscreen.txt`).
- Poprawki słownictwa: 55 podejrzanych haseł w `notatki/slownictwo/`, sprawdzonych tylko częściowo.
- Wersja 0.10.0 (versionCode 36), notatki do wydania, README, podpisany plik AAB.
- Usunięcie tego katalogu.

## Decyzje do potwierdzenia przez właściciela

- Data matury z angielskiego na poziomie podstawowym: przyjęto 6 maja 2027.
- Adres kontaktowy w polityce prywatności: w aplikacji i na matura.uk jest inny niż w sklepie.
- Numer versionCode 36 nie może być wcześniej użyty na ścieżce testowej w Play Console.

## Jak nałożyć łatkę

    git checkout 46b7ef5 && git apply --index .wip-backup/zestawy-edytor.patch
