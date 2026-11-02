package com.matura.app.ui

import android.app.ActivityManager
import android.app.AppOpsManager
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.matura.app.BuildConfig
import java.io.File
import org.vosk.LogLevel
import org.vosk.LibVosk
import org.vosk.Model
import org.vosk.Recognizer
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

/**
 * Rozpoznawanie mowy BEZ uslugi systemowej — wlasny mikrofon i model offline (Vosk).
 *
 * DLACZEGO TO ISTNIEJE. Zgloszenie z motoroli edge 50 neo (Android 16) wyglada tak: kazda
 * z trzech zainstalowanych uslug rozpoznawania melduje onReadyForSpeech po 0,2 s i milczy —
 * zero probek poziomu, zero wynikow, zero bledow. Drabinka silnikow w [VoiceInput] ma piec
 * szczebli, ale KAZDY konczy sie zwiazaniem z jedna z tych samych trzech uslug, a dwie rzeczy
 * ustalone w dokumentacji i w AOSP mowia, ze wiecej szczebli nic nie da:
 *
 *  - `createOnDeviceSpeechRecognizer` IGNORUJE ComponentName i `Settings.Secure
 *    .voice_recognition_service`; system_server zawsze podstawia
 *    `config_defaultOnDeviceSpeechRecognitionService`, wiec dopoki tryb to ON_DEVICE,
 *    aplikacja NIE MA jak wskazac innej uslugi;
 *  - framework nie ma zadnego limitu czasu miedzy `onReadyForSpeech` a wynikiem, wiec silnik,
 *    ktory zamilkl, jest dla aplikacji niewidoczny w nieskonczonosc.
 *
 * Ta klasa omija caly ten mechanizm: otwiera [AudioRecord] SAMA i nie dotyka
 * `android.speech.RecognitionService` w zadnym miejscu. Awaria "usluga melduje gotowosc
 * i milczy" nie ma tu gdzie zajsc, bo nie ma uslugi.
 *
 * CZEGO TA KLASA NIE NAPRAWIA. Jesli mikrofon jest naprawde odciety (przelacznik prywatnosci
 * z Androida 12+, czyli globalne ograniczenie AppOps na OP_RECORD_AUDIO), to AudioRecord odda
 * same zera i tutaj tez nic nie bedzie slychac — z ta roznica, ze umiemy to NAZWAC (patrz
 * [przelacznikPrywatnosci]), zamiast pokazywac "silnik milczy".
 *
 * Powierzchnia publiczna jest celowo taka sama jak w [VoiceInput] w zakresie, ktorego uzywa
 * PlayScreen — dzieki temu wlaczenie tej drogi to podmiana obiektu, a nie przepisywanie ekranu.
 */
class VoskVoiceInput(private val context: Context) {

    // ---- powierzchnia zgodna z VoiceInput --------------------------------------------------

    /**
     * Zawsze [VoiceMode.ON_DEVICE] albo [VoiceMode.UNAVAILABLE] — trzeciej mozliwosci nie ma,
     * bo ta droga nigdy nie siega do sieci. Pasek glosu czyta to pole przez VoicePolicy.label
     * i ma prawo napisac "offline" nawet wtedy, gdy telefon jest w samolocie.
     */
    var mode by mutableStateOf(VoiceMode.UNAVAILABLE)
        private set
    var listening by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
        private set

    /**
     * Czy w systemie jest okno ACTION_RECOGNIZE_SPEECH.
     *
     * Ta klasa go nie uzywa, ale PlayScreen pyta o nie przy wyborze drogi awaryjnej i musi
     * dostac prawde takze wtedy, gdy gra chodzi na silniku wbudowanym: gdyby model offline
     * nie wstal, ekran ma jeszcze okno systemowe.
     */
    var oknoDostepne by mutableStateOf(false)
        private set

    /** Poziom dzwieku 0..1 na pasek glosu. Liczony z wlasnych probek, nie z onRmsChanged. */
    var poziomGlosu by mutableStateOf(0f)
        private set

    // Komunikaty podstawia ekran (klasa Str, PL/EN) — tak samo jak w VoiceInput, bo powstaja
    // asynchronicznie, poza kompozycja, i ta klasa nie ma jak siegnac po jezyk interfejsu.
    var tekstCiszy: String = "Mikrofon milczy — wyłączony lub zajęty?"
    var tekstInnySilnik: String = "próbuję innego silnika mowy…"
    var tekstBrakMowy: String = "Nie rozpoznaję mowy — wpisz odpowiedź"
    var tekstBrakSieci: String = "Brak internetu dla głosu — wpisz odpowiedź"
    var tekstBrakPakietuJezyka: String = "Brak pakietu mowy: %s — wpisz odpowiedź"
    var tekstBrakPakietu: String = "Brak pakietu mowy — wpisz odpowiedź"
    var tekstSilnikMilczy: String = "Silnik mowy milczy (%s/%s) — wpisz odpowiedź"
    var tekstGlosNiedostepny: String = "Głos niedostępny (%s/%s) — wpisz odpowiedź"
    var tekstBrakZgody: String = "Brak zgody na mikrofon (%s)"
    var tekstJezykPolski: String = "polski"
    var tekstJezykAngielski: String = "angielski"

    // Komunikaty, ktorych VoiceInput nie ma, bo dotycza wylacznie drogi offline.
    /** Pierwsze uruchomienie: model jedzie z pakietu na dysk i wstaje. Trwa kilka sekund. */
    var tekstPrzygotowanie: String = "Przygotowuję głos offline…"
    /** Modelu nie da sie wczytac (brak miejsca, uszkodzone pliki) — droga offline odpada. */
    var tekstBrakModelu: String = "Głos offline niedostępny — wpisz odpowiedź"
    /** Telefon zgloszony przez producenta jako maloplamieciowy: model sie na nim nie zmiesci. */
    var tekstMalaPamiec: String = "Za mało pamięci na głos offline — wpisz odpowiedź"
    /** Przelacznik prywatnosci mikrofonu (Android 12+) odcina dzwiek na poziomie systemu. */
    var tekstPrzelacznik: String = "Mikrofon wyłączony przełącznikiem prywatności"

