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

/**
 * Czy cykl, ktory przez caly swoj limit nie oddal ANI wyniku, ANI bledu, ma zejsc drabinka.
 *
 * To jest druga polowa tej samej zasady co [eskalowacPoPustym]: silnik degradujemy dopiero
 * wtedy, gdy nie ma dowodu, ze pracuje. Dowodem jest WYLACZNIE oddany tekst.
 *
 * Gotowosc ([android.speech.RecognitionListener.onReadyForSpeech]) i strumien poziomu
 * ([android.speech.RecognitionListener.onRmsChanged]) dowodem nie sa: silnik bez modelu jezyka
 * robi jedno i drugie, a potem milczy — to wlasnie ta awaria, dla ktorej straznik cyklu powstal.
 *
 * Odwrotnie: silnik, ktory w tej sesji cokolwiek juz zamienil na tekst, jest sprawny, a cisza
 * trwajaca dluzej niz limit cyklu znaczy wtedy tyle, ze uczen sie zastanawia. Zejscie drabinka
 * kosztowaloby go szczebel i — przez useEngine() — slownik zestawu do konca sesji, czyli
 * pogorszyloby glos dokladnie temu, komu akurat dziala. Taki cykl po prostu wznawiamy.
 *
 * @param oddalWynik czy ten silnik zamienil juz cokolwiek na tekst w tej sesji
 */
fun zejscPoMilczacymCyklu(oddalWynik: Boolean): Boolean = !oddalWynik

/** Uzytkownik mowil, a silnik nic nie zwrocil — dwa takie cykle wystarcza. */
const val PUSTE_Z_MOWA = 2
/** Sama cisza moze byc wina uzytkownika; dajemy silnikowi wiecej prob. */
const val PUSTE_BEZ_MOWY = 4

/**
 * Co drabinka silnikow ma zrobic z kodem bledu.
 *
 * Decyzja siedzi tu, a nie w [VoiceInput], z jednego powodu: kodow 12 i 13 nie da sie wywolac
 * na maszynie bez telefonu, a to wlasnie one padaja, gdy na urzadzeniu nie ma pobranego pakietu
 * polskiego (typowe na Androidzie 13+). Czysta funkcja pozwala sprawdzic testem, ze takiego kodu
 * NIE potraktujemy jak zwyklej ciszy i ze doprowadzi do zejscia drabinka.
 *
 * Numery stoja wprost, bo sa czescia publicznego API android.speech.SpeechRecognizer.ERROR_*
 * (ustalone kolejno od 1) — dzieki temu funkcja nie potrzebuje Androida.
 */
enum class ReakcjaNaBlad {
    /** 6 SPEECH_TIMEOUT, 7 NO_MATCH — pusty cykl; dalej decyduje [eskalowacPoPustym]. */
    PUSTY_CYKL,
    /** 3 AUDIO — mikrofon zajety albo chwilowo niedostepny; kilka ponowien ma sens. */
    NAGRYWANIE,
    /** 8 RECOGNIZER_BUSY — poprzednia sesja jeszcze sie sprzata. */
    ZAJETY,
    /** 9 INSUFFICIENT_PERMISSIONS — sprawa zgody albo kaprys uslugi; rozstrzyga ekran. */
    ZGODA,
    /** 12 LANGUAGE_NOT_SUPPORTED / 13 LANGUAGE_UNAVAILABLE — brak modelu tego jezyka. */
    BRAK_JEZYKA,
    /** Wszystko inne: 5 CLIENT, 4 SERVER, 1/2 siec, 10, 11, 14, 15 i kody spoza zakresu. */
    INNY,
}

/**
 * Kod bledu -> reakcja drabinki. Kody jezykowe maja WLASNA galaz, bo do 0.9.10 wpadaly do worka
 * "kazdy inny blad": drabinka schodzila, owszem, ale z zapalonym EXTRA_PREFER_OFFLINE i pelna
 * lista podpowiedzi slownikowych — czyli z tymi dwiema rzeczami, ktore silnik bez pakietu
 * jezyka odrzuca w pierwszej kolejnosci.
 */
