package com.matura.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import com.matura.app.game.Crate
import com.matura.app.game.FlashKind
import com.matura.app.game.GameConfig
import com.matura.app.game.GameEngine
import com.matura.app.game.GameSnapshot
import com.matura.app.game.Matching
import com.matura.app.game.SubmitResult
import com.matura.app.model.Options
import com.matura.app.model.ScoreRecord
import com.matura.app.model.WordSet
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.matura.app.game.VoiceMatch
import com.matura.app.model.Direction
import com.matura.app.model.UiLanguage
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlin.math.sin

// ---- napisy i reguly ekranu ------------------------------------------------

/** Lokalna tablica napisow — [Str] jest plikiem wspolnym i nie wolno go rozszerzac. */
private fun t(pl: String, en: String, lang: UiLanguage) = if (lang == UiLanguage.PL) pl else en

/**
 * Co pasek ma powiedziec o mikrofonie JEZYKIEM GRACZA.
 *
 * Wczesniej w podpisie stal tryb silnika ("na urzadzeniu" / "online"). Uczen nie ma z tego nic:
 * to informacja o naszej implementacji, a nie o tym, czy juz mowic, czy jeszcze czekac. Nazwy
 * silnikow i kody bledow zostaja tam, gdzie sa naprawde potrzebne — w nakladce diagnostycznej
 * pod dlugim przytrzymaniem paska.
 *
 * Stan bierzemy z [VoiceInput.stan], czyli z tych samych flag, ktore steruja nasluchem. Osobne
 * pole "co teraz pokazac" trzeba by zerowac w kazdej sciezce wyjscia i rozjechaloby sie
 * z rzeczywistoscia dokladnie wtedy, gdy uczen patrzy na pasek i pyta, czemu mikrofon milczy.
 */
fun opisStanuMikrofonu(stan: VoiceStan, lang: UiLanguage): String = when (stan) {
    VoiceStan.BEZCZYNNY -> t("mikrofon gotowy", "microphone ready", lang)
    VoiceStan.URUCHAMIANIE -> t("uruchamiam mikrofon…", "starting the microphone…", lang)
    VoiceStan.NASLUCH -> t("słucham — mów teraz", "listening — speak now", lang)
    VoiceStan.PONOWIENIE -> t("ponawiam nasłuch…", "retrying…", lang)
    VoiceStan.BLAD -> t("brak dostępu do mikrofonu", "no microphone access", lang)
}

/**
 * Czy wolno teraz wstrzymac runde "z urzedu" — po awarii drogi glosowej albo przy zejsciu
 * aplikacji w tlo.
 *
 * Awaria mikrofonu nie jest odpowiedzia ucznia. Potwor, ktory dochodzi do dziala, gdy uczen
 * czyta komunikat o bledzie i przesiada sie na klawiature, zabiera zycie za usterke silnika
 * mowy — a brief mowi wprost, ze brak transkrypcji nie jest dowodem niewiedzy. Zatrzymujemy
 * wiec plansze, ale tylko wtedy, gdy jest co zatrzymywac: przy wlasnej pauzie ucznia nie ma
 * czego zmieniac, a po koncu rundy pauza tylko zablokowalaby ekran wyniku.
 */
fun wstrzymacRunde(paused: Boolean, gameOver: Boolean): Boolean = !paused && !gameOver

/**
 * Podpis po awarii drogi glosowej (wyczerpana drabinka silnikow, brak okna systemowego).
 *
 * Bez "gra stoi": pauza po awarii konczy sie przy pierwszym klawiszu, wiec zdanie o wstrzymaniu
 * byloby falszywe pol sekundy pozniej. Sam fakt przerwy widac na planszy i na przycisku pauzy;
 * podpis mowi to, co zostaje prawda takze po wznowieniu — co uczen ma teraz zrobic.
 */
fun podpisAwariiMikrofonu(lang: UiLanguage): String =
    t("Mikrofon zawiódł — wpisz odpowiedź", "Mic failed — type your answer", lang)

/**
 * Podpis po odmowie zgody na mikrofon. Zastepuje [Str.micDenied] ("bez zgody tryb glosowy nie
 * zadziala"), bo tamto zdanie zostawia ucznia przed wstrzymana plansza bez zadnej dalszej drogi.
 */
fun podpisBrakuMikrofonu(lang: UiLanguage): String =
    t("Bez mikrofonu — wpisz odpowiedź", "No mic — type your answer", lang)

