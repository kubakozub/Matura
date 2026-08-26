# Matura — CLASSIC

Gra do nauki angielskiego słownictwa, zbudowana wprost z dokumentu projektowego
*Verbume — Verbum + Game*. Android, Kotlin, Jetpack Compose. Tryb CLASSIC z pełną progresją.

**Wersja 0.8.0** · 102 zestawy · 2084 hasła · 14 działów matury podstawowej

> Aplikacja nazywała się wcześniej **Verbume**. Zmieniła się nazwa i identyfikator
> pakietu (`com.verbume.app` → `com.matura.app`); mechanika, słownictwo i grafika
> zostały bez zmian.

## Co nowego w 0.8.0

Znowu wszystko z uwag testerów.

- **Hasło z łącznikiem można wpisać.** Tester wylosował „otwarty" i nie miał jak
  odpowiedzieć `open-minded`: klawiatura ma tylko A–Z, a łącznik i tak zamienia się
  w normalizacji na spację. Do przyjmowanych odpowiedzi dochodzi wariant **bez
  odstępu**, więc zaliczają się i `open minded`, i `openminded`, a podświetlanie
  prefiksu działa w obu. Dotyczy to wszystkich 665 haseł wielowyrazowych, nie tylko
  37 z łącznikiem.
- **Lista DO POWTÓRKI po grze.** Ekran KONIEC GRY pokazuje pod wynikiem każde hasło,
  które doszło do działka, wraz z tłumaczeniem w oryginalnej pisowni — z ogonkami
  i łącznikiem, nie w postaci znormalizowanej. To samo słowo przegrane dwa razy to
  jedna pozycja z licznikiem `x2`. Lista przewija się we własnej ramce o stałej
  wysokości, więc długa rozgrywka nie zepchnie przycisków poza ekran.
- **Koniec z kumulacją tych samych słów.** Losowanie było ze zwracaniem, więc jedno
  hasło potrafiło wracać bez przerwy, a inne nie paść ani razu. Teraz działa worek:
  cały zestaw jest tasowany i dobierany bez zwracania, a nowe tasowanie następuje
  dopiero po jego wyczerpaniu. Nowa kolejka nie zaczyna się tym, czym skończyła się
  poprzednia. Powtórki w obrębie rozgrywki zostają — o nie chodzi w zestawach po
  około dwadzieścia haseł — ale rozkładają się równo.

Demo w przeglądarce dostało te same trzy zmiany, żeby dalej odpowiadało silnikowi.

## Co nowego w 0.7.0

Wszystkie trzy zmiany wynikają wprost z uwag z testów.

- **Wolniejsze tempo.** Bazowa prędkość potworków spadła z `0.030` do `0.024`, przyrost
  na poziom z `0.0055` do `0.0042`, a fale są rzadsze (`3.8 s` zamiast `3.4 s`, minimum
  `1.4 s` zamiast `1.0 s`). Przy dłuższych hasłach potworek dochodził do działka, zanim
  dało się dokończyć pisanie.
- **Tempo jako ustawienie.** OPCJE → *Tempo*: `WOLNO` (×0,75), `NORMALNIE` (×1),
  `SZYBKO` (×1,3). Mnożnik skaluje całą krzywą prędkości, razem z przyrostem na poziom.
  Punktacja się nie zmienia — wynik z WOLNO liczy się tak samo.
- **Spacja była za nisko.** Dolny wiersz klawiatury na części telefonów leżał pod
  paskiem gestów. Dolny wiersz jest teraz wyższy (56 dp zamiast 48 dp), spacja szersza
  (waga 6,6 zamiast 5,5), a boczne wypełniacze zniknęły.
- **Jedno miejsce na chrom systemu.** `MaturaApp` woła `safeDrawingPadding()`, więc od
  korzenia w dół treść omija pasek stanu, pasek nawigacji, wycięcie w ekranie i otwartą
  klawiaturę systemową. Wcześniej odsuwała się tylko klawiatura gry — MENU, OPCJE, SETS
  i edytor kończyły się stałym odstępem i ostatni przycisk potrafił wpaść pod pasek
  gestów. Na Androidzie 15 zegarek systemowy leżał dodatkowo na sercach i liczniku
  amunicji.