    /** Wolane, gdy droga offline sie skonczyla — ekran siega wtedy po okno systemowe/klawiature. */
    var onExhausted: (() -> Unit)? = null
    /** Wolane, gdy mikrofonu nie da sie otworzyc z powodu braku zgody. */
    var onBrakZgody: (() -> Unit)? = null

    /**
     * Numer wypowiedzi — rosnie po KAZDYM wyniku koncowym. Znaczenie dokladnie takie samo jak
     * w [VoiceInput.wypowiedz]: wynik czesciowy i koncowy tej samej wypowiedzi maja ten sam
     * numer, wiec zaliczaja odpowiedz najwyzej raz (patrz zaliczycWypowiedz).
     *
     * Podnosimy go na WATKU GLOWNYM, tuz po oddaniu wyniku ekranowi — nie na watku nasluchu.
     * PlayScreen czyta `voice.wypowiedz` dopiero w chwili, gdy jego domkniecie sie wykona;
     * gdyby watek nasluchu zdazyl w miedzyczasie podniesc licznik, wynik koncowy dostalby numer
     * nastepnej wypowiedzi i przestalby sie parowac ze swoim wynikiem czesciowym.
     */
    var wypowiedz = 0
        private set

    val stan: VoiceStan
        get() = stanSesji(chceNasluchu, przygotowanie, cokolwiekSlychac, wyczerpany)

    /**
     * Czy droga offline jest w ogole do uzycia. Trzy powody na "nie": telefon maloplamieciowy,
     * brak modelu w pakiecie i wyczerpana proba (do czasu [refresh]).
     */
    val maSilnik: Boolean
        get() = !wyczerpany && !malaPamiec && modelWPakiecie

    // ---- stan wewnetrzny -------------------------------------------------------------------

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Dziennik zdarzen do nakladki diagnostycznej.
     *
     * Pisze do niego WATEK NASLUCHU, a czyta watek glowny (dlugie przytrzymanie paska), wiec
     * kazdy dostep idzie pod zamkiem. VoiceLog stoi na zwyklej ArrayDeque i bez tego jedno
     * otwarcie diagnostyki w trakcie mowienia potrafiloby ja wywrocic — akurat w chwili, gdy
     * uczen probuje sprawdzic, czemu glos nie dziala.
     */
    private val dziennik = VoiceLog()

    private fun zapiszWDzienniku(co: String) {
        // Dopisanie do dziennika, nie wolanie samego siebie. Poprzednia wersja rekurowala
        // bez warunku stopu, wiec KAZDE z dziesieciu wywolan konczylo sie StackOverflowError
        // — i to w chwili startu nasluchu, czyli zanim rozpoznawanie offline mialo szanse ruszyc.
        // Wzorzec jak w glownym VoiceInput: czas podaje wolajacy, zamek chroni ArrayDeque
        // przed jednoczesnym zapisem z watku nasluchu i odczytem z watku glownego.
        synchronized(dziennik) { dziennik.dodaj(System.currentTimeMillis(), co) }
    }

    @Volatile private var chceNasluchu = false
    @Volatile private var jezyk = "pl-PL"
    @Volatile private var slownik = VoskGrammar.PUSTY
    /** Znacznik sesji. Watek i kazde jego domkniecie nosza swoj numer; starsze wpadaja do kosza. */
    @Volatile private var pokolenie = 0
    @Volatile private var watek: Thread? = null

    private var onHeard: ((List<String>, Boolean) -> Unit)? = null
    private var kierunek = ""
    private var wyczerpany = false
    private var przygotowanie = false
    private var cokolwiekSlychac = false
    private var ostatniStart = 0L
    @Volatile private var ostatniBlad = ""
    /**
     * Ile prob POD RZAD skonczylo sie niczym. Zerowane, gdy z mikrofonu wejdzie dzwiek —
     * bo to znaczy, ze droga dziala, a nie ze mamy za soba kolejne udane wlaczenie.
     * Gdyby liczylo wszystkie proby, uczen po szostej rundzie zostawalby bez glosu offline
     * mimo ze za kazdym razem dzialal.
     */
    private var nieudaneProby = 0

    private val malaPamiec: Boolean by lazy { urzadzenieMaloplamieciowe() }
    private val modelWPakiecie: Boolean by lazy { KODY_JEZYKA.values.any { modelJestWPakiecie(it) } }

    init {
        // Vosk domyslnie sypie do logcata pelnym sladem dekodera (kilkadziesiat linii na sekunde).
        // Ustawiamy to raz i od razu, zanim powstanie jakikolwiek Model.
        try {
            // W buildzie debug zostawiamy ostrzezenia (LogLevel nie ma niczego cichszego),
            // a w wersji ze sklepu schodzimy pod nie surowa liczba -1: enum org.vosk.LogLevel
            // zna tylko WARNINGS/INFO/DEBUG, a dekoder Kaldiego potrafi sypac ostrzezeniami
            // przy kazdej wypowiedzi.
            if (BuildConfig.DEBUG) LibVosk.setLogLevel(LogLevel.WARNINGS)
            else LibVosk.vosk_set_log_level(-1)
        } catch (t: Throwable) {
            // Brak biblioteki natywnej dla tej architektury (patrz abiFilters w app/build.gradle.kts)
            // wywala sie wlasnie tutaj, przy pierwszym odwolaniu do LibVosk — a nie pozniej,
            // w srodku nasluchu. Zapisujemy powod, zeby diagnostyka miala co pokazac.
            ostatniBlad = "LibVosk: " + t.javaClass.simpleName
        }
        oknoDostepne = sprawdzOkno()
    }

    // ---- sterowanie ------------------------------------------------------------------------

