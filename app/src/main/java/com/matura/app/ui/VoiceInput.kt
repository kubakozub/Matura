package com.matura.app.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

/** Ktory silnik faktycznie obsluguje nasluch. */
enum class VoiceMode { UNAVAILABLE, ON_DEVICE, ONLINE }

/**
 * Cienka warstwa nad [SpeechRecognizer]. Cala celnosc siedzi w [com.matura.app.game.VoiceMatch];
 * tutaj chodzi wylacznie o to, zeby nasluch dzialal ciagle i zeby bylo widac, co sie dzieje.
 *
 * Trzy rzeczy, ktore Android robi inaczej niz przegladarka:
 *  - rozpoznawanie konczy sie po KAZDEJ wypowiedzi, wiec wznawiamy je sami;
 *  - [RecognizerIntent.EXTRA_BIASING_STRINGS] dziala takze poza trybem on-device
 *    (w Chrome biasowanie wymaga processLocally), wiec slownik zestawu podajemy zawsze;
 *  - [RecognizerIntent.EXTRA_PREFER_OFFLINE] pozwala poprosic o rozpoznanie bez sieci,
 *    co jest wazne, bo aplikacja obiecuje dzialanie bez internetu.
 *
 * Od 0.9.7 obowiazuje zasada "nigdy slepa uliczka": ta klasa nigdy nie konczy komunikatem
 * o porazce, tylko oddaje decyzje ekranowi przez [onExhausted] (okno systemowe / klawiatura
 * telefonu) albo [onBrakZgody] (ustawienia aplikacji). Do tego watchdog na milczacy silnik
 * i wykrywanie gluchego mikrofonu z poziomu RMS ([SilenceDetector]).
 */
class VoiceInput(private val context: Context) {

    var mode by mutableStateOf(VoiceMode.UNAVAILABLE)
        private set
    var listening by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
        private set
    /** Czy system ma aplikacje obslugujaca okno ACTION_RECOGNIZE_SPEECH. Odswiezane w [refresh]. */
    var oknoDostepne by mutableStateOf(false)
        private set

    /** Podpowiedz o gluchym mikrofonie — ekran podstawia wersje z Str (PL/EN). */
    var tekstCiszy: String = "Mikrofon nic nie słyszy — wyłączony lub zajęty?"

    private var recognizer: SpeechRecognizer? = null
    private var wantListening = false
    private var language = "pl-PL"
    private var biasing: List<String> = emptyList()
    private var onHeard: ((List<String>, Boolean) -> Unit)? = null
    /** Wolane, gdy WSZYSTKIE szczeble fallbacku zawiodly — ekran moze wtedy siegnac
     *  po okno systemowe, ktore dziala inna droga niz wiazanie z usluga. */
    var onExhausted: (() -> Unit)? = null
    /** Wolane, gdy silnik odmowil z powodu braku zgody na mikrofon (ERROR_INSUFFICIENT_PERMISSIONS). */
    var onBrakZgody: (() -> Unit)? = null

    // Drabinka fallbacku silnika. Wiele urzadzen (np. Samsung) ma silnik on-device
    // "dostepny", ktory i tak odrzuca prosbe z EXTRA_PREFER_OFFLINE bledem ERROR_CLIENT.
    // Wtedy schodzimy nizej: silnik sieciowy Google, a potem bez wymuszania offline.
    private var preferOffline = true
    private var attemptedOnline = false

    // Lista KONKRETNYCH silnikow zainstalowanych na urzadzeniu. Domyslny silnik bywa
    // producenta (Samsung/Bixby) i potrafi odmawiac obslugi aplikacjom trzecim, mimo ze
    // `isRecognitionAvailable` zwraca true. Wtedy jedynym wyjsciem jest zwiazanie sie
    // z konkretna usluga po ComponentName — najczesciej dziala ta od Google.
    private var silniki: List<ComponentName> = emptyList()
    private var silnikIdx = -1