fun reakcjaNaBlad(kod: Int): ReakcjaNaBlad = when (kod) {
    6, 7 -> ReakcjaNaBlad.PUSTY_CYKL
    3 -> ReakcjaNaBlad.NAGRYWANIE
    8 -> ReakcjaNaBlad.ZAJETY
    9 -> ReakcjaNaBlad.ZGODA
    12, 13 -> ReakcjaNaBlad.BRAK_JEZYKA
    else -> ReakcjaNaBlad.INNY
}

/**
 * W ktorym jezyku beda odpowiedzi lezace na planszy — czyli jakiego modelu ma uzyc silnik mowy.
 *
 * Problem dotyczy WYLACZNIE kierunku losowego (Direction.RANDOM). GameEngine losuje kierunek
 * dla KAZDEGO wiersza osobno, wiec na planszy leza jednoczesnie odpowiedzi polskie i angielskie,
 * a startListening przyjmuje tylko jeden tag jezyka. Do 0.9.10 rozstrzygal to ksztalt slowa
 * (dwuznaki cz/sz/rz) i dla zestawu w rodzaju "plecy, kolano, glowa" wychodzil ANGIELSKI.
 *
 * To osobna, realna usterka — ale NIE tlumaczy zgloszenia z telefonu przy kierunku domyslnym.
 * Domyslny jest Direction.TERM_TO_DEF (angielski -> polski), gdzie jezyk odpowiedzi bral sie
 * z samego kierunku i pl-PL szlo do silnika juz w 0.9.10. Jesli uzytkownik nie przestawil
 * kierunku na losowy, ta poprawka nie zmienia u niego nic i przyczyny trzeba szukac dalej:
 * w ekranie OPCJE -> Test mowy albo w `adb logcat -s MaturaVoice`.
 *
 * Zgadywac nie trzeba: obie strony zestawu sa znane, a odpowiedzi na planszy pochodza wprost
 * z jednej z nich. Liczymy wiec, ktora strona przewaza. Slowo obecne po obu stronach (albo po
 * zadnej — np. po zmianie zestawu) nie glosuje.
 *
 * @param naPlanszy odpowiedzi lezace na planszy, w tej samej postaci co w zestawach
 * @param polskie znormalizowane odpowiedzi ze strony polskiej zestawu
 * @param angielskie znormalizowane odpowiedzi ze strony angielskiej zestawu
 * @return true/false, gdy jedna strona przewaza; null przy remisie — wtedy decyduje
 *         [wygladaPoPolsku], bo nie ma czego policzyc
 */
fun odpowiedziPoPolsku(
    naPlanszy: List<String>,
    polskie: Set<String>,
    angielskie: Set<String>,
): Boolean? = policzStrony(naPlanszy, polskie, angielskie).przewaga()

/** Ile odpowiedzi na planszy nalezy do polskiej, a ile do angielskiej strony zestawu. */
data class GlosyPlanszy(val pl: Int, val en: Int) {
    /** true/false, gdy jedna strona przewaza; null przy remisie — nie ma czego liczyc. */
    fun przewaga(): Boolean? = when {
        pl > en -> true
        en > pl -> false
        else -> null
    }
}

/** Zliczenie glosow z planszy. Slowo obecne po obu stronach (albo po zadnej) nie glosuje. */
fun policzStrony(
    naPlanszy: List<String>,
    polskie: Set<String>,
    angielskie: Set<String>,
): GlosyPlanszy {
    var pl = 0
    var en = 0
    for (o in naPlanszy) {
        val wPolskich = o in polskie
        val wAngielskich = o in angielskie
        if (wPolskich == wAngielskich) continue   // z obu stron albo z zadnej — nie glosuje
        if (wPolskich) pl += 1 else en += 1
    }
    return GlosyPlanszy(pl, en)
}