@Composable
fun PlayScreen(
    set: WordSet,
    options: Options,
    str: Str,
    playerName: String,
    onExit: () -> Unit,
    onFinished: (ScoreRecord) -> Unit,
    // Zmiana opcji z poziomu gry (awaryjne wlaczenie klawiatury telefonu). Idzie ta sama
    // droga co ekran OPCJE, wiec zapis jest trwaly; domyslnie pusta — testy i podglad
    // nie musza niczego podawac.
    onOptionsChange: (Options) -> Unit = {},
) {
    var restarts by remember { mutableIntStateOf(0) }
    // Systemowy back = czyste wyjscie do menu (bez cichego gubienia rundy na SetPick).
    BackHandler { onExit() }
    val engine = remember(set.id, restarts, options.lives, options.direction, options.speed) {
        GameEngine(
            set = set,
            config = GameConfig(
                lives = options.lives,
                direction = options.direction,
                speed = options.speed,
            ),
        )
    }
    var snap by remember(engine) { mutableStateOf(engine.snapshot()) }
    var highlighted by remember(engine) { mutableStateOf(emptySet<Long>()) }
    var highlightedCrates by remember(engine) { mutableStateOf(emptySet<Long>()) }
    var paused by remember(engine) { mutableStateOf(false) }
    var reported by remember(engine) { mutableStateOf(false) }
    var muzzle by remember(engine) { mutableFloatStateOf(0f) }

    val sprites = rememberSprites()
    val font = rememberPixelFont()
    val sfx = remember { Sfx() }
    DisposableEffect(Unit) { onDispose { sfx.release() } }

    var lastFlash by remember(engine) { mutableStateOf<FlashKind?>(null) }

    LaunchedEffect(engine, paused) {
        if (paused) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = ((now - last) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.05f)
                    engine.tick(dt)
                    if (muzzle > 0f) muzzle -= dt
                }
                last = now
                snap = engine.snapshot()
                highlighted = engine.highlightedIds()
                highlightedCrates = engine.highlightedCrateIds()
            }
        }
    }

    LaunchedEffect(snap.flash) {
        val f = snap.flash
        if (f != null && f != lastFlash) sfx.play(f, options.soundEnabled)
        lastFlash = f
    }

    LaunchedEffect(snap.gameOver) {
        if (snap.gameOver && !reported) {
            reported = true
            onFinished(
                ScoreRecord(
                    playerName = playerName,
                    setTitle = set.title,
                    score = snap.score,
                    level = snap.level,
                    bestStreak = snap.bestStreak,
                    kills = snap.kills,
                    playedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    val theme = textureTheme(options.texture)

    fun submit() {
        if (paused) return
        val r = engine.submit()
        if (r is SubmitResult.Fired) muzzle = 0.11f
    }

    // ---- tryb glosowy -------------------------------------------------------
    // Celnosc siedzi w VoiceMatch: slownik na planszy jest zamkniety, wiec zamiast
    // pytac "czy transkrypt rowna sie odpowiedzi" szukamy najblizszej z kilkunastu.
    val context = LocalContext.current
    val voice = rememberVoiceInput(context)
    val lang = options.uiLanguage
    var voiceMsg by remember(engine) { mutableStateOf("") }
    var lastSpoken by remember(engine) { mutableStateOf("") }
    /**
     * Numer wypowiedzi, ktora zaliczyla [lastSpoken]. Do 0.9.11 stalo tu okno 700 ms od
     * poprzedniego trafienia i to ono mialo pilnowac, zeby jedna wypowiedz nie punktowala dwa
     * razy. Odstep miedzy wynikiem czesciowym a koncowym TEJ SAMEJ wypowiedzi zalezy jednak od
     * silnika i od ciszy po slowie - bywa dluzszy niz 700 ms (wtedy slowo liczylo sie dwa razy)
     * albo krotszy niz przerwa miedzy dwoma osobnymi "kolano" (wtedy drugie nie liczylo sie
     * wcale). Numer przebiegu rozstrzyga to bez zegara: patrz VoiceInput.wypowiedz.
     */
    var lastSpokenWypowiedz by remember(engine) { mutableIntStateOf(0) }
    /**
     * Osobna, UJEMNA numeracja dla systemowego okna dyktowania - ono nie przechodzi przez
     * VoiceInput, wiec nie ma numeru przebiegu. Bez tego dwa kolejne dyktowania tego samego
     * slowa dzielilyby numer 0 i drugie przepadaloby jako "ta sama wypowiedz".
     */
    var oknoWypowiedz by remember(engine) { mutableIntStateOf(0) }
    /**
     * Jezyk, w ktorym silnik NAPRAWDE transkrybuje biezacy nasluch — ustalany w chwili startu
     * i podawany dalej do dopasowania. Liczenie go na nowo przy kazdej wypowiedzi rozjezdzalo sie
     * z silnikiem w trybie losowym: plansza zmienia sklad w trakcie rundy, wiec silnik moglby
     * transkrybowac en-US, a dopasowanie punktowac juz po polsku.
     */
    var jezykNasluchuPolski by remember(engine) { mutableStateOf(true) }

    // Odpowiedzi zestawu po obu stronach, znormalizowane dokladnie tak samo jak te, ktore
    // GameEngine kladzie na plansze. Sluza WYLACZNIE wyborowi jezyka dla silnika mowy.
    val polskieOdpowiedzi = remember(set) {
        set.entries.flatMap { Matching.acceptedAnswers(it.definition) }.toSet()
    }
    val angielskieOdpowiedzi = remember(set) {
        set.entries.flatMap { Matching.acceptedAnswers(it.term) }.toSet()
    }

    // Jezyk ODPOWIEDZI, nie pytania — to on idzie do silnika mowy jako EXTRA_LANGUAGE.
    //
    // Przy kierunku LOSOWYM GameEngine losuje kierunek dla kazdego wiersza osobno, wiec na planszy
    // leza jednoczesnie odpowiedzi polskie i angielskie, a silnik przyjmuje tylko jeden jezyk.
    // Do 0.9.10 rozstrzygal to ksztalt slowa i dla zestawu bez dwuznakow ("plecy", "kolano",
    // "glowa") wychodzil ANGIELSKI. Teraz liczymy, ktora strona zestawu przewaza na planszy;
    // zgadywanka zostaje na remis.
    //
    // UWAGA, zeby nie zamknac sprawy przedwczesnie: ta galaz dotyczy WYLACZNIE Direction.RANDOM.
    // Kierunek domyslny to TERM_TO_DEF (angielski -> polski), gdzie jezyk bierze sie wprost
    // z kierunku i pl-PL szlo do silnika juz w 0.9.10. Zgloszenia z Motoroli ta poprawka wiec
    // NIE tlumaczy, dopoki nie wiemy, ze uzytkownik gral w trybie losowym — rozstrzyga to
    // kierunek zapisany w dzienniku (nakladka diagnostyczna / logcat -s MaturaVoice) albo
    // raport z ekranu OPCJE -> Test mowy.
    fun answeringPolish(): Boolean = when (options.direction) {
        Direction.DEF_TO_TERM -> false
        Direction.TERM_TO_DEF -> true
        Direction.RANDOM -> {
            val onBoard = engine.snapshot().monsters.flatMap { it.answers }
            odpowiedziPoPolsku(onBoard, polskieOdpowiedzi, angielskieOdpowiedzi)
                ?: wygladaPoPolsku(onBoard)
        }
    }

    fun biasingWords(): List<String> = buildList {
        for (e in set.entries) {
            when (options.direction) {
                Direction.DEF_TO_TERM -> add(e.term)
                Direction.TERM_TO_DEF -> add(e.definition)
                Direction.RANDOM -> { add(e.term); add(e.definition) }
            }
        }
    }.map { it.trim() }.filter { it.length > 1 }.distinct()

    /**
     * @param wypowiedz numer przebiegu rozpoznawania, z ktorego pochodzi ten wynik. Wynik
     *        czesciowy i koncowy tej samej wypowiedzi maja ten sam numer, a spozniony wynik
     *        poprzedniego cyklu - numer sprzed restartu; obie sytuacje maja dac jedno zaliczenie.
     */
    fun onHeard(alternatives: List<String>, isFinal: Boolean, wypowiedz: Int) {
        if (paused) return
        val live = engine.snapshot()
        if (live.gameOver) return
        val board = (live.monsters.flatMap { it.answers } + live.crates.flatMap { it.answers }).distinct()
        if (board.isEmpty()) return
        val result = VoiceMatch.match(alternatives, board, jezykNasluchuPolski)
        // Wynik czesciowy przyjmujemy tylko, gdy jest naprawde pewny - inaczej
        // czekamy na koncowy, zeby nie strzelac w polowie slowa.
        if (!isFinal && result.best.score < 0.9) return
        if (!result.accepted) {
            if (isFinal) voiceMsg = "\u201E" + alternatives.first().trim() + "\u201D"
            return
        }
        val answer = result.best.answer ?: return
        if (!zaliczycWypowiedz(answer, lastSpoken, wypowiedz, lastSpokenWypowiedz)) return
        lastSpoken = answer
        lastSpokenWypowiedz = wypowiedz
        engine.clearTyped()
        for (ch in answer) engine.type(ch)
        submit()
        voiceMsg = "\u2713 " + answer
    }

    fun startVoice() {
        voiceMsg = ""
        // Jezyk zapada TU i od tej chwili obowiazuje takze dopasowanie (onHeard) — obie strony
        // musza mowic o tym samym, inaczej wracamy do bledu, ktory wlasnie naprawilismy.
        jezykNasluchuPolski = answeringPolish()
        voice.start(
            language = if (jezykNasluchuPolski) "pl-PL" else "en-US",
            biasing = biasingWords(),
            // Kierunek trafia do dziennika i logu: bez niego z raportu telefonu nie da sie
            // odczytac, czy jezyk wybieral kierunek, czy heurystyka planszy.
            kierunek = options.direction.name,
            // Numer przebiegu czytamy W CHWILI wyniku, nie przy skladaniu wywolania:
            // miedzy startem a wypowiedzia moglo byc kilka wznowien.
            onHeard = { alternatywy, koncowy -> onHeard(alternatywy, koncowy, voice.wypowiedz) },
        )
    }

    // Podpowiedz o gluchym mikrofonie VoiceInput pokazuje sam (asynchronicznie, bez dostepu
    // do Str) — podstawiamy mu wersje w jezyku interfejsu.
    LaunchedEffect(str) {
        voice.tekstCiszy = str.micSilentHint
        voice.tekstInnySilnik = str.voiceOtherEngine
        voice.tekstBrakMowy = str.voiceNoSpeech
        // Bez tych szesciu linii friendlyError() zostaje przy polskich wartosciach domyslnych,
        // wiec uczen z angielskim interfejsem dostaje polskie zdanie w chwili awarii glosu.
        voice.tekstBrakSieci = str.voiceNoNetwork
        voice.tekstBrakPakietuJezyka = str.voiceNoLanguagePack
        voice.tekstBrakPakietu = str.voiceNoPack
        voice.tekstSilnikMilczy = str.voiceEngineSilent
        voice.tekstGlosNiedostepny = str.voiceUnavailable
        voice.tekstJezykPolski = str.voiceLangPolish
        voice.tekstJezykAngielski = str.voiceLangEnglish
    }

    // Jezyk silnika ustala sie RAZ, przy starcie nasluchu — a w trybie losowym plansza zmienia
    // sklad w trakcie rundy i przewaga jezykowa potrafi sie odwrocic. Bez tego silnik dalej
    // transkrybowalby en-US, gdy na planszy leza juz same polskie odpowiedzi (albo odwrotnie):
    // ta sama awaria, ktora naprawia odpowiedziPoPolsku, tylko przesunieta w czas. Regula zmiany
    // (histereza, zeby kazdy zabity potwor przy remisie nie restartowal nasluchu) siedzi
    // w VoicePolicy i jest pokryta testem bez urzadzenia.
    val glosyPlanszy = if (options.direction == Direction.RANDOM) {
        policzStrony(snap.monsters.flatMap { it.answers }, polskieOdpowiedzi, angielskieOdpowiedzi)
    } else null
    LaunchedEffect(glosyPlanszy, voice.listening, paused) {
        val glosy = glosyPlanszy ?: return@LaunchedEffect
        if (!voice.listening || paused) return@LaunchedEffect
        if (!zmienicJezykNasluchu(jezykNasluchuPolski, glosy)) return@LaunchedEffect
        val polski = !jezykNasluchuPolski
        val tag = if (polski) "pl-PL" else "en-US"
        // WZNOWIENIE tej samej sesji, nie nowy start. stop() + start() kasowalo dziennik zdarzen
        // (jedyne zrodlo nakladki diagnostycznej pod dlugim przytrzymaniem paska), zerowalo
        // drabinke i mrugalo paskiem — a w trybie losowym plansza zmienia sklad po kazdym zabitym
        // potworze, wiec trafialo czasem dokladnie w chwile, gdy uczen sprawdzal, czemu mikrofon
        // milczy. Odstepu miedzy przelaczeniami pilnuje VoiceInput; gdy odmowi, czekamy tyle,
        // ile trzeba, i probujemy raz jeszcze — inaczej ostatnia zmiana planszy w rundzie
        // zostawilaby silnik w zlym jezyku do konca.
        if (!voice.przelaczJezyk(tag)) {
            delay(ODSTEP_JEZYKA_MS)
            if (!voice.listening || paused || !voice.przelaczJezyk(tag)) return@LaunchedEffect
        }
        jezykNasluchuPolski = polski
    }

    // NAPRAWDE OSTATNIA DESKA: klawiatura telefonu z wlasnym dyktowaniem. Gdy nie ma ani uslugi
    // do zwiazania, ani okna systemowego (albo okno nie chce sie otworzyc), przelaczamy opcje
    // "klawiatura telefonu" ta sama droga co ekran OPCJE — wiec ustawienie ZOSTAJE na kolejne
    // gry. To nie jest rozpoznawanie mowy w aplikacji, tylko zwykle wprowadzanie tekstu;
    // mikrofon pochodzi z klawiatury systemowej, wiec nie zawodzi tak, jak SpeechRecognizer.
    // [podpis] — co zostaje pod przyciskiem; domyslnie instrukcja, jak uzyc mikrofonu klawiatury.
    /** Awaryjne przejscie na klawiature telefonu — tylko na te rozgrywke, bez zapisu w opcjach. */
    var klawiaturaTymczasowa by remember { mutableStateOf(false) }
    var nasluchPrzedDiagnostyka by remember { mutableStateOf(false) }
    fun klawiaturaAwaryjna(podpis: String = str.voiceKeyboardAuto, zapisz: Boolean = false) {
        // Awaria silnika przelacza klawiature TYLKO na te rozgrywke: trwala zmiana ustawien bez
        // pytania zabralaby uczniowi klawiature z gry na zawsze, a jedna wpadka silnika nie jest
        // decyzja. Zapisujemy dopiero, gdy uczen sam wybral te droge (dotkniecie paska).
        if (zapisz && !options.systemKeyboard) onOptionsChange(options.copy(systemKeyboard = true))
        else if (!options.systemKeyboard) klawiaturaTymczasowa = true
        voiceMsg = podpis
    }

    // UCZCIWOSC OCENIANIA. Gdy droga glosowa sie wyczerpie albo zgoda na mikrofon nie przyjdzie,
    // plansza NIE moze isc dalej: potworki dochodzace do dziala odbieraja zycie i zeruja serie,
    // czyli awaria silnika mowy zamienia sie w kare za odpowiedz, ktorej uczen nigdy nie udzielil.
    //
    // Uzywamy TEJ SAMEJ pauzy, ktora ma przycisk u gory (paused) — drugi, rownolegly mechanizm
    // wstrzymania rozjechalby sie z nia przy pierwszej sciezce, ktorej ktos nie przewidzi.
    // Flaga mowi tylko tyle, ze te pauze zalozyla awaria, a nie uczen: dzieki temu konczy sie
    // sama przy pierwszym klawiszu (patrz [wznowPoAwarii]) — inaczej uczen wpisywalby odpowiedz
    // w zatrzymana gre i nic by sie nie dzialo.
    var pauzaOdAwarii by remember(engine) { mutableStateOf(false) }
    fun bezpiecznaPrzerwa() {
        if (wstrzymacRunde(paused, engine.snapshot().gameOver)) { paused = true; pauzaOdAwarii = true }
    }
    /** Przesiadka na klawiature skonczona — presja czasu wraca dokladnie wtedy, gdy uczen pisze. */
    fun wznowPoAwarii() {
        if (pauzaOdAwarii) { pauzaOdAwarii = false; paused = false }
    }

    val podpisAwarii = podpisAwariiMikrofonu(lang)
    val podpisBezMikrofonu = podpisBrakuMikrofonu(lang)

    // OSTATNIA DESKA RATUNKU (przed klawiatura): okno systemowe rozpoznawania mowy.
    // Rozni sie od calej reszty w sposob zasadniczy — nie WIAZE sie z zadna usluga
    // (to wlasnie wiazanie odmawia na czesci telefonow, m.in. Samsungach), tylko oddaje
    // zadanie systemowi, ktory sam wybiera obslugujaca aplikacje. Dziala wszedzie, gdzie
    // jest jakiekolwiek wprowadzanie glosowe. Cena: modalne okno zamiast ciaglego nasluchu,
    // wiec gra jest na ten czas wstrzymana, a rozpoznanie dotyczy jednej wypowiedzi.
    // Pamietamy, czy to MY wstrzymalismy gre na czas okna — zeby nie odwiesic pauzy,
    // ktora uczen wlaczyl sam przed nacisnieciem mikrofonu.
    var pauzaOdOkna by remember { mutableStateOf(false) }
    val okno = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { wynik ->
        if (pauzaOdOkna) { paused = false; pauzaOdOkna = false }
        val slowa = if (wynik.resultCode == Activity.RESULT_OK)
            wynik.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS) else null
        if (!slowa.isNullOrEmpty()) {
            oknoWypowiedz -= 1
            onHeard(slowa.toList(), true, oknoWypowiedz)
        }
        // Wynik okna ZAPISUJEMY w kodzie w nawiasie: "(0)" = okno zamkniete bez danych,
        // "(0/0)" = okno zwrocilo pusta liste. Zdanie jest w jezyku interfejsu, kod zostaje
        // dla diagnostyki — bez niego "nie zadzialalo" nie da sie odroznic od "zamknal okno".
        else voiceMsg = str.voiceDialogNoResult + " (" + wynik.resultCode +
            (if (wynik.data == null) ")" else "/0)")
    }

    fun oknoSystemowe() {
        // Okno tez jest nasluchem: jezyk zapada przy jego otwarciu i ten sam ma obowiazywac,
        // gdy wynik wroci (onHeard) — nawet jesli plansza zmieni sie w miedzyczasie.
        jezykNasluchuPolski = answeringPolish()
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (jezykNasluchuPolski) "pl-PL" else "en-US")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 6)
            putExtra(RecognizerIntent.EXTRA_PROMPT, str.speakNow)
        }
        // Gra stoi na czas okna — inaczej potworki szlyby dalej, gdy uczen mowi.
        if (!paused) { paused = true; pauzaOdOkna = true }
        // Brak aplikacji od okna (ActivityNotFoundException) albo inna wywrotka przy starcie:
        // nie komunikat o porazce, tylko klawiatura telefonu — ona jest zawsze.
        try { okno.launch(i) } catch (_: Throwable) {
            // Okno sie nie otworzylo, wiec pauza "na czas okna" traci powod — ale gra ma zostac
            // wstrzymana dalej, juz jako przerwa na przesiadke (patrz bezpiecznaPrzerwa).
            if (pauzaOdOkna) { paused = false; pauzaOdOkna = false }
            bezpiecznaPrzerwa()
            klawiaturaAwaryjna(podpisAwarii)
        }
    }

    // Gdy zwiazane silniki zawiodly: okno systemowe, a bez okna — klawiatura telefonu.
    // Okno wstrzymuje runde SAMO (pauzaOdOkna) i samo ja odwiesza po wyniku, wiec presji czasu
    // zdejmowac tu nie wolno — inaczej wynik z okna wrocilby do gry, ktora wciaz stoi, i onHeard
    // by go odrzucil. Droga klawiaturowa takiego domkniecia nie ma i tam pauza jest potrzebna.
    fun awaryjnie() {
        if (voice.oknoDostepne) oknoSystemowe()
        else { bezpiecznaPrzerwa(); klawiaturaAwaryjna(podpisAwarii) }
    }

    // ZGODA NA MIKROFON. Po odmowie sprawdzamy, czy system jeszcze pokaze pytanie
    // (shouldShowRequestPermissionRationale). Jesli nie — odmowa jest trwala i jedyna droga
    // to ustawienia aplikacji: NASTEPNE dotkniecie paska je otwiera, zamiast prosic w prozni.
    // Tak samo traktujemy ERROR_INSUFFICIENT_PERMISSIONS od silnika — to ta sama blokada,
    // tylko zgloszona z drugiej strony (np. mikrofon odebrany aplikacji w menedzerze uprawnien).
    //
    // Zgoda dotyczy TYLKO silnika (okno systemowe i klawiatura telefonu jej nie potrzebuja),
    // a ustawienia otwieramy RAZ: uczen, ktory wrocil z nich bez zmiany, nie ma krecic sie
    // w kolko ustawienia -> cicha odmowa -> ustawienia. Po tym dotkniecie schodzi do okna /
    // klawiatury (VoicePolicy: settingsTried), a szczebel zgody wraca, gdy zgoda sie pojawi.
    val activity = remember(context) { context.znajdzActivity() }
    var ustawieniaPrzyDotknieciu by remember { mutableStateOf(false) }
    var ustawieniaOtwarte by remember { mutableStateOf(false) }
    /** Czy przed prosba system deklarowal, ze warto pokazac uzasadnienie (czyli byla juz odmowa). */
    var uzasadnieniePrzed by remember { mutableStateOf(false) }
    var prosbyOZgode by remember { mutableStateOf(0) }

    fun zgodaNaMikrofon(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    fun odmowaMikrofonu() {
        // Zgoda JEST, a mimo to "odmowa" (silnik zglosil kod 9 z wlasnej winy, kaprys AppOps):
        // to awaria silnika, nie ucznia. shouldShowRequestPermissionRationale dla dozwolonego
        // uprawnienia zwraca false i wygladaloby to jak odmowa trwala — ustawienia z juz
        // wlaczonym mikrofonem nic by nie naprawily. Idziemy droga awaryjna (okno/klawiatura).
        if (zgodaNaMikrofon()) { awaryjnie(); return }
        // shouldShowRequestPermissionRationale zwraca false takze wtedy, gdy uczen nigdy jeszcze
        // nie odmowil (zamkniete okno, cofniecie). Sama ta flaga braloby pierwsze "nie teraz" za
        // odmowe trwala i na zawsze zabieraloby szczebel zgody. Trwala = brak uzasadnienia PO
        // prosbie i (byla juz odmowa wczesniej albo prosilismy co najmniej dwa razy).
        val uzasadnieniePo = activity != null &&
            ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
        val trwala = activity != null && !uzasadnieniePo && (uzasadnieniePrzed || prosbyOZgode >= 2)
        // Odmowa zgody tez nie jest odpowiedzia ucznia: gra staje, zeby zdazyl przejsc na
        // klawiature, zamiast tracic zycie przy czytaniu komunikatu.
        bezpiecznaPrzerwa()
        if (trwala) {
            ustawieniaPrzyDotknieciu = true
            ustawieniaOtwarte = false
            voiceMsg = str.micDeniedSettings
        } else {
            // str.micDenied mowi tylko, ze bez zgody tryb glosowy nie zadziala — a uczen stoi
            // wlasnie przed wstrzymana plansza i musi wiedziec, co ma teraz zrobic.
            voiceMsg = podpisBezMikrofonu
        }
    }

    fun otworzUstawienia() {
        // Zaliczamy probe niezaleznie od wyniku: gdy ustawien nie da sie otworzyc, ponawianie
        // tez nic nie da — nastepne dotkniecie ma isc do okna / klawiatury.
        ustawieniaOtwarte = true
        val i = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:" + context.packageName),
        )
        try {
            if (activity != null) activity.startActivity(i)
            else context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) { voiceMsg = str.micDeniedSettings }
    }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Okno zgody potrafi wrocic po dluzszej chwili - uczen zdazyl wlaczyc pauze albo skonczyc
        // gre. Bezwarunkowe startVoice() wlaczalo wtedy mikrofon do rundy, ktorej juz nie ma.
        when {
            !granted -> odmowaMikrofonu()
            wznowicPoZgodzie(true, paused, engine.snapshot().gameOver) -> startVoice()
            else -> voiceMsg = ""
        }
    }

    // Droge glosu wybieramy PRZY DOTKNIECIU, nie przy zlozeniu ekranu: uczen mogl w miedzyczasie
    // dac zgode w ustawieniach, doinstalowac aplikacje Google albo zwolnic mikrofon. Kolejnosc
    // i regula siedza w VoicePolicy (czysta logika, testowana bez Androida).
    fun toggleVoice() {
        if (voice.listening) { voice.stop(); voiceMsg = ""; return }
        // Przy pauzie wyniki i tak sa odrzucane (onHeard), a pasek zapalilby sie na niby.
        if (paused) { voiceMsg = str.voicePaused; return }
        voice.refresh()
        val zgoda = zgodaNaMikrofon()
        // Uczen sam wlaczyl mikrofon w ustawieniach — szczebel zgody zaczyna od nowa (a ze
        // zgoda jest, VoicePolicy pojdzie prosto do silnika).
        if (zgoda && ustawieniaPrzyDotknieciu) { ustawieniaPrzyDotknieciu = false; ustawieniaOtwarte = false }
        when (VoicePolicy.path(zgoda, ustawieniaPrzyDotknieciu, voice.maSilnik, voice.oknoDostepne, ustawieniaOtwarte)) {
            VoicePath.OPEN_SETTINGS -> otworzUstawienia()
            VoicePath.REQUEST_PERMISSION -> {
                uzasadnieniePrzed = activity != null && ActivityCompat
                    .shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
                prosbyOZgode += 1
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
            VoicePath.ENGINE -> startVoice()
            VoicePath.SYSTEM_DIALOG -> oknoSystemowe()
            // Po wyczerpanym szczeblu zgody podpis dalej wskazuje prawdziwa naprawe (ustawienia);
            // klawiatura z mikrofonem i tak jest juz na ekranie.
            VoicePath.KEYBOARD -> klawiaturaAwaryjna(
                if (ustawieniaOtwarte) str.micDeniedSettings else str.voiceKeyboardAuto,
                zapisz = true, // ta droga wybral uczen dotknieciem paska
            )
        }
    }

    // Gdy drabinka silnikow sie wyczerpie, przechodzimy na okno systemowe (albo klawiature
    // telefonu) zamiast konczyc komunikatem o porazce. Brak zgody zglaszany przez silnik
    // traktujemy jak odmowe z okienka uprawnien.
    // Efekt zaklada sie RAZ na VoiceInput, a awaryjnie()/odmowaMikrofonu() lapia options,
    // str i engine z biezacego zlozenia — po "Zagraj jeszcze raz" (nowy engine) stare domkniecie
    // czytaloby stara plansze. rememberUpdatedState zawsze wola wersje z ostatniego zlozenia.
    val awaryjnieNow by rememberUpdatedState({ awaryjnie() })
    val odmowaNow by rememberUpdatedState({ odmowaMikrofonu() })
    DisposableEffect(voice) {
        voice.onExhausted = { awaryjnieNow() }
        voice.onBrakZgody = { odmowaNow() }
        onDispose { voice.onExhausted = null; voice.onBrakZgody = null }
    }

    // Pauza i koniec rundy koncza nasluch - mikrofon nie ma chodzic w tle.
    LaunchedEffect(paused, snap.gameOver) {
        if ((paused || snap.gameOver) && voice.listening) { voice.stop(); voiceMsg = "" }
    }

    // ZEJSCIE W TLO (HOME, przelaczenie aplikacji, zgaszony ekran). Zadne z nich nie wyprowadza
    // ekranu z kompozycji, wiec onDispose z rememberVoiceInput NIE leci, a pauza sama sie nie
    // wlacza: do tej pory nasluch zostawal wlaczony poza aplikacja (mikrofon zajety, a od
    // Androida 12 w tle i tak gluchy), a runda odliczala dalej. Oddajemy mikrofon od razu
    // i wstrzymujemy plansze — czas spedzony poza gra nie jest odpowiedzia ucznia.
    //
    // Domkniecie przez rememberUpdatedState, bo "Zagraj jeszcze raz" tworzy NOWY stan paused
    // (remember(engine)), a obserwator kluczowany wlascicielem cyklu zyje dalej i pisalby do
    // poprzedniego — ta sama pulapka, ktora zalatano wyzej przy onExhausted.
    val wTlo by rememberUpdatedState({
        if (voice.listening) { voice.stop(); voiceMsg = "" }
        // To NIE jest pauza od awarii: powrot z tla ma byc swiadomy, wiec zdejmuje ja dopiero
        // przycisk u gory, a nie pierwszy dotkniety klawisz.
        if (wstrzymacRunde(paused, engine.snapshot().gameOver)) paused = true
    })
    val wlascicielCyklu = LocalLifecycleOwner.current
    DisposableEffect(wlascicielCyklu) {
        val obserwator = LifecycleEventObserver { _, zdarzenie ->
            if (zdarzenie == Lifecycle.Event.ON_STOP) wTlo()
        }
        wlascicielCyklu.lifecycle.addObserver(obserwator)
        onDispose { wlascicielCyklu.lifecycle.removeObserver(obserwator) }
    }

    // DIAGNOSTYKA GLOSU (dlugie przytrzymanie paska): pelny zrzut w nakladce nad plansza,
    // nie w podpisie pod przyciskiem — podpis ma jedna linie i stala wysokosc, zeby klawiatura
    // pod nim nie skakala. Gra stoi na czas nakladki, tak jak przy oknie systemowym.
    var diagnostyka by remember { mutableStateOf<String?>(null) }
    var pauzaOdDiagnostyki by remember { mutableStateOf(false) }
    fun pokazDiagnostyke() {
        diagnostyka = voice.diagnostyka()
        nasluchPrzedDiagnostyka = voice.listening
        if (!paused) { paused = true; pauzaOdDiagnostyki = true }
    }
    fun zamknijDiagnostyke() {
        diagnostyka = null
        if (pauzaOdDiagnostyki) { paused = false; pauzaOdDiagnostyki = false }
        // Pauza na czas podgladu zatrzymala nasluch (LaunchedEffect). Skoro badalismy wlasnie
        // dzialajacy mikrofon, wracamy do sluchania — inaczej diagnostyka konczy sesje.
        if (nasluchPrzedDiagnostyka) { nasluchPrzedDiagnostyka = false; startVoice() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
    ) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(5.dp)
                .border(3.dp, Palette.Ink)
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawField(snap, highlighted, highlightedCrates, theme, sprites, font, options, muzzle)
            }

            if (!engine.isPlayable) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .background(Palette.Ink)
                            .border(3.dp, Palette.SurfaceHigh)
                            .padding(14.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            PixelText(
                                str.emptySetWarning,
                                color = Palette.Danger,
                                glyphHeight = 9.dp,
                                maxWidthDp = 240.dp,
                                font = font,
                            )
                            Spacer(Modifier.height(14.dp))
                            MenuButton(str.toMenu, onClick = onExit)
                        }
                    }
                }
            }

            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 5.dp)
                    .background(Palette.Ink.copy(alpha = 0.75f))
                    .border(2.dp, Palette.SurfaceHigh)
                    // Wznowienie reka ucznia konczy takze pauze od awarii — inaczej flaga
                    // zostalaby zapalona i nastepny klawisz "odwieszalby" gre, ktora juz idzie.
                    .clickable { paused = !paused; if (!paused) pauzaOdAwarii = false }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PixelImage(pixel(com.matura.app.R.drawable.icon_pause), Modifier.size(13.dp))
                    Spacer(Modifier.width(7.dp))
                    PixelText(
                        if (paused) str.resume else str.pause,
                        color = Palette.Bone,
                        glyphHeight = 8.dp,
                        font = font,
                    )
                }
            }

            if (snap.gameOver) {
                GameOverOverlay(snap, str, sprites, font, onAgain = { restarts++ }, onMenu = onExit)
            }

            diagnostyka?.let { tekst ->
                DiagnosticsOverlay(tekst, str, font, onClose = { zamknijDiagnostyke() })
            }
        }

        AnswerBar(snap.typed, snap.cursor, font)

        // Podpis paska, od najwazniejszego: wlasny komunikat ekranu (trafienie, awaria, odmowa),
        // potem konkretna podpowiedz silnika (gluchy mikrofon, inny silnik), a dopiero na koncu
        // stan mikrofonu jezykiem gracza. Gdy silnika NIE MA, zostawiamy pusto — wtedy VoicePolicy
        // wpisuje pod przyciskiem droge, ktora pojdzie dotkniecie (okno systemowe / klawiatura),
        // a to jest w tej sytuacji jedyna informacja, ktora do czegos prowadzi.
        val podpisPaska = when {
            voiceMsg.isNotEmpty() -> voiceMsg
            voice.status.isNotEmpty() -> voice.status
            voice.maSilnik || voice.listening -> opisStanuMikrofonu(voice.stan, lang)
            else -> ""
        }

        VoiceBar(
            listening = voice.listening,
            mode = voice.mode,
            message = podpisPaska,
            onDiagnostyka = { pokazDiagnostyke() },
            str = str,
            onToggle = { toggleVoice() },
            dialogAvailable = voice.oknoDostepne,
            hasEngine = voice.maSilnik,
        )

        if (options.systemKeyboard || klawiaturaTymczasowa) {
            // POLE SYSTEMOWE. Wbudowana klawiatura pikselowa nie ma mikrofonu, a tryb glosowy
            // aplikacji na czesci telefonow nie dziala (silnik odmawia obslugi aplikacjom trzecim).
            // Zwykle pole tekstowe otwiera klawiature TELEFONU — a ta ma wlasne dyktowanie,
            // ktore nie przechodzi przez ten mechanizm w ogole. To najpewniejsza droga do glosu.
            val fokus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { fokus.requestFocus() } }
            BasicTextField(
                // Zrodlem prawdy zostaje silnik: pole pokazuje `snap.typed`, a kursor trzymamy
                // na koncu, zeby dyktowanie doklejalo tekst tam, gdzie uzytkownik sie spodziewa.
                value = TextFieldValue(snap.typed, TextRange(snap.typed.length)),
                onValueChange = { nowa ->
                    // Pisanie = koniec przesiadki po awarii mikrofonu (patrz wznowPoAwarii).
                    wznowPoAwarii()
                    if (paused) return@BasicTextField
                    val czyste = nowa.text.filter { it.isLetter() || it == ' ' || it == '-' }.take(40)
                    if (czyste != snap.typed) {
                        engine.clearTyped()
                        for (ch in czyste) engine.type(ch)
                    }
                },
                singleLine = true,
                textStyle = TextStyle(color = Palette.Ink, fontSize = 22.sp, textAlign = TextAlign.Center),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Palette.Accent),
                keyboardOptions = KeyboardOptions(
                    autoCorrect = false,
                    capitalization = KeyboardCapitalization.None,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .background(Palette.Bone)
                    .border(3.dp, Palette.Ink)
                    .padding(14.dp)
                    .focusRequester(fokus),
            )
        } else {
            GameKeyboard(
                // Pierwszy klawisz konczy pauze zalozona przez awarie mikrofonu — uczen przeszedl
                // wlasnie na klawiature, wiec przerwa spelnila swoja role. Pauzy WLASNEJ (przycisk
                // u gory, zejscie w tle) to nie rusza: tam czekamy na swiadome wznowienie.
                onChar = { c -> wznowPoAwarii(); if (!paused) { engine.type(c); sfx.key(options.soundEnabled) } },
                onBackspace = { wznowPoAwarii(); if (!paused) engine.backspace() },
                onEnter = { wznowPoAwarii(); submit() },
                onSpace = { wznowPoAwarii(); if (!paused) engine.type(' ') },
                onLeft = { engine.cursorLeft() },
                onRight = { engine.cursorRight() },
            )
        }
    }
}

