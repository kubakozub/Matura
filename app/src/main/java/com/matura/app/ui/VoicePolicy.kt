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
     * Ktora droga glosu po dotknieciu paska.
     *
     * Zgoda na mikrofon (RECORD_AUDIO) dotyczy WYLACZNIE szczebla silnika: okno systemowe
     * nagrywa aplikacja Google na wlasnej zgodzie, a klawiatura telefonu to zwykle wpisywanie
     * tekstu. Dlatego bez silnika nie prosimy o zgode, ktorej i tak bysmy nie uzyli, tylko
     * od razu idziemy do okna albo klawiatury.
     *
     * Po trwalej odmowie ([settingsPending]) dotkniecie otwiera ustawienia aplikacji — ale
     * tylko RAZ. Jesli uczen wroci z ustawien bez zmiany zgody ([settingsTried]), ponowna
     * prosba nic nie da (Android 11+ odpowiada natychmiast "nie", bez zadnego okna), a kolejne
     * ustawienia bylyby natretne: schodzimy wtedy do okna systemowego / klawiatury, ktore
     * zgody aplikacji nie potrzebuja. Szczebel zgody wraca, gdy zgoda sie zmieni (ekran).
     */
    fun path(
        permissionGranted: Boolean,
        settingsPending: Boolean,
        hasEngine: Boolean,
        dialogAvailable: Boolean,
        settingsTried: Boolean = false,
    ): VoicePath {
        val bezSilnika = if (dialogAvailable) VoicePath.SYSTEM_DIALOG else VoicePath.KEYBOARD
        return when {
            !hasEngine -> bezSilnika
            permissionGranted -> VoicePath.ENGINE
            settingsTried -> bezSilnika
            settingsPending -> VoicePath.OPEN_SETTINGS
            else -> VoicePath.REQUEST_PERMISSION
        }
    }

    /**
     * Podpis pod przyciskiem: komunikat ma pierwszenstwo, potem tryb silnika, potem droga
     * awaryjna. [hasEngine] (a nie sam [mode]) rozstrzyga, czy dotkniecie pojdzie do silnika:
     * tryb UNAVAILABLE z niepusta lista uslug i tak zwiaze sie z pierwsza z nich (online),
     * a wyczerpana drabinka przy trybie ONLINE pojdzie do okna — podpis ma mowic prawde.
     */
    fun label(
        listening: Boolean,
        mode: VoiceMode,
        hasMessage: Boolean,
        dialogAvailable: Boolean,
        hasEngine: Boolean,
    ): VoiceLabel = when {
        hasMessage -> VoiceLabel.MESSAGE
        !listening && !hasEngine -> if (dialogAvailable) VoiceLabel.DIALOG else VoiceLabel.KEYBOARD
        mode == VoiceMode.ON_DEVICE -> VoiceLabel.ON_DEVICE
        else -> VoiceLabel.ONLINE
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

/**
 * Czy pusty cykl rozpoznawania (NO_MATCH / SPEECH_TIMEOUT) ma juz oznaczac awarie silnika.
 *
 * Cisza jest normalna, dopoki silnik cokolwiek rozpoznal — wtedy wiadomo, ze dziala, a uzytkownik
 * po prostu milczy. Silnik, ktory NIC jeszcze nie rozpoznal, zachowuje sie tak samo, gdy nie ma
 * modelu jezyka albo odmawia pracy offline: przyjmuje nagranie i zwraca "nic nie slyszalem" w kolko.
 * Rozroznienie robi mikrofon: jesli slychac bylo mowe, a silnik i tak nic nie zwrocil, nie ma na co
 * czekac.
 *
 * @param dzialal czy ten silnik w tej sesji rozpoznal juz cokolwiek
 * @param pusteCykle ile pustych cykli z rzedu (biezacy wliczony)
 * @param slyszalMowe czy w tych cyklach mikrofon rejestrowal dzwiek ponad progiem ciszy
 */
fun eskalowacPoPustym(dzialal: Boolean, pusteCykle: Int, slyszalMowe: Boolean): Boolean {
    if (dzialal) return false
    val prog = if (slyszalMowe) PUSTE_Z_MOWA else PUSTE_BEZ_MOWY
    return pusteCykle >= prog
}

/** Uzytkownik mowil, a silnik nic nie zwrocil — dwa takie cykle wystarcza. */
const val PUSTE_Z_MOWA = 2
/** Sama cisza moze byc wina uzytkownika; dajemy silnikowi wiecej prob. */
const val PUSTE_BEZ_MOWY = 4

/**
 * Dziennik ostatnich zdarzen silnika mowy. Sluzy WYLACZNIE diagnostyce pod dlugim przytrzymaniem
 * paska: bez niego zgloszenie "przycisk dziala, nic sie nie dzieje" nie niesie zadnej informacji.
 * Nie zapisuje tresci wypowiedzi — tylko rodzaj zdarzenia, kod bledu i czas wzgledny.
 */
class VoiceLog(private val limit: Int = 14) {
    private val wpisy = ArrayDeque<String>()
    private var start = 0L

    fun reset(teraz: Long) { wpisy.clear(); start = teraz }

    fun dodaj(teraz: Long, co: String) {
        if (start == 0L) start = teraz
        val s = (teraz - start) / 100L
        wpisy.addLast("%d.%ds %s".format(s / 10, s % 10, co))
        while (wpisy.size > limit) wpisy.removeFirst()
    }

    fun tekst(): String = if (wpisy.isEmpty()) "—" else wpisy.joinToString("\n")
}