    /**
     * Drabinka doszla do konca ([onExhausted] juz poszlo do ekranu). Do nastepnej ZMIANY listy
     * uslug albo trybu z sondy [maSilnik] jest false — inaczej kazde dotkniecie najpierw
     * mieliloby ten sam martwy silnik (szybki ERROR_CLIENT albo pelne 6 s "SŁUCHAM…"),
     * zanim ekran doszedlby do okna systemowego, a podpis dalej klamalby "online".
     */
    private var wyczerpany = false
    /** Kod bledu, ktory zamknal drabinke, i chwila zamkniecia — do decyzji, czy blokada wygasa. */
    private var wyczerpanyKod = 0
    private var wyczerpanyOd = 0L

    /** Czy biezacy silnik dal znak zycia (gotowosc albo wynik). Decyduje o resecie drabinki. */
    private var dzialal = false
    /** Watchdog: czekamy na PIERWSZE zdarzenie od silnika po startListening. */
    private var czekamNaSilnik = false
    private var bledyAudio = 0
    private var bledyBusy = 0
    private val cisza = SilenceDetector()

    private fun dostepneSilniki(): List<ComponentName> = try {
        context.packageManager
            .queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .mapNotNull { it.serviceInfo?.let { s -> ComponentName(s.packageName, s.name) } }
            .sortedBy { if (it.packageName.contains("google", true)) 0 else 1 }
    } catch (_: Throwable) { emptyList() }

    private fun sprawdzOkno(): Boolean = try {
        context.packageManager.resolveActivity(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0) != null
    } catch (_: Throwable) { false }

    private val handler = Handler(Looper.getMainLooper())

