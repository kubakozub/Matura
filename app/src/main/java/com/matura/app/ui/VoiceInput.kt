package com.matura.app.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
 */
class VoiceInput(private val context: Context) {

    var mode by mutableStateOf(VoiceMode.UNAVAILABLE)
        private set
    var listening by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
        private set

    private var recognizer: SpeechRecognizer? = null
    private var wantListening = false
    private var language = "pl-PL"
    private var biasing: List<String> = emptyList()
    private var onHeard: ((List<String>, Boolean) -> Unit)? = null

    // Drabinka fallbacku silnika. Wiele urzadzen (np. Samsung) ma silnik on-device
    // "dostepny", ktory i tak odrzuca prosbe z EXTRA_PREFER_OFFLINE bledem ERROR_CLIENT.
    // Wtedy schodzimy nizej: silnik sieciowy Google, a potem bez wymuszania offline.
    private var preferOffline = true
    private var attemptedOnline = false
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

    private fun create(): SpeechRecognizer? = when (mode) {
        VoiceMode.ON_DEVICE -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else null
        VoiceMode.ONLINE -> SpeechRecognizer.createSpeechRecognizer(context)
        VoiceMode.UNAVAILABLE -> null
    }

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

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { status = "" }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            deliver(partialResults, final = false)
        }

        override fun onResults(results: Bundle?) {
            deliver(results, final = true)
            restartSoon()
        }

        override fun onError(error: Int) {
            when (error) {
                // Cisza i brak dopasowania sa normalne przy ciaglym nasluchu.
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restartSoon()

                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> restartSoon()

                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    status = "brak zgody na mikrofon"; stop()
                }
                // Kazdy inny blad (ERROR_CLIENT=5, ERROR_SERVER, brak pakietu jezyka…)
                // najpierw probujemy obejsc, schodzac na nastepny silnik. Dopiero gdy
                // drabinka sie skonczy — pokazujemy komunikat i oddajemy pole klawiaturze.
                else -> if (!escalate()) { status = friendlyError(error); stop() }
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
        return false
    }

    /** Przelaczenie silnika: niszczy obecny recognizer i tworzy nowy pod nowy tryb. */
    private fun useEngine(newMode: VoiceMode, offline: Boolean) {
        try { recognizer?.destroy() } catch (_: Throwable) {}
        mode = newMode
        preferOffline = offline
        recognizer = create()?.also { it.setRecognitionListener(listener) }
    }

    private fun friendlyError(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "brak internetu dla głosu — wpisz odpowiedź"
        13, 12 -> "brak pakietu mowy PL — wpisz odpowiedź" // LANGUAGE_UNAVAILABLE / NOT_SUPPORTED
        else -> "głos niedostępny — wpisz odpowiedź"
    }

    private fun deliver(bundle: Bundle?, final: Boolean) {
        val list = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
        if (list.isEmpty()) return
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
            val r = recognizer ?: return@postDelayed
            try {
                r.cancel()
                r.startListening(buildIntent())
            } catch (_: Throwable) {
                // Kolejna proba przyjdzie z nastepnego zdarzenia; nie ubijamy trybu.
            }
        }, delayMs)
    }

    fun start(
        language: String,
        biasing: List<String>,
        onHeard: (List<String>, Boolean) -> Unit,
    ) {
        // Nie sondujemy na nowo, jesli poprzednio doszlismy fallbackiem do dzialajacego
        // silnika sieciowego — inaczej co wlaczenie zaczynaloby od zepsutego on-device.
        if (mode == VoiceMode.UNAVAILABLE) probe()
        if (mode == VoiceMode.UNAVAILABLE) {
            status = "to urządzenie nie ma rozpoznawania mowy"
            return
        }
        this.language = language
        this.biasing = biasing
        this.onHeard = onHeard
        if (recognizer == null) recognizer = create()
        val r = recognizer ?: run { status = "nie udało się uruchomić nasłuchu"; return }
        r.setRecognitionListener(listener)
        wantListening = true
        listening = true
        status = ""
        try {
            r.startListening(buildIntent())
        } catch (t: Throwable) {
            if (!escalate()) { status = "nie udało się uruchomić nasłuchu"; stop() }
        }
    }

    fun stop() {
        wantListening = false
        listening = false
        handler.removeCallbacksAndMessages(null)
        try { recognizer?.cancel() } catch (_: Throwable) {}
        try { recognizer?.stopListening() } catch (_: Throwable) {}
    }

    fun release() {
        stop()
        try { recognizer?.destroy() } catch (_: Throwable) {}
        recognizer = null
        onHeard = null
    }

    private companion object {
        const val MAX_ALTERNATIVES = 6
        const val MAX_BIASING_WORDS = 200
    }
}

/** Trzyma [VoiceInput] przy zyciu na czas ekranu i zwalnia go przy wyjsciu. */
@Composable
fun rememberVoiceInput(context: Context): VoiceInput {
    val voice = remember(context) { VoiceInput(context).also { it.probe() } }
    DisposableEffect(voice) { onDispose { voice.release() } }
    return voice
}

// ---- UI --------------------------------------------------------------------

/**
 * Pasek trybu glosowego nad klawiatura. Przycisk zajmuje cala szerokosc, bo jest
 * alternatywa dla CALEJ klawiatury pod spodem, a nie jedna z jej opcji.
 *
 * Pod przyciskiem leci jedna linia informacji zwrotnej: co uslyszal silnik albo
 * dlaczego nie strzelil. Bez niej nietrafiona wypowiedz wyglada jak awaria.
 */
@Composable
fun VoiceBar(
    listening: Boolean,
    mode: VoiceMode,
    message: String,
    str: Str,
    onToggle: () -> Unit,
) {
    if (mode == VoiceMode.UNAVAILABLE && message.isEmpty()) return

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
                .clickable { onToggle() },
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
            val label = when {
                message.isNotEmpty() -> message
                mode == VoiceMode.ON_DEVICE -> str.voiceOnDevice
                else -> str.voiceOnline
            }
            Text(
                text = label,
                color = if (message.startsWith("✓")) Palette.Accent else Palette.Muted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}
