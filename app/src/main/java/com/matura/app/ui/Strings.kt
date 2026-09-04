package com.matura.app.ui

import com.matura.app.model.UiLanguage

/**
 * Tiny two-language string table. OPTIONS offers "change language" in the design
 * document, and the app itself is bilingual PL/EN.
 */
class Str(private val lang: UiLanguage) {
    private fun pick(pl: String, en: String) = if (lang == UiLanguage.PL) pl else en

    val appTagline get() = pick("Verbum + Game", "Verbum + Game")
    val playAnonymously get() = pick("GRAJ ANONIMOWO", "PLAY ANONYMOUSLY")
    val offlineNote
        get() = pick(
            "Bez kont i bez logowania — wpisz nazwę i graj. Wyniki zapisują się lokalnie na telefonie.",
            "No accounts, no sign-in — pick a name and play. Scores are stored locally on your device."
        )
    val modeClassic get() = pick("KLASYCZNY", "CLASSIC")
    val modeTrueFalse get() = pick("PRAWDA / FAŁSZ", "TRUE / FALSE")
    val modeMultiple get() = pick("WIELOKROTNY WYBÓR", "MULTIPLE CHOICE")
    val modeTimeAttack get() = pick("NA CZAS", "TIME ATTACK")
    val modeTrueFalseDesc get() = pick("Oceń, czy tłumaczenie pasuje", "Decide if the translation fits")
    val modeMultipleDesc get() = pick("Wybierz poprawne z czterech", "Pick the right one of four")
    val modeTimeAttackDesc get() = pick("Ile zdążysz, zanim czas minie", "How many before the clock runs out")
    val filterAll get() = pick("WSZYSTKIE", "ALL")
    val yourName get() = pick("Twoja nazwa", "Your name")

    val play get() = pick("GRAJ", "PLAY")
    val sets get() = pick("ZESTAWY", "SETS")
    val privacy get() = pick("Prywatność", "Privacy")
    val privacyOpen get() = pick("Otwórz pełną wersję na matura.uk", "Open the full version on matura.uk")
    val privacyShow get() = pick("POKAŻ", "SHOW")
    val privacyBody get() = pick(PRIVACY_PL, PRIVACY_EN)
    val options get() = pick("OPCJE", "OPTIONS")
    val achievements get() = pick("OSIĄGNIĘCIA", "ACHIEVEMENTS")
    val scores get() = pick("WYNIKI", "SCORES")
    val exit get() = pick("WYJŚCIE", "EXIT")
    val back get() = pick("Wróć", "Back")

    val chooseSet get() = pick("Wybierz zestaw", "Choose a set")
    val chooseTopic get() = pick("Wybierz temat", "Choose a topic")
    val randomMix get() = pick("LOSOWANIE", "RANDOM MIX")
    val randomMixDesc
        get() = pick("40 słówek ze wszystkich działów · za każdym razem inne",
            "40 words from every topic · a fresh draw each time")
    val speakAnswer get() = pick("MÓW ODPOWIEDŹ", "SPEAK THE ANSWER")
    val listeningNow get() = pick("SŁUCHAM…", "LISTENING…")
    val micDenied get() = pick("Bez zgody na mikrofon tryb głosowy nie zadziała.",
        "Voice mode needs microphone access.")
    // Podpisy pod paskiem glosu, gdy nie ma silnika do zwiazania: ktora droga pojdzie glos.
    // Krotkie — mieszcza sie w jednej linii 12 sp.
    val voiceDialog get() = pick("okno systemowe", "system dialog")
    val voiceKeyboardHint get() = pick("głos przez klawiaturę telefonu", "voice via phone keyboard")
    // Komunikaty w linii pod przyciskiem: JEDNA linia (pasek ma stala wysokosc, pod nim jest
    // klawiatura gry), wiec do ~45 znakow — tyle miesci sie w 12 sp na ekranie 360 dp.
    val voiceKeyboardAuto get() = pick(
        "Głos: użyj mikrofonu klawiatury telefonu",
        "Voice: use the mic key on your keyboard")
    val micDeniedSettings get() = pick(
        "Mikrofon zablokowany — włącz w ustawieniach",
        "Mic blocked — allow it in app settings")
    val voiceOtherEngine get() = pick("próbuję innego silnika mowy…", "trying another speech engine…")
    val voiceNoSpeech get() = pick("nie rozpoznaję mowy — wpisz odpowiedź", "speech not recognised — type your answer")
    val micSilentHint get() = pick(
        "Mikrofon nic nie słyszy — wyłączony lub zajęty?",
        "Mic hears nothing — muted or in use elsewhere?")
    // Okno systemowe zamkniete bez odpowiedzi; kod wyniku ekran dokleja w nawiasie.
    val voiceDialogNoResult get() = pick("Okno zamknięte bez odpowiedzi", "Dialog closed without an answer")
    // Naglowek nakladki z diagnostyka glosu (czcionka pikselowa — bez polskich znakow).
    val voiceDiagnostics get() = pick("DIAGNOSTYKA MIKROFONU", "MICROPHONE DIAGNOSTICS")
    // Skad wziac pelny test silnikow — diagnostyka pokazuje JEDEN przebieg drabinki,
    // ekran testu przechodzi cala matryce konfiguracji po kolei.
    val voiceTestWhere get() = pick("Pełny test silników: OPCJE → Test mowy", "Full engine test: OPTIONS → Speech test")