    /**
     * Ponowna sonda przy dotknieciu paska. W tej drodze zmienic sie moze tylko jedno: zgoda na
     * mikrofon albo przelacznik prywatnosci. Modelu nikt w trakcie gry nie doinstaluje, wiec
     * [modelWPakiecie] zostaje policzone raz.
     */
    fun refresh() {
        oknoDostepne = sprawdzOkno()
        // Wyczerpana proba wraca do gry po kazdym dotknieciu: przyczyna (zajety mikrofon,
        // przelacznik prywatnosci, brak zgody) bywa chwilowa i uczen wlasnie mogl ja usunac.
        // To inna regula niz w VoiceInput, gdzie wyczerpanie znaczy "przeszlismy cala drabinke
        // uslug" i powtorka kosztuje sekundy; tutaj koszt ponowienia to jedno otwarcie mikrofonu.
        if (wyczerpany && nieudaneProby < MAX_PROB) wyczerpany = false
        if (malaPamiec) mode = VoiceMode.UNAVAILABLE
        else if (modelWPakiecie) mode = VoiceMode.ON_DEVICE
    }

    /**
     * @param language tag BCP 47 ("pl-PL" / "en-US") — wybiera model.
     * @param biasing slowa, ktore moga padnac. W [VoiceInput] to tylko PODPOWIEDZ dla silnika,
     *        tutaj to GRAMATYKA: lista jest zamknieta i nic spoza niej (poza [VoskGrammar.UNK])
     *        nie moze zostac rozpoznane. Stad cala celnosc tej drogi.
     * @param kierunek kierunek gry — wylacznie do dziennika, tak jak w [VoiceInput].
     */
    fun start(
        language: String,
        biasing: List<String>,
        kierunek: String = "",
        onHeard: (List<String>, Boolean) -> Unit,
    ) {
        if (malaPamiec) {
            wyczerpane(tekstMalaPamiec, "mala pamiec")
            return
        }
        if (!modelWPakiecie) {
            wyczerpane(tekstBrakModelu, "brak modelu w pakiecie")
            return
        }
        stopWatek()
        this.jezyk = language
        this.kierunek = kierunek
        this.onHeard = onHeard
        this.slownik = VoskGrammar.zbuduj(biasing)
        if (slownik.slowa.isEmpty()) {
            // Pusta plansza to nie awaria silnika, tylko brak czegokolwiek do uslyszenia.
            // Gramatyka z samym [unk] nie rozpoznalaby nigdy niczego, wiec nie ma po co
            // zajmowac mikrofonu.
            wyczerpane(tekstBrakMowy, "pusta gramatyka")
            return
        }
        wyczerpany = false
        cokolwiekSlychac = false
        mode = VoiceMode.ON_DEVICE
        przygotowanie = true
        chceNasluchu = true
        listening = true
        status = ""
        poziomGlosu = 0f
        ostatniStart = android.os.SystemClock.elapsedRealtime()
        pokolenie += 1
        synchronized(dziennik) { dziennik.reset(System.currentTimeMillis()) }
        zapiszWDzienniku(
            "start (" + language + (if (kierunek.isEmpty()) "" else ", " + kierunek) +
                ", offline, slow " + slownik.slowa.size + ")",
        )
        log("start jezyk=" + language + " kierunek=" + kierunek + " slow=" + slownik.slowa.size +
            " sdk=" + Build.VERSION.SDK_INT)
        val moje = pokolenie
        val t = Thread({ petla(moje) }, "MaturaVosk")
        // Watek nasluchu ma priorytet nagrywania, a nie domyslny: przy domyslnym
        // AudioRecord.read() spoznia sie pod obciazeniem (animacja planszy + dekoder),
        // a spozniony odczyt to przepelniony bufor sprzetowy i urwana wypowiedz.
        t.priority = Thread.NORM_PRIORITY + 1
        watek = t
        t.start()
    }

    fun stop() {
        log("stop (nasluch byl " + listening + ")")
        zapiszWDzienniku("stop")
        stopWatek()
        listening = false
        przygotowanie = false
        poziomGlosu = 0f
    }

    fun release() {
        log("release")
        stop()
        onHeard = null
    }

    /**
     * Zmiana jezyka nasluchu W TRAKCIE rundy (tryb losowy: plansza zmienia sklad po kazdym
     * zabitym potworze). Zwraca true, gdy jezyk faktycznie sie zmienil.
     *
     * Zmiana jest DROZSZA niz w [VoiceInput]: tam to nowy tag w intencji, tutaj zwolnienie
     * jednego modelu i wczytanie drugiego (~100 MB pamieci rezydentnej na model, wiec dwa naraz
     * nie wchodza w gre). Mikrofon zostaje otwarty przez caly ten czas — inaczej Android
     * zgasilby i zapalil wskaznik nagrywania przy kazdej zmianie przewagi na planszy.
     * Odstepu pilnuje [ODSTEP_JEZYKA_MS], dokladnie tak jak w drodze systemowej.
     */
    fun przelaczJezyk(nowy: String): Boolean {
        if (!chceNasluchu || nowy == jezyk) return false
        val teraz = android.os.SystemClock.elapsedRealtime()
        if (teraz - ostatniStart < ODSTEP_JEZYKA_MS) {
            log("przelaczJezyk " + nowy + " odlozony — od startu " + (teraz - ostatniStart) + " ms")
            return false
        }
        jezyk = nowy
        ostatniStart = teraz
        cokolwiekSlychac = false
        zapiszWDzienniku("język -> " + nowy)
        log("przelaczJezyk -> " + nowy)
        return true
    }

    /**
     * Zmiana SLOWNIKA bez przerywania nasluchu.
     *
     * PlayScreen podaje przy starcie caly zestaw, ale gramatyka wezsza o slowa, ktorych na
     * planszy juz nie ma, myli sie rzadziej. Watek podmieni Recognizera na granicy wypowiedzi
     * — koszt to 0,4 ms przy 240 slowach, wiec mozna to robic po kazdym zabitym potworze.
     */
    fun ustawSlownik(slowa: List<String>) {
        val nowy = VoskGrammar.zbuduj(slowa)
        if (nowy.slowa.isEmpty() || nowy.json == slownik.json) return
        slownik = nowy
        log("slownik -> " + nowy.slowa.size + " slow")
    }

