package com.matura.app.ui

/**
 * Decyzje trybu glosowego BEZ Androida — czysta logika, testowalna zwyklym JUnitem.
 *
 * Zasada: przycisk mikrofonu ma byc ZAWSZE do dotkniecia i kazde dotkniecie ma prowadzic
 * do jakiejs drogi glosu. Sciezke wybieramy w chwili dotkniecia, nie przy zlozeniu ekranu,
 * bo stan telefonu sie zmienia: uczen moze dac zgode w ustawieniach, doinstalowac aplikacje
 * Google, pobrac pakiet jezyka albo zwolnic mikrofon zajety przez inna aplikacje.
 */
enum class VoicePath {
    /** Otworz ustawienia aplikacji — zgoda odrzucona na stale, system nie pokaze juz pytania. */
    OPEN_SETTINGS,
    /** Popros o zgode na mikrofon. */
    REQUEST_PERMISSION,
    /** Ciagly nasluch przez zwiazana usluge (drabinka silnikow w [VoiceInput]). */
    ENGINE,
    /** Modalne okno systemowe ACTION_RECOGNIZE_SPEECH — nie wiaze sie z zadna usluga. */
    SYSTEM_DIALOG,
    /** Ostatnia deska ratunku: klawiatura telefonu z wlasnym dyktowaniem. */
    KEYBOARD,
}

/** Co pokazac pod przyciskiem paska glosu. */
enum class VoiceLabel { MESSAGE, ON_DEVICE, ONLINE, DIALOG, KEYBOARD }

object VoicePolicy {

    /**
     * Ktora droga glosu po dotknieciu paska. Kolejnosc jest celowa: najpierw sprawy zgody
     * (bez niej silnik i tak odpowie ERROR_INSUFFICIENT_PERMISSIONS), potem ciagly nasluch,
     * potem okno systemowe, a na koncu klawiatura telefonu — ktora dziala zawsze.
     */
    fun path(
        permissionGranted: Boolean,
        settingsPending: Boolean,
        hasEngine: Boolean,
        dialogAvailable: Boolean,
    ): VoicePath = when {
        settingsPending -> VoicePath.OPEN_SETTINGS
        !permissionGranted -> VoicePath.REQUEST_PERMISSION
        hasEngine -> VoicePath.ENGINE
        dialogAvailable -> VoicePath.SYSTEM_DIALOG
        else -> VoicePath.KEYBOARD
    }

    /** Podpis pod przyciskiem: komunikat ma pierwszenstwo, potem tryb silnika, potem droga awaryjna. */
    fun label(
        listening: Boolean,
        mode: VoiceMode,
        hasMessage: Boolean,
        dialogAvailable: Boolean,
    ): VoiceLabel = when {
        hasMessage -> VoiceLabel.MESSAGE
        mode == VoiceMode.ON_DEVICE -> VoiceLabel.ON_DEVICE
        listening || mode == VoiceMode.ONLINE -> VoiceLabel.ONLINE
        dialogAvailable -> VoiceLabel.DIALOG
        else -> VoiceLabel.KEYBOARD
    }
}

/**
 * Wykrywanie GLUCHEGO mikrofonu z poziomu sygnalu, ktory silnik zglasza w onRmsChanged.
 *
 * Silnik Google raportuje ok. -2,12 dB dla strumienia samych zer — dokladnie to dostaje
 * aplikacja, gdy mikrofon jest wylaczony przelacznikiem prywatnosci (Android 12+), wyciszony
 * sprzetowo albo zajety przez inna aplikacje. Nawet w cichym pokoju jeden cykl nasluchu (5–8 s)
 * choc raz przekracza -1,5 dB, wiec prog -1,5 dB (0,6 dB nad dnem) odroznia "nic nie wchodzi"
 * od "cicho, ale mikrofon zyje". Dwa puste cykle z rzedu — zeby pojedynczy pusty cykl (uczen
 * jeszcze nie zaczal mowic) nie straszyl komunikatem. Wymagamy tez kilku probek RMS: silnik,
 * ktory w ogole nie raportuje poziomu (bywa), nie moze uchodzic za wyciszony mikrofon.
 *
 * Stanu przelacznika prywatnosci nie da sie odczytac wprost: publiczne API SensorPrivacyManager
 * ma tylko supportsSensorToggle(), a isSensorPrivacyEnabled() jest @SystemApi. Stad heurystyka.
 */
class SilenceDetector(
    private val thresholdDb: Float = SILENCE_DB,
    private val minSamples: Int = MIN_SAMPLES,
    private val cyclesNeeded: Int = CYCLES,
) {
    /** Najglosniejsza probka w biezacym cyklu — do diagnostyki. */
    var maxRms = Float.NEGATIVE_INFINITY
        private set
    private var samples = 0

    /** Ile pustych cykli z rzedu skonczylo sie z RMS na dnie. */
    var silentCycles = 0
        private set

    /** Czy podpowiedz o gluchym mikrofonie jest w tej chwili uzasadniona. */
    val active: Boolean get() = silentCycles >= cyclesNeeded

    fun onCycleStart() {
        maxRms = Float.NEGATIVE_INFINITY
        samples = 0
    }

    fun onRms(db: Float) {
        samples += 1
        if (db > maxRms) maxRms = db
    }

    /** Cykl skonczyl sie bez mowy (NO_MATCH / SPEECH_TIMEOUT). true = pokaz podpowiedz. */
    fun onEmptyCycle(): Boolean {
        val silent = samples >= minSamples && maxRms <= thresholdDb
        silentCycles = if (silent) silentCycles + 1 else 0
        onCycleStart()
        return active
    }

    /** Cos zostalo rozpoznane — mikrofon na pewno slyszy. */
    fun onSpeech() { silentCycles = 0 }

    fun reset() {
        silentCycles = 0
        onCycleStart()
    }

    companion object {
        const val SILENCE_DB = -1.5f
        const val MIN_SAMPLES = 5
        const val CYCLES = 2
    }
}