    // --- EKRAN TESTU MOWY ---
    // Nazwy konfiguracji w wynikach sa techniczne i te same w obu jezykach (trafiaja do
    // raportu dla programisty); tlumaczymy tylko to, co czyta uzytkownik.
    val voiceTest get() = pick("Test mowy", "Speech test")
    val voiceTestOpen get() = pick("URUCHOM", "OPEN")
    val voiceTestIntro get() = pick(
        "Powiedz wyraźnie: KOLANO — test sprawdzi po kolei wszystkie silniki mowy w tym telefonie.",
        "Say clearly: KOLANO — the test checks every speech engine in this phone, one by one.")
    val voiceTestRepeat get() = pick(
        "Powtarzaj to słowo, aż test się skończy. Każda próba trwa najwyżej 8 sekund, więc całość to około minuty.",
        "Keep repeating the word until the test ends. Each attempt takes at most 8 seconds, so the whole run is about a minute.")
    val voiceTestStart get() = pick("START", "START")
    val voiceTestStop get() = pick("PRZERWIJ", "STOP")
    val voiceTestAgain get() = pick("JESZCZE RAZ", "RUN AGAIN")
    val voiceTestProgress get() = pick("Próba", "Attempt")
    val voiceTestCopy get() = pick("KOPIUJ RAPORT", "COPY REPORT")
    val voiceTestCopied get() = pick(
        "Raport skopiowany — wklej go w rozmowie.",
        "Report copied — paste it in the chat.")
    val voiceTestNoMic get() = pick(
        "Bez zgody na mikrofon test nie ma czego sprawdzić. Włącz mikrofon w ustawieniach aplikacji i uruchom go jeszcze raz.",
        "Without microphone access there is nothing to test. Allow the microphone in app settings and run it again.")
    val voiceTestSettings get() = pick("USTAWIENIA APLIKACJI", "APP SETTINGS")
    val voiceTestNothing get() = pick(
        "Ten telefon nie ma ani jednego silnika mowy do sprawdzenia — zostaje klawiatura telefonu z mikrofonem.",
        "This phone has no speech engine to test — the phone keyboard microphone is the way to go.")
    val voiceTestWorksWith get() = pick("Działa konfiguracja: ", "Working configuration: ")
    /**
     * Przebieg, ktory nie objal calej matrycy. Zdanie MUSI byc inne niz [voiceTestNone]: raport
     * z trzech prob nie ma prawa orzekac o telefonie, a przerwanie (zgaszony ekran, PRZERWIJ,
     * przejscie do innej aplikacji) nie jest przypadkiem brzegowym przy minutowym tescie.
     */
    fun voiceTestInterrupted(wykonane: Int, ile: Int) = pick(
        "PRZEBIEG PRZERWANY po " + wykonane + " z " + ile +
            " prób — wynik jest niepełny i nic nie mówi o tym telefonie. Uruchom test jeszcze raz.",
        "RUN INTERRUPTED after " + wykonane + " of " + ile +
            " attempts — the result is incomplete and says nothing about this phone. Run the test again.")
    val voiceTestTextNote get() = pick(
        "Raport zawiera to, co usłyszał silnik — jeśli trafiło tam coś prywatnego, usuń to przed wklejeniem.",
        "The report contains what the engine heard — if anything private landed there, delete it before pasting.")
    val voiceTestNone get() = pick(
        "Żadna konfiguracja nie rozpoznała słowa — na tym telefonie rozpoznawanie mowy w aplikacji nie działa.",
        "No configuration recognised the word — in-app speech recognition does not work on this phone.")
    val voiceTestMicHeard get() = pick(
        "Mikrofon działa (silniki słyszały dźwięk), więc brakuje modelu polskiego — sprawdź pakiety języka w aplikacji Google.",
        "The microphone works (engines heard sound), so the Polish model is missing — check the language packs in the Google app.")
    val voiceTestMicSilent get() = pick(
        "Mikrofon nie dał żadnego sygnału — sprawdź przełącznik prywatności mikrofonu i czy nie zajmuje go inna aplikacja.",
        "The microphone gave no signal at all — check the mic privacy toggle and whether another app is holding it.")
    // TRZECI stan, bez ktorego dwa poprzednie klamia: wywolanie onRmsChanged nie jest obowiazkowe
    // i czesc uslug wiazanych po ComponentName go nie implementuje. Wtedy KAZDA proba ma "brak
    // pomiaru" — a to nie jest to samo co "mikrofon milczal" i prowadzi do zupelnie innej naprawy.
    val voiceTestMicUnknown get() = pick(
        "Żaden silnik nie podał poziomu mikrofonu — z tego przebiegu nie wynika, czy mikrofon słyszał.",
        "No engine reported a microphone level — this run does not say whether the mic heard anything.")
    // Etykiety pojedynczej proby (VoiceTestTeksty).
    val voiceTestWorks get() = pick("DZIAŁA", "WORKS")
    val voiceTestSilence get() = pick("cisza (silnik nic nie zwrócił)", "silence (engine returned nothing)")
    val voiceTestError get() = pick("błąd", "error")
    val voiceTestNoEngine get() = pick("nie udało się utworzyć rozpoznawacza", "could not create the recognizer")
    val voiceTestReady get() = pick("gotowość", "ready")
    val voiceTestNoReady get() = pick("bez gotowości", "no ready signal")
    val voiceTestPartial get() = pick("wyniki częściowe", "partial results")
    val speakNow get() = pick("Powiedz odpowiedź", "Say the answer")
    val voicePaused get() = pick("Gra wstrzymana — wznów, aby mówić", "Game paused — resume to speak")
    val voiceOnDevice get() = pick("na urządzeniu", "on-device")
    val voiceOnline get() = pick("online", "online")
    val speakPl get() = pick("mów po polsku", "answer in Polish")
    val speakEn get() = pick("mów po angielsku", "answer in English")
    val ownSets get() = pick("Moje zestawy", "My own sets")
    val setsShort get() = pick("zestawy", "sets")
    val chooseMode get() = pick("Wybierz tryb", "Choose a mode")
    val classic get() = pick("CLASSIC", "CLASSIC")
    val classicDesc
        get() = pick(
            "Wpisuj tłumaczenia, zanim potworki dojdą do działka.",
            "Type translations before the monsters reach the cannon."
        )
    val createSet get() = pick("Utwórz zestaw", "Create a set")
    val editSet get() = pick("Edytuj zestaw", "Edit set")
    val setTitle get() = pick("Tytuł zestawu", "Set title")
    val terms get() = pick("Hasła", "Terms")
    val definitions get() = pick("Tłumaczenia", "Definitions")
    val addRow get() = pick("Dodaj wiersz", "Add row")
    val save get() = pick("Zapisz", "Save")
    val delete get() = pick("Usuń", "Delete")
    val confirmDelete get() = pick("Na pewno?", "Sure?")
    val deleteHint
        get() = pick(
            "Wbudowanych zestawów nie da się usunąć. Własne kasujesz koszem — dwa razy, żeby nie zrobić tego przypadkiem.",
            "Built-in sets cannot be removed. Your own go with the bin — twice, so it does not happen by accident."
        )
    val swapLanguages get() = pick("Zamień strony", "Swap sides")
    val builtIn get() = pick("wbudowany", "built-in")
    val entriesLabel get() = pick("haseł", "entries")
    val playedLabel get() = pick("rozegrań", "plays")
    val commaHint
        get() = pick(
            "Kilka tłumaczeń rozdziel przecinkiem — każde będzie zaliczone.",
            "Separate alternative translations with commas — each one is accepted."
        )
    val needTwoEntries
        get() = pick(
            "Zestaw musi mieć przynajmniej jedno wypełnione hasło z tłumaczeniem.",
            "A set needs at least one row with both sides filled in."
        )

