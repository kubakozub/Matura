package com.matura.app.ui

import com.matura.app.learn.FormyLiczby
import com.matura.app.learn.Podsumowanie
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
    val yourName get() = pick("Nazwa gracza", "Your name")
    /** Podpowiedz w polu nazwy i nazwa zapisywana, gdy uczen zostawi pole puste. */
    val defaultPlayerName get() = pick("Gracz", "Player")

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
    val chooseTopic get() = pick("Wybierz dział", "Choose a topic")
    val randomMix get() = pick("LOSOWY MIKS", "RANDOM MIX")
    val randomMixDesc
        get() = pick("40 słówek ze wszystkich działów · za każdym razem inne",
            "40 words from every topic · a fresh draw each time")
    val speakAnswer get() = pick("POWIEDZ ODPOWIEDŹ", "SPEAK THE ANSWER")
    val listeningNow get() = pick("SŁUCHAM…", "LISTENING…")
    val micDenied get() = pick("Bez zgody na mikrofon tryb głosowy nie zadziała.",
        "Voice mode needs microphone access.")
    // Podpisy pod paskiem glosu, gdy nie ma silnika do zwiazania: ktora droga pojdzie glos.
    // Krotkie — mieszcza sie w jednej linii 12 sp.
    val voiceDialog get() = pick("głos przez okno systemowe", "voice via system dialog")
    val voiceKeyboardHint get() = pick("głos przez klawiaturę telefonu", "voice via phone keyboard")
    // Komunikaty w linii pod przyciskiem: JEDNA linia (pasek ma stala wysokosc, pod nim jest
    // klawiatura gry), wiec do ~45 znakow — tyle miesci sie w 12 sp na ekranie 360 dp.
    val voiceKeyboardAuto get() = pick(
        "Głos: użyj mikrofonu klawiatury telefonu",
        "Voice: use the mic key on your keyboard")
    val micDeniedSettings get() = pick(
        "Mikrofon zablokowany — włącz w ustawieniach",
        "Mic blocked — allow it in app settings")
    /** Silnik zglosil brak zgody (kod w nawiasie) — podstawiane do VoiceInput jak reszta komunikatow. */
    val voiceNoPermission get() = pick("Brak zgody na mikrofon (%s)", "No microphone permission (%s)")
    val voiceOtherEngine get() = pick("próbuję innego silnika mowy…", "trying another speech engine…")
    val voiceNoSpeech get() = pick("Nie rozpoznaję mowy — wpisz odpowiedź", "Speech not recognised — type your answer")

    // KOMUNIKATY O AWARII GLOSU. Trafiaja do VoiceInput przez pola (patrz LaunchedEffect w PlayScreen),
    // bo warstwa mowy nie ma dostepu do Str — jest tworzona poza kompozycja i zyje dluzej niz ekran.
    // Wzorce "%s" wypelnia VoiceInput: nazwa jezyka albo kod bledu i liczba silnikow.
    // Komunikaty z poleceniem ("— wpisz odpowiedz") zaczynaja sie wielka litera jak reszta instrukcji
    // pod paskiem ("Mikrofon zawiodl — wpisz odpowiedz"); mala litera zostaje dla stanow ("slucham").
    val voiceNoNetwork get() = pick(
        "Brak internetu dla głosu — wpisz odpowiedź",
        "No internet for speech — type your answer",
    )
    /** "%s" to nazwa jezyka ([voiceLangPolish] / [voiceLangEnglish]); po dwukropku, zeby zmiescic sie w linii. */
    val voiceNoLanguagePack get() = pick(
        "Brak pakietu mowy: %s — wpisz odpowiedź",
        "No %s speech pack — type your answer",
    )
    /** Gdy tag jezyka jest nieznany — bez nazwy, zamiast zgadywania. */
    val voiceNoPack get() = pick(
        "Brak pakietu mowy — wpisz odpowiedź",
        "Speech pack missing — type your answer",
    )
    /**
     * Silnik on-device NIE MA pobranego pakietu tego jezyka; "%s" to nazwa jezyka.
     *
     * To nie jest awaria: nasluch idzie dalej, tylko przez siec. Zdanie ma powiedziec uczniowi,
     * co sie stalo i co da sie z tym zrobic — do 0.9.16 gra schodzila na siec zupelnie po cichu.
     */
    val voiceNoOfflinePack get() = pick(
        "Brak pakietu offline: %s — słucham przez sieć",
        "No offline %s pack — listening online",
    )
    val voiceEngineSilent get() = pick(
        "Silnik mowy milczy (%s/%s) — wpisz odpowiedź",
        "Speech engine silent (%s/%s) — type your answer",
    )
    val voiceUnavailable get() = pick(
        "Głos niedostępny (%s/%s) — wpisz odpowiedź",
        "Speech unavailable (%s/%s) — type your answer",
    )
    // Po polsku mianownik po dwukropku ("Brak pakietu mowy: polski"), po angielsku przydawka ("no Polish speech pack").
    val voiceLangPolish get() = pick("polski", "Polish")
    val voiceLangEnglish get() = pick("angielski", "English")


    // ---- SILNIK MOWY WBUDOWANY (offline, Vosk) -----------------------------
    // Ustawienie w OPCJACH i komunikaty drogi offline. "Wbudowany" zamiast "Vosk":
    // uczen nie ma powodu znac nazwy biblioteki, a ma powod wiedziec, ze ta droga
    // nie potrzebuje ani internetu, ani aplikacji Google.
    val speechEngine get() = pick("Silnik mowy", "Speech engine")
    val speechEngineAuto get() = pick("AUTOMATYCZNY", "AUTOMATIC")
    val speechEngineSystem get() = pick("SYSTEMOWY", "SYSTEM")
    val speechEngineOffline get() = pick("WBUDOWANY", "BUILT-IN")
    val speechEngineAutoDesc get() = pick(
        "Najpierw systemowy, a gdy zamilknie — wbudowany.",
        "System first; the built-in one takes over if it goes quiet.",
    )
    val speechEngineSystemDesc get() = pick(
        "Rozpoznawanie Google lub producenta telefonu.",
        "Recognition by Google or by your phone's maker.",
    )
    val speechEngineOfflineDesc get() = pick(
        "Wbudowany w grę — działa bez internetu i bez aplikacji Google. Zajmuje więcej miejsca.",
        "Built into the game — works with no internet and no Google app. Takes more space.",
    )
    /** Pierwsze uzycie glosu offline: model jedzie z pakietu na dysk. Trwa kilka sekund. */
    val voskPreparing get() = pick("Przygotowuję głos offline…", "Preparing offline voice…")
    /** Modelu nie da sie wczytac (brak miejsca, uszkodzone pliki) — zostaje wpisywanie. */
    val voskUnavailable get() = pick(
        "Głos offline niedostępny — wpisz odpowiedź",
        "Offline voice unavailable — type your answer",
    )
    /** Telefon zglasza sie jako maloplamieciowy — model 100 MB by go przewrocil. */
    val voskLowMemory get() = pick(
        "Za mało pamięci na głos offline — wpisz odpowiedź",
        "Not enough memory for offline voice — type your answer",
    )
    /**
     * Przelacznik prywatnosci mikrofonu (Android 12+). To JEDYNY komunikat, ktory nazywa
     * przyczyne ciszy wprost — dowiadujemy sie o niej z AppOps, a nie zgadujemy z braku probek.
     */
    val micPrivacyToggle get() = pick(
        "Mikrofon wyłączony przełącznikiem prywatności",
        "Microphone is off (privacy toggle)",
    )

    // ---- Etap 3: trudne slowa i codzienna sesja ----------------------------
    val hardWordsMenu get() = pick("TRUDNE SŁOWA", "HARD WORDS")
    val hardWordsMenuDesc get() = pick("Słowa, przy których się potykasz", "The words you keep missing")
    val dailyMenu get() = pick("DZISIEJSZE 5 MINUT", "TODAY'S 5 MINUTES")
    val dailyMenuDesc get() = pick("Krótka sesja na dziś", "A short session for today")
    val hardWords get() = pick("TRUDNE SŁOWA", "HARD WORDS")
    val hardWordsCount get() = pick("Słowa do powtórki", "Words to review")
    val hardReasonDue get() = pick("termin powtórki minął", "review overdue")
    val hardReasonBackToStart get() = pick("cofnięte po błędzie", "reset after a mistake")
    val hardReasonMistakes get() = pick("były pomyłki", "answered wrong before")
    val hardReasonHints get() = pick("z podpowiedzią", "needed a hint")
    val hardCountMistakes get() = pick("pomyłki", "mistakes")
    val hardCountHints get() = pick("podpowiedzi", "hints")
    val hardOverdueDays get() = pick("dni po terminie", "days overdue")
    val hardDueToday get() = pick("termin: dzisiaj", "due today")
    val hardListen get() = pick("Posłuchaj wymowy", "Listen to the pronunciation")
    val hardPractice get() = pick("ĆWICZ", "PRACTISE")
    val hardPracticeAll get() = pick("ĆWICZ WSZYSTKIE", "PRACTISE ALL")
    val hardEmptyNoHistory get() = pick(
        "Nic tu jeszcze nie ma. Zagraj kilka rund — wrócą tutaj słowa, przy których się potkniesz.",
        "Nothing here yet. Play a few rounds — the words you stumble on will show up here.",
    )
    val hardEmptyNothingHard get() = pick(
        "Nic tu nie wymaga poprawki — wszystko czeka na swój termin.",
        "Nothing here needs fixing — everything is waiting for its next review.",
    )
    val micSilentHint get() = pick(
        "Mikrofon milczy — wyłączony lub zajęty?",
        "Mic silent — muted or in use elsewhere?")
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
    val voiceTestOpen get() = pick("OTWÓRZ", "OPEN")
    val voiceTestIntro get() = pick(
        "Powiedz wyraźnie: KOLANO — test sprawdzi po kolei wszystkie silniki mowy w tym telefonie.",
        "Say clearly: KOLANO — the test checks every speech engine in this phone, one by one.")
    val voiceTestRepeat get() = pick(
        "Powtarzaj to słowo, aż test się skończy. Każda próba trwa najwyżej 8 sekund, więc całość to około minuty.",
        "Keep repeating the word until the test ends. Each attempt takes at most 8 seconds, so the whole run is about a minute.")
    val voiceTestStart get() = pick("START", "START")
    val voiceTestStop get() = pick("PRZERWIJ", "STOP")
    val voiceTestAgain get() = pick("JESZCZE RAZ", "RUN AGAIN")
    // Glowny przycisk w trakcie przebiegu jest MARTWY (PRZERWIJ stoi osobno, nizej): napis ma
    // powiedziec, ze test idzie, zeby uczen nie dotykal go po raz drugi i nie zabijal przebiegu.
    // Czcionka pikselowa przycisku nie ma polskich znakow — stad napis bez diakrytykow.
    val voiceTestRunning get() = pick("TEST TRWA", "TEST RUNNING")
    // Raport z POPRZEDNIEGO przebiegu przezyl zamkniecie aplikacji (SharedPreferences) — przebieg
    // trwa okolo minuty i najczesciej konczy go system, ubijajac aplikacje w tle.
    val voiceTestCopyLast get() = pick("KOPIUJ OSTATNI RAPORT", "COPY LAST REPORT")
    // Proba zamknieta w locie przez przerwanie przebiegu (KOD_PRZERWANE) — to nie jest blad
    // silnika i nie wolno jej tak opisac; reszta linii (czas, gotowosc, RMS max) zostaje.
    val voiceTestAborted get() = pick("próba przerwana", "attempt interrupted")
    val voiceTestProgress get() = pick("Próba", "Attempt")
    val voiceTestCopy get() = pick("KOPIUJ RAPORT", "COPY REPORT")
    val voiceTestCopied get() = pick(
        "Raport skopiowany — wklej go w wiadomości do autora aplikacji.",
        "Report copied — paste it into a message to the app author.")
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
        "Mikrofon działa (silniki słyszały dźwięk), więc brakuje polskiego pakietu mowy — sprawdź języki rozpoznawania mowy w ustawieniach aplikacji Google.",
        "The microphone works (engines heard sound), so the Polish speech pack is missing — check the speech recognition languages in the Google app settings.")
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
    // Model on-device (API 33+): silnik obsługuje język, ale pakiet nie jest pobrany — można to zlecić stąd.
    val voiceTestDownloadModel get() = pick("POBIERZ MODEL OFFLINE", "DOWNLOAD OFFLINE MODEL")
    val voiceTestDownloadHint get() = pick(
        "Ten telefon obsługuje polski offline, ale model nie jest pobrany. Po pobraniu rozpoznawanie działa bez internetu.",
        "This phone supports offline Polish, but the model is not downloaded. Once it is, recognition works without internet.")
    val voiceTestDownloadScheduled get() = pick("Pobieranie zlecone — uruchom test jeszcze raz za chwilę.", "Download scheduled — run the test again in a moment.")
    val voiceTestDownloadProgress get() = pick("Pobieranie modelu: %s%%", "Downloading model: %s%%")
    val voiceTestDownloadDone get() = pick("Model pobrany — uruchom test jeszcze raz.", "Model downloaded — run the test again.")
    val voiceTestDownloadFailed get() = pick("Nie udało się pobrać modelu (%s).", "Could not download the model (%s).")

    // ---- TEST MIKROFONU (surowy) — patrz RawMicTest.kt ----------------------
    val rawMic get() = pick("Test mikrofonu", "Microphone test")
    val rawMicOpen get() = pick("OTWÓRZ", "OPEN")
    val rawMicWhere get() = pick("Surowy test mikrofonu: OPCJE → Test mikrofonu", "Raw microphone test: OPTIONS → Microphone test")
    val rawMicIntro get() = pick(
        "Ten test pomija rozpoznawanie mowy. Nagrywa po dwie sekundy z każdego wejścia mikrofonu i liczy, co naprawdę dotarło do aplikacji.",
        "This test skips speech recognition entirely. It records two seconds from every microphone input and measures what actually reached the app.",
    )
    val rawMicSpeak get() = pick(
        "Mów normalnie przez cały czas trwania testu — na przykład licz: raz, dwa, trzy.",
        "Keep talking for the whole test — counting out loud works fine.",
    )
    val rawMicPlay get() = pick("ODSŁUCHAJ NAGRANIE", "PLAY THE RECORDING")
    val rawMicPlayHint get() = pick(
        "Nagranie zostaje w pamięci aplikacji i jest nadpisywane przy każdym teście. Nic nie wychodzi z telefonu.",
        "The recording stays in the app's private storage and is overwritten by the next test. Nothing leaves the phone.",
    )
    val rawMicVerdictHears get() = pick(
        "Mikrofon fizycznie działa: do aplikacji dochodzi dźwięk. Winna jest warstwa rozpoznawania mowy, nie mikrofon.",
        "The microphone physically works: audio reaches the app. The fault is in the speech layer, not the microphone.",
    )
    val rawMicVerdictZeros get() = pick(
        "Dźwięk NIE dochodzi: system oddaje aplikacji same zera. Żaden silnik mowy nie ma czego rozpoznać.",
        "No audio reaches the app: the stream is all zeros. No speech engine could recognise anything.",
    )
    val rawMicVerdictNoFrames get() = pick(
        "Strumień ruszył, ale nie oddał ani jednej próbki — tor audio stoi.",
        "Capture started but not a single frame came back — the audio path is stalled.",
    )
    val rawMicVerdictNoInit get() = pick(
        "Nie udało się otworzyć mikrofonu z żadnego źródła — coś go trzyma albo sprzęt nie odpowiada.",
        "The microphone could not be opened from any source — something holds it, or the hardware does not answer.",
    )
    val rawMicVerdictNone get() = pick("Brak pomiaru.", "No measurement.")
    val rawMicSilencedBySystem get() = pick(
        "System potwierdza wyciszenie (isClientSilenced): przełącznik prywatności mikrofonu albo inna aplikacja z wyższym priorytetem.",
        "The system confirms silencing (isClientSilenced): the microphone privacy toggle, or another app with higher priority.",
    )
    val rawMicQuiet get() = pick("cicho, ale nie zero", "quiet, but not zero")
    val rawMicError get() = pick("błąd", "error")
    val rawMicNoInit get() = pick("nie udało się otworzyć", "could not open")
    val speakNow get() = pick("Powiedz odpowiedź", "Say the answer")
    val voicePaused get() = pick("Gra wstrzymana — wznów, aby mówić", "Game paused — resume to speak")
    val voiceOnDevice get() = pick("na urządzeniu", "on-device")
    val voiceOnline get() = pick("online", "online")
    val speakPl get() = pick("mów po polsku", "answer in Polish")
    val speakEn get() = pick("mów po angielsku", "answer in English")
    val ownSets get() = pick("Moje zestawy", "My own sets")
    val chooseMode get() = pick("Wybierz tryb", "Choose a mode")
    val classic get() = pick("KLASYCZNY", "CLASSIC")
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
    val addRow get() = pick("DODAJ WIERSZ", "ADD ROW")
    val save get() = pick("ZAPISZ", "SAVE")
    val delete get() = pick("Usuń", "Delete")
    val confirmDelete get() = pick("Na pewno?", "Sure?")
    val deleteHint
        get() = pick(
            "Wbudowanych zestawów nie da się usunąć. Własne kasujesz koszem — dwa razy, żeby nie zrobić tego przypadkiem.",
            "Built-in sets cannot be removed. Your own go with the bin — twice, so it does not happen by accident."
        )
    val swapLanguages get() = pick("Zamień strony", "Swap sides")
    val builtIn get() = pick("wbudowany", "built-in")
    /** Nazwa zestawu, gdy uczen zapisze go bez tytulu — w jezyku interfejsu, nie na sztywno. */
    val untitledSet get() = pick("Bez tytułu", "Untitled")
    // LICZBA Z NAZWA. Po polsku forma zalezy od liczby (1 haslo, 2-4 hasla, 5+ hasel; 22 hasla!),
    // wiec sklejanie "$n ${str.entriesLabel}" dawalo "22 hasel". Regule ma juz Podsumowanie.forma.
    fun entriesCount(n: Int): String = "$n " + pick(
        Podsumowanie.forma(n, FormyLiczby("hasło", "hasła", "haseł")),
        if (n == 1) "entry" else "entries",
    )
    fun playsCount(n: Int): String = "$n " + pick(
        Podsumowanie.forma(n, FormyLiczby("rozegranie", "rozegrania", "rozegrań")),
        if (n == 1) "play" else "plays",
    )
    fun setsCount(n: Int): String = "$n " + pick(
        Podsumowanie.forma(n, FormyLiczby("zestaw", "zestawy", "zestawów")),
        if (n == 1) "set" else "sets",
    )
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
    val texture get() = pick("Tło planszy", "Board background")
    val textureGrass get() = pick("TRAWA", "GRASS")
    val textureNight get() = pick("NOC", "NIGHT")
    val textureSand get() = pick("PIASEK", "SAND")
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
    val playAgain get() = pick("JESZCZE RAZ", "PLAY AGAIN")
    val toMenu get() = pick("DO MENU", "TO MENU")
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
            "Ten zestaw nie ma hasła z tłumaczeniem. Uzupełnij go w ZESTAWACH.",
            "This set has no usable rows. Fill it in under SETS."
        )
    val topSet get() = pick("Najpopularniejszy zestaw", "Most played set")
    val bestRuns get() = pick("Najlepsze gry", "Best games")
}