    /** Czy na tym urzadzeniu w ogole jest jakikolwiek silnik mowy. */
    fun probe() {
        mode = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context) -> VoiceMode.ON_DEVICE
            SpeechRecognizer.isRecognitionAvailable(context) -> VoiceMode.ONLINE
            else -> VoiceMode.UNAVAILABLE
        }
    }

    /**
     * Ponowna sonda NA DOTKNIECIE paska. Lista uslug, okno systemowe i tryb moga sie zmienic
     * w trakcie gry (instalacja aplikacji Google, pobranie pakietu jezyka), a raz ustalone
     * UNAVAILABLE nie moze byc wyrokiem na cala rozgrywke.
     *
     * Biezacy silnik z drabinki zostaje ten sam i zostaje PIERWSZY na liscie: sortowanie
     * Google-first z [dostepneSilniki] nie moze cofac kolejnosci ustawionej w [resetujDrabinke],
     * bo [escalate] idzie tylko do przodu (silnikIdx + 1) i uslugi przed biezacym nigdy nie
     * dostalyby drugiej szansy w nowej sesji.
     */
    fun refresh() {
        val stare = silniki
        val trybPrzed = mode
        val biezacy = stare.getOrNull(silnikIdx)
        val nowe = dostepneSilniki()
        when {
            biezacy == null -> { silniki = nowe; silnikIdx = -1 }
            biezacy in nowe -> { silniki = listOf(biezacy) + nowe.filter { it != biezacy }; silnikIdx = 0 }
            else -> { silniki = nowe; silnikIdx = -1; zwolnijRecognizer() }
        }
        oknoDostepne = sprawdzOkno()
        if (mode == VoiceMode.UNAVAILABLE) probe()
        // Wyczerpana drabinka wraca do gry tylko, gdy cos sie naprawde zmienilo: inna lista
        // uslug albo inny tryb z sondy. Samo dotkniecie nie jest zmiana.
        // Blokada znika, gdy: zmienila sie lista uslug albo tryb, przyczyna byla przejsciowa
        // (siec, serwer, cichy silnik przy watchdogu) albo po prostu minelo dosc czasu. Trwale
        // zostaja tylko awarie strukturalne (np. brak pakietu jezyka) i tylko do konca rozgrywki.
        val przejsciowy = wyczerpanyKod in setOf(
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER,
            KOD_WATCHDOG,
        )
        val ostyglo = android.os.SystemClock.elapsedRealtime() - wyczerpanyOd > BLOKADA_MS
        if (wyczerpany && (nowe.toSet() != stare.toSet() || mode != trybPrzed || przejsciowy || ostyglo)) {
            wyczerpany = false
            silnikIdx = -1
            preferOffline = true
            attemptedOnline = false
        }
    }

    /**
     * Czy jest cokolwiek, z czym da sie zwiazac: silnik domyslny albo konkretna usluga —
     * i czy drabinka nie jest juz wyczerpana. To wejscie dla VoicePolicy (droga i podpis).
     */
    val maSilnik: Boolean
        get() = !wyczerpany && (mode != VoiceMode.UNAVAILABLE || silniki.isNotEmpty())

    private fun create(): SpeechRecognizer? = when (mode) {
        VoiceMode.ON_DEVICE -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } catch (_: Throwable) {
                // Dostepnosc on-device zmienila sie miedzy sonda a dotknieciem (usuniety pakiet
                // jezyka, wylaczony silnik) — createOnDeviceSpeechRecognizer rzuca wtedy
                // UnsupportedOperationException. Zamiast wywrotki: zwykly silnik, bez wymuszania
                // offline; null i tak trafia w start()/scheduleRestart() na drabinke lub onExhausted.
                mode = VoiceMode.ONLINE
                preferOffline = false
                zwyklyRecognizer()
            }
        } else null
        // Gdy drabinka doszla do konkretnego silnika, wiazemy sie WPROST z nim.
        VoiceMode.ONLINE -> silniki.getOrNull(silnikIdx)
            ?.let { try { SpeechRecognizer.createSpeechRecognizer(context, it) } catch (_: Throwable) { null } }
            ?: zwyklyRecognizer()
        VoiceMode.UNAVAILABLE -> null
    }

    private fun zwyklyRecognizer(): SpeechRecognizer? = try {
        SpeechRecognizer.createSpeechRecognizer(context)
    } catch (_: Throwable) { null }

    private fun zwolnijRecognizer() {
        try { recognizer?.destroy() } catch (_: Throwable) {}
        recognizer = null
    }

    /** Ostatni kod bledu i silnik, na ktorym padl — do odczytu z ekranu. */
    var ostatniBlad by mutableStateOf(0)
        private set
    var probowane = mutableListOf<String>()
        private set

    private fun zanotujProbe(wpis: String) {
        probowane.add(wpis)
        // Historia do diagnostyki, nie dziennik — przy wielokrotnym wlaczaniu nie ma rosnac bez konca.
        while (probowane.size > MAX_PROBOWANE) probowane.removeAt(0)
    }

    /** Pelny obraz sytuacji na TYM urzadzeniu. Bez tego kazda kolejna awaria to zgadywanie. */
    fun diagnostyka(): String {
        if (silniki.isEmpty()) silniki = dostepneSilniki()
        val naUrzadzeniu = try {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        } catch (_: Throwable) { false }
        val jestUsluga = try { SpeechRecognizer.isRecognitionAvailable(context) } catch (_: Throwable) { false }
        oknoDostepne = sprawdzOkno()
        return buildString {
            append("Android ").append(Build.VERSION.SDK_INT)
            append(" · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("on-device: ").append(if (naUrzadzeniu) "tak" else "nie")
            append(" · usługa: ").append(if (jestUsluga) "tak" else "nie")
            append(" · okno: ").append(if (oknoDostepne) "tak" else "NIE").append('\n')
            append("silniki (").append(silniki.size).append("): ")
            append(if (silniki.isEmpty()) "brak" else silniki.joinToString(", ") { it.packageName })
            append('\n').append("próbowano: ")
            append(if (probowane.isEmpty()) "—" else probowane.joinToString(", "))
            append('\n').append("ostatni błąd: ").append(ostatniBlad)
            append(" · drabinka: ").append(if (wyczerpany) "wyczerpana" else "ok")
            append(" · rms max: ")
            append(if (cisza.maxRms == Float.NEGATIVE_INFINITY) "—" else "%.1f".format(cisza.maxRms))
            append(" · wyciszony: ").append(if (mikrofonWyciszony()) "tak" else "nie")
        }
    }

    /** Nazwa silnika, ktory wlasnie probujemy — do komunikatu diagnostycznego. */
    val silnikOpis: String
        get() = silniki.getOrNull(silnikIdx)?.packageName?.substringAfterLast('.') ?: "domyślny"

    private fun buildIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_ALTERNATIVES)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        // Prosimy o rozpoznanie bez sieci — ale tylko dopoki silnik to akceptuje.
        // Gdy odmowi (ERROR_CLIENT), escalate() wylacza offline i dzwiek moze pojsc siecia.
        if (preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && biasing.isNotEmpty()) {
            // Slownik zestawu: silnik nie zgaduje z calego jezyka, tylko wazy te slowa.
            putStringArrayListExtra(
                RecognizerIntent.EXTRA_BIASING_STRINGS,
                ArrayList(biasing.take(MAX_BIASING_WORDS)),
            )
        }
    }

    // WATCHDOG. Czesc silnikow (widziane: producenta, po odinstalowaniu pakietu jezyka) przyjmuje
    // startListening i milczy — ani onReadyForSpeech, ani onError. Bez limitu czasu przycisk
    // "SŁUCHAM…" swiecilby w nieskonczonosc. Po WATCHDOG_MS bez zadnego zdarzenia traktujemy
    // silnik jak zepsuty i schodzimy po drabince; gdy drabinki nie ma — oddajemy ekranowi.
    private val watchdog = Runnable {
        if (!wantListening || !czekamNaSilnik) return@Runnable
        czekamNaSilnik = false
        ostatniBlad = ERROR_WATCHDOG
        zanotujProbe(silnikOpis + ":" + ERROR_WATCHDOG)
        if (!escalate()) wyczerpane(friendlyError(ERROR_WATCHDOG))
    }

    private fun uzbrojWatchdog() {
        czekamNaSilnik = true
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_MS)
    }

    /** Silnik dal znak zycia — watchdog schodzi z posterunku do nastepnego startu. */
    private fun zyje() {
        czekamNaSilnik = false
        handler.removeCallbacks(watchdog)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            // Gotowosc NIE zeruje licznika ERROR_AUDIO: silnik z mikrofonem zajetym przez inna
            // aplikacje potrafi zglaszac onReadyForSpeech i zaraz ERROR_AUDIO, w kolko — licznik
            // nigdy nie doszedlby do limitu. Zeruje go dopiero dowod, ze dzwiek plynie (wynik
            // albo pelny cykl nasluchu bez mowy).
            zyje(); dzialal = true
            // Podpowiedz o gluchym mikrofonie ma zostac, dopoki cos nie wejdzie — nasluch trwa dalej.
            if (!cisza.active && bledyAudio == 0 && !mikrofonWyciszony()) status = ""
        }
        override fun onBeginningOfSpeech() { zyje() }
        override fun onRmsChanged(rmsdB: Float) { zyje(); cisza.onRms(rmsdB) }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            if (!wantListening) return
            zyje(); dzialal = true
            deliver(partialResults, final = false)
        }

        override fun onResults(results: Bundle?) {
            if (!wantListening) return
            zyje(); dzialal = true
            deliver(results, final = true)
            restartSoon()
        }

        override fun onError(error: Int) {
            // Po stop() silnik potrafi jeszcze odbic bledem (klasycznie ERROR_CLIENT po cancel).
            // Bez tego strazu spozniony kod schodzil drabinke i gasil glos na cala rozgrywke.
            if (!wantListening) return
            zyje()
            when (error) {
                // Cisza i brak dopasowania sa normalne przy ciaglym nasluchu. Ale dwa puste cykle
                // z rzedu przy RMS na dnie to mikrofon, ktory nic nie slyszy — podpowiadamy
                // (przelacznik w ustawieniach szybkich, inna aplikacja), nasluch trwa dalej.
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    bledyAudio = 0
                    if (cisza.onEmptyCycle()) status = tekstCiszy
                    restartSoon()
                }

                // Zajety silnik: kilka ponowien ma sens (poprzednia sesja jeszcze sie sprzata),
                // ale nie w nieskonczonosc z zapalonym "SŁUCHAM…" — po limicie traktujemy to jak
                // kazda inna awarie i schodzimy drabinka nizej.
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    ostatniBlad = error
                    bledyBusy += 1
                    if (bledyBusy < MAX_BUSY_RETRIES) restartSoon()
                    else {
                        zanotujProbe(silnikOpis + ":" + error)
                        if (!escalate()) wyczerpane(friendlyError(error))
                    }
                }

                // Brak zgody to sprawa ekranu (ustawienia aplikacji), nie drabinki silnikow —
                // ale tylko gdy aplikacja NAPRAWDE nie ma RECORD_AUDIO. Silnik potrafi zglosic
                // kod 9 z wlasnej winy (usluga producenta bez wlasnej zgody, kaprys AppOps);
                // wtedy ustawienia nic nie dadza (mikrofon juz jest dozwolony), a nastepny silnik
                // z drabinki (Google) zwykle dziala — traktujemy to jak kazdy inny blad.
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    ostatniBlad = error
                    if (maZgode()) {
                        zanotujProbe(silnikOpis + ":" + error)
                        if (!escalate()) wyczerpane(friendlyError(error))
                    } else {
                        status = "brak zgody na mikrofon (" + error + ")"
                        stop()
                        onBrakZgody?.invoke()
                    }
                }

                // Blad nagrywania (3): mikrofon zajety przez inna aplikacje albo chwilowo niedostepny.
                // Jedna szybka ponowka; potem podpowiedz i rzadsze proby, az mikrofon sie zwolni
                // albo uczen sam wylaczy nasluch. Limit, zeby nie mielic w nieskonczonosc.
                SpeechRecognizer.ERROR_AUDIO -> {
                    ostatniBlad = error
                    bledyAudio += 1
                    when {
                        bledyAudio == 1 -> scheduleRestart(AUDIO_RETRY_MS)
                        bledyAudio < MAX_AUDIO_RETRIES -> { status = tekstCiszy; scheduleRestart(AUDIO_RETRY_SLOW_MS) }
                        else -> { status = tekstCiszy; stop() }
                    }
                }

                // Kazdy inny blad (ERROR_CLIENT=5, ERROR_SERVER, brak pakietu jezyka…)
                // najpierw probujemy obejsc, schodzac na nastepny silnik. Dopiero gdy
                // drabinka sie skonczy — pokazujemy komunikat i oddajemy decyzje ekranowi.
                else -> {
                    ostatniBlad = error
                    zanotujProbe(silnikOpis + ":" + error)
                    if (!escalate()) wyczerpane(friendlyError(error))
                }
            }
        }
    }

    /** Kolejny szczebel fallbacku. Zwraca true, jesli ponowiono nasluch innym silnikiem. */
    private fun escalate(): Boolean {
        if (!wantListening) return false
        // 1) on-device odmawia -> sprobuj silnika sieciowego (zwykle Google), bez offline.
        if (mode == VoiceMode.ON_DEVICE && !attemptedOnline &&
            SpeechRecognizer.isRecognitionAvailable(context)
        ) {
            attemptedOnline = true
            useEngine(VoiceMode.ONLINE, offline = false)
            scheduleRestart(60L)
            return true
        }
        // 2) silnik sieciowy z wymuszonym offline -> ponow bez offline (pozwol na siec).
        if (mode == VoiceMode.ONLINE && preferOffline) {
            preferOffline = false
            scheduleRestart(60L)
            return true
        }
        // 3) domyslny silnik odmawia -> przechodzimy po KONKRETNYCH uslugach z urzadzenia,
        //    zaczynajac od Google. To jest szczebel, ktorego brakowalo: na czesci telefonow
        //    (Samsung) domyslna usluga nalezy do producenta i nie obsluguje aplikacji trzecich,
        //    a usluga Google siedzi obok i dziala, tylko trzeba sie z nia zwiazac po nazwie.
        if (silniki.isEmpty()) silniki = dostepneSilniki()
        if (silnikIdx + 1 < silniki.size) {
            silnikIdx += 1
            useEngine(VoiceMode.ONLINE, offline = false)
            scheduleRestart(80L)
            return true
        }
        return false
    }

    /** Przelaczenie silnika: niszczy obecny recognizer i tworzy nowy pod nowy tryb. */
    private fun useEngine(newMode: VoiceMode, offline: Boolean) {
        try { recognizer?.destroy() } catch (_: Throwable) {}
        mode = newMode
        preferOffline = offline
        dzialal = false
        recognizer = create()?.also { it.setRecognitionListener(listener) }
    }

    /**
     * Poprzednia sesja skonczyla sie na DZIALAJACYM silniku: zostaje pierwszy w kolejce
     * (nie zaczynamy znow od zepsutego on-device), ale reszta drabinki wraca do dyspozycji,
     * gdyby tym razem odmowil — np. po utracie sieci albo gdy inna aplikacja zajela usluge.
     */
    private fun resetujDrabinke() {
        attemptedOnline = false
        when (mode) {
            VoiceMode.ON_DEVICE -> { silnikIdx = -1; preferOffline = true }
            VoiceMode.ONLINE -> {
                val dzialajacy = silniki.getOrNull(silnikIdx)
                if (dzialajacy != null) {
                    silniki = listOf(dzialajacy) + silniki.filter { it != dzialajacy }
                    silnikIdx = 0
                }
            }
            VoiceMode.UNAVAILABLE -> {}
        }
    }

    // Kod bledu ZOSTAJE w komunikacie. Wersja 0.9.3 zastapila surowy kod ladnym zdaniem
    // i przez to kolejna awaria byla nie do zdiagnozowania z opisu uzytkownika. Kod jest
    // maly i w nawiasie — nie przeszkadza, a ratuje pol godziny zgadywania.
    private fun friendlyError(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "brak internetu dla głosu — wpisz odpowiedź"
        13, 12 -> "brak pakietu mowy PL — wpisz odpowiedź" // LANGUAGE_UNAVAILABLE / NOT_SUPPORTED
        ERROR_WATCHDOG -> "silnik mowy nie odpowiada (" + error + "/" + silniki.size + ") — wpisz odpowiedź"
        else -> "głos niedostępny (" + error + "/" + silniki.size + ") — wpisz odpowiedź"
    }

    private fun deliver(bundle: Bundle?, final: Boolean) {
        val list = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
        if (list.isEmpty()) return
        cisza.onSpeech()
        bledyAudio = 0 // wynik = dzwiek plynie; dopiero to kasuje serie ERROR_AUDIO
        bledyBusy = 0
        onHeard?.invoke(list.toList(), final)
    }

    /** Android konczy nasluch po kazdej wypowiedzi - wznawiamy, dopoki gracz chce. */
    private fun restartSoon() = scheduleRestart(150L)

    /**
     * Wznowienie nasluchu z krotka zwloka. Bez zwloki natychmiastowy cancel()+startListening()
     * na czesci urzadzen sam wywoluje ERROR_CLIENT/ERROR_RECOGNIZER_BUSY, wiec dajemy silnikowi
     * chwile na sprzatniecie poprzedniej sesji. Wszystko idzie glownym watkiem (wymog SpeechRecognizer).
     */
    private fun scheduleRestart(delayMs: Long) {
        if (!wantListening) return
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (!wantListening) return@postDelayed
            val r = recognizer
            if (r == null) {
                // Szczebel bez recognizera (np. on-device ponizej Androida 12) — dalej po drabince.
                if (!escalate()) wyczerpane(friendlyError(ostatniBlad))
                return@postDelayed
            }
            cisza.onCycleStart()
            try {
                r.cancel()
                r.startListening(buildIntent())
            } catch (_: Throwable) {
                // Wyjatek przy starcie nie daje zadnego zdarzenia zwrotnego — watchdog ponizej
                // potraktuje to jak martwy silnik i zejdzie na nastepny szczebel.
            }
            uzbrojWatchdog()
        }, delayMs)
    }

    fun start(
        language: String,
        biasing: List<String>,
        onHeard: (List<String>, Boolean) -> Unit,
    ) {
        if (silniki.isEmpty()) silniki = dostepneSilniki()
        if (mode == VoiceMode.UNAVAILABLE) probe()
        // Domyslny silnik "niedostepny", ale konkretne uslugi sa (zdarza sie po wylaczeniu
        // aplikacji producenta): wiazemy sie od razu z pierwsza z listy.
        if (mode == VoiceMode.UNAVAILABLE && silniki.isNotEmpty()) {
            mode = VoiceMode.ONLINE; silnikIdx = 0; preferOffline = false
        }
        // Brak czegokolwiek do zwiazania NIE jest koncem: ekran ma jeszcze okno systemowe
        // i klawiature telefonu. Oddajemy mu decyzje zamiast zostawiac komunikat o porazce.
        if (mode == VoiceMode.UNAVAILABLE) {
            wyczerpane()
            return
        }
        // Nie sondujemy na nowo, jesli poprzednio doszlismy fallbackiem do dzialajacego
        // silnika — inaczej co wlaczenie zaczynaloby od zepsutego on-device. Ale drabinka
        // pod nim wraca do dyspozycji.
        if (dzialal) resetujDrabinke()
        dzialal = false
        this.language = language
        this.biasing = biasing
        this.onHeard = onHeard
        if (recognizer == null) recognizer = create()
        val r = recognizer ?: run { wyczerpane(); return }
        r.setRecognitionListener(listener)
        wantListening = true
        listening = true
        bledyBusy = 0
        status = ""
        bledyAudio = 0
        cisza.reset()
        // Wyciszenie programowe (AudioManager.isMicrophoneMute) widac od razu — nie trzeba czekac
        // na dwa puste cykle. Przelacznika prywatnosci mikrofonu (Android 12+) publiczne API NIE
        // zdradza: SensorPrivacyManager ma jawnie tylko supportsSensorToggle(), stan jest @SystemApi
        // — ten przypadek lapie dopiero SilenceDetector po RMS.
        if (mikrofonWyciszony()) status = tekstCiszy
        try {
            r.startListening(buildIntent())
        } catch (t: Throwable) {
            if (!escalate()) wyczerpane()
            return
        }
        uzbrojWatchdog()
    }

    private fun mikrofonWyciszony(): Boolean = try {
        (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.isMicrophoneMute == true
    } catch (_: Throwable) { false }

    /** Czy aplikacja ma RECORD_AUDIO — rozstrzyga, czyj jest ERROR_INSUFFICIENT_PERMISSIONS. */
    private fun maZgode(): Boolean = try {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }

    /**
     * Koniec drabinki: nic wiecej do zwiazania. Komunikat (jesli jest) zostaje w podpisie,
     * nasluch staje, a decyzje przejmuje ekran ([onExhausted]: okno systemowe / klawiatura).
     * Od tej chwili [maSilnik] = false, az [refresh] zobaczy inna liste uslug albo inny tryb.
     */
    private fun wyczerpane(komunikat: String = "") {
        if (wyczerpany) return
        wyczerpany = true
        wyczerpanyKod = ostatniBlad
        wyczerpanyOd = android.os.SystemClock.elapsedRealtime()
        status = komunikat
        stop()
        onExhausted?.invoke()
    }

    fun stop() {
        wantListening = false
        listening = false
        czekamNaSilnik = false
        handler.removeCallbacksAndMessages(null)
        // Samo cancel(): stopListening() po cancel() trafia w usluge bez sesji, a ta odpowiada
        // ERROR_CLIENT. Ten spozniony blad wracal do nas juz PO stop() i konczyl sie zamknieciem
        // calej drabinki (wyczerpany=true) mimo ze uczen tylko wylaczyl nasluch.
        try { recognizer?.cancel() } catch (_: Throwable) {}
    }

    fun release() {
        stop()
        zwolnijRecognizer()
        onHeard = null
    }

    private companion object {
        /** Ile razy z rzedu wolno uslyszec "silnik zajety", zanim uznamy to za awarie. */
        const val MAX_BUSY_RETRIES = 5
        /** Jak dlugo wyczerpana drabinka zostaje zamknieta (ms). */
        const val BLOKADA_MS = 25_000L
        /** Sztuczny kod bledu watchdoga (silnik milczy). */
        const val KOD_WATCHDOG = 99
        const val MAX_ALTERNATIVES = 6
        const val MAX_BIASING_WORDS = 200
        const val MAX_PROBOWANE = 12
        /** Ile czekamy na PIERWSZE zdarzenie od silnika po startListening. */
        const val WATCHDOG_MS = 6_000L
        const val AUDIO_RETRY_MS = 400L
        const val AUDIO_RETRY_SLOW_MS = 1_500L
        const val MAX_AUDIO_RETRIES = 6
        /** Syntetyczny kod dla watchdoga — poza zakresem kodow SpeechRecognizer (1–15). */
        const val ERROR_WATCHDOG = 99
    }
}