/**
 * Activity spod LocalContext. Compose potrafi owinac je w ContextWrapper, wiec schodzimy
 * po lancuchu baseContext; null, gdy kontekst nie nalezy do zadnego Activity (podglad, test).
 */
private fun Context.znajdzActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

@Composable
private fun AnswerBar(typed: String, cursor: Int, font: PixelFont) {
    // The design puts the answer on a bone panel with ink lettering and a green caret.
    Box(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(Palette.Bone)
            .border(3.dp, Palette.Ink),
        contentAlignment = Alignment.Center,
    ) {
        val safe = cursor.coerceIn(0, typed.length)
        // Dlugie hasla (>16 znakow) zmniejszaja glify, zeby caly wpis + kursor miescil sie
        // w pasku na waskich ekranach (wczesniej tekst >~22 znakow wychodzil poza krawedzie).
        val gh = (if (typed.length <= 16) 17f else (272f / (typed.length + 1)).coerceAtLeast(9f)).dp
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (safe > 0) {
                PixelText(
                    typed.substring(0, safe),
                    color = Palette.Ink,
                    outline = null,
                    glyphHeight = gh,
                    font = font,
                )
            }
            PixelText("_", color = Palette.AccentDim, outline = null, glyphHeight = gh, font = font)
            if (safe < typed.length) {
                PixelText(
                    typed.substring(safe),
                    color = Palette.Ink,
                    outline = null,
                    glyphHeight = gh,
                    font = font,
                )
            }
        }
    }
}