- Demo w przeglądarce: **[kubakozub.github.io/Matura](https://kubakozub.github.io/Matura/)**
- Pełna dokumentacja projektowa: [`docs/Matura-dokumentacja.pdf`](docs/Matura-dokumentacja.pdf)

---

## Jak się gra

Na środku planszy stoi działko. Z boków nadchodzą potworki z angielskimi słowami nad głową.
Wpisujesz polskie tłumaczenie — działko obraca się i strzela. Potworek, który dojdzie do
działka, zabiera życie.

Wpisywanie działa po przedrostku: `cie` podświetla i `ciekawość`, i `cierpki`. Enter strzela,
jeśli tekst pasuje dokładnie; przy kilku pasujących celem zostaje ten najbliżej działka.

## Co jest zaimplementowane

**Rozgrywka (CLASSIC)** — punkty 1–6 i 9 dokumentu

| Element z dokumentu | Stan |
|---|---|
| Działko na środku z ruchomą lufą | jest — lufa celuje w potworka, którego aktualnie wpisujesz |
| Potworki wychodzące z boków ekranu | jest |
| Podświetlanie po prefiksie | jest, z testem odtwarzającym przykład z dokumentu |
| Pole tekstowe + ograniczona klawiatura (A–Z, spacja, backspace, kursor) | jest |
| Życia (domyślnie 3, konfigurowalne 1–5) | jest |
| Licznik amunicji, najsłabsza nieskończona | jest — `∞ / N` |
| Punkty (lewy dół) i poziom trudności (prawy dół) | jest |
| Skrzynie z amunicją na wyższych poziomach | jest — od poziomu 2, zbierasz wpisując tłumaczenie |
| Pancerz: pierwszy pocisk go zbija, potrzebne drugie tłumaczenie | jest — od poziomu 3 |
| Mocniejsze naboje zabijają opancerzonego jednym strzałem | jest — dobierane automatycznie z zapasu |
| Mnożniki x2 / x3 / x4 / x5 za serię | jest — progi na 3, 6, 9 i 12 trafieniach z rzędu |
| Rosnąca trudność: szybsze potworki, częstszy spawn | jest — nowy poziom co 8 zabić |

**Reszta aplikacji**

- Ekran startowy z „graj anonimowo" (punkt 7)
- Menu: PLAY / SETS / OPTIONS / ACHIEVEMENTS / SCORES / EXIT (punkt 8)
- SETS: lista zestawów, edytor w układzie Quizleta — numerowane wiersze, dwie kolumny z językami
  i przyciskiem zamiany stron (punkty 10–11). Własne zestawy kasujesz koszem: pierwsze tapnięcie
  uzbraja przycisk, drugie usuwa. Wbudowanych nie da się usunąć.
- OPTIONS: dźwięk, liczba żyć, tekstury, tempo potworków, język interfejsu (PL/EN),
  kierunek tłumaczenia (punkt 12)
- ACHIEVEMENTS: 8 odznak liczonych z Twoich statystyk
- SCORES: lokalny ranking, najlepsze przebiegi, najpopularniejszy zestaw (punkt 13)
- **102 zestawy, 2084 hasła** — słownictwo pod zakres tematyczny matury podstawowej, podzielone
  na 14 działów (Człowiek, Miejsce zamieszkania, Edukacja, Praca, Życie prywatne, Żywienie,
  Zakupy, Podróże, Kultura, Sport, Zdrowie, Nauka i technika, Przyroda, Życie społeczne).
  Każdy zestaw ma 20–25 haseł, żeby jedna rozgrywka pokazała każde słowo kilka razy.
- **Grafika pixel-art**: 97 sprite'ów skalowanych wyłącznie nearest-neighbour, w `drawable-nodpi`,
  żeby Android nie rozmywał ich już przy wczytywaniu
- **Font bitmapowy** `font_8x8` — cały interfejs poza dłuższą prozą rysowany glifami 5×7
  z arkusza, z konturem +1/+1; polskie znaki jako osobne glify, nie z kroju systemowego
- **Animacje**: dwuklatkowy chód co 200 ms, klatka zgonu przez 350 ms, błysk trafienia,
  baner nowego poziomu
- **Demo przeglądarkowe** — jeden plik HTML z tą samą mechaniką, do pokazania bez instalowania

## Czego nie ma i dlaczego

- **Konta, logowanie, zestawy innych użytkowników, globalne rankingi** — wymagają serwera.
  Aplikacja jest w pełni offline, wszystko leży w prywatnym pliku JSON na urządzeniu.
  „Play anonymously" to jedyna ścieżka.
- **TRUE/FALSE, MULTIPLE CHOICE, TIME ATTACK** — widoczne w menu wyboru trybu jako nieaktywne.
  Silnik jest pod nie przygotowany (dopasowywanie i model zestawu są wspólne), ale same tryby
  nie są napisane.
- **Automatyczne tłumaczenie przyciskiem „T"** — wymaga płatnego Google Translate API i klucza.
- **Dźwięk** — krótkie tony generowane na urządzeniu, bez plików audio.
- **Import z Quizleta w aplikacji** — demo przeglądarkowe przyjmuje wklejony eksport, aplikacja
  jeszcze nie.

## Jedna decyzja projektowa, która nie wynika wprost z dokumentu

Klawiatura z makiety ma tylko A–Z — nie ma polskich znaków. Bez zmiany reguł nie dałoby się
wpisać „zręczny", czyli odpowiedzi z Waszej własnej makiety. Dlatego dopasowanie odpowiedzi
ignoruje diakrytyki, wielkość liter i nadmiarowe spacje: `ZRECZNY`, `zreczny` i `zręczny` są
równoważne. Przecinek w kolumnie tłumaczeń rozdziela warianty — każdy jest zaliczany osobno,
a treść w nawiasie jest opcjonalna.

## Instalacja APK

APK jest podpisany kluczem debugowym, więc instaluje się bez Android Studio:

1. Przerzuć plik na telefon. Gmail odrzuca `.apk`, również w archiwum — użyj kabla, chmury
   albo pobierz plik bezpośrednio w przeglądarce telefonu.
2. Otwórz plik w menedżerze plików i zezwól mu na „instalowanie nieznanych aplikacji".
3. Zainstaluj.

Albo przez kabel: `adb install -r app-debug.apk`

Minimalny Android: 7.0 (API 24). Aplikacja jest w orientacji pionowej.

## Budowanie ze źródeł

```bash
echo "sdk.dir=/sciezka/do/Android/Sdk" > local.properties
./gradlew :app:assembleDebug          # APK w app/build/outputs/apk/debug/
./gradlew :app:bundleRelease          # AAB do Google Play w app/build/outputs/bundle/release/
./gradlew :app:testDebugUnitTest      # 54 testy jednostkowe
```

Bez `keystore.properties` paczka release wychodzi niepodpisana — podpis zakłada się
osobno, na maszynie, która ma klucz.

Android Studio: `File → Open` na katalogu repozytorium. Wymagany JDK 21 i Android SDK 36
(kod bajtowy powstaje pod Javę 17).

## Mapa kodu

```
app/src/main/java/com/matura/app/
  MainActivity.kt            punkt wejścia
  model/Models.kt            zestawy, opcje, profil, wyniki, odznaki
  data/Repository.kt         zapis do JSON
  data/BuiltInSets.kt        generowane zestawy słownictwa (nie edytuj ręcznie)
  game/Matching.kt           normalizacja i dopasowanie odpowiedzi
  game/GameEngine.kt         symulacja CLASSIC — czysty Kotlin, bez Androida
  ui/App.kt                  stos ekranów
  ui/PlayScreen.kt           plansza, rysowanie, pętla klatek
  ui/Keyboard.kt             ograniczona klawiatura z dokumentu
  ui/Screens.kt              menu, zestawy, edytor, opcje, odznaki, wyniki
  ui/Sprites.kt              rejestr sprite'ów, klatki animacji, kafelkowanie
  ui/PixelFont.kt            renderer fontu bitmapowego font_8x8
app/src/test/java/com/matura/app/
  EngineTest.kt              GameEngineTest (28) + MatchingTest (9)
  BuiltInSetsTest.kt         10 testów pilnujących reguł słownictwa
  PersistenceTest.kt         7 testów zapisu i odczytu
```

`GameEngine` celowo nie zna Androida ani Compose — dzięki temu cała mechanika jest testowana
zwykłym JUnitem, bez emulatora. Razem 54 testy.

## Publikacja

`docs/` jest źródłem dla GitHub Pages — w ustawieniach repozytorium: gałąź **master**,
folder **/docs**. APK celowo nie jest wersjonowany: jednorazowe wrzucenie go rozdmuchało
katalog `.git` z kilkuset kilobajtów do jedenastu megabajtów.

Dlatego APK wisi w **GitHub Releases**, nie w `docs/`. `docs/pobierz.html` linkuje do
`releases/latest/download/MaturaV080.apk`, więc przy każdym wydaniu plik musi nazywać się
dokładnie tak — inaczej przycisk pobierania zwróci 404. Do 0.7.0 link prowadził do pliku
w `docs/`, którego tam nie było, i pobieranie po prostu nie działało.

## Siostrzana aplikacja

**[Aleima](https://github.com/kubakozub/aleima)** — ta sama gra po angielsku, do nauki
hiszpańskiego, ze słownictwem podzielonym według poziomów CEFR.