    val sound get() = pick("Dźwięk", "Sound")
    val lives get() = pick("Życia", "Lives")
    val texture get() = pick("Tekstury", "Textures")
    val systemKeyboard get() = pick("Klawiatura telefonu", "Phone keyboard")
    val systemKeyboardHint get() = pick(
        "Zamiast klawiatury z gry — z mikrofonem do dyktowania",
        "Use your phone's keyboard — it has a microphone")
    val speed get() = pick("Tempo", "Speed")
    val speedSlow get() = pick("WOLNO", "SLOW")
    val speedNormal get() = pick("NORMALNIE", "NORMAL")
    val speedFast get() = pick("SZYBKO", "FAST")
    val speedNote
        get() = pick(
            "Tempo ustawia szybkość potworków. Na WOLNO masz więcej czasu na wpisanie " +
                "dłuższych haseł — punkty liczą się tak samo.",
            "Speed sets how fast monsters walk. On SLOW you get more time for longer " +
                "answers — scoring is unchanged."
        )
    val language get() = pick("Język", "Language")
    val direction get() = pick("Kierunek tłumaczenia", "Translation direction")
    val dirTermToDef get() = pick("hasło → tłumaczenie", "term → definition")
    val dirDefToTerm get() = pick("tłumaczenie → hasło", "definition → term")
    val dirRandom get() = pick("losowo", "random")
    val soundNote
        get() = pick(
            "Dźwięk gra prostymi tonami generowanymi na urządzeniu — nie ma plików audio.",
            "Sound uses simple tones generated on the device — there are no audio assets."
        )