@Composable
private fun GameOverOverlay(
    snap: GameSnapshot,
    str: Str,
    sprites: Sprites,
    font: PixelFont,
    onAgain: () -> Unit,
    onMenu: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Ink.copy(alpha = 0.86f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PixelImage(pixel(com.matura.app.R.drawable.skull), Modifier.size(60.dp))
            Spacer(Modifier.height(12.dp))
            PixelText(str.gameOver, color = Palette.Danger, glyphHeight = 18.dp, font = font)
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PixelImage(sprites.coin, Modifier.size(26.dp))
                Spacer(Modifier.width(9.dp))
                PixelText("${snap.score}", color = Palette.Gold, glyphHeight = 26.dp, font = font)
            }
            Spacer(Modifier.height(14.dp))
            PixelText(
                "LVL ${snap.level}  ${str.killsLabel} ${snap.kills}  x${snap.bestStreak}",
                color = Palette.Muted,
                glyphHeight = 9.dp,
                font = font,
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.Center) {
                PrimaryButton(str.playAgain, onClick = onAgain)
                Spacer(Modifier.width(12.dp))
                GhostButton(str.toMenu, onClick = onMenu)
            }
        }
    }
}

/**
 * Zrzut diagnostyki glosu (VoiceInput.diagnostyka) nad plansza. Zwykla czcionka, nie pikselowa
 * (nazwy pakietow, polskie znaki), tekst zaznaczalny — zeby dalo sie go skopiowac i przeslac,
 * zamiast przepisywac z ekranu telefonu.
 */
