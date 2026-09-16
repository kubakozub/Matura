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
 * SensorPrivacyManager nie odpowie: publicznie ma tylko supportsSensorToggle(), a
 * isSensorPrivacyEnabled() jest @SystemApi. Od 0.9.17 o sam przelacznik pytamy inna droga —
 * przez AppOps ([przelacznikPrywatnosciZAppOps], API 29+) — ale ta heurystyka zostaje i ma po
 * co zostac: dziala od API 24 i lapie takze mikrofon zajety przez inna aplikacje, czego AppOps
 * nie pokazuje wcale.
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

        /**
         * Od tego poziomu w cyklu uznajemy, ze do mikrofonu weszla MOWA (a nie tylko "cos"). To inny
         * prog niz [SILENCE_DB]: tamten odroznia gluchy mikrofon od zywego (0,6 dB nad dnem), a ten
         * mowe od szumu otoczenia. Na emulatorze z mikrofonem hosta cichy pokoj dawal 1–2 dB i przy
         * progu -1,5 dB kazdy pusty cykl liczyl sie jako "slyszal mowe": sprawny silnik tracil
         * szczebel po dwoch cyklach, choc nikt nic nie powiedzial. Slowo wypowiedziane normalnie
         * daje 6–10 dB.
         */
        const val MOWA_DB = 4f
    }
}

/**
 * Ktore hipotezy silnika naprawde niosa transkrypcje.
 *
 * SpeechRecognizer ma CZTERY sposoby na powiedzenie "nic nie uslyszalem", a do 0.9.11 kod
 * rozpoznawal tylko dwa: brak Bundle i pusta lista. Trzeci i czwarty — lista jednego pustego
 * ciagu albo samych bialych znakow — przechodzily obie bramki w deliver(), ustawialy "silnik
 * oddal wynik" i przez to na zawsze wylaczaly schodzenie drabinka. Objaw jest dokladnie ten
 * ze zgloszenia: pasek swieci "SLUCHAM…", w logu same wyniki, mowa bez reakcji.
 *
 * Filtr jest celowo prosty: trim, a potem wymog choc jednego znaku alfanumerycznego. Sam
 * isNotBlank przepuscilby hipoteze "." albo ",", ktora czesc silnikow zwraca zamiast pustego
 * ciagu, a ktora dla dopasowania odpowiedzi znaczy tyle samo co nic.
 *
 * @param surowe lista spod RESULTS_RECOGNITION; null = brak Bundle albo brak klucza. Typ
 *        dopuszcza null W SRODKU listy, bo przychodzi ona z uslugi spoza aplikacji i Kotlin
 *        nie sprawdza jej elementow — jeden null wywracalby nasluch wyjatkiem.
 * @return przyciete hipotezy z trescia, w kolejnosci od silnika; pusta lista = BRAK TRANSKRYPCJI
 */
