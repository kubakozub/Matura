package com.matura.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.SensorPrivacyManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * TEST MIKROFONU (SUROWY) — po co ten ekran istnieje.
 *
 * [VoiceTestScreen] przepuszcza telefon przez matryce SILNIKOW mowy. Na Motoroli edge 50 neo
 * wraca z niego obraz, ktorego zadna kolejna proba z silnikiem nie rozstrzygnie: silnik melduje
 * gotowosc (onReadyForSpeech po 0,2 s), po czym NIE przychodzi ani jedna probka poziomu
 * (rms max: —), zaden wynik czesciowy, zaden wynik koncowy i ZADEN kod bledu. Taki obraz daja
 * dwie zupelnie rozne awarie:
 *   (1) do aplikacji nie dochodzi dzwiek (mikrofon wyciszony przelacznikiem prywatnosci, zajety
 *       przez inna aplikacje, wyciszony przez polityke wspolbieznego nagrywania, martwy HAL),
 *   (2) dzwiek dochodzi, ale silnik rozpoznawania nic z nim nie robi (brak modelu jezyka,
 *       usluga, ktora nie obsluguje aplikacji trzecich, silnik bez onRmsChanged).
 * SpeechRecognizer nie umie ich rozroznic, bo caly tor audio jest po jego stronie procesu.
 *
 * Ten ekran omija silniki CALKOWICIE: otwiera AudioRecord, czyta surowe probki PCM i liczy je.
 * Odpowiada na jedno pytanie — CZY DZWIEK FIZYCZNIE DOCHODZI DO APLIKACJI — i odpowiada liczba,
 * a nie wnioskiem: szczyt amplitudy, RMS w dBFS i liczba probek rownych dokladnie zeru. Strumien
 * samych zer to odcisk palca wyciszenia (przelacznik prywatnosci Android 12+ oddaje aplikacji
 * "silent audio", https://developer.android.com/about/versions/12/behavior-changes-all), a nie
 * cichego pokoju: w cichym pokoju szum wlasny przetwornika daje setki probek roznych od zera.
 *
 * Drugie zrodlo prawdy to sam system: AudioRecordingConfiguration.isClientSilenced() (API 29)
 * mowi WPROST, ze framework podaje nam cisze. W AOSP ta flaga zapala sie takze przy wlaczonym
 * przelaczniku prywatnosci — AudioPolicyService::updateUidStates_l ma jawna galaz
 * "if Sensor Privacy is enabled then all recordings should be silenced" -> silenceAllRecordings_l()
 * -> setAppState_l(APP_STATE_IDLE) -> client->silenced = true. Dlatego ta jedna linia raportu
 * jest wazniejsza niz cala reszta ekranu.
 *
 * Nagranie NIE opuszcza telefonu: WAV ladzie w katalogu prywatnym aplikacji (filesDir), sluzy do
 * odsluchania na miejscu i jest nadpisywany przy kazdym przebiegu. Aplikacja nie ma uprawnienia
 * do sieci.
 */

// ---- czysta logika (bez Androida, testowana zwyklym JUnitem) ----------------
//
// Stale MediaRecorder.AudioSource.* i AudioFormat.* sa stalymi kompilacji (inlinowane przez
// kompilator), wiec ta czesc pliku nie laduje zadnej klasy Androida i da sie ja testowac
// zwyklym JUnitem, bez Robolectrica.

/** Docelowy format proby: 16 kHz mono PCM 16-bit — dokladnie to, czym karmi sie rozpoznawanie. */
const val RAW_SR_DOCELOWY = 16_000
/** Zapasowa czestotliwosc: 44,1 kHz jest obowiazkowa dla kazdego urzadzenia z Androidem. */
const val RAW_SR_ZAPASOWY = 44_100
/** Ile czasu czytamy z jednego zrodla. Dwie sekundy wystarcza na "raz, dwa" i nie nudza. */
const val RAW_PROBA_MS = 2_000L
/** Twardy limit na zrodlo: gdyby read() sie zawiesil, watchdog i tak zamyka strumien. */
const val RAW_WATCHDOG_MS = 3_500L
/** Prog, powyzej ktorego uznajemy, ze do mikrofonu weszla MOWA, a nie samo tlo. */
const val RAW_MOWA_DBFS = -45f
/** Sciezka nagrania w katalogu prywatnym aplikacji. */
const val RAW_WAV_NAZWA = "mikrofon-test.wav"

/**
 * Jedno zrodlo z matrycy. [minSdk] pilnuje stalych mlodszych niz minSdk aplikacji (24):
 * UNPROCESSED to API 24, VOICE_PERFORMANCE to API 29.
 */
data class RawZrodlo(val kod: Int, val nazwa: String, val minSdk: Int = 1)

/**
 * Matryca zrodel. Kolejnosc jest celowa:
 *  - MIC i VOICE_RECOGNITION to dwie drogi, ktorymi naprawde chodzi rozpoznawanie mowy,
 *  - VOICE_COMMUNICATION i CAMCORDER sa "privacy-sensitive" (patrz dokumentacja "Sharing audio
 *    input"): maja WYZSZY priorytet w polityce wspolbieznego przechwytywania, wiec jesli
 *    zadzialaja TYLKO one, winna jest inna aplikacja trzymajaca mikrofon, a nie sprzet,
 *  - UNPROCESSED omija cala obrobke (AGC/NS/AEC): jesli slyszy tylko ono, dzwiek zjada
 *    preprocessing producenta,
 *  - DEFAULT jest na koncu jako kontrola: tak wybiera zrodlo silnik, ktory nic nie ustawia.
 */
val RAW_ZRODLA: List<RawZrodlo> = listOf(
    RawZrodlo(MediaRecorder.AudioSource.MIC, "MIC"),
    RawZrodlo(MediaRecorder.AudioSource.VOICE_RECOGNITION, "VOICE_RECOGNITION"),
    RawZrodlo(MediaRecorder.AudioSource.VOICE_COMMUNICATION, "VOICE_COMMUNICATION"),
    RawZrodlo(MediaRecorder.AudioSource.CAMCORDER, "CAMCORDER"),
    RawZrodlo(MediaRecorder.AudioSource.UNPROCESSED, "UNPROCESSED", minSdk = Build.VERSION_CODES.N),
    RawZrodlo(MediaRecorder.AudioSource.DEFAULT, "DEFAULT"),
)

/** Co zostalo po jednej probie. Same liczby — wnioski sa osobno, w [werdyktRaw]. */
data class RawWynik(
    val nazwa: String,
    /** AudioRecord w ogole powstal i zglosil STATE_INITIALIZED. */
    val init: Boolean,
    /** startRecording() przestawilo stan na RECORDSTATE_RECORDING. */
    val nagrywa: Boolean,
    val sampleRate: Int,
    val bufBajty: Int,
    /** Ile ramek (probek mono) naprawde wrocilo z read(). */
    val ramki: Long,
    /** Najwieksza wartosc bezwzgledna probki: 0 = strumien samych zer. */
    val szczyt: Int,
    /** RMS w dBFS; NEGATIVE_INFINITY = same zera. */
    val rmsDbfs: Float,
    /** Ile probek bylo DOKLADNIE zerem. */
    val zera: Long,
    /** Czy system choc raz przyznal, ze wycisza nas polityka (isClientSilenced, API 29+). */
    val wyciszonyPrzezSystem: Boolean?,
    /** Na ktore wejscie system skierowal ten strumien (getRoutedDevice, API 23+). */
    val wejscie: String?,
    /** Komunikat awarii albo null. */
    val blad: String?,
    val czasMs: Long,
) {
    /** ODCISK PALCA WYCISZENIA: ramki przyszly, ale wszystkie probki sa zerem. */
    val sameZera: Boolean get() = ramki > 0 && szczyt == 0
    /** Cos naprawde weszlo do aplikacji. */
    val slyszy: Boolean get() = ramki > 0 && szczyt > 0
}

/** RMS w dBFS z sumy kwadratow probek 16-bit. Pelna skala = 32768. */
fun rawDbfs(sumaKwadratow: Double, ramki: Long): Float {
    if (ramki <= 0L) return Float.NEGATIVE_INFINITY
    val rms = sqrt(sumaKwadratow / ramki)
    if (rms <= 0.0) return Float.NEGATIVE_INFINITY
    return (20.0 * log10(rms / 32768.0)).toFloat()
}

/** dBFS do raportu: "-∞" zamiast "-Infinity", jedno miejsce po przecinku. */
fun rawDbfsOpis(db: Float): String = if (db == Float.NEGATIVE_INFINITY) "-∞" else "%.1f".format(db)

/** Nazwa zrodla z kodu — w raporcie ma byc widac, co znaczy "6". */
fun nazwaZrodla(kod: Int): String = when (kod) {
    MediaRecorder.AudioSource.DEFAULT -> "DEFAULT"
    MediaRecorder.AudioSource.MIC -> "MIC"
    MediaRecorder.AudioSource.VOICE_UPLINK -> "VOICE_UPLINK"
    MediaRecorder.AudioSource.VOICE_DOWNLINK -> "VOICE_DOWNLINK"
    MediaRecorder.AudioSource.VOICE_CALL -> "VOICE_CALL"
    MediaRecorder.AudioSource.CAMCORDER -> "CAMCORDER"
    MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
    MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
    9 -> "UNPROCESSED"        // API 24
    10 -> "VOICE_PERFORMANCE" // API 29
    else -> kod.toString()
}

/**
 * Nazwa typu wejscia audio. Stale AudioDeviceInfo.TYPE_* sa stalymi kompilacji, wiec mozna je
 * wymieniac bez wzgledu na minSdk — ale TYLKO te, ktore istnieja w compileSdk. Nieznany kod
 * wychodzi liczba, zeby raport z przyszlego Androida nie klamal.
 */
fun nazwaWejscia(typ: Int): String = when (typ) {
    AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
    AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
    AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
    AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB_ACCESSORY"
    AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
    AudioDeviceInfo.TYPE_FM_TUNER -> "FM_TUNER"
    AudioDeviceInfo.TYPE_TV_TUNER -> "TV_TUNER"
    AudioDeviceInfo.TYPE_LINE_ANALOG -> "LINE_ANALOG"
    AudioDeviceInfo.TYPE_LINE_DIGITAL -> "LINE_DIGITAL"
    AudioDeviceInfo.TYPE_DOCK -> "DOCK"
    AudioDeviceInfo.TYPE_IP -> "IP"
    AudioDeviceInfo.TYPE_BUS -> "BUS"
    AudioDeviceInfo.TYPE_HDMI -> "HDMI"
    AudioDeviceInfo.TYPE_UNKNOWN -> "UNKNOWN"
    else -> "typ " + typ
}

/** Nazwa trybu audio. MODE_IN_CALL i MODE_IN_COMMUNICATION znacza, ze mikrofon ma rozmowa. */
fun nazwaTrybuAudio(tryb: Int): String = when (tryb) {
    AudioManager.MODE_NORMAL -> "NORMAL"
    AudioManager.MODE_RINGTONE -> "RINGTONE"
    AudioManager.MODE_IN_CALL -> "IN_CALL"
    AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
    else -> "tryb " + tryb
}

/**
 * Co caly przebieg mowi o mikrofonie. Stanow jest PIEC, nie dwa — i kazdy prowadzi gdzie indziej.
 * Zadnego z nich nie da sie orzec z poziomu SpeechRecognizera, dlatego ten ekran w ogole powstal.
 */
enum class RawWerdykt {
    /** Ktores zrodlo oddalo probki rozne od zera: dzwiek FIZYCZNIE dochodzi do aplikacji. */
    SLYSZY,
    /** Ramki przychodza, ale wszystkie probki to zero — system oddaje nam cisze. */
    SAME_ZERA,
    /** Strumien ruszyl, ale read() nie oddal ani jednej ramki — tor audio stoi. */
    BRAK_RAMEK,
    /** Zadne zrodlo sie nie otworzylo — mikrofonu nie da sie nawet zajac. */
    NIE_RUSZYL,
    /** Przebiegu nie bylo albo nie doszedl do pierwszego pomiaru. */
    BRAK,
}

fun werdyktRaw(wyniki: List<RawWynik>): RawWerdykt = when {
    wyniki.isEmpty() -> RawWerdykt.BRAK
    wyniki.any { it.slyszy } -> RawWerdykt.SLYSZY
    wyniki.any { it.sameZera } -> RawWerdykt.SAME_ZERA
    wyniki.any { it.nagrywa } -> RawWerdykt.BRAK_RAMEK
    else -> RawWerdykt.NIE_RUSZYL
}

/** Etykiety ekranu i raportu — ekran podstawia wersje PL/EN z [Str], testy swoje wlasne. */
data class RawMicTeksty(
    val slyszy: String,
    val sameZera: String,
    val brakRamek: String,
    val nieRuszyl: String,
    val brak: String,
    val wyciszonyPrzezSystem: String,
    val cicho: String,
    val blad: String,
    val brakInit: String,
)

/** Jedno zdanie werdyktu + zdanie o tym, co z nim zrobic. */
fun opisWerdyktuRaw(wyniki: List<RawWynik>, t: RawMicTeksty): String {
    val w = werdyktRaw(wyniki)
    val zdanie = when (w) {
        RawWerdykt.SLYSZY -> t.slyszy
        RawWerdykt.SAME_ZERA -> t.sameZera
        RawWerdykt.BRAK_RAMEK -> t.brakRamek
        RawWerdykt.NIE_RUSZYL -> t.nieRuszyl
        RawWerdykt.BRAK -> t.brak
    }
    // Flaga systemowa ma pierwszenstwo nad kazda heurystyka: to system mowi wprost, ze wycisza.
    val wyciszony = wyniki.any { it.wyciszonyPrzezSystem == true }
    return if (wyciszony && w != RawWerdykt.SLYSZY) zdanie + " " + t.wyciszonyPrzezSystem else zdanie
}

/** Jedna linia opisu proby — ta sama na ekranie i w raporcie. */
fun opisRawWyniku(w: RawWynik, t: RawMicTeksty): String {
    val czesci = mutableListOf<String>()
    czesci += when {
        !w.init -> t.brakInit
        w.blad != null -> t.blad + " " + w.blad
        w.slyszy -> "szczyt " + w.szczyt + " · RMS " + rawDbfsOpis(w.rmsDbfs) + " dBFS"
        w.sameZera -> t.sameZera
        else -> t.brakRamek
    }
    czesci += w.sampleRate.toString() + " Hz"
    czesci += "buf " + w.bufBajty + " B"
    czesci += "ramki " + w.ramki
    czesci += "zera " + w.zera + "/" + w.ramki
    if (w.wyciszonyPrzezSystem == true) czesci += "isClientSilenced=TAK"
    if (w.wejscie != null) czesci += w.wejscie
    czesci += "%.1f s".format(w.czasMs / 1000.0)
    return czesci.joinToString(" · ")
}

/**
 * Caly raport do schowka. Naglowek powstaje PRZED probami (stan telefonu w chwili startu),
 * linia "próby: N/M · PRZERWANY" broni wklejonego raportu przed nadinterpretacja — czyta go
 * ktos, kto nie widzial ekranu. Ta sama lekcja co w [raportMowy].
 */
fun raportRaw(
    naglowek: String,
    wyniki: List<RawWynik>,
    werdykt: String,
    t: RawMicTeksty,
    ile: Int = wyniki.size,
    przerwany: Boolean = false,
    powodPrzerwania: String = "",
    nasluch: String = "",
    wav: String? = null,
): String = buildString {
    append(naglowek)
    append("próby: ").append(wyniki.size).append('/').append(ile)
    if (przerwany) {
        append(" · PRZERWANY")
        if (powodPrzerwania.isNotEmpty()) append(" (").append(powodPrzerwania).append(')')
    }
    append('\n')
    wyniki.forEachIndexed { i, w ->
        append('\n').append(i + 1).append(". ").append(w.nazwa).append(" -> ").append(opisRawWyniku(w, t))
    }
    if (nasluch.isNotEmpty()) append('\n').append('\n').append(nasluch)
    if (wav != null) append('\n').append('\n').append("nagranie: ").append(wav)
    append('\n').append('\n').append(werdykt)
}

// ---- warstwa Androida ------------------------------------------------------

/**
 * Przepuszcza telefon przez matryce zrodel. Czytanie z AudioRecord BLOKUJE, wiec caly przebieg
 * chodzi na wlasnym watku, a stan Compose jest przestawiany przez [handler] na watku glownym.
 */
class RawMicProbe(private val context: Context) {

    var wyniki by mutableStateOf<List<RawWynik>>(emptyList())
        private set
    var teraz by mutableStateOf<String?>(null)
        private set
    var ile by mutableIntStateOf(0)
        private set
    var trwa by mutableStateOf(false)
        private set
    var skonczone by mutableStateOf(false)
        private set
    var przerwane by mutableStateOf(false)
        private set
    /**
     * DLACZEGO przebieg stanal. Ekran testu mowy do 0.9.16 przerywal sie po zerowej probie i
     * raport nie mowil, co go zabilo — z "PRZERWANY po 0 z 10 prob" nie da sie niczego odczytac.
     * Tu powod jest czescia pomiaru.
     */
    var powodPrzerwania by mutableStateOf("")
        private set
    /** Poziom ostatniego kawalka (0..1) — pasek na ekranie, zeby bylo widac, ze cos sie dzieje. */
    var poziom by mutableFloatStateOf(0f)
        private set
    var naglowekPrzebiegu by mutableStateOf("")
        private set
    /** Sciezka gotowego WAV-a albo null. */
    var wav by mutableStateOf<String?>(null)
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val audio: AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    @Volatile private var stop = false
    @Volatile private var biezacy: AudioRecord? = null
    private var watek: Thread? = null
    private var player: MediaPlayer? = null
    /** Sesja audio biezacej proby — po niej poznajemy SWOJ wpis w liscie systemowej. */
    @Volatile private var sesja = -1

    /**
     * Nasluch zmian konfiguracji nagrywania na poziomie CALEGO systemu
     * (AudioManager.registerAudioRecordingCallback, API 24 — czyli dokladnie minSdk aplikacji).
     *
     * Odpowiednik na samym AudioRecordzie (API 29) jest wygodniejszy, ale dokumentacja mowi
     * o nim wprost: "The callback is executed only when the app is receiving audio and a change
     * occurs" — czyli w awarii, ktora badamy, moglby nie odezwac sie ANI RAZU. Wersja z
     * AudioManagera dostaje kazda zmiane, takze te, w ktorej to NAS wlasnie wyciszono.
     */
    private val nasluchNagrywania = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
            val lista = configs ?: return
            zmianyKonfiguracji += 1
            if (lista.size > maxJednoczesnych) maxJednoczesnych = lista.size
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
            val moja = lista.firstOrNull { it.clientAudioSessionId == sesja } ?: return
            if (moja.isClientSilenced) {
                wyciszenieZNasluchu = true
                Log.i(VOICE_TAG, "raw-mic nasłuch: system wyciszył naszą sesję " + sesja)
            }
        }
    }
    @Volatile private var zmianyKonfiguracji = 0
    @Volatile private var maxJednoczesnych = 0
    @Volatile private var wyciszenieZNasluchu = false

    /** Rusza caly przebieg. Wolane z watku glownego. */
    fun start() {
        zatrzymaj("")
        stop = false
        wyniki = emptyList()
        skonczone = false
        przerwane = false
        powodPrzerwania = ""
        poziom = 0f
        wav = null
        naglowekPrzebiegu = naglowek()
        zmianyKonfiguracji = 0
        maxJednoczesnych = 0
        wyciszenieZNasluchu = false
        try { audio?.registerAudioRecordingCallback(nasluchNagrywania, handler) } catch (_: Throwable) {}
        val matryca = RAW_ZRODLA.filter { Build.VERSION.SDK_INT >= it.minSdk }
        ile = matryca.size
        trwa = true
        watek = Thread({ przebieg(matryca) }, "matura-rawmic").apply {
            // Tor audio nie lubi czekac: watek czytajacy ma miec pierwszenstwo nad reszta.
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /** Przerywa przebieg i ODDAJE mikrofon. Bezpieczne do wolania wielokrotnie. */
    fun zatrzymaj(powod: String) {
        val wToku = trwa
        stop = true
        try { audio?.unregisterAudioRecordingCallback(nasluchNagrywania) } catch (_: Throwable) {}
        try { biezacy?.stop() } catch (_: Throwable) {}
        try { player?.stop() } catch (_: Throwable) {}
        try { player?.release() } catch (_: Throwable) {}
        player = null
        if (wToku) {
            przerwane = true
            powodPrzerwania = powod
            trwa = false
            teraz = null
            Log.i(VOICE_TAG, "raw-mic PRZERWANY po " + wyniki.size + "/" + ile + " (" + powod + ")")
        }
    }

    /** Odsluchanie nagrania — jedyny sposob, zeby uczen SAM ocenil, czy w pliku jest jego glos. */
    fun odtworz() {
        val sciezka = wav ?: return
        try {
            player?.release()
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(sciezka)
                setOnCompletionListener { mp ->
                    try { mp.release() } catch (_: Throwable) {}
                    if (player === mp) player = null
                }
                prepare()
                start()
            }
        } catch (t: Throwable) {
            Log.w(VOICE_TAG, "raw-mic odtwarzanie: " + t.javaClass.simpleName)
            try { player?.release() } catch (_: Throwable) {}
            player = null
        }
    }

    fun zwolnij() {
        zatrzymaj("wyjście z ekranu")
        watek = null
    }

    // ---- przebieg na watku roboczym ---------------------------------------

    private fun przebieg(matryca: List<RawZrodlo>) {
        var najlepszePcm: ByteArray? = null
        var najlepszySzczyt = -1
        var najlepszeSr = RAW_SR_DOCELOWY
        for (z in matryca) {
            if (stop) break
            handler.post { teraz = z.nazwa }
            val (w, pcm) = jednoZrodlo(z)
            handler.post { wyniki = wyniki + w }
            Log.i(VOICE_TAG, "raw-mic " + w.nazwa + " -> ramki=" + w.ramki + " szczyt=" + w.szczyt +
                " rms=" + rawDbfsOpis(w.rmsDbfs) + " zera=" + w.zera +
                " silenced=" + w.wyciszonyPrzezSystem + " blad=" + w.blad)
            if (pcm != null && w.szczyt > najlepszySzczyt) {
                najlepszePcm = pcm
                najlepszySzczyt = w.szczyt
                najlepszeSr = w.sampleRate
            }
        }
        // WAV powstaje takze ze strumienia samych zer: cisza w sluchawce jest DOWODEM, ktory
        // uczen slyszy sam, a nie kolejnym zdaniem aplikacji o samej sobie.
        val sciezka = najlepszePcm?.let { zapiszWav(it, najlepszeSr) }
        try { audio?.unregisterAudioRecordingCallback(nasluchNagrywania) } catch (_: Throwable) {}
        handler.post {
            wav = sciezka
            teraz = null
            trwa = false
            if (!przerwane) skonczone = true
            poziom = 0f
        }
    }

    /** Jedna proba: zbuduj, wystartuj, czytaj [RAW_PROBA_MS], policz. Zwraca wynik i surowe PCM. */
    private fun jednoZrodlo(z: RawZrodlo): Pair<RawWynik, ByteArray?> {
        val start = SystemClock.elapsedRealtime()
        var blad: String? = null
        var rec: AudioRecord? = null
        var sr = RAW_SR_DOCELOWY
        var buf = 0

        // 16 kHz mono to format rozpoznawania; 44,1 kHz jest obowiazkowe dla kazdego urzadzenia,
        // wiec stoi jako zapas — inaczej telefon bez 16 kHz wygladalby jak telefon bez mikrofonu.
        for (kandydat in intArrayOf(RAW_SR_DOCELOWY, RAW_SR_ZAPASOWY)) {
            val min = try {
                AudioRecord.getMinBufferSize(kandydat, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            } catch (_: Throwable) { AudioRecord.ERROR }
            if (min <= 0) {
                blad = "getMinBufferSize=" + min + " @" + kandydat
                continue
            }
            // Z zapasem: bufor na okolo pol sekundy chroni przed przepelnieniem, gdy watek
            // roboczy zostanie na chwile wywlaszczony.
            val rozmiar = max(min * 4, kandydat)
            val r = try {
                buduj(z.kod, kandydat, rozmiar)
            } catch (t: Throwable) {
                blad = t.javaClass.simpleName + (t.message?.let { ": " + it } ?: "")
                null
            }
            if (r != null && r.state == AudioRecord.STATE_INITIALIZED) {
                rec = r
                sr = kandydat
                buf = rozmiar
                blad = null
                break
            }
            try { r?.release() } catch (_: Throwable) {}
            if (r != null && blad == null) blad = "state=UNINITIALIZED @" + kandydat
        }

        if (rec == null) {
            return RawWynik(
                nazwa = z.nazwa, init = false, nagrywa = false, sampleRate = sr, bufBajty = buf,
                ramki = 0, szczyt = 0, rmsDbfs = Float.NEGATIVE_INFINITY, zera = 0,
                wyciszonyPrzezSystem = null, wejscie = null, blad = blad ?: "brak AudioRecord",
                czasMs = SystemClock.elapsedRealtime() - start,
            ) to null
        }

        biezacy = rec
        sesja = try { rec.audioSessionId } catch (_: Throwable) { -1 }
        var nagrywa = false
        var ramki = 0L
        var szczyt = 0
        var zera = 0L
        var suma = 0.0
        var wyciszony: Boolean? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) false else null
        var wejscie: String? = null
        val pcm = java.io.ByteArrayOutputStream(sr * 2 * 3)

        // Watchdog: read() na martwym torze audio potrafi nie wrocic. stop() z innego watku
        // odblokowuje czytanie, wiec przebieg nie zawisa i "zawieszony strumien" tez jest wynikiem.
        val pies = Runnable { try { rec.stop() } catch (_: Throwable) {} }
        handler.postDelayed(pies, RAW_WATCHDOG_MS)

        try {
            rec.startRecording()
            nagrywa = rec.recordingState == AudioRecord.RECORDSTATE_RECORDING
            if (!nagrywa) blad = "recordingState=STOPPED"
            val kawalek = ShortArray(max(256, sr / 10))
            val bajty = ByteArray(kawalek.size * 2)
            while (!stop && SystemClock.elapsedRealtime() - start < RAW_PROBA_MS) {
                val n = rec.read(kawalek, 0, kawalek.size)
                if (n < 0) {
                    blad = "read=" + kodCzytania(n)
                    break
                }
                if (n == 0) continue
                var szczytKawalka = 0
                for (i in 0 until n) {
                    val s = kawalek[i].toInt()
                    if (s == 0) zera += 1
                    val a = abs(s)
                    if (a > szczytKawalka) szczytKawalka = a
                    suma += (s.toDouble() * s.toDouble())
                    bajty[i * 2] = (s and 0xFF).toByte()
                    bajty[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                }
                pcm.write(bajty, 0, n * 2)
                ramki += n
                if (szczytKawalka > szczyt) szczyt = szczytKawalka
                val podglad = szczytKawalka / 32768f
                handler.post { poziom = podglad }
                // Pytamy system, a nie siebie: to jedyna droga, ktora odroznia "cicho" od
                // "framework oddaje nam cisze". Callback tu nie wystarczy — dokumentacja mowi
                // wprost, ze leci TYLKO wtedy, gdy aplikacja dostaje dzwiek i cos sie zmieni.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val konf = try { rec.activeRecordingConfiguration } catch (_: Throwable) { null }
                    if (konf != null && konf.isClientSilenced) wyciszony = true
                }
                // getRoutedDevice() odpowiada TYLKO w trakcie nagrywania, wiec pytamy w petli,
                // a nie po niej: przerwany odczyt nie moze zabrac tej informacji z raportu.
                if (wejscie == null) {
                    wejscie = try {
                        rec.routedDevice?.let { nazwaWejscia(it.type) + " #" + it.id }
                    } catch (_: Throwable) { null }
                }
            }
        } catch (t: Throwable) {
            blad = t.javaClass.simpleName + (t.message?.let { ": " + it } ?: "")
        } finally {
            handler.removeCallbacks(pies)
            try { rec.stop() } catch (_: Throwable) {}
            try { rec.release() } catch (_: Throwable) {}
            biezacy = null
        }

        if (wyciszenieZNasluchu) wyciszony = true
        val w = RawWynik(
            nazwa = z.nazwa, init = true, nagrywa = nagrywa, sampleRate = sr, bufBajty = buf,
            ramki = ramki, szczyt = szczyt, rmsDbfs = rawDbfs(suma, ramki), zera = zera,
            wyciszonyPrzezSystem = wyciszony, wejscie = wejscie, blad = blad,
            czasMs = SystemClock.elapsedRealtime() - start,
        )
        return w to (if (ramki > 0) pcm.toByteArray() else null)
    }

    /**
     * Budowa jednego strumienia. Zgode sprawdzamy TUTAJ, a SecurityException lapiemy na miejscu:
     * bez tego `AudioRecord.Builder.build()` (udokumentowane @RequiresPermission(RECORD_AUDIO))
     * jest bledem lintu, a co wazniejsze — odebranie zgody w trakcie przebiegu wywracaloby caly
     * ekran diagnostyczny zamiast dac sie zapisac jako wynik proby.
     */
    private fun buduj(zrodlo: Int, sr: Int, buf: Int): AudioRecord? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("brak zgody RECORD_AUDIO")
        }
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sr)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val b = AudioRecord.Builder()
            .setAudioSource(zrodlo)
            .setAudioFormat(format)
            .setBufferSizeInBytes(buf)
        // NIE ustawiamy setPrivacySensitive(true) (API 30): to wyprosiloby z mikrofonu inne
        // aplikacje i test przestalby mierzyc sytuacje, w ktorej gra naprawde dziala.
        return try {
            b.build()
        } catch (e: SecurityException) {
            Log.w(VOICE_TAG, "raw-mic build: " + e.javaClass.simpleName)
            null
        }
    }

    private fun kodCzytania(n: Int): String = when (n) {
        AudioRecord.ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION"
        AudioRecord.ERROR_BAD_VALUE -> "ERROR_BAD_VALUE"
        AudioRecord.ERROR_DEAD_OBJECT -> "ERROR_DEAD_OBJECT"
        AudioRecord.ERROR -> "ERROR"
        else -> n.toString()
    }

    /** WAV 16-bit mono w katalogu PRYWATNYM aplikacji. Nadpisywany przy kazdym przebiegu. */
    private fun zapiszWav(pcm: ByteArray, sr: Int): String? = try {
        val plik = File(context.filesDir, RAW_WAV_NAZWA)
        FileOutputStream(plik).use { out ->
            out.write(naglowekWav(pcm.size, sr))
            out.write(pcm)
        }
        plik.absolutePath
    } catch (t: Throwable) {
        Log.w(VOICE_TAG, "raw-mic zapis WAV: " + t.javaClass.simpleName)
        null
    }

    /** Kanoniczny 44-bajtowy naglowek RIFF/WAVE, PCM 16-bit mono, little-endian. */
    private fun naglowekWav(dane: Int, sr: Int): ByteArray {
        val kanaly = 1
        val bity = 16
        val bajtowNaSek = sr * kanaly * bity / 8
        val h = ByteArray(44)
        fun str(pos: Int, s: String) { for (i in s.indices) h[pos + i] = s[i].code.toByte() }
        fun le32(pos: Int, v: Int) {
            h[pos] = (v and 0xFF).toByte()
            h[pos + 1] = ((v shr 8) and 0xFF).toByte()
            h[pos + 2] = ((v shr 16) and 0xFF).toByte()
            h[pos + 3] = ((v shr 24) and 0xFF).toByte()
        }
        fun le16(pos: Int, v: Int) {
            h[pos] = (v and 0xFF).toByte()
            h[pos + 1] = ((v shr 8) and 0xFF).toByte()
        }
        str(0, "RIFF"); le32(4, 36 + dane); str(8, "WAVE")
        str(12, "fmt "); le32(16, 16); le16(20, 1); le16(22, kanaly)
        le32(24, sr); le32(28, bajtowNaSek); le16(32, kanaly * bity / 8); le16(34, bity)
        str(36, "data"); le32(40, dane)
        return h
    }

    // ---- sonda urzadzenia --------------------------------------------------

    private fun maZgode(): Boolean = try {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }

    private fun wersja(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (_: Throwable) { "?" }

    /** Lista WEJSC audio widzianych przez system. Pusta lista = telefon bez mikrofonu. */
    fun wejscia(): List<String> = try {
        val m = audio ?: return emptyList()
        m.getDevices(AudioManager.GET_DEVICES_INPUTS).map { d ->
            buildString {
                append(nazwaWejscia(d.type)).append(" #").append(d.id)
                val nazwa = d.productName?.toString()?.trim().orEmpty()
                if (nazwa.isNotEmpty()) append(" \"").append(nazwa).append('"')
                // getAddress() to API 28 — ponizej po prostu nie ma tej informacji.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val adres = try { d.address } catch (_: Throwable) { "" }
                    if (adres.isNotEmpty()) append(" addr=").append(adres)
                }
            }
        }
    } catch (_: Throwable) { emptyList() }

    /**
     * Kto jeszcze nagrywa. UWAGA na granice tej odpowiedzi: dla zwyklej aplikacji system oddaje
     * konfiguracje ZANONIMIZOWANE (AOSP RecordingActivityMonitor.anonymizeForPublicConsumption ->
     * AudioRecordingConfiguration.anonymizedCopy: uid = -1, nazwa pakietu = ""). Nazwy aplikacji
     * TU NIE BEDZIE i raport nie ma jej udawac. Zostaje to, co naprawde niesie informacje:
     * zrodlo klienta, jego format i flaga wyciszenia.
     */
    fun nagrywajacy(): List<String> = try {
        val m = audio ?: return emptyList()
        m.activeRecordingConfigurations.map { opisKonfiguracji(it) }
    } catch (_: Throwable) { emptyList() }

    private fun opisKonfiguracji(k: AudioRecordingConfiguration): String = buildString {
        append("źródło ").append(nazwaZrodla(k.clientAudioSource))
        append(" · sesja ").append(k.clientAudioSessionId)
        val f = try { k.clientFormat } catch (_: Throwable) { null }
        if (f != null) append(" · ").append(f.sampleRate).append(" Hz")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            append(" · wyciszony: ").append(if (k.isClientSilenced) "TAK" else "nie")
        }
        val d = try { k.audioDevice } catch (_: Throwable) { null }
        if (d != null) append(" · ").append(nazwaWejscia(d.type))
    }

    /**
     * Co system mowi o przelaczniku prywatnosci mikrofonu. Publiczne API daje TYLKO
     * supportsSensorToggle() (API 31) — isSensorPrivacyEnabled() jest @SystemApi, wiec STANU
     * przelacznika odczytac sie nie da i raport nie ma tego zmyslac. Stan rozstrzyga pomiar:
     * strumien samych zer + isClientSilenced=TAK.
     */
    fun przelacznikPrywatnosci(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return "brak (API < 31)"
        return try { toggleOpis() } catch (_: Throwable) { "nieznany" }
    }

    /**
     * Klasa SensorPrivacyManager istnieje dopiero od API 31, wiec kazde odwolanie do niej siedzi
     * w OSOBNEJ metodzie. Inaczej weryfikator ART-a musialby ja rozwiazac przy wejsciu w metode
     * wolajaca — a ta chodzi takze na Androidzie 7.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun toggleOpis(): String {
        val spm = context.getSystemService(SensorPrivacyManager::class.java)
            ?: return "nieznany (brak usługi)"
        val jest = spm.supportsSensorToggle(SensorPrivacyManager.Sensors.MICROPHONE)
        return if (jest) "obsługiwany (stanu nie da się odczytać: @SystemApi)" else "nieobsługiwany"
    }

    /** Co zobaczyl nasluch systemowy w czasie przebiegu — linia do raportu. */
    fun opisNasluchu(): String =
        "nasłuch zmian nagrywania: " + zmianyKonfiguracji +
            " · najwięcej jednoczesnych nagrań: " + maxJednoczesnych +
            " · system wyciszył naszą sesję: " + (if (wyciszenieZNasluchu) "TAK" else "nie")

    /** Naglowek raportu: stan telefonu PRZED probami, policzony raz. */
    fun naglowek(): String {
        val we = wejscia()
        val nag = nagrywajacy()
        return buildString {
            append("MATURA ").append(wersja()).append(" — test mikrofonu (surowy)\n")
            append("Android ").append(Build.VERSION.SDK_INT)
            append(" · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("zgoda: ").append(if (maZgode()) "tak" else "NIE")
            append(" · isMicrophoneMute: ").append(if (audio?.isMicrophoneMute == true) "TAK" else "nie")
            append(" · tryb audio: ").append(nazwaTrybuAudio(audio?.mode ?: -1)).append('\n')
            append("przełącznik prywatności: ").append(przelacznikPrywatnosci()).append('\n')
            append("wejścia (").append(we.size).append("): ")
            append(if (we.isEmpty()) "brak" else we.joinToString(" · "))
            append('\n')
            append("nagrywa teraz (").append(nag.size).append("): ")
            append(if (nag.isEmpty()) "nikt" else nag.joinToString(" | "))
            append('\n')
        }
    }
}