@Composable
private fun DiagnosticsOverlay(text: String, str: Str, font: PixelFont, onClose: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Ink.copy(alpha = 0.86f))
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Palette.Surface)
                .border(3.dp, Palette.SurfaceHigh)
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PixelText(str.voiceDiagnostics, color = Palette.Accent, glyphHeight = 9.dp, font = font)
            Spacer(Modifier.height(10.dp))
            SelectionContainer {
                Text(
                    text,
                    color = Palette.Bone,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
            Spacer(Modifier.height(10.dp))
            // Nakladka pokazuje JEDEN przebieg drabinki. Pelna matryca silnikow siedzi na
            // osobnym ekranie; stad tylko droga do niego — otwarcie go z gry skasowaloby runde.
            Text(
                str.voiceTestWhere,
                color = Palette.Muted,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(12.dp))
            GhostButton(str.back, onClick = onClose)
        }
    }
}

// ---- drawing ---------------------------------------------------------------

private fun DrawScope.drawField(
    snap: GameSnapshot,
    highlighted: Set<Long>,
    highlightedCrates: Set<Long>,
    theme: FieldTheme,
    sprites: Sprites,
    font: PixelFont,
    options: Options,
    muzzle: Float,
) {
    val w = size.width
    val h = size.height
    val unit = min(w, h)
    val fs = (unit * 0.0065f).roundToInt().coerceAtLeast(1).toFloat()

    drawRect(color = theme.field)
    clipRect {
        drawTiledBackground(sprites.tiles(options.texture), (unit * 0.11f).coerceAtLeast(24f))
    }

    // A hit knocks the whole field about a little; keeps the camera alive without animation.
    val shake = if (snap.flash == FlashKind.LIFE_LOST) unit * 0.006f else 0f

    translate(
        left = if (shake > 0f) (snap.elapsed * 97f % 2f - 1f) * shake else 0f,
        top = if (shake > 0f) (snap.elapsed * 61f % 2f - 1f) * shake else 0f,
    ) {
        fun px(x: Float) = x * w
        fun py(y: Float) = y * h

        val cx = px(GameEngine.CANNON_X)
        val cy = py(GameEngine.CANNON_Y)

        val aim = snap.monsters
            .filter { highlighted.isEmpty() || it.id in highlighted }
            .minByOrNull { hypot(px(it.x) - cx, py(it.y) - cy) }
        val aimAngle = if (aim != null) {
            Math.toDegrees(atan2((py(aim.y) - cy).toDouble(), (px(aim.x) - cx).toDouble())).toFloat()
        } else 0f

        // corpses first, so a fresh kill sits behind whatever walks over it
        for (c in snap.corpses) {
            val art = sprites.art(c.spawnLevel, c.variant)
            drawSprite(
                art.death, px(c.x), py(c.y), unit * 0.105f,
                alpha = (c.ttl / GameEngine.DEATH_FRAME_TIME).coerceIn(0f, 1f),
            )
        }

        for (c in snap.crates) {
            val x = px(c.x)
            val y = py(c.y)
            val hgt = unit * 0.085f
            val blink = if (c.ttl < 4f) (if ((c.ttl * 6f).toInt() % 2 == 0) 0.4f else 1f) else 1f
            drawSprite(sprites.crateAmmo, x, y, hgt, alpha = blink)
            drawCentredLabel(
                font, c.prompt, x, y - hgt * 0.62f, fs * 0.85f,
                if (c.id in highlightedCrates) Palette.Accent else theme.hudInk,
                clampToCanvas = true,
            )
        }

        for (m in snap.monsters) {
            val x = px(m.x)
            val y = py(m.y)
            val hgt = unit * 0.105f
            val art = sprites.art(m.spawnLevel, m.variant)
            drawSprite(sprites.walkFrame(art, snap.elapsed, (m.id % 2).toInt()), x, y, hgt)
            if (m.armored) {
                drawSprite(sprites.armorFor(m.spawnLevel, m.variant), x, y + hgt * 0.14f, hgt * 0.30f)
            }
            if (m.hitFlash > 0f) {
                drawSprite(sprites.hitFlash, x, y, hgt * 1.05f, alpha = (m.hitFlash / 0.18f).coerceIn(0f, 1f))
            }
            drawCentredLabel(
                font, m.prompt, x, y - hgt * 0.62f, fs,
                if (m.id in highlighted) Palette.Accent else theme.hudInk,
                clampToCanvas = true,
            )
        }

        drawTurret(cx, cy, unit, aimAngle, sprites, muzzle)

        for (p in snap.projectiles) {
            val target = snap.monsters.firstOrNull { it.id == p.targetId } ?: continue
            val x = px(p.x)
            val y = py(p.y)
            val a = Math.toDegrees(
                atan2((py(target.y) - y).toDouble(), (px(target.x) - x).toDouble())
            ).toFloat()
            val sprite = if (p.heavy) sprites.bulletMid else sprites.bulletBasic
            val hgt = unit * (if (p.heavy) 0.048f else 0.038f)
            rotate(degrees = a, pivot = Offset(x, y)) { drawSprite(sprite, x, y, hgt) }
        }

        drawHud(snap, w, h, unit, fs, sprites, font)

        if (snap.levelUpBanner > 0f) {
            val alpha = (snap.levelUpBanner / GameEngine.LEVEL_BANNER_TIME).coerceIn(0f, 1f)
            val bh = unit * 0.075f
            val by = h * 0.28f
            drawSprite(sprites.bannerLevelUp, w / 2f, by, bh, alpha = alpha)
            drawCentredLabel(font, "LVL ${snap.level}", w / 2f, by + bh * 0.18f, fs, Palette.Ink, outline = null)
        }
    }
}