    val score get() = pick("Punkty", "Score")
    val level get() = pick("Poziom", "Level")
    val gameOver get() = pick("KONIEC GRY", "GAME OVER")
    val playAgain get() = pick("Jeszcze raz", "Play again")
    val toMenu get() = pick("Do menu", "To menu")
    val pause get() = pick("Pauza", "Pause")
    val resume get() = pick("Wróć do gry", "Resume")
    val killsLabel get() = pick("Pokonane", "Defeated")
    val bestStreakLabel get() = pick("Najdłuższa seria", "Best streak")
    val noScores get() = pick("Brak wyników — zagraj pierwszą grę.", "No scores yet — play your first game.")
    val gamesPlayed get() = pick("Rozegrane gry", "Games played")
    val totalKills get() = pick("Łącznie pokonanych", "Total defeated")
    val bestScore get() = pick("Najlepszy wynik", "Best score")
    val unlocked get() = pick("zdobyte", "unlocked")
    val locked get() = pick("zablokowane", "locked")
    val emptySetWarning
        get() = pick(
            "Ten zestaw nie ma hasła z tłumaczeniem — uzupełnij go w SETS.",
            "This set has no usable rows — fill it in under SETS."
        )
    val topSet get() = pick("Najpopularniejszy zestaw", "Most played set")
    val bestRuns get() = pick("Najlepsze przebiegi", "Best runs")
}


