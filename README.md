# Matura — CLASSIC

[![Pobierz z Google Play](https://img.shields.io/badge/Google%20Play-pobierz-a8e05a?style=for-the-badge&logo=googleplay&logoColor=101322&labelColor=101322)](https://play.google.com/store/apps/details?id=com.matura.app&referrer=utm_source%3Dgithub%26utm_medium%3Dreadme%26utm_campaign%3Drepo)
[![Strona i demo w przeglądarce](https://img.shields.io/badge/matura.uk-demo%20w%20przegl%C4%85darce-101322?style=for-the-badge&labelColor=a8e05a)](https://matura.uk)

**Aplikacja jest w Google Play — darmowa, bez reklam i bez konta.** Grę można też przetestować bez instalacji w przeglądarce na [matura.uk](https://matura.uk).

[![Zwiastun gry MATURA — 32 s na YouTube](https://matura.uk/assets/zwiastun.527946e5.png)](https://www.youtube.com/watch?v=B8ScJBjyoMg)

*Zwiastun (32 s) — kliknij obrazek, żeby obejrzeć na YouTube.*

Gra do nauki angielskiego słownictwa, zbudowana wprost z dokumentu projektowego
*Verbume — Verbum + Game*. Android, Kotlin, Jetpack Compose. Tryb CLASSIC z pełną progresją.

**Wersja 0.9.18** · 102 zestawy · 2084 hasła · 14 działów matury podstawowej

> Aplikacja nazywała się wcześniej **Verbume**. Zmieniła się nazwa i identyfikator
> pakietu (`com.verbume.app` → `com.matura.app`); mechanika, słownictwo i grafika
> zostały bez zmian.

## Co nowego w 0.9.18

- **Cztery tryby gry.** Obok KLASYCZNEGO doszły PRAWDA/FAŁSZ, WIELOKROTNY WYBÓR i NA CZAS.
  Każdy respektuje życia, tempo, kierunek tłumaczenia, dźwięk i język; dystraktory są
  filtrowane przez `Matching.normalize`, więc nigdy nie ma dwóch poprawnych odpowiedzi.
  Tabela wyników jest osobna dla każdego trybu.
- **Codzienna sesja i powtórki rozłożone w czasie.** `learn/Srs.kt` prowadzi każde słowo
  przez pudełka powtórek, `learn/Sesja.kt` składa z nich dzienną trasę, a ekran DZISIAJ
  pokazuje, co jest do powtórki i kiedy wraca następna porcja. Postęp jest liczony per
  słowo i per kierunek, nie per zestaw.
- **Trudne słowa.** Osobny ekran z hasłami, które najczęściej wygrywają z uczniem
  (`learn/Trudne.kt`), z wymową i możliwością przerobienia samej tej listy.
- **Wymowa.** Nagrania odtwarzane z pakietu, bez sieci i bez uprawnienia INTERNET; dla
  haseł spoza pakietu — w tym własnych zestawów ucznia — wchodzi systemowy TextToSpeech.
- **Odpowiadanie głosem.** Przycisk mikrofonu jest zawsze widoczny, a droga głosu
  wybierana przy dotknięciu: silnik rozpoznawania, okno systemowe, a w ostateczności
  klawiatura telefonu z własnym dyktowaniem. Doszedł strażnik ciszy (głuchy mikrofon
  rozpoznawany po RMS) i strażnik martwego silnika, bo „gotowy, ale zero poziomu" to
  na części telefonów stan trwały.
- **Rozpoznawanie offline jako polisa.** Dla telefonów bez żadnej usługi rozpoznawania
  mowy aplikacja umie użyć modeli Vosk. Nie wchodzą do domyślnej paczki i nie leżą w tym
  repozytorium — patrz *Budowanie ze źródeł*.
- **Dane ucznia przestały ginąć.** Zapis idzie przez plik tymczasowy, `fsync` i zmianę
  nazwy, poprzednia kompletna wersja ląduje w kopii, a plik, którego nie da się odczytać,
  jest odkładany na bok zamiast nadpisywany. Nieudany zapis **mówi o sobie uczniowi**
  zamiast cicho cofać ekran.
- **Poprawki słownictwa docierają do telefonów, które już grały.** Każdy wpis ma trwały
  identyfikator liczony z treści, a wersjonowana migracja (`schemaVersion`) przeprowadza
  postęp pod nowy identyfikator po korekcie hasła. Bez tego literówka w haśle zostawała u
  ucznia na zawsze — a aplikacja do matury uczyła błędu.

## Co nowego w 0.9.0

- **Uklad na duze ekrany.** Android 16 ignoruje `android:screenOrientation="portrait"` na
  ekranach od 600 dp, wiec gra i tak trafi na tablety i skladane w poziomie. Wpis zniknal
  z manifestu, a `PlayScreen` przy oknie szerszym niz wyzszym przechodzi na uklad
  **dwukolumnowy**: plansza po lewej, pasek odpowiedzi i klawiatura po prawej. Ulozone
  jedno pod drugim, na telefonie 891×411 dp plansza schodzila do paska 13% wysokosci,
  a sama klawiatura zjadala ponad polowe ekranu.
- **Podsumowanie po grze na calym oknie.** Ekran KONIEC GRY rysuje sie na calym ekranie,
  nie na samej planszy — w poziomie plansza to polowa szerokosci i lista DO POWTORKI
  by sie w niej nie zmiescila.
- **Podglady ekranow renderowane na JVM.** `./gradlew :app:updateDebugScreenshotTest`
  rysuje szesc ukladow (telefon w pionie i poziomie, skladany, tablet, sama klawiatura)
  przez Layoutlib — bez emulatora i bez urzadzenia. To one pokazaly, ze poziom byl zepsuty.
  Obrazy laduja w `app/build/outputs/screenshotTest-results/` i **nie sa wersjonowane**:
  waza 1,5 MB i zmieniaja sie przy kazdej poprawce ukladu, a APK juz raz rozdal `.git`.

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
- OPTIONS: dźwięk, liczba żyć, tekstury, język interfejsu (PL/EN), kierunek tłumaczenia (punkt 12)
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

./gradlew :app:assembleDebug            # APK w app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest        # 483 testy
./gradlew :app:testReleaseUnitTest      # 439 z nich, w konfiguracji wydania
```

Android Studio: `File → Open` na katalogu repozytorium. Wymagany JDK 17 lub nowszy
i Android SDK 36 (kod bajtowy powstaje pod Javę 17).

**Dwa warianty testów uruchamiają różne zbiory, i to jest zamierzone.** Testy Compose
i Robolectrica leżą w `app/src/testDebug/`, bo manifest testowy deklarujący
`ComponentActivity` (`androidx.compose.ui:ui-test-manifest`) wchodzi tylko do wariantu
debug. Wariant release składa się z inną scaloną konfiguracją, więc zielony debug nie
dowodzi, że release się buduje — dlatego CI uruchamia oba.

### Rozpoznawanie offline (opcjonalne)

Modeli Vosk **nie ma w repozytorium**: to 107 MB cudzych wag, dziesięć razy więcej niż
cały kod i cała grafika razem. Zamiast ich kopii repozytorium trzyma przepis — adres
paczki upstreamu, jej skrót SHA-256 i skróty 26 plików, które z niej wychodzą:

```bash
./tools/vosk-fetch.sh                      # pobiera, odchudza, sprawdza skróty
./gradlew :app:assembleDebug -Pvosk=true   # paczka z rozpoznawaniem offline
```

Wynik jest **identyczny bajt w bajt** z tym, co było wcześniej wersjonowane — skrypt to
sprawdza i przerywa, gdy cokolwiek się nie zgadza. Bez tego kroku aplikacja buduje się
normalnie: kod Voska sam zgłasza, że nie ma czym rozpoznawać, a głos idzie przez silniki
systemowe.

## Mapa kodu

```
app/src/main/java/com/matura/app/
  MainActivity.kt            punkt wejścia
  model/Models.kt            zestawy, opcje, profil, wyniki, odznaki, postęp per słowo
  data/Repository.kt         zapis do JSON — atomowo, z kopią i kwarantanną
  data/BuiltInSets.kt        generowane zestawy słownictwa (nie edytuj ręcznie)
  game/Matching.kt           normalizacja i dopasowanie odpowiedzi
  game/GameEngine.kt         symulacja CLASSIC — czysty Kotlin, bez Androida
  game/VoiceMatch.kt         dopasowanie tego, co usłyszał mikrofon
  learn/Srs.kt               pudełka powtórek i terminy
  learn/Sesja.kt             dzienna trasa nauki
  learn/Trudne.kt            dobór słów, które wygrywają z uczniem
  learn/EntryId.kt           trwała tożsamość wpisu liczona z treści
  learn/Podsumowanie.kt      liczby po rundzie (NIEPODŁĄCZONE — patrz niżej)
  audio/Pronunciation.kt     nagrania z pakietu, TTS jako zapas
  ui/App.kt                  stos ekranów
  ui/PlayScreen.kt           plansza, rysowanie, pętla klatek
  ui/TrueFalseScreen.kt      ·  ui/MultipleChoiceScreen.kt  ·  ui/TimeAttackScreen.kt
  ui/DzisiejszaSesjaScreen.kt  ·  ui/TrudneSlowaScreen.kt
  ui/VoiceInput.kt           drabinka silników mowy i strażnicy
  ui/VoicePolicy.kt          która droga głosu — czysta logika, bez Androida
  ui/VoiceTest.kt            ·  ui/RawMicTest.kt      diagnostyka ścieżki głosu
  ui/Keyboard.kt             ograniczona klawiatura z dokumentu
  ui/Screens.kt              menu, zestawy, edytor, opcje, odznaki, wyniki
  ui/Common.kt               wspólne elementy (ScreenScaffold, InfoCard, przyciski)
  ui/Sprites.kt              rejestr sprite'ów, klatki animacji, kafelkowanie
  ui/PixelFont.kt            renderer fontu bitmapowego font_8x8
app/src/vosk/java/com/matura/app/ui/      kod wchodzący tylko z -Pvosk=true
app/src/test/java/        439 testów, oba warianty — logika, zapis, migracje, głos
app/src/testDebug/java/    44 testy Compose i Robolectrica (patrz wyżej)
tools/vosk-fetch.sh       odtworzenie modeli offline z paczek upstreamu
tools/vosk-slim.py        odchudzanie modelu (dlaczego i jak — w nagłówku pliku)
```

`GameEngine`, `Srs`, `Sesja`, `Trudne`, `Matching` i `VoicePolicy` celowo nie znają
Androida ani Compose — dzięki temu mechanika i logika decyzji są testowane zwykłym
JUnitem, bez emulatora.

**Niepodłączone:** `learn/Podsumowanie.kt` i `ui/PodsumowanieRundy.kt` to gotowy,
udokumentowany i przetestowany blok „co ta runda zrobiła dla nauki", który nie ma w
aplikacji ani jednego wywołania. `Podsumowanie.rundy()` woła dziś wyłącznie
`PodsumowanieTest`. Do podłączenia zostaje postawienie komponentu na ekranie końca gry.

## Publikacja

`docs/` jest źródłem dla GitHub Pages — w ustawieniach repozytorium: gałąź **master**,
folder **/docs**. APK celowo nie jest wersjonowany: jednorazowe wrzucenie go rozdmuchało
katalog `.git` z kilkuset kilobajtów do jedenastu megabajtów.

## Siostrzana aplikacja

**[Aleima](https://github.com/kubakozub/aleima)** — ta sama gra po angielsku, do nauki
hiszpańskiego, ze słownictwem podzielonym według poziomów CEFR.

## Licencja i noty

Składniki innych autorów — modele Vosk, biblioteki AndroidX, Kotlin, Compose — są
wymienione w [`NOTICE`](NOTICE) razem z ich licencjami i z opisem zmian, które
`tools/vosk-slim.py` wprowadza w modelach (wymaga tego Apache License 2.0).
Grafika, font bitmapowy i słownictwo są własną pracą autora; zasady palety, siatki
i nazw opisuje [`docs/pack/README.md`](docs/pack/README.md).