/**
 * Centres one line of bitmap text horizontally on [cx], with its baseline box above [bottomY].
 *
 * Monsters enter from just outside the canvas, so a label centred on the sprite would hang off
 * the edge — until 0.7.0 a long word like "disciplined" read as "plined" for the first couple of
 * seconds, which is exactly the time the player needs to start typing. The label is therefore
 * kept inside the canvas; only the sprite walks in from off-screen.
 */
private fun DrawScope.drawCentredLabel(
    font: PixelFont,
    text: String,
    cx: Float,
    bottomY: Float,
    scale: Float,
    color: Color,
    outline: Color? = Palette.Ink,
    clampToCanvas: Boolean = false,
) {
    val width = font.widthPx(text, scale)
    var x = cx - width / 2f
    if (clampToCanvas) {
        val margin = 2f * scale
        x = when {
            width + 2 * margin >= size.width -> (size.width - width) / 2f   // dłuższe niż ekran
            else -> x.coerceIn(margin, size.width - width - margin)
        }
    }
    drawPixelText(
        font = font,
        text = text,
        x = x,
        y = bottomY - PixelFont.GLYPH_H * scale,
        scale = scale,
        color = color,
        outline = outline,
    )
}

private fun DrawScope.drawTurret(
    cx: Float,
    cy: Float,
    unit: Float,
    angleDeg: Float,
    sprites: Sprites,
    muzzle: Float,
) {
    val barrelH = unit * 0.036f
    val baseH = unit * 0.072f
    drawSpriteRotatedFromPivot(
        sprite = sprites.turretBarrel,
        pivotX = cx, pivotY = cy,
        height = barrelH,
        angleDeg = angleDeg,
        inset = unit * 0.012f,
    )
    if (muzzle > 0f) {
        val a = Math.toRadians(angleDeg.toDouble())
        val reach = unit * 0.062f
        drawSprite(
            sprites.muzzleFlash,
            cx + (cos(a) * reach).toFloat(),
            cy + (sin(a) * reach).toFloat(),
            unit * 0.045f,
            alpha = (muzzle / 0.11f).coerceIn(0f, 1f),
        )
    }
    drawSprite(sprites.turretBase, cx, cy, baseH)
}