/**
 * Czy przestawic jezyk silnika W TRAKCIE nasluchu.
 *
 * Jezyk ustala sie raz, przy voice.start(), a plansza w trybie losowym zmienia sklad w trakcie
 * rundy: po zabiciu kilku potworow przewaga moze sie odwrocic i silnik dalej transkrybowalby
 * en-US, gdy na planszy leza juz same polskie odpowiedzi. To ta sama klasa bledu, ktora
 * naprawia [odpowiedziPoPolsku], tylko przesunieta w czas.
 *
 * Restart nasluchu nie jest darmowy (silnik gubi ulamek sekundy i moze zglosic ZAJETY), wiec
 * przestawiamy sie dopiero, gdy druga strona prowadzi o [PRZEWAGA_ZMIANY] odpowiedzi. Bez tej
 * histerezy kazdy zabity potwor przy wyniku 3:3 restartowalby nasluch.
 */
fun zmienicJezykNasluchu(biezacyPolski: Boolean, glosy: GlosyPlanszy): Boolean {
    val przewagaDrugiej = if (biezacyPolski) glosy.en - glosy.pl else glosy.pl - glosy.en
    return przewagaDrugiej >= PRZEWAGA_ZMIANY
}

/** O tyle odpowiedzi musi prowadzic druga strona, zeby oplacilo sie przestawic silnik. */
const val PRZEWAGA_ZMIANY = 2

/**
 * Zapasowa zgadywanka po ksztalcie slowa — uzywana TYLKO wtedy, gdy [odpowiedziPoPolsku] nie ma
 * czego policzyc (zestaw wlasny z ta sama trescia po obu stronach, pusta plansza). Regula jest
 * ta sama co przed 0.9.11, zeby remis zachowywal sie tak jak dotad; jej slabosc (polskie slowa
 * bez dwuznakow, jak "plecy" czy "kolano", uchodza za angielskie) jest wlasnie powodem, dla
 * ktorego nie jest juz pierwszym wyborem. Pusta plansza to polski: gra jest do nauki angielskiego,
 * wiec strona polska jest ta, ktora uczen zna.
 */
fun wygladaPoPolsku(slowa: List<String>): Boolean =
    slowa.count { DWUZNAKI_POLSKIE.containsMatchIn(it) } * 2 >= slowa.size

/** prz/trz mieszcza sie juz w rz — zostaja, zeby regula byla ta sama co przed 0.9.11. */
private val DWUZNAKI_POLSKIE = Regex("(cz|sz|rz|prz|trz|dz)")

/**
 * Znacznik ZAPLANOWANEGO wznowienia nasluchu.
 *
 * Wznowienie leci przez Handler z opoznieniem (silnik potrzebuje chwili na sprzatniecie
 * poprzedniej sesji). Samo `removeCallbacksAndMessages` nie jest tu pelna odpowiedzia: nie
 * dosiega akcji, ktora zdazyla juz wyjsc z kolejki, i milczaco zaklada, ze KAZDA odroczona
 * akcja sama sprawdzi, czy nasluch jeszcze trwa. Znacznik zamienia te milczaca umowe w warunek,
 * ktory widac i ktory da sie sprawdzic testem bez urzadzenia: [anuluj] uniewaznia wszystko,
 * co zaplanowano wczesniej, wiec restart zamowiony chwile przed stop() nie wskrzesi nasluchu
 * po wyjsciu z ekranu.
 */
class Wznowienie {
    private var znacznik = 0

    /** Czy jakiekolwiek wznowienie czeka na wykonanie. */
    var zaplanowane = false
        private set

    /** Rezerwuje wznowienie i zwraca znacznik, ktorym odroczona akcja sie wylegitymuje. */
    fun zaplanuj(): Int {
        znacznik += 1
        zaplanowane = true
        return znacznik
    }

    /** Czy akcja z tym znacznikiem ma sie jeszcze wykonac. */
    fun aktualne(z: Int): Boolean = zaplanowane && z == znacznik

    /** Akcja sie wykonala — nie ma juz czego anulowac. */
    fun wykonano(z: Int) { if (z == znacznik) zaplanowane = false }

    /** stop() / release() / nowy start: wszystko, co bylo zaplanowane, traci waznosc. */
    fun anuluj() {
        znacznik += 1
        zaplanowane = false
    }
}

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