/**
 * Polityka prywatnosci wbudowana w aplikacje. Google Play wymaga, zeby byla dostepna
 * NIE tylko jako link w Konsoli, ale takze w samej aplikacji — a ze aplikacja nie ma
 * uprawnienia do internetu, tekst musi byc lokalny. Pelna wersja (i data aktualizacji)
 * zyje pod https://matura.uk/polityka-prywatnosci i otwiera ja przegladarka.
 */
private val PRIVACY_PL = """
MATURA — słówka angielskie · polityka prywatności
Ostatnia aktualizacja: 4 września 2026

W SKRÓCIE
Gra działa na Twoim urządzeniu. Nie ma kont, logowania, reklam ani śledzenia. Aplikacja
nie ma uprawnienia dostępu do internetu, więc sama nie może niczego wysłać.

CO ZAPISUJEMY (tylko na urządzeniu)
• najlepsze wyniki i statystyki gry,
• ustawienia (życia, tempo, kierunek tłumaczenia, język, dźwięk, klawiatura),
• zestawy słówek, które sam(a) dodasz.
Te dane leżą w pamięci aplikacji i znikają po jej odinstalowaniu. Nie mamy do nich dostępu.

MIKROFON I TRYB MÓWIENIA
Tryb mówienia jest opcjonalny — możesz grać, wpisując odpowiedzi z klawiatury. Jeśli go
włączysz, aplikacja poprosi o zgodę na mikrofon (RECORD_AUDIO).
• Nie nagrywamy dźwięku i nie zapisujemy plików audio.
• Wypowiedź zamienia na tekst usługa rozpoznawania mowy zainstalowana w Twoim telefonie
  (najczęściej Google, u niektórych producentów własna). Aplikacja dostaje sam tekst.
• Najpierw prosimy o rozpoznawanie bez internetu. Gdy silnik telefonu tego odmówi,
  rozpoznanie może zostać wykonane w usłudze sieciowej producenta — wtedy wypowiedź
  trafia do niego i podlega JEGO polityce prywatności.
• To samo dotyczy systemowego okna dyktowania i mikrofonu na klawiaturze telefonu.
• Zgodę na mikrofon cofniesz w Ustawieniach systemu → Aplikacje → MATURA → Uprawnienia.

CZEGO NIE ZBIERAMY
Imienia, adresu e-mail, lokalizacji, kontaktów, zdjęć, identyfikatorów reklamowych.
Brak reklam, analityki i bibliotek śledzących.

KONTAKT
kuba1k@gmail.com · https://matura.uk/polityka-prywatnosci
""".trimIndent()

private val PRIVACY_EN = """
MATURA — English vocabulary · privacy policy
Last updated: 4 September 2026

IN SHORT
The game runs on your device. No accounts, no sign-in, no ads, no tracking. The app has no
internet permission, so it cannot send anything anywhere by itself.

WHAT IS STORED (on the device only)
• best scores and game statistics,
• settings (lives, speed, translation direction, language, sound, keyboard),
• word sets you add yourself.
This data lives in the app storage and is removed when you uninstall the app.

MICROPHONE AND SPEAKING MODE
Speaking mode is optional — you can always type your answers. If you turn it on, the app
asks for the microphone permission (RECORD_AUDIO).
• We do not record or store any audio.
• Your speech is turned into text by the speech recognition service installed on your phone
  (usually Google, sometimes the manufacturer's own). The app only receives the text.
• We first ask for on-device recognition. If the engine refuses, recognition may happen in
  the provider's online service — your speech then goes to them under THEIR privacy policy.
• The same applies to the system dictation dialog and to your keyboard's microphone key.
• You can revoke the permission in System settings → Apps → MATURA → Permissions.

WHAT WE DO NOT COLLECT
Name, e-mail, location, contacts, photos, advertising identifiers. No ads, no analytics,
no tracking libraries.

CONTACT
kuba1k@gmail.com · https://matura.uk/polityka-prywatnosci
""".trimIndent()