private fun DrawScope.drawHud(
    snap: GameSnapshot,
    w: Float,
    h: Float,
    unit: Float,
    fs: Float,
    sprites: Sprites,
    font: PixelFont,
) {
    val pad = unit * 0.030f
    val iconH = unit * 0.048f

    // lives, top-left
    for (i in 0 until snap.maxLives) {
        val sprite = if (i < snap.lives) sprites.heart else sprites.heartEmpty
        drawSprite(sprite, pad + iconH / 2f + i * (iconH + unit * 0.012f), pad + iconH / 2f, iconH)
    }

    // ammo, top-right. Basic rounds are endless, so an "∞" readout could never change
    // and only taught a mechanic the player cannot see yet: heavy rounds come from
    // crates, which spawn from level 2 and only matter against armour from level 3.
    // Show the heavy count alone, and only once it exists — its appearance is the cue.
    val midY = pad + iconH / 2f
    val textTop = midY - PixelFont.GLYPH_H * fs / 2f
    var rx = w - pad
    if (snap.heavyAmmo > 0) {
        val heavy = "×${snap.heavyAmmo}"
        rx -= font.widthPx(heavy, fs)
        drawPixelText(font, heavy, rx, textTop, fs, Palette.Gold)
        rx -= unit * 0.016f + iconH
        drawSprite(sprites.bulletMid, rx + iconH / 2f, midY, iconH)
    }

    // score, bottom-left
    val bottomMid = h - pad - iconH / 2f
    val bottomTop = bottomMid - PixelFont.GLYPH_H * fs / 2f
    drawSprite(sprites.coin, pad + iconH / 2f, bottomMid, iconH * 0.85f)
    val score = "${snap.score}"
    val scoreX = pad + iconH + unit * 0.012f
    drawPixelText(font, score, scoreX, bottomTop, fs, Palette.Bone)
    sprites.badge(snap.multiplier)?.let { badge ->
        drawSprite(
            badge,
            scoreX + font.widthPx(score, fs) + unit * 0.014f + iconH * 0.5f,
            bottomMid,
            iconH * 0.85f,
        )
    }

    // level, bottom-right
    val lvl = "LVL ${snap.level}"
    val lx = w - pad - font.widthPx(lvl, fs)
    drawPixelText(font, lvl, lx, bottomTop, fs, Palette.Gold)
    drawSprite(sprites.iconTarget, lx - unit * 0.014f - iconH * 0.45f, bottomMid, iconH * 0.8f)
}