fun uzyteczneHipotezy(surowe: List<String?>?): List<String> =
    surowe.orEmpty().mapNotNull { h ->
        h?.trim()?.takeIf { t -> t.any { z -> z.isLetterOrDigit() } }
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
fun eskalowacPoPustym(oddalWynik: Boolean, pusteCykle: Int, slyszalMowe: Boolean): Boolean {
    // Prog zalezy od dwoch rzeczy: czy silnik KIEDYKOLWIEK oddal tekst i czy mikrofon slyszal mowe.
    //
    // Sama gotowosc silnika nie liczy sie tu w ogole — i to jest sedno tej poprawki. Do 0.9.11
    // decydowal znak zycia (`dzialal`), ustawiany juz przez onReadyForSpeech, wiec silnik, ktory
    // melduje gotowosc i nie rozpoznaje ani slowa, na zawsze wylaczal eskalacje. Objaw: pasek
    // swieci, zadnego bledu nie ma, mowa nie daje reakcji.
    val prog = when {
        // Silnik sprawdzony w boju: cisza gracza NIGDY go nie degraduje...
        oddalWynik && !slyszalMowe -> return false
        // ...ale gdy mikrofon slyszy mowe, a on przestal cokolwiek zwracac, po szesciu cyklach
        // schodzimy nizej. Wczesniejszy sukces nie moze blokowac odzyskania dzialania na zawsze.
        oddalWynik -> PUSTE_PO_SUKCESIE
        slyszalMowe -> PUSTE_Z_MOWA
        else -> PUSTE_BEZ_MOWY
    }
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
/**
 * Silnik, ktory juz raz oddal tekst, dostaje wyrazny kredyt zaufania: dopiero szesc cykli z mowa
 * w mikrofonie i bez zadnego wyniku znaczy, ze przestal dzialac. Prog jest wyzszy niz [PUSTE_Z_MOWA],
 * bo u sprawnego silnika taka seria to zwykle wina otoczenia (halas, ktos mowi obok), a nie awarii.
 */
const val PUSTE_PO_SUKCESIE = 6
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
class VoiceLog(private val limit: Int = LIMIT_WPISOW) {
    private val wpisy = ArrayDeque<String>()
    private var start = 0L

    fun reset(teraz: Long) { wpisy.clear(); start = teraz }

    /**
     * Granica miedzy proba a proba — historia ZOSTAJE.
     *
     * Do 0.9.16 start() wolal tu reset(), wiec kazde ponowne dotkniecie paska kasowalo dokladnie
     * ten slad, po ktory uczen otwieral diagnostyke: probe, ktora wlasnie zawiodla. A dotkniecie
     * paska po nieudanej probie jest odruchem, nie wyjatkiem. Zegar wpisow rebasujemy na nowa
     * sesje, zeby czasy dalej znaczyly "ile od startu TEJ proby", a nie od wlaczenia ekranu.
     */
    fun nowaSesja(teraz: Long, separator: String = SEPARATOR_SESJI) {
        if (wpisy.isNotEmpty()) {
            wpisy.addLast(separator)
            while (wpisy.size > limit) wpisy.removeFirst()
        }
        start = teraz
    }

    fun dodaj(teraz: Long, co: String) {
        if (start == 0L) start = teraz
        val s = (teraz - start) / 100L
        wpisy.addLast("%d.%ds %s".format(s / 10, s % 10, co))
        while (wpisy.size > limit) wpisy.removeFirst()
    }

    fun tekst(): String = if (wpisy.isEmpty()) "—" else wpisy.joinToString("\n")

    companion object {
        /**
         * Ile wpisow miesci dziennik.
         *
         * Bylo 14 i starczalo, dopoki dziennik trzymal JEDNA probe. Od 0.9.17 jest ciagly (nowa
         * proba dokleja sie za separatorem), a doszly do niego restarty nasluchu, zajety silnik,
         * bledy nagrywania i uzbrojenie straznika dzwieku — przy 14 wpisach nieudana proba
         * wypadalaby z gory, zanim uczen zdazylby przytrzymac pasek. 40 mniej wiecej odpowiada
         * dwom pelnym probom z cala ich historia.
         */
        const val LIMIT_WPISOW = 40

        /** Widoczna granica miedzy probami w nakladce diagnostycznej. */
        const val SEPARATOR_SESJI = "— nowa sesja —"
    }
}

/**
 * Stan przebiegu rozpoznawania - do diagnostyki i do odpowiedzi na pytanie "co sie teraz dzieje".
 *
 * Stan NIE jest osobnym polem: wyliczamy go z flag, ktore i tak steruja nasluchem ([stanSesji]).
 * Osobne pole trzeba by zerowac w kazdej z kilkunastu sciezek wyjscia i predzej czy pozniej
 * rozjechaloby sie z rzeczywistoscia - a wtedy diagnostyka klamie akurat tam, gdzie ma pomoc.
 */
enum class VoiceStan { BEZCZYNNY, URUCHAMIANIE, NASLUCH, PONOWIENIE, BLAD }

/**
 * @param chceNasluchu czy nasluch jest wlaczony (wantListening)
 * @param czekamNaSilnik czy czekamy na PIERWSZE zdarzenie po startListening (watchdog uzbrojony)
 * @param znakZycia czy silnik w tej sesji dal juz jakikolwiek znak zycia (gotowosc, RMS, wynik)
 * @param wyczerpany czy drabinka doszla do konca
 */
fun stanSesji(
    chceNasluchu: Boolean,
    czekamNaSilnik: Boolean,
    znakZycia: Boolean,
    wyczerpany: Boolean,
): VoiceStan = when {
    wyczerpany && !chceNasluchu -> VoiceStan.BLAD
    !chceNasluchu -> VoiceStan.BEZCZYNNY
    czekamNaSilnik -> VoiceStan.URUCHAMIANIE
    znakZycia -> VoiceStan.NASLUCH
    else -> VoiceStan.PONOWIENIE
}

/**
 * Czy ta wypowiedz ma zaliczyc odpowiedz.
 *
 * Jedna wypowiedz = najwyzej jedno zaliczenie. Do 0.9.11 pilnowalo tego okno 700 ms od
 * poprzedniego trafienia - a odstep miedzy wynikiem CZESCIOWYM a KONCOWYM tej samej wypowiedzi
 * zalezy od silnika i od ciszy po slowie, i bywa wyraznie dluzszy. Ta sama wypowiedz potrafila
 * wiec zaliczyc slowo dwa razy, a dwa osobne "kolano" tuz po sobie (dwa potwory z ta sama
 * odpowiedzia) bywaly liczone raz. Numer wypowiedzi rosnie przy kazdym startListening, wiec
 * rozstrzyga PRZEBIEG, a nie zegar.
 *
 * @param wypowiedz numer biezacej wypowiedzi (VoiceInput.wypowiedz); wartosci ujemne rezerwuje
 *        okno systemowe, ktore ma wlasna numeracje i nigdy nie koliduje z nasluchem w grze.
 */
fun zaliczycWypowiedz(
    odpowiedz: String,
    ostatniaOdpowiedz: String,
    wypowiedz: Int,
    ostatniaWypowiedz: Int,
): Boolean = !(odpowiedz == ostatniaOdpowiedz && wypowiedz == ostatniaWypowiedz)

/**
 * Czy po powrocie z systemowego okna zgody wolno wlaczyc nasluch.
 *
 * Okno zgody potrafi wrocic po dluzszej chwili - uczen zdazyl wlaczyc pauze albo skonczyc gre.
 * Bezwarunkowe startVoice() w callbacku wlaczalo wtedy mikrofon do rundy, ktorej juz nie ma:
 * pasek swiecil na ekranie wyniku, a wypowiedzi szly w nicosc.
 */
fun wznowicPoZgodzie(zgoda: Boolean, pauza: Boolean, koniecGry: Boolean): Boolean =
    zgoda && !pauza && !koniecGry

/** Tagi jezyka, o ktore gra pyta silnik mowy: odpowiedzi na planszy sa polskie albo angielskie. */
val JEZYKI_GRY: List<String> = listOf("pl-PL", "en-US")

/**
 * Czy lista modeli on-device z RecognitionSupport pokrywa tag, o ktory pytamy.
 *
 * Silnik podaje tagi BCP 47, ale nie zawsze w tej samej pisowni co my ("pl-PL", "pl_PL", "pl").
 * Liczy sie: ten sam tag, sam jezyk bez regionu, a gdy MY pytamy bez regionu — dowolny region
 * tego jezyka. Inny region ("en-GB" przy pytaniu o "en-US") NIE liczy sie za model: czesc silnikow
 * odrzuca wtedy pelny tag kodem 13, a szczebel "jezyk bez regionu" i tak to obsluzy pozniej.
 *
 * @param zainstalowane RecognitionSupport.installedOnDeviceLanguages (albo supportedOnDeviceLanguages)
 */
fun modelZainstalowany(tag: String, zainstalowane: List<String>): Boolean {
    val t = tag.trim().replace('_', '-').lowercase()
    if (t.isEmpty()) return false
    val jezyk = jezykBezRegionu(t).ifEmpty { t }
    return zainstalowane.any { wpis ->
        val z = wpis.trim().replace('_', '-').lowercase()
        z == t || z == jezyk || (t == jezyk && jezykBezRegionu(z) == jezyk)
    }
}

/**
 * Tag jezyka bez regionu ("en-US" -> "en"), albo pusty gdy nie ma czego upraszczac.
 *
 * Czesc silnikow ma model podpisany samym jezykiem albo INNYM regionem (typowo en-GB tam, gdzie
 * prosimy o en-US) i na pelny tag odpowiada ERROR_LANGUAGE_UNAVAILABLE, chociaz angielski
 * rozpoznaje. To jeden szczebel drabinki, nie zgadywanie listy regionow.
 */
fun jezykBezRegionu(tag: String): String {
    val i = tag.indexOfFirst { it == '-' || it == '_' }
    if (i <= 0) return ""
    return tag.substring(0, i)
}

/**
 * Nazwa jezyka do komunikatu o braku pakietu mowy — z TAGU, ktory naprawde poszedl do silnika.
 *
 * Do 0.9.11 komunikat brzmial "brak pakietu mowy PL" niezaleznie od tego, o co silnik byl pytany,
 * a gra rownie czesto slucha po angielsku: jezyk odpowiedzi ustala ekran z kierunku gry albo
 * z przewagi na planszy. Uczen dostawal wiec polecenie zainstalowania nie tego pakietu, co trzeba.
 *
 * Surowego tagu nie pokazujemy — "en-US" nic uczniowi nie mowi. Gra uzywa dokladnie dwoch tagow
 * i ich wersji bez regionu (szczebel 2b drabinki zamienia "en-US" na "en"), wiec rozstrzyga
 * przedrostek; dla czegokolwiek innego zwracamy pusty ciag, a wolajacy pokazuje wtedy zdanie
 * bez nazwy jezyka, zamiast wpisywac w nie kod BCP 47.
 *
 * @param polski nazwa jezyka w postaci, ktora wchodzi do zdania — ekran podstawia wersje PL/EN
 */
fun jezykDoKomunikatu(tag: String, polski: String, angielski: String): String = when {
    tag.startsWith("pl", ignoreCase = true) -> polski
    tag.startsWith("en", ignoreCase = true) -> angielski
    else -> ""
}

// ---- LIMITY CZASU SCIEZKI GLOSOWEJ ----------------------------------------------------
//
// Progi stoja TU, a nie w prywatnym companion object VoiceInput, z jednego powodu: kazdy z nich
// powstal po konkretnej awarii z telefonu, a dopoki byly prywatne, zaden test nie mogl nawet
// zapytac, czy okno startu w ogole ma jakikolwiek limit. Tyle wystarczylo, zeby galaz sprawdzania
// modelu on-device chodzila bez wlasnego limitu przez cztery wersje.

/**
 * Ile czekamy na PIERWSZE zdarzenie od silnika po startListening (watchdog).
 *
 * Szesc sekund: silnik zwiazywany na zimno (pierwsze wiazanie uslugi po starcie telefonu) melduje
 * gotowosc w okolicach 2 s, a na obciazonym urzadzeniu widziano 4 s. Krocej zrywaloby sesje,
 * ktore jeszcze wstaja; dluzej to wieczne "SŁUCHAM…" bez ani jednego zdarzenia.
 */
const val VOICE_WATCHDOG_MS = 6_000L

/**
 * Ile czekamy na odpowiedz checkRecognitionSupport, zanim uznamy model za brakujacy.
 *
 * Usluga, ktora odpowiada, robi to w 40-120 ms (emulator); poltorej sekundy to zapas na zimne
 * wiazanie. Brak odpowiedzi liczy sie jako "brak modelu": lepiej zaczac od silnika domyslnego
 * niz od tego, ktory wlasnie pokazal, ze potrafi milczec.
 */
const val VOICE_MODEL_MS = 1_500L

/**
 * Ile czekamy po gotowosci silnika na PIERWSZY znak, ze dzwiek wchodzi.
 *
 * Bylo 2500 ms i to byla za dluga chwila: uzytkownik motoroli edge 50 neo przerwal probe po 2,1 s,
 * czyli 0,6 s PRZED jedynym straznikiem, ktory mial te awarie zlapac. Straznik, ktorego nikt nie
 * doczeka, nie istnieje. 1200 ms wystarcza, bo czekamy na PROBKE POZIOMU, a nie na slowo: sprawny
 * silnik raportuje poziom kilkadziesiat razy na sekunde, zanim gracz zdazy otworzyc usta.
 */
const val VOICE_DZWIEK_MS = 1_200L

/**
 * Twardy limit na okno miedzy start() a faktycznym startListening.
 *
 * Gdy start() pyta najpierw o model on-device, na odpowiedz idzie [VOICE_MODEL_MS], a dopiero
 * potem rusza wiazanie silnika — wiec okno musi pomiescic OBIE rzeczy. Suma, a nie wieksza
 * z dwoch: to sa etapy jeden po drugim, nie warianty tego samego etapu.
 *
 * @param sprawdzamModel czy przed startem leci pytanie o model on-device (API 33+, tryb ON_DEVICE)
 */
fun limitOknaStartu(
    sprawdzamModel: Boolean,
    modelMs: Long = VOICE_MODEL_MS,
    watchdogMs: Long = VOICE_WATCHDOG_MS,
): Long = if (sprawdzamModel) modelMs + watchdogMs else watchdogMs

/**
 * Czy straznik uzbrojony w pokoleniu [pokStraznika] ma jeszcze prawo glosu.
 *
 * Ta sama zasada co w sluchaczu silnika — i z tego samego powodu. Straznicy (watchdog, straznik
 * cyklu, straznik dzwieku) byli do 0.9.16 bezpokoleniowi, a start() na zywej sesji podbija
 * pokolenie i NIE czysci kolejki handlera. Straznik zamowiony przez poprzednia sesje budzil sie
 * wiec w nowej i degradowal silnik, ktory nie mial jeszcze kiedy zawiesc: z ekranu wygladalo to
 * jak "pasek sam schodzi na inny silnik zaraz po wlaczeniu".
 */
fun straznikAktualny(pokStraznika: Int, pokolenie: Int, chceNasluchu: Boolean): Boolean =
    chceNasluchu && pokStraznika == pokolenie

/**
 * Nastepny szczebel listy KONKRETNYCH uslug rozpoznawania, z pominieciem pakietow, ktore juz
 * w tej sesji zawiodly.
 *
 * Bez czarnej listy drabinka potrafila wrocic do tej samej uslugi inna droga: bywa ona
 * zarejestrowana pod kilkoma komponentami tego samego pakietu, a [VoiceInput.refresh] przestawia
 * kolejnosc listy. Pakiet, ktory raz zamilkl albo odmowil, nie ma dostawac drugiej szansy w tym
 * samym przebiegu — kazda taka proba to kolejne sekundy z zapalonym paskiem i bez dzwieku.
 *
 * @param pakiety nazwy pakietow uslug, w kolejnosci drabinki
 * @param biezacy indeks uslugi, na ktorej stoimy (-1 = silnik domyslny, przed lista)
 * @return indeks nastepnej uslugi do sprobowania albo -1, gdy nie ma juz zadnej
 */
fun nastepnyIndeksSilnika(pakiety: List<String>, biezacy: Int, czarnaLista: Set<String>): Int {
    var i = maxOf(biezacy, -1) + 1
    while (i < pakiety.size) {
        if (pakiety[i] !in czarnaLista) return i
        i += 1
    }
    return -1
}

/**
 * Indeks uslugi z DANEGO pakietu, jesli jeszcze jej nie skreslilismy; -1 gdy nie ma czego probowac.
 *
 * Potrzebny dla jawnego szczebla [PAKIET_MOWY_GOOGLE]: trybu on-device nie da sie przeadresowac
 * (system podstawia wlasna usluge — patrz komentarz przy drabince w [VoiceInput]), wiec jedyna
 * droga do silnika Google prowadzi przez wiazanie po nazwie komponentu.
 */
fun indeksPakietu(pakiety: List<String>, pakiet: String, czarnaLista: Set<String>): Int =
    if (pakiet in czarnaLista) -1 else pakiety.indexOf(pakiet)

/**
 * Usluga rozpoznawania od Google przeznaczona dla aplikacji trzecich ("Speech Services by Google").
 *
 * Na telefonie ze zgloszenia (motorola edge 50 neo, Android 16) NIE MA aplikacji Google
 * (com.google.android.googlequicksearchbox), a mimo to sa trzy uslugi rozpoznawania — i to jest
 * jedyna z nich, ktora jest komponentem Google i da sie z nia zwiazac po nazwie.
 */
const val PAKIET_MOWY_GOOGLE = "com.google.android.tts"

// ---- MIKROFON: TRZY NIEZALEZNE BLOKADY ------------------------------------------------

/** AppOpsManager.MODE_IGNORED — operacja cicho odrzucana; dla OP_RECORD_AUDIO to przelacznik prywatnosci. */
const val APPOPS_MODE_IGNORED = 1

/**
 * Czy odpowiedz AppOps na OP_RECORD_AUDIO znaczy "mikrofon sciety przelacznikiem prywatnosci".
 *
 * Przelacznik prywatnosci mikrofonu (Android 12+) jest w AOSP zrobiony jako GLOBALNE ograniczenie
 * AppOps na OP_RECORD_AUDIO, wiec publiczne unsafeCheckOpNoThrow zwraca wtedy MODE_IGNORED.
 * MODE_ERRORED (2) to co innego — twarda odmowa uprawnienia — i tego z przelacznikiem nie mylimy,
 * bo od zgody jest osobne pytanie ([StanMikrofonu.zgoda]).
 *
 * @param tryb wynik AppOpsManager.unsafeCheckOpNoThrow; null = API < 29 (metoda jest od 29)
 *        albo wyjatek — wtedy odpowiedz brzmi "nie wiadomo", a nie "nie"
 */
fun przelacznikPrywatnosciZAppOps(tryb: Int?): Boolean? = when (tryb) {
    null -> null
    APPOPS_MODE_IGNORED -> true
    else -> false
}

/**
 * Trzy niezalezne powody, dla ktorych do silnika moze nie wchodzic dzwiek.
 *
 * Do 0.9.16 pytalismy o jeden: AudioManager.isMicrophoneMute. On z definicji pokazuje wylacznie
 * wyciszenie PROGRAMOWE (setMicrophoneMute), a nie systemowy przelacznik prywatnosci — czyli
 * akurat nie to, co najczesciej gasi mikrofon na Androidzie 12+. Diagnostyka odpowiadala wiec
 * "wyciszony: nie" na telefonie z odcietym mikrofonem, i to bylo jedyne, co mowila.
 *
 * @param przelacznikPrywatnosci null = nie da sie zapytac (API < 29); "nie wiadomo" jest osobna
 *        odpowiedzia od "nie" i tak ma byc pokazane
 */
data class StanMikrofonu(
    val wyciszonyProgramowo: Boolean,
    val przelacznikPrywatnosci: Boolean?,
    val zgoda: Boolean,
) {
    /** Mikrofon jest GLUCHY, choc aplikacja ma prawo nagrywac — to podpowiadamy uczniowi. */
    val gluchy: Boolean get() = wyciszonyProgramowo || przelacznikPrywatnosci == true

    /** Cokolwiek z tych trzech stoi dzwiekowi na drodze (brak zgody to sprawa ekranu, nie paska). */
    val blokada: Boolean get() = gluchy || !zgoda
}

/** Jedna linia diagnostyki: wszystkie trzy odpowiedzi, kazda pod wlasna nazwa. */
fun opisMikrofonu(stan: StanMikrofonu): String = buildString {
    append("wyciszony: ").append(if (stan.wyciszonyProgramowo) "TAK" else "nie")
    append(" · przełącznik prywatności: ")
    append(
        when (stan.przelacznikPrywatnosci) {
            true -> "BLOKUJE"
            false -> "nie"
            null -> "nie wiadomo"
        },
    )
    append(" · zgoda: ").append(if (stan.zgoda) "tak" else "NIE")
}

// ---- KTO WYLACZYL NASLUCH -------------------------------------------------------------

/**
 * Powod wylaczenia nasluchu. Enum, a nie napis, bo ten sam powod trafia do dziennika diagnostyki
 * i do logcata — a wolajacy ma go WYBRAC z listy, nie wymyslic.
 *
 * Do 0.9.16 dziennik zapisywal samo "stop" i z raportu telefonu nie dalo sie odroznic pauzy,
 * zejscia w tlo, dotkniecia paska i konca drabinki — czyli czterech zupelnie roznych historii,
 * z ktorych tylko jedna jest awaria.
 */
enum class PowodStop {
    /** Pauza rundy albo koniec gry (ekran). */
    PAUZA,
    /** Aplikacja zeszla w tlo: HOME, przelaczenie aplikacji, zgaszony ekran. */
    TLO,
    /** Uczen sam dotknal paska. */
    DOTKNIECIE,
    /** Drabinka silnikow doszla do konca — [VoiceInput] oddaje decyzje ekranowi. */
    DRABINKA,
    /** Silnik zglosil brak zgody na mikrofon i zgody faktycznie nie ma. */
    ZGODA,
    /** Seria bledow nagrywania — mikrofon zajety przez inna aplikacje. */
    MIKROFON,
    /** Ekran znika (release). */
    EKRAN,
    /** Wywolanie bez podanego powodu — zostaje samo "stop". */
    NIEZNANY,
}

/** Wpis do dziennika dla [PowodStop]. */
fun opisStopu(powod: PowodStop): String = when (powod) {
    PowodStop.PAUZA -> "stop (pauza)"
    PowodStop.TLO -> "stop (tło)"
    PowodStop.DOTKNIECIE -> "stop (dotknięcie)"
    PowodStop.DRABINKA -> "stop (drabinka)"
    PowodStop.ZGODA -> "stop (brak zgody)"
    PowodStop.MIKROFON -> "stop (mikrofon zajęty)"
    PowodStop.EKRAN -> "stop (koniec ekranu)"
    PowodStop.NIEZNANY -> "stop"
}