/**
 * Polityka prywatnosci wbudowana w aplikacje. Google Play wymaga, zeby byla dostepna
 * NIE tylko jako link w Konsoli, ale takze w samej aplikacji — a ze aplikacja nie ma
 * uprawnienia do internetu, tekst musi byc lokalny. Pelna wersja (i data aktualizacji)
 * zyje pod https://matura.uk/polityka-prywatnosci i otwiera ja przegladarka.
 */
private val PRIVACY_PL = """
MATURA — słówka angielskie · polityka prywatności
Ostatnia aktualizacja: 16 września 2026

W SKRÓCIE
Gra działa na Twoim urządzeniu. Nie ma kont, logowania, reklam ani śledzenia. Aplikacja nie ma uprawnienia dostępu do internetu, więc sama nie może niczego wysłać.

CO ZAPISUJEMY (tylko na urządzeniu)
• najlepsze wyniki i statystyki gry,
• ustawienia (życia, tempo, kierunek tłumaczenia, język, dźwięk, klawiatura),
• zestawy słówek, które sam(a) dodasz,
• postęp nauki (które słowa znasz i kiedy wracają do powtórki).
Te dane leżą w pamięci aplikacji i znikają po jej odinstalowaniu. Nie mamy do nich dostępu.

MIKROFON I TRYB GŁOSOWY
Tryb głosowy jest opcjonalny — możesz grać, wpisując odpowiedzi z klawiatury. Jeśli go włączysz, aplikacja poprosi o zgodę na mikrofon (RECORD_AUDIO).
• W czasie gry nie nagrywamy dźwięku i nie zapisujemy plików audio — wypowiedź idzie prosto do usługi rozpoznawania mowy.
• JEDYNY wyjątek to OPCJE → Test mikrofonu, uruchamiany ręcznie: żeby pokazać, czy mikrofon w ogóle działa, zapisuje on kilkusekundowe nagranie do prywatnej pamięci aplikacji i pozwala je odsłuchać. Plik jest nadpisywany przy każdym teście, znika po odinstalowaniu aplikacji i nigdzie nie jest wysyłany — aplikacja nie ma dostępu do internetu.
• Wypowiedź zamienia na tekst usługa rozpoznawania mowy zainstalowana w Twoim telefonie (najczęściej Google, u niektórych producentów własna). Aplikacja dostaje sam tekst.
• Jeśli telefon ma pobrany model rozpoznawania offline dla danego języka, używamy go i dźwięk nie opuszcza urządzenia. W przeciwnym razie rozpoznanie wykonuje usługa sieciowa dostawcy (najczęściej Google) — wtedy wypowiedź trafia do niego i podlega JEGO polityce prywatności.
• To samo dotyczy systemowego okna dyktowania i mikrofonu na klawiaturze telefonu.
• Zgodę na mikrofon cofniesz w Ustawieniach systemu → Aplikacje → MATURA → Uprawnienia.

CZEGO NIE ZBIERAMY
Imienia, adresu e-mail, lokalizacji, kontaktów, zdjęć, identyfikatorów reklamowych. Brak reklam, analityki i bibliotek śledzących.

KONTAKT
kuba1k@gmail.com · https://matura.uk/polityka-prywatnosci
""".trimIndent()