/** Etykiety w jezyku interfejsu — jedno miejsce, uzywane przez ekran i raport. */
fun rawMicTeksty(str: Str) = RawMicTeksty(
    slyszy = str.rawMicVerdictHears,
    sameZera = str.rawMicVerdictZeros,
    brakRamek = str.rawMicVerdictNoFrames,
    nieRuszyl = str.rawMicVerdictNoInit,
    brak = str.rawMicVerdictNone,
    wyciszonyPrzezSystem = str.rawMicSilencedBySystem,
    cicho = str.rawMicQuiet,
    blad = str.rawMicError,
    brakInit = str.rawMicNoInit,
)

@Composable
fun rememberRawMicProbe(context: Context = LocalContext.current): RawMicProbe =
    remember(context) { RawMicProbe(context) }

/**
 * Ekran. Wzor jak [VoiceTestScreen]: ScreenScaffold + LazyColumn z jednym elementem, raport
 * do skopiowania JEDNYM dotknieciem i ten sam tekst w [SelectionContainer] pod spodem (schowek
 * na czesci telefonow bywa czyszczony, zanim uczen zdazy wkleic).
 */
@Composable
fun RawMicTestScreen(
    str: Str,
    onBack: () -> Unit,
    probe: RawMicProbe = rememberRawMicProbe(),
) {
    val context = LocalContext.current

    // Zejscie w tlo MUSI przerwac pomiar — i to nie z grzecznosci. Android wycisza nagrywanie
    // aplikacji, ktora nie jest na wierzchu (AudioPolicyService: APP_STATE_IDLE -> silenced),
    // wiec przebieg dokonczony w tle zmierzylby polityke systemu zamiast mikrofonu i wypisal
    // "same zera" na sprawnym telefonie. Powod przerwania idzie do raportu.
    val wlasciciel = LocalLifecycleOwner.current
    DisposableEffect(wlasciciel, probe) {
        val obserwator = LifecycleEventObserver { _, zdarzenie ->
            if (zdarzenie == Lifecycle.Event.ON_STOP) probe.zatrzymaj("aplikacja zeszła w tło")
        }
        wlasciciel.lifecycle.addObserver(obserwator)
        onDispose {
            wlasciciel.lifecycle.removeObserver(obserwator)
            probe.zwolnij()
        }
    }

    val widok = LocalView.current
    DisposableEffect(widok, probe.trwa) {
        widok.keepScreenOn = probe.trwa
        onDispose { widok.keepScreenOn = false }
    }

    val teksty = remember(str) { rawMicTeksty(str) }
    var brakZgody by remember { mutableStateOf(false) }
    var skopiowane by remember { mutableStateOf(false) }

    val prosba = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { zgoda ->
        brakZgody = !zgoda
        if (zgoda) probe.start()
    }

    fun maZgode(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    fun uruchom() {
        skopiowane = false
        if (maZgode()) {
            brakZgody = false
            probe.start()
        } else {
            prosba.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun otworzUstawienia() {
        val i = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:" + context.packageName),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { context.startActivity(i) } catch (_: Throwable) {}
    }

    val wyniki = probe.wyniki
    val werdykt = opisWerdyktuRaw(wyniki, teksty)
    val gotowe = !probe.trwa && (probe.skonczone || probe.przerwane || wyniki.isNotEmpty())
    val raport = raportRaw(
        probe.naglowekPrzebiegu, wyniki, werdykt, teksty,
        ile = probe.ile,
        przerwany = probe.przerwane,
        powodPrzerwania = probe.powodPrzerwania,
        nasluch = probe.opisNasluchu(),
        wav = probe.wav,
    )

    fun kopiuj() {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("MATURA", raport))
            skopiowane = true
        } catch (_: Throwable) {
            skopiowane = false
        }
    }

    ScreenScaffold(str.rawMic, onBack, str.back) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Text(
                    str.rawMicIntro,
                    color = Palette.Bone,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(str.rawMicSpeak, color = Palette.Muted, fontSize = 12.sp, lineHeight = 17.sp)
                Spacer(Modifier.height(14.dp))

                PrimaryButton(
                    label = when {
                        probe.trwa -> str.voiceTestStop
                        wyniki.isNotEmpty() || probe.skonczone || probe.przerwane -> str.voiceTestAgain
                        else -> str.voiceTestStart
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (probe.trwa) probe.zatrzymaj("przycisk PRZERWIJ") else uruchom()
                }

                if (brakZgody) {
                    Spacer(Modifier.height(12.dp))
                    InfoCard(str.voiceTestNoMic)
                    Spacer(Modifier.height(10.dp))
                    GhostButton(str.voiceTestSettings) { otworzUstawienia() }
                }

                if (probe.trwa) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        str.voiceTestProgress + " " + (wyniki.size + 1) + "/" + probe.ile +
                            " · " + (probe.teraz ?: ""),
                        color = Palette.Accent,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    PoziomPasek(probe.poziom)
                }

                if (wyniki.isNotEmpty()) Spacer(Modifier.height(16.dp))
                wyniki.forEach { w -> RawWiersz(w, teksty) }

                if (gotowe) {
                    Spacer(Modifier.height(14.dp))
                    InfoCard(werdykt)
                    if (probe.wav != null) {
                        Spacer(Modifier.height(10.dp))
                        // Odsluch jest czescia DOWODU, nie ozdoba: aplikacja moze policzyc
                        // probki blednie, ale ucho nie pomyli glosu z cisza.
                        GhostButton(str.rawMicPlay) { probe.odtworz() }
                        Spacer(Modifier.height(6.dp))
                        Text(str.rawMicPlayHint, color = Palette.Muted, fontSize = 11.sp, lineHeight = 15.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                    GhostButton(str.voiceTestCopy) { kopiuj() }
                    if (skopiowane) {
                        Spacer(Modifier.height(8.dp))
                        Text(str.voiceTestCopied, color = Palette.Accent, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(14.dp))
                    SelectionContainer {
                        Text(
                            raport,
                            color = Palette.Muted,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Spacer(Modifier.height(30.dp))
            }
        }
    }
}

/** Pasek poziomu: jedyna rzecz na tym ekranie, ktora dzieje sie NA ZYWO. */
@Composable
private fun PoziomPasek(poziom: Float) {
    val pelne = (poziom.coerceIn(0f, 1f) * 20).toInt()
    Row(Modifier.fillMaxWidth()) {
        repeat(20) { i ->
            Box(
                Modifier
                    .width(12.dp)
                    .height(10.dp)
                    .padding(end = 2.dp)
                    .background(if (i < pelne) Palette.Accent else Palette.SurfaceHigh)
            )
        }
    }
}

@Composable
private fun RawWiersz(w: RawWynik, t: RawMicTeksty) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Text(w.nazwa, color = Palette.Bone, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(3.dp))
        Text(
            opisRawWyniku(w, t),
            color = if (w.slyszy) Palette.Accent else Palette.Muted,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
    }
}