/** Trzyma [VoiceInput] przy zyciu na czas ekranu i zwalnia go przy wyjsciu. */
@Composable
fun rememberVoiceInput(context: Context): VoiceInput {
    val voice = remember(context) { VoiceInput(context).also { it.refresh() } }
    DisposableEffect(voice) { onDispose { voice.release() } }
    return voice
}

// ---- UI --------------------------------------------------------------------

/**
 * Pasek trybu glosowego nad klawiatura. Przycisk zajmuje cala szerokosc, bo jest
 * alternatywa dla CALEJ klawiatury pod spodem, a nie jedna z jej opcji.
 *
 * Pasek jest ZAWSZE widoczny — takze bez silnika mowy. Do 0.9.6 znikal przy UNAVAILABLE
 * i uczen bez uslugi rozpoznawania nie mial nawet czego dotknac. Teraz kazde dotkniecie
 * prowadzi do jakiejs drogi glosu (silnik, okno systemowe, klawiatura telefonu), a podpis
 * pod przyciskiem mowi, ktora to bedzie.
 *
 * Pod przyciskiem leci JEDNA linia informacji zwrotnej: co uslyszal silnik albo
 * dlaczego nie strzelil. Bez niej nietrafiona wypowiedz wyglada jak awaria. Jedna linia
 * jest celowa: pasek nie ma stalej wysokosci, a pod nim siedzi klawiatura gry — gdyby podpis
 * rosl z komunikatem, klawisze skakalyby pod kciukami w trakcie rundy. Dluzsze teksty
 * (diagnostyka z przytrzymania) pokazuje ekran w nakladce nad plansza.
 *
 * [hasEngine] mowi, czy dotkniecie pojdzie do zwiazanego silnika (VoiceInput.maSilnik);
 * sam [mode] tego nie rozstrzyga (UNAVAILABLE z lista uslug, wyczerpana drabinka).
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun VoiceBar(
    listening: Boolean,
    mode: VoiceMode,
    message: String,
    str: Str,
    onToggle: () -> Unit,
    onDiagnostyka: () -> Unit = {},
    dialogAvailable: Boolean = false,
    hasEngine: Boolean = mode != VoiceMode.UNAVAILABLE,
) {
    val body = if (listening) Palette.Accent else Palette.Bone
    val shadow = if (listening) Palette.AccentDim else Palette.Muted

    androidx.compose.foundation.layout.Column(
        androidx.compose.ui.Modifier
            .fillMaxWidth()
            .padding(horizontal = 5.dp)
            .padding(top = 4.dp),
    ) {
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier
                .fillMaxWidth()
                .height(44.dp)
                .background(shadow)
                .padding(bottom = 4.dp)
                .background(body)
                .border(3.dp, Palette.Ink)
                // Dlugie przytrzymanie = pelna diagnostyka glosu. Ukryte przed uczniem,
                // dostepne od reki, gdy trzeba ustalic, czemu mikrofon milczy na danym telefonie.
                .combinedClickable(onLongClick = onDiagnostyka) { onToggle() },
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            // Czysta czcionka (nie pixel-font): wielkie polskie znaki diakrytyczne (Ó, Ź, Ł)
            // w foncie 8px sa sciśniete i wygladaja zle. To jedyny przycisk z takimi znakami.
            Text(
                text = if (listening) str.listeningNow else str.speakAnswer,
                color = Palette.Ink,
                fontSize = 16.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.5.sp,
                maxLines = 1,
            )
        }
        androidx.compose.foundation.layout.Row(
            androidx.compose.ui.Modifier.fillMaxWidth().padding(top = 3.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) {
            val label = when (VoicePolicy.label(listening, mode, message.isNotEmpty(), dialogAvailable, hasEngine)) {
                VoiceLabel.MESSAGE -> message
                VoiceLabel.ON_DEVICE -> str.voiceOnDevice
                VoiceLabel.ONLINE -> str.voiceOnline
                VoiceLabel.DIALOG -> str.voiceDialog
                VoiceLabel.KEYBOARD -> str.voiceKeyboardHint
            }
            Text(
                text = label,
                color = if (message.startsWith("✓")) Palette.Accent else Palette.Muted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                // Stala wysokosc paska: jedna linia, reszta w wielokropku. Teksty w Strings.kt
                // sa skrojone pod ~45 znakow, zeby na 360 dp nic nie trzeba bylo ucinac.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
