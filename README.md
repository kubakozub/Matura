# Verbume — CLASSIC

Gra do nauki słówek zbudowana wprost z dokumentu projektowego *Verbume — Verbum + Game*.
Android, Kotlin, Jetpack Compose. Tryb CLASSIC z pełną progresją.

---

## Co jest zaimplementowane

**Rozgrywka (CLASSIC)** — punkty 1–6 i 9 dokumentu

| Element z dokumentu | Stan |
|---|---|
| Działko na środku z ruchomą lufą | jest — lufa celuje w potworka, którego aktualnie wpisujesz |
| Potworki wychodzące z boków ekranu | jest |
| Podświetlanie po prefiksie (`cie` → `ciekawość` **i** `cierpki`) | jest, z testem odtwarzającym przykład z dokumentu |
| Pole tekstowe + ograniczona klawiatura (A–Z, spacja, backspace, kursor) | jest |
| Życia (domyślnie 3, konfigurowalne) | jest |
| Licznik amunicji, najsłabsza nieskończona | jest — `∞ / N` |
| Punkty (lewy dół) i poziom trudności (prawy dół) | jest |
| Skrzynie z amunicją na wyższych poziomach | jest — od poziomu 2, zbierasz wpisując tłumaczenie |
| Pancerz: pierwszy pocisk go zbija, potrzebne drugie tłumaczenie | jest — od poziomu 3 |
| Mocniejsze naboje zabijają opancerzonego jednym strzałem | jest — dobierane automatycznie, gdy masz je w zapasie |
| Mnożniki x2 / x3 / x4 / x5 za serię | jest — progi na 3, 6, 9 i 12 trafieniach z rzędu |
| Rosnąca trudność: szybsze potworki, częstszy spawn | jest — nowy poziom co 8 zabić |

**Reszta aplikacji**

- Ekran startowy z „graj anonimowo" (punkt 7)
- Menu: PLAY / SETS / OPTIONS / ACHIEVEMENTS / SCORES / EXIT (punkt 8)
- SETS: lista zestawów, edytor w układzie Quizleta — numerowane wiersze, dwie kolumny z językami i przyciskiem zamiany stron (punkty 10–11)
- OPTIONS: dźwięk, liczba żyć, tekstury, język interfejsu (PL/EN), kierunek tłumaczenia (punkt 12)
- ACHIEVEMENTS: 8 odznak liczonych z Twoich statystyk
- SCORES: lokalny ranking, najlepsze przebiegi, najpopularniejszy zestaw (punkt 13)
- **66 wbudowanych zestawów, 1695 haseł** — słownictwo pod zakres tematyczny matury podstawowej, podzielone na 14 działów (Człowiek, Miejsce zamieszkania, Edukacja, Praca, Życie prywatne, Żywienie, Zakupy, Podróże, Kultura, Sport, Zdrowie, Nauka i technika, Przyroda, Życie społeczne)
- **Grafika pixel-art** z paczki `Pakiet grafiki pixel-art do gry`: 96 sprite'ów 16×16 skalowanych wyłącznie nearest-neighbour, paleta ośmiu kolorów z konturem `#101322`
- **Font bitmapowy** `font_8x8` — cały interfejs poza dłuższą prozą rysowany glifami 5×7 z arkusza, z konturem +1/+1 zgodnie z `assets/font-layout.md`; obsługa polskich znaków przez mapę glifów, nie przez systemowy krój
- **Animacje**: dwuklatkowy chód potworków co 200 ms, klatka zgonu przez 350 ms, błysk trafienia, baner nowego poziomu
- Trzy warianty kafla na motyw, mieszane deterministycznie — ten sam kafel zawsze w tym samym miejscu, więc tło nie migocze
- **Demo przeglądarkowe** — jeden plik HTML z tą samą mechaniką, do pokazania bez instalowania czegokolwiek

## Czego nie ma i dlaczego

- **Konta, logowanie, zestawy innych użytkowników, globalne rankingi** — wymagają serwera. Aplikacja jest w pełni offline, wszystko leży w prywatnym pliku JSON na urządzeniu. „Play anonymously" to jedyna ścieżka.
- **TRUE/FALSE, MULTIPLE CHOICE, TIME ATTACK** — widoczne w menu wyboru trybu jako nieaktywne. Silnik jest pod nie przygotowany (dopasowywanie i model zestawu są wspólne), ale same tryby nie są napisane.
- **Automatyczne tłumaczenie przyciskiem „T"** — wymaga płatnego Google Translate API i klucza.
- **Grafika i dźwięk** — potworki, działko i pociski są rysowane wektorowo na Canvasie według makiet z dokumentu, nie ma plików graficznych. Dźwięk to krótkie tony systemowe.

## Jedna decyzja projektowa, która nie wynika wprost z dokumentu

Klawiatura z makiety ma tylko A–Z — nie ma polskich znaków. Bez zmiany reguł nie dałoby się
wpisać „zręczny", czyli odpowiedzi z Waszej własnej makiety. Dlatego dopasowanie odpowiedzi
ignoruje diakrytyki, wielkość liter i nadmiarowe spacje: `ZRECZNY`, `zreczny` i `zręczny` są
równoważne. Przecinek w kolumnie tłumaczeń rozdziela warianty — każdy jest zaliczany osobno,
a treść w nawiasie jest opcjonalna.

## Instalacja APK

`app-debug.apk` jest podpisany kluczem debugowym, więc instaluje się bez Android Studio:

1. Przerzuć plik na telefon.
2. Włącz „instalowanie z nieznanych źródeł" dla aplikacji, z której otwierasz plik.
3. Otwórz APK i zainstaluj.

Albo przez kabel: `adb install -r app-debug.apk`

Minimalny Android: 7.0 (API 24). Aplikacja jest w orientacji pionowej.

## Budowanie ze źródeł

```bash
unzip verbume-src.zip && cd verbume
./gradlew :app:assembleDebug          # APK w app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest      # 34 testy jednostkowe
```

Android Studio: `File → Open` na katalogu `verbume`. `local.properties` wygeneruje się samo.
Wymagany JDK 17+ i Android SDK 35.

## Mapa kodu

```
app/src/main/java/com/verbume/app/
  MainActivity.kt            punkt wejścia
  model/Models.kt            zestawy, opcje, profil, wyniki, odznaki
  data/Repository.kt         zapis do JSON + wbudowane zestawy
  game/Matching.kt           normalizacja i dopasowanie odpowiedzi
  game/GameEngine.kt         symulacja CLASSIC — czysty Kotlin, bez Androida
  ui/App.kt                  nawigacja
  ui/PlayScreen.kt           plansza, rysowanie, pętla klatek
  ui/Keyboard.kt             ograniczona klawiatura z dokumentu
  ui/Screens.kt              menu, zestawy, edytor, opcje, odznaki, wyniki
app/src/test/java/com/verbume/app/
  EngineTest.kt              28 testów mechaniki
  PersistenceTest.kt         6 testów zapisu i zestawów startowych
```

`GameEngine` celowo nie zna Androida ani Compose — dzięki temu cała mechanika jest
testowana zwykłym JUnitem, bez emulatora.