    /**
     * Rozpakowanie modelu W TLE, zanim uczen dotknie mikrofonu.
     *
     * Pierwsze wejscie kosztuje przepisanie kilkudziesieciu MB z pakietu na dysk. Zrobione
     * dopiero przy dotknieciu paska wyglada jak zawieszenie gry; zrobione przy wlaczeniu opcji
     * "silnik wbudowany" jest niewidoczne.
     */
    fun przygotujWTle(language: String) {
        if (malaPamiec || !modelWPakiecie) return
        val kod = kodJezyka(language)
        Thread({ katalogGotowegoModelu(kod) }, "MaturaVoskPrep").apply {
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    // ---- watek nasluchu --------------------------------------------------------------------

    private fun petla(moje: Int) {
        var model: Model? = null
        var zaladowanyKod = ""
        var rozpoznawacz: Recognizer? = null
        var uzytySlownik = slownik
        var audio: AudioRecord? = null
        try {
            val mikrofon = otworzMikrofon() ?: return
            audio = mikrofon
            // startRecording() potrafi przejsc bez wyjatku i mimo to nie zaczac nagrywac:
            // mikrofon trzyma wtedy kto inny. Najczestszy "kto inny" to MY SAMI sprzed chwili
            // — poprzedni watek nasluchu, ktory jeszcze nie zdazyl oddac sprzetu po stop()
            // (patrz DOLACZENIE_MS). Dlatego zanim uznamy mikrofon za zajety, probujemy
            // jeszcze dwa razy z przerwa; dopiero potem oddajemy sprawe ekranowi.
            var proba = 0
            while (true) {
                try { mikrofon.startRecording() } catch (_: Throwable) {}
                if (mikrofon.recordingState == AudioRecord.RECORDSTATE_RECORDING) break
                proba += 1
                if (proba >= START_PROB || !chceNasluchu || moje != pokolenie) {
                    log("startRecording nieudane po " + proba + " probach")
                    naGlownym(moje) { wyczerpane(tekstCiszy, "mikrofon zajety") }
                    return
                }
                try { Thread.sleep(START_ODSTEP_MS) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
            val bufor = ShortArray(ROZMIAR_BUFORA)
            var probekWOdcinku = 0L
            var probekBezDzwieku = 0L
            var szczyt = 0
            var odOstatniegoCzesciowego = 0L
            var ciszaZgloszona = false

            while (chceNasluchu && moje == pokolenie && !Thread.currentThread().isInterrupted) {
                // Model wczytujemy DOPIERO tutaj, w petli, a nie przed otwarciem mikrofonu:
                // pierwsze wejscie to kilka sekund rozpakowywania i przez ten czas pasek ma juz
                // swiecic, a nie czekac z zapalonym niczym.
                val kod = kodJezyka(jezyk)
                if (kod != zaladowanyKod) {
                    naGlownym(moje) { przygotowanie = true; status = tekstPrzygotowanie }
                    // Jeden model naraz: stary Recognizer trzyma wskaznik do starego modelu,
                    // wiec kolejnosc zamykania jest jedyna dopuszczalna.
                    rozpoznawacz?.close()
                    rozpoznawacz = null
                    model?.close()
                    model = null
                    model = wczytajModel(kod)
                    if (model == null) {
                        naGlownym(moje) { wyczerpane(tekstBrakModelu, "model " + kod + " nie wstal") }
                        return
                    }
                    zaladowanyKod = kod
                    uzytySlownik = VoskGrammar.PUSTY
                    // Wczytanie modelu trwa ulamek sekundy do kilku sekund (pierwszy raz — z
                    // rozpakowaniem) i przez ten czas nikt nie czyta z mikrofonu. Dwa skutki,
                    // oba trzeba obsluzyc TU, a nie pozniej:
                    //  - uczen mogl w miedzyczasie wylaczyc nasluch; bez tego sprawdzenia
                    //    trzymalibysmy mikrofon (i zapalony wskaznik nagrywania) do konca
                    //    biezacego odczytu, bo petla sprawdza warunek dopiero na gorze;
                    //  - w buforze sprzetowym lezy nagranie sprzed przelaczenia, w POPRZEDNIM
                    //    jezyku. Oddane nowemu modelowi byloby wypowiedzia, ktorej nikt wlasnie
                    //    nie wypowiedzial.
                    if (!chceNasluchu || moje != pokolenie) return
                    oproznijBufor(mikrofon, bufor)
                    naGlownym(moje) { przygotowanie = false; status = "" }
                }
                if (uzytySlownik.json != slownik.json) {
                    uzytySlownik = slownik
                    rozpoznawacz?.close()
                    rozpoznawacz = zbudujRozpoznawacz(model, uzytySlownik) ?: run {
                        naGlownym(moje) { wyczerpane(tekstBrakModelu, "recognizer nie wstal") }
                        return
                    }
                    probekWOdcinku = 0
                }
                val r = rozpoznawacz
                if (r == null) {
                    // Nieosiagalne: po obu galeziach wyzej rozpoznawacz albo istnieje, albo
                    // wyszlismy z petli. Zostaje jako bezpiecznik, bo alternatywa (continue)
                    // krecilaby watek w kolko na pelnym gazie.
                    naGlownym(moje) { wyczerpane(tekstBrakModelu, "brak rozpoznawacza") }
                    return
                }

                val ile = mikrofon.read(bufor, 0, bufor.size)
                if (ile <= 0) {
                    // ERROR_INVALID_OPERATION (-3) po odebraniu uprawnienia w trakcie gry,
                    // ERROR_DEAD_OBJECT (-6) po przejeciu mikrofonu przez system. Obie sytuacje
                    // konczy ta sama droga: oddajemy decyzje ekranowi.
                    log("read() = " + ile)
                    naGlownym(moje) {
                        if (ile == AudioRecord.ERROR_INVALID_OPERATION) brakZgody("read " + ile)
                        else wyczerpane(tekstCiszy, "read " + ile)
                    }
                    return
                }
                probekWOdcinku += ile

                // POZIOM DZWIEKU. onRmsChanged jest udokumentowane jako "no guarantee that this
                // method will be called", wiec w drodze systemowej brak probek nie dowodzi
                // niczego. Tutaj probki sa NASZE: liczymy je z tego samego bufora, ktory idzie
                // do dekodera, wiec ich brak jest juz dowodem.
                szczyt = 0
                for (i in 0 until ile) {
                    val v = abs(bufor[i].toInt())
                    if (v > szczyt) szczyt = v
                }
                val poziom = poziomPaska(szczyt)
                naGlownym(moje) { poziomGlosu = poziom }
                if (szczyt > PROG_SYGNALU) {
                    probekBezDzwieku = 0
                    if (!cokolwiekSlychac) naGlownym(moje) { cokolwiekSlychac = true }
                    ciszaZgloszona = false
                } else {
                    probekBezDzwieku += ile
                    if (!ciszaZgloszona && probekBezDzwieku > CISZA_PROBEK) {
                        ciszaZgloszona = true
                        val przelacznik = przelacznikPrywatnosci()
                        log("cisza absolutna " + (probekBezDzwieku / 16000) + " s, przelacznik=" + przelacznik)
                        zapiszWDzienniku("cisza (szczyt 0)")
                        naGlownym(moje) { status = if (przelacznik) tekstPrzelacznik else tekstCiszy }
                    }
                }

                if (r.acceptWaveForm(bufor, ile)) {
                    val sekundy = probekWOdcinku / CZESTOTLIWOSC.toDouble()
                    probekWOdcinku = 0
                    val wynik = VoskGrammar.czytajWynik(
                        r.result, sekundy, uzytySlownik, kodJezyka(jezyk) == KOD_PL,
                    )
                    oddajKoncowy(moje, wynik, sekundy)
                    odOstatniegoCzesciowego = 0
                } else if (probekWOdcinku - odOstatniegoCzesciowego > CZESCIOWY_PROBEK) {
                    odOstatniegoCzesciowego = probekWOdcinku
                    val hipotezy = VoskGrammar.czytajCzesciowy(r.partialResult, uzytySlownik)
                    if (hipotezy.isNotEmpty()) oddajCzesciowy(moje, hipotezy)
                }
            }
        } catch (t: Throwable) {
            log("watek nasluchu: " + t.javaClass.simpleName + " " + t.message)
            naGlownym(moje) { wyczerpane(tekstBrakModelu, t.javaClass.simpleName) }
        } finally {
            try { rozpoznawacz?.close() } catch (_: Throwable) {}
            try { model?.close() } catch (_: Throwable) {}
            // stop() na nienagrywajacym AudioRecordzie rzuca IllegalStateException, a release()
            // na juz zwolnionym nie robi nic — obie drogi musza przejsc, bo od nich zalezy,
            // czy wskaznik nagrywania zgasnie i czy mikrofon wroci do innych aplikacji.
            try { audio?.stop() } catch (_: Throwable) {}
            try { audio?.release() } catch (_: Throwable) {}
            naGlownym(moje) { poziomGlosu = 0f }
        }
    }

    private fun oddajKoncowy(moje: Int, wynik: VoskGrammar.Wynik?, sekundy: Double) {
        if (wynik == null) {
            zapiszWDzienniku("cykl pusty")
            // Pusty cykl podnosi numer wypowiedzi tak samo jak cykl z wynikiem: ekran liczy
            // numery przebiegow, a nie trafien, i dwa "kolano" rozdzielone chrzaknieciem maja
            // sie zaliczyc osobno.
            naGlownym(moje) { wypowiedz += 1 }
            return
        }
        val opis = "wynik %.0f conf/s %s".format(
            wynik.pewnoscNaSekunde, if (wynik.przyjete) "przyjety" else "ODRZUCONY",
        )
        zapiszWDzienniku(opis)
        log(opis + " (odcinek %.2f s)".format(sekundy))
        naGlownym(moje) {
            cokolwiekSlychac = true
            // Wynik z mikrofonu jest dowodem, ze ta droga dziala — licznik nieudanych prob
            // ma liczyc awarie POD RZAD, a nie sumowac sie przez cala rozgrywke.
            nieudaneProby = 0
            status = ""
            if (wynik.przyjete) onHeard?.invoke(wynik.hipotezy, true)
            // Numer rosnie PO oddaniu wyniku — patrz [wypowiedz].
            wypowiedz += 1
        }
    }

    private fun oddajCzesciowy(moje: Int, hipotezy: List<String>) {
        naGlownym(moje) {
            cokolwiekSlychac = true
            onHeard?.invoke(hipotezy, false)
        }
    }

    /** Domkniecie wykonane na watku glownym i tylko wtedy, gdy nadal dotyczy biezacej sesji. */
    private fun naGlownym(moje: Int, co: () -> Unit) {
        handler.post {
            if (moje == pokolenie) co()
        }
    }

    private fun stopWatek() {
        chceNasluchu = false
        pokolenie += 1
        val t = watek ?: return
        watek = null
        t.interrupt()
        // Czekamy KROTKO i tylko po to, zeby mikrofon zdazyl sie zwolnic, zanim ktos zazada go
        // ponownie (np. natychmiastowe ponowne dotkniecie paska). Dluzsze czekanie na watku
        // glownym byloby zacieciem obrazu; petla i tak konczy sie sama po biezacym odczycie.
        try { t.join(DOLACZENIE_MS) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // ---- mikrofon --------------------------------------------------------------------------

    private fun otworzMikrofon(): AudioRecord? {
        val min = try {
            AudioRecord.getMinBufferSize(CZESTOTLIWOSC, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        } catch (_: Throwable) {
            AudioRecord.ERROR
        }
        if (min <= 0) {
            naGlownym(pokolenie) { wyczerpane(tekstGlosNiedostepny.format("buf", 0), "getMinBufferSize " + min) }
            return null
        }
        // Bufor sprzetowy CZTERY razy wiekszy od minimum. Minimum wystarcza tylko wtedy, gdy
        // odczyt nigdy sie nie spoznia; tutaj miedzy odczytami siedzi dekoder Vosk-a, wiec przy
        // buforze minimalnym gubilyby sie probki dokladnie w chwili, gdy uczen mowi.
        val rozmiar = max(min * 4, ROZMIAR_BUFORA * 2 * 4)
        val audio = try {
            AudioRecord(
                // VOICE_RECOGNITION, a nie MIC: ten tor ma wylaczone automatyczne wzmocnienie
                // i redukcje szumu nastawiona na mowe, czyli dokladnie to, na czym uczono model.
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                CZESTOTLIWOSC,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                rozmiar,
            )
        } catch (t: Throwable) {
            // Brak zgody na mikrofon rzuca tu SecurityException (a na czesci wersji Androida
            // konstruktor przechodzi i dopiero read() zwraca -3) — obie drogi prowadza do
            // onBrakZgody, bo dla ucznia to jedna i ta sama sprawa.
            log("AudioRecord: " + t.javaClass.simpleName)
            naGlownym(pokolenie) { brakZgody(t.javaClass.simpleName) }
            return null
        }
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            audio.release()
            naGlownym(pokolenie) { brakZgody("state " + audio.state) }
            return null
        }
        return audio
    }

    /**
     * Oprozniajacy odczyt bez blokowania — zabiera z bufora sprzetowego to, co sie w nim
     * nazbieralo, gdy petla byla zajeta czym innym.
     *
     * READ_NON_BLOCKING jest tu warunkiem dzialania: zwykly read() poczekalby na pelny bufor
     * i zamiast oproznic kolejke, dolozylby do niej kolejne 0,2 s.
     */
    private fun oproznijBufor(mikrofon: AudioRecord, bufor: ShortArray) {
        var rundy = 0
        while (rundy < MAX_OPROZNIEN) {
            val n = try {
                mikrofon.read(bufor, 0, bufor.size, AudioRecord.READ_NON_BLOCKING)
            } catch (_: Throwable) {
                return
            }
            if (n <= 0) return
            rundy += 1
        }
    }

    /**
     * Czy mikrofon jest odciety PRZELACZNIKIEM PRYWATNOSCI (Android 12+), a nie przez brak zgody.
     *
     * W AOSP przelacznik jest zrobiony jako globalne ograniczenie AppOps na OP_RECORD_AUDIO,
     * wiec publiczne zapytanie o ten op zwraca wtedy MODE_IGNORED mimo przyznanego uprawnienia.
     * `AudioManager.isMicrophoneMute` tego NIE pokazuje — to inny mechanizm (wyciszenie toru)
     * i sprawdzanie go byloby odpowiedzia na inne pytanie. `SensorPrivacyManager` tez odpada:
     * publiczne jest tylko `supportsSensorToggle()`, a `isSensorPrivacyEnabled()` to @SystemApi.
     */
    private fun przelacznikPrywatnosci(): Boolean {
        // Sam przelacznik istnieje dopiero od Androida 12. Nizej MODE_IGNORED tez sie zdarza,
        // ale znaczy co innego (op odebrany aplikacji), a komunikat mowi wprost o przelaczniku
        // — wiec tam nie pytamy, zamiast nazywac rzecz nie po imieniu.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        return try {
            // checkOpNoThrow, a nie unsafeCheckOpNoThrow z wiekszosci opisow tego mechanizmu:
            // ta druga jest w API 36 oznaczona jako przestarzala (sprawdzone w android.jar),
            // a checkOpNoThrow — nie. Odczyt jest ten sam; roznica dotyczy tego, czy dostep
            // jest odnotowywany w rejestrze prywatnosci, a my niczego nie odczytujemy z
            // mikrofonu, tylko pytamy o jego stan.
            ops.checkOpNoThrow(
                AppOpsManager.OPSTR_RECORD_AUDIO, Process.myUid(), context.packageName,
            ) == AppOpsManager.MODE_IGNORED
        } catch (_: Throwable) {
            false
        }
    }

    // ---- model -----------------------------------------------------------------------------

    private fun zbudujRozpoznawacz(model: Model?, slownik: VoskGrammar.Slownik): Recognizer? {
        val m = model ?: return null
        return try {
            Recognizer(m, CZESTOTLIWOSC.toFloat(), slownik.json).apply {
                // Obie linie sa warunkiem istnienia bramki pewnosci: bez setMaxAlternatives
                // wynik nie ma pola `confidence`, a bez setWords nie ma czasow slow, ktore
                // sluza diagnostyce. Patrz VoskGrammar.MAX_ALTERNATIVES.
                setMaxAlternatives(VoskGrammar.MAX_ALTERNATIVES)
                setWords(true)
            }
        } catch (t: Throwable) {
            log("Recognizer: " + t.javaClass.simpleName + " " + t.message)
            null
        }
    }

    private fun wczytajModel(kod: String): Model? {
        val katalog = katalogGotowegoModelu(kod) ?: return null
        return try {
            val t0 = android.os.SystemClock.elapsedRealtime()
            val m = Model(katalog.absolutePath)
            log("model " + kod + " wczytany w " + (android.os.SystemClock.elapsedRealtime() - t0) + " ms")
            m
        } catch (t: Throwable) {
            ostatniBlad = "Model: " + t.javaClass.simpleName
            log("Model(" + katalog + "): " + t.javaClass.simpleName + " " + t.message)
            null
        }
    }

    /**
     * Katalog z modelem gotowym do wczytania — rozpakowuje go z pakietu, gdy trzeba.
     *
     * Rozpakowujemy WLASNYM kodem do [Context.getFilesDir], a nie przez `StorageService.unpack`
     * z biblioteki: tamto kopiuje do `getExternalFilesDir(null)`, czyli do pamieci wspoldzielonej,
     * gdzie pliki widzi menedzer plikow, czysci je "czyszczenie pamieci" i na czesci telefonow
     * nie ma ich wcale, gdy karta jest odmontowana.
     *
     * Rozpakowujemy TYLKO ten jezyk, o ktory poproszono: uczen grajacy domyslnym kierunkiem
     * (odpowiedzi po polsku) nigdy nie zaplaci miejscem za model angielski.
     */
    private fun katalogGotowegoModelu(kod: String): File? {
        val cel = File(File(context.filesDir, KATALOG), kod)
        val znacznik = File(cel, ZNACZNIK)
        val oczekiwany = BuildConfig.VERSION_CODE.toString()
        if (znacznik.isFile && znacznik.readTextBezpiecznie() == oczekiwany) return cel
        return synchronized(zamekRozpakowania) {
            // Drugi raz pod zamkiem: watek nasluchu i watek przygotowania w tle moga trafic tu
            // jednoczesnie, a dwa rownolegle rozpakowania tego samego katalogu daja plik
            // przyciety w polowie i model, ktory nie wstaje.
            if (znacznik.isFile && znacznik.readTextBezpiecznie() == oczekiwany) return@synchronized cel
            try {
                cel.deleteRecursively()
                val bajty = rozpakuj(ZRODLO + "/" + kod, cel)
                if (bajty <= 0L) return@synchronized null
                znacznik.writeText(oczekiwany)
                log("model " + kod + " rozpakowany: " + (bajty / 1_000_000) + " MB do " + cel)
                cel
            } catch (t: Throwable) {
                ostatniBlad = "rozpakowanie: " + t.javaClass.simpleName
                log("rozpakowanie " + kod + ": " + t.javaClass.simpleName + " " + t.message)
                // Polowa modelu na dysku jest gorsza niz nic: zajmuje miejsce i przy nastepnym
                // wejsciu wygladalaby na gotowa, gdyby znacznik zdazyl powstac.
                cel.deleteRecursively()
                null
            }
        }
    }

    /** Kopiuje podkatalog pakietu na dysk. Zwraca liczbe przepisanych bajtow (0 = nic nie bylo). */
    private fun rozpakuj(sciezkaWPakiecie: String, cel: File): Long {
        val nazwy = context.assets.list(sciezkaWPakiecie) ?: return 0L
        if (nazwy.isEmpty()) {
            // Liscia w drzewie pakietu jest PLIK — AssetManager.list zwraca dla niego pusta
            // tablice, nie null, wiec to jedyny sposob odroznienia pliku od pustego katalogu.
            cel.parentFile?.mkdirs()
            return context.assets.open(sciezkaWPakiecie).use { we ->
                cel.outputStream().use { wy -> we.copyTo(wy, KOPIA_BUFOR) }
            }
        }
        cel.mkdirs()
        var suma = 0L
        for (nazwa in nazwy) suma += rozpakuj(sciezkaWPakiecie + "/" + nazwa, File(cel, nazwa))
        return suma
    }

    private fun modelJestWPakiecie(kod: String): Boolean = try {
        // Sam katalog nie wystarczy: pusty katalog tez zwroci niepusta liste rodzica.
        context.assets.list(ZRODLO + "/" + kod + "/am")?.isNotEmpty() == true
    } catch (_: Throwable) {
        false
    }

    /**
     * Czy producent oznaczyl ten telefon jako maloplamieciowy.
     *
     * Model polski zajmuje po odchudzeniu 113 MB pamieci rezydentnej (pomiar), angielski 85 MB
     * — na urzadzeniu z flaga low-RAM to polowa tego, co system w ogole daje aplikacji, wiec
     * gra zostalaby ubita w trakcie rundy. Drugiego progu (np. na `MemoryInfo.totalMem`) tu
     * celowo nie ma: byl by zgadywany, a `isLowRamDevice` jest dokladnie po to i ustawia ja
     * ten, kto zna to urzadzenie.
     */
    private fun urzadzenieMaloplamieciowe(): Boolean = try {
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
    } catch (_: Throwable) {
        false
    }

    // ---- drobiazgi -------------------------------------------------------------------------

    private fun wyczerpane(komunikat: String, powod: String) {
        log("wyczerpane: " + powod)
        zapiszWDzienniku("koniec: " + powod)
        ostatniBlad = powod
        nieudaneProby += 1
        chceNasluchu = false
        listening = false
        przygotowanie = false
        wyczerpany = true
        poziomGlosu = 0f
        status = komunikat
        onExhausted?.invoke()
    }

    private fun brakZgody(powod: String) {
        log("brak zgody: " + powod)
        zapiszWDzienniku("brak zgody")
        ostatniBlad = "zgoda/" + powod
        chceNasluchu = false
        listening = false
        przygotowanie = false
        poziomGlosu = 0f
        status = tekstBrakZgody.format(powod)
        onBrakZgody?.invoke()
    }

    private fun sprawdzOkno(): Boolean = try {
        val i = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.resolveActivity(context.packageManager) != null
    } catch (_: Throwable) {
        false
    }

    /** Pelny obraz drogi offline — trafia do tej samej nakladki co [VoiceInput.diagnostyka]. */
    fun diagnostyka(): String = buildString {
        append("Android ").append(Build.VERSION.SDK_INT)
        append(" · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        append("silnik: WBUDOWANY (Vosk, bez usługi systemowej)").append('\n')
        append("model w pakiecie: ").append(if (modelWPakiecie) "tak" else "NIE")
        append(" · mała pamięć: ").append(if (malaPamiec) "TAK" else "nie")
        append(" · okno: ").append(if (oknoDostepne) "tak" else "NIE").append('\n')
        for ((tag, kod) in KODY_JEZYKA) {
            val k = File(File(context.filesDir, KATALOG), kod)
            append(tag).append(": ")
            append(if (File(k, ZNACZNIK).isFile) "rozpakowany" else if (modelJestWPakiecie(kod)) "w pakiecie" else "brak")
            append(" · ")
        }
        append('\n')
        append("język: ").append(jezyk)
        append(" · kierunek: ").append(if (kierunek.isEmpty()) "—" else kierunek)
        append(" · słów w gramatyce: ").append(slownik.slowa.size).append('\n')
        append("stan: ").append(stan.name.lowercase())
        append(" · wypowiedź: ").append(wypowiedz)
        append(" · słychać dźwięk: ").append(if (cokolwiekSlychac) "tak" else "NIE")
        append(" · przełącznik prywatności: ").append(if (przelacznikPrywatnosci()) "TAK" else "nie").append('\n')
        append("ostatni błąd: ").append(ostatniBlad.ifEmpty { "—" })
        append(" · nieudanych prób: ").append(nieudaneProby).append('\n')
        append("— przebieg —\n").append(synchronized(dziennik) { dziennik.tekst() })
    }

    private fun log(co: String) {
        if (BuildConfig.DEBUG) Log.i(VOICE_TAG, "vosk: " + co)
    }

    private fun File.readTextBezpiecznie(): String = try {
        readText().trim()
    } catch (_: Throwable) {
        ""
    }

    // Companion jest `internal`, a nie prywatny, z jednego powodu: dwie funkcje ponizej
    // (przeliczenie poziomu na pasek i wybor modelu z tagu jezyka) to czysta logika, ktora
    // psuje sie po cichu — zly wykladnik daje pasek stojacy pusty przy normalnej mowie,
    // a zle mapowanie tagu wczytuje model drugiego jezyka. Testy musza je widziec.
    internal companion object {
        /** Model jest uczony na 16 kHz mono — inna czestotliwosc to inny model, nie inna opcja. */
        const val CZESTOTLIWOSC = 16_000

        /**
         * Ile probek bierzemy z mikrofonu na raz (0,2 s przy 16 kHz).
         *
         * Krocej znaczy czesciej budzic watek i czesciej wolac dekoder bez potrzeby; dluzej
         * znaczy pasek glosu skaczacy co pol sekundy i wynik czesciowy przychodzacy z wyraznym
         * opoznieniem. 0,2 s to ten sam rzad, w ktorym pracuje AudioRecord na wiekszosci
         * telefonow (minimalny bufor to zwykle 0,04-0,1 s).
         */
        const val ROZMIAR_BUFORA = 3_200

        /**
         * Poziom, powyzej ktorego uznajemy, ze do mikrofonu cokolwiek wchodzi (probka 16-bitowa).
         *
         * To NIE jest prog mowy, tylko prog ZYCIA toru audio. Mikrofon odciety przelacznikiem
         * prywatnosci albo odebrany przez system oddaje dokladne zera; nawet zupelnie cichy
         * pokoj daje kilkadziesiat jednostek szumu. Dwadziescia cztery to z zapasem powyzej
         * zera i gleboko ponizej czegokolwiek slyszalnego.
         */
        const val PROG_SYGNALU = 24

        /** Ile probek ciszy absolutnej (3 s) zamienia sie w podpowiedz o gluchym mikrofonie. */
        const val CISZA_PROBEK = 3L * CZESTOTLIWOSC

        /**
         * Co ile probek (0,3 s) pytamy o wynik czesciowy.
         *
         * Wynik czesciowy sluzy tylko temu, zeby trafienie zaliczylo sie, zanim uczen skonczy
         * milczec po slowie. Pytanie co bufor byloby marnowaniem pracy: Vosk i tak przelicza
         * hipoteze dopiero po kilku klatkach.
         */
        const val CZESCIOWY_PROBEK = 4_800L

        /**
         * Ile watek glowny czeka na zakonczenie watku nasluchu przy stop().
         *
         * Nie chodzi o porzadek — watek konczy sie sam — tylko o to, zeby mikrofon zdazyl
         * wrocic, zanim ktos zazada go ponownie (dwa dotkniecia paska pod rzad). Dluzej nie
         * mozna, bo to czekanie na watku rysujacym: 120 ms to najwyzej kilka klatek, a stop()
         * leci takze przy pauzie i przy zejsciu w tlo. Gdy to nie wystarczy, mikrofon otwiera
         * sie za druga proba (patrz MAX_OPROZNIEN nizej i obsluga recordingState).
         */
        const val DOLACZENIE_MS = 120L

        /** Ile razy oprozniamy bufor mikrofonu po przerwie w odczycie (0,2 s na runde). */
        const val MAX_OPROZNIEN = 25

        /** Ile razy probujemy zajac mikrofon, zanim uznamy go za zajety przez kogos innego. */
        const val START_PROB = 3
        /** Przerwa miedzy tymi probami — tyle mniej wiecej trwa oddanie sprzetu przez poprzednika. */
        const val START_ODSTEP_MS = 120L

        /** Ile razy pod rzad wolno probowac po nieudanym starcie, zanim droga offline odpadnie. */
        const val MAX_PROB = 6

        const val KATALOG = "vosk"
        const val ZRODLO = "vosk"
        const val ZNACZNIK = ".gotowy"
        const val KOD_PL = "pl"
        const val KOPIA_BUFOR = 64 * 1024

        /** Tag jezyka gry -> katalog modelu. Innych jezykow gra nie zna. */
        val KODY_JEZYKA = linkedMapOf("pl-PL" to "pl", "en-US" to "en")

        /** Rozpakowanie to jedyna rzecz, ktora moga robic dwa watki naraz. */
        val zamekRozpakowania = Any()

        fun kodJezyka(tag: String): String =
            KODY_JEZYKA[tag] ?: if (tag.startsWith("pl", ignoreCase = true)) "pl" else "en"

        /**
         * Szczyt probki -> wypelnienie paska 0..1.
         *
         * Skala jest logarytmiczna, bo ucho tez: przy skali liniowej pasek stoi pusty przy
         * normalnej mowie (szczyt ok. 3000 z 32768) i wychyla sie dopiero przy krzyku.
         * Dolna granica -48 dBFS to poziom cichego pokoju.
         */
        fun poziomPaska(szczyt: Int): Float {
            if (szczyt <= 0) return 0f
            val db = 20.0 * log10(szczyt / 32768.0)
            return ((db + 48.0) / 48.0).coerceIn(0.0, 1.0).toFloat()
        }
    }
}

/** Trzyma [VoskVoiceInput] przy zyciu na czas ekranu i zwalnia mikrofon przy wyjsciu. */
@Composable
fun rememberVoskVoiceInput(context: Context): VoskVoiceInput {
    val voice = remember(context) { VoskVoiceInput(context).also { it.refresh() } }
    DisposableEffect(voice) { onDispose { voice.release() } }
    return voice
}