private val PRIVACY_EN = """
MATURA — English vocabulary · privacy policy
Last updated: 16 September 2026

IN SHORT
The game runs on your device. No accounts, no sign-in, no ads, no tracking. The app has no internet permission, so it cannot send anything anywhere by itself.

WHAT IS STORED (on the device only)
• best scores and game statistics,
• settings (lives, speed, translation direction, language, sound, keyboard),
• word sets you add yourself,
• learning progress (which words you know and when they come back for review).
This data lives in the app storage and is removed when you uninstall the app. We have no access to it.

MICROPHONE AND VOICE MODE
Voice mode is optional — you can always type your answers. If you turn it on, the app asks for the microphone permission (RECORD_AUDIO).
• During play we do not record or store any audio — your speech goes straight to the speech recognition service.
• The ONLY exception is OPTIONS → Microphone test, which you start yourself: to show whether the microphone works at all, it saves a few seconds of audio to the app's private storage and lets you play it back. The file is overwritten by the next test, disappears when you uninstall the app, and is never sent anywhere — the app has no internet access.
• Your speech is turned into text by the speech recognition service installed on your phone (usually Google, sometimes the manufacturer's own). The app only receives the text.
• If your phone has an offline recognition model for the language, we use it and the audio never leaves the device. Otherwise recognition happens in the provider's online service (usually Google) — your speech then goes to them under THEIR privacy policy.
• The same applies to the system dictation dialog and to your keyboard's microphone key.
• You can revoke the permission in System settings → Apps → MATURA → Permissions.

WHAT WE DO NOT COLLECT
Name, e-mail, location, contacts, photos, advertising identifiers. No ads, no analytics, no tracking libraries.

CONTACT
kuba1k@gmail.com · https://matura.uk/polityka-prywatnosci
""".trimIndent()
