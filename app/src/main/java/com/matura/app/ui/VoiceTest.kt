package com.matura.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
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

/**
 * TEST MOWY — po co ten ekran istnieje.
 *
 * Zgloszenie "pasek sie wlacza, nic sie nie dzieje, zadnego bledu" nie niesie ZADNEJ informacji:
 * tak samo wyglada silnik bez modelu polskiego, silnik odmawiajacy pracy aplikacjom trzecim,
 * mikrofon wyciszony przelacznikiem prywatnosci i zajety mikrofon. Bez urzadzenia w reku nie da
 * sie tego rozstrzygnac, a drabinka w [VoiceInput] chodzi w tle gry i widac tylko jej skutek.
 *
 * Ten ekran robi jedna rzecz: przepuszcza telefon przez CALA matryce konfiguracji rozpoznawania
 * po kolei i pokazuje, ktora zadzialala. Wynik da sie skopiowac do schowka i wkleic — zamiast
 * zdania "nadal nie dziala" wraca lista prob z kodami bledow.
 *
 * Nic stad nie wychodzi na zewnatrz: raport zostaje w schowku uzytkownika, aplikacja nie ma
 * uprawnienia do sieci. Nie zapisujemy tresci wypowiedzi poza JEDNYM rozpoznanym slowem, ktore
 * uzytkownik sam wypowiada na polecenie ekranu.
 */

// ---- czysta logika (bez Androida, testowana zwyklym JUnitem) ----------------

/** Skad bierzemy rozpoznawacz w danej probie. */
enum class VoiceProbaTyp {
    /** SpeechRecognizer.createOnDeviceSpeechRecognizer — tylko API 31+. */
    ON_DEVICE,
    /** SpeechRecognizer.createSpeechRecognizer(context) — silnik domyslny systemu. */
    DOMYSLNY,
    /** Konkretna usluga RecognitionService, wiazana po ComponentName. */
    USLUGA,
}

/**
 * Jedna proba z matrycy. [pakiet]/[klasa] tylko dla [VoiceProbaTyp.USLUGA].
 * [nazwa] jest techniczna i celowo taka sama w obu jezykach interfejsu — trafia do raportu,
 * ktory czyta programista, a nie uczen.
 */
data class VoiceKonfig(
    val nazwa: String,
    val typ: VoiceProbaTyp,
    val offline: Boolean,
    val podpowiedzi: Boolean,
    val pakiet: String? = null,
    val klasa: String? = null,
    /**
     * Podpowiedzi w liczbie, ktora wysyla GRA ([MAX_BIASING_WORDS]), a nie w liczbie sladowej.
     * Bez tej proby matryca nie odpowiada na pytanie, przez ktore powstala: czy dluga lista
     * podpowiedzi dlawi silnik.
     */
    val duzyBias: Boolean = false,
)

/** Co proba zostawila po sobie. Nic wiecej sie nie zapisuje. */
data class VoiceWynik(
    val nazwa: String,
    /** Czy przyszlo onReadyForSpeech — silnik w ogole otworzyl mikrofon. */
    val gotowy: Boolean,
    /** Najglosniejsza probka RMS; NEGATIVE_INFINITY = silnik nie raportowal poziomu. */
    val maxRms: Float,
    /** Czy byly wyniki czesciowe (onPartialResults z niepusta lista). */
    val czesciowe: Boolean,
    /** PIERWSZY rozpoznany tekst albo null. */
    val tekst: String?,
    /** Kod bledu albo null. */
    val blad: Int?,
    val czasMs: Long,
)

/** Etykiety wyniku — ekran podstawia wersje PL/EN z [Str], testy swoje wlasne. */
data class VoiceTestTeksty(
    val cisza: String,
    val blad: String,
    val brakSilnika: String,
    val gotowy: String,
    val brakGotowosci: String,
    val czesciowe: String,
    val dziala: String,
    val zadna: String,
)

/** Slowo, ktore uzytkownik ma wypowiedziec. Krotkie, polskie, bez zbitki trudnej dla silnika. */
const val VOICE_TEST_SLOWO = "KOLANO"

/** Podpowiedzi slownikowe do proby (a): silnik ma wazyc te slowa, a nie caly jezyk. */
val VOICE_TEST_SLOWNIK = listOf("kolano", "głowa", "plecy", "ramię", "stopa", "łokieć")

/**
 * Lista podpowiedzi tej samej dlugosci, ktora wysyla gra ([MAX_BIASING_WORDS]).
 *
 * Gra wysyla do silnika slownik CALEGO zestawu, a jedna z hipotez tego zgloszenia brzmi: dluga
 * lista podpowiedzi dlawi silnik i ten przestaje zwracac wyniki bez zadnego bledu. Proba z
 * szescioma slowami nie umie tego ani potwierdzic, ani obalic — dopiero roznica miedzy ta lista
 * a brakiem podpowiedzi jest odpowiedzia. Slowo z [VOICE_TEST_SLOWO] jest tu PIERWSZE: gdyby go
 * zabraklo, proba przegralaby z zupelnie innego powodu.
 */
val VOICE_TEST_SLOWNIK_DLUGI: List<String> = listOf(
    "kolano", "głowa", "plecy", "ramię", "stopa", "łokieć", "ręka", "noga", "palec", "brzuch",
    "szyja", "ucho", "oko", "nos", "usta", "ząb", "włosy", "serce", "płuco", "żołądek",
    "kostka", "biodro", "pierś", "kciuk", "paznokieć", "broda", "policzek", "czoło", "język",
    "gardło", "bark", "nadgarstek", "dłoń", "pięta", "udo", "łydka", "kręgosłup", "żebro",
    "skóra", "mózg", "nerka", "wątroba", "krew", "kość", "mięsień", "staw", "powieka", "brew",
    "rzęsa", "warga",
).take(MAX_BIASING_WORDS)

/** Jezyk testu. Hipoteza brzmi "brak modelu polskiego", wiec testujemy dokladnie polski. */
const val VOICE_TEST_JEZYK = "pl-PL"

/** Od API 31 istnieje createOnDeviceSpeechRecognizer / isOnDeviceRecognitionAvailable. */
const val SDK_ON_DEVICE = 31
/** Od API 33 dziala EXTRA_BIASING_STRINGS — nizej proba "z podpowiedziami" nie roznilaby sie. */
const val SDK_BIASING = 33

/** Rozpoznawacza nie udalo sie w ogole utworzyc (null albo wyjatek z fabryki). */
const val KOD_BRAK_SILNIKA = -1
/** startListening rzucilo wyjatkiem — silnik nie przyjal nawet zlecenia. */
const val KOD_WYJATEK = -2

/**
 * Matryca prob dla TEGO urzadzenia. Kolejnosc jest celowa: od najwezszego przypadku
 * (on-device, offline, ze slownikiem) do najszerszego (dowolna usluga, siec dozwolona,
 * bez slownika). Pierwsza proba, ktora cokolwiek rozpozna, jest odpowiedzia na pytanie
 * "co ustawic na tym telefonie".
 *
 * @param uslugi pary (pakiet, klasa) z queryIntentServices — kolejnosc dowolna, tu porzadkujemy
 */
fun budujKonfiguracje(
    sdk: Int,
    onDevice: Boolean,
    uslugaDomyslna: Boolean,
    uslugi: List<Pair<String, String>>,
): List<VoiceKonfig> = buildList {
    if (sdk >= SDK_ON_DEVICE && onDevice) {
        // (a) Podpowiedzi slownikowe maja sens tylko tam, gdzie system je czyta (API 33+);
        // nizej byloby to dokladnie to samo zlecenie co (b) i tylko marnowaloby 8 sekund.
        if (sdk >= SDK_BIASING) {
            add(VoiceKonfig("on-device / offline / bias", VoiceProbaTyp.ON_DEVICE, offline = true, podpowiedzi = true))
            // Ta sama droga, ale ze slownikiem DOKLADNIE tej dlugosci, ktora wysyla gra.
            // Bez tej proby hipoteza "dluga lista podpowiedzi dlawi silnik" byla sprawdzana
            // wylacznie na sciezce sieciowej — a gra zaczyna wlasnie TUTAJ (VoiceInput startuje
            // z mode = ON_DEVICE i preferOffline = true). Stoi tuz przed proba bez podpowiedzi,
            // zeby w raporcie obie lezaly jedna pod druga i dalo sie je porownac.
            add(
                VoiceKonfig(
                    nazwa = "on-device / offline / bias" + MAX_BIASING_WORDS,
                    typ = VoiceProbaTyp.ON_DEVICE,
                    offline = true,
                    podpowiedzi = true,
                    duzyBias = true,
                )
            )
        }
        add(VoiceKonfig("on-device / offline", VoiceProbaTyp.ON_DEVICE, offline = true, podpowiedzi = false))
    }
    if (uslugaDomyslna) {
        add(VoiceKonfig("default / offline", VoiceProbaTyp.DOMYSLNY, offline = true, podpowiedzi = false))
        // Tak startuje GRA na telefonie bez rozpoznawania on-device: silnik domyslny, offline
        // wymuszony i pelny slownik zestawu. Bez tej proby raport moglby uznac sciezke gry za
        // sprawna, chociaz to wlasnie ona zawodzi — a po to jest ten ekran, zeby tego nie robil.
        if (sdk >= SDK_BIASING) {
            add(
                VoiceKonfig(
                    nazwa = "default / offline / bias" + MAX_BIASING_WORDS,
                    typ = VoiceProbaTyp.DOMYSLNY,
                    offline = true,
                    podpowiedzi = true,
                    duzyBias = true,
                )
            )
        }
        add(VoiceKonfig("default / net-ok", VoiceProbaTyp.DOMYSLNY, offline = false, podpowiedzi = false))
        // Ta sama droga, ale ze slownikiem DOKLADNIE tej dlugosci, ktora wysyla gra. Stoi zaraz
        // za proba bez podpowiedzi, zeby obie dalo sie w raporcie porownac jedna pod druga:
        // roznica miedzy nimi jest odpowiedzia na pytanie "czy to podpowiedzi dlawia silnik".
        if (sdk >= SDK_BIASING) {
            add(
                VoiceKonfig(
                    nazwa = "default / net-ok / bias" + MAX_BIASING_WORDS,
                    typ = VoiceProbaTyp.DOMYSLNY,
                    offline = false,
                    podpowiedzi = true,
                    duzyBias = true,
                )
            )
        }
    }
    // Google najpierw — tak samo jak drabinka w VoiceInput.dostepneSilniki(). sortedBy jest
    // stabilne, wiec reszta zachowuje kolejnosc z systemu.
    uslugi.distinct()
        .sortedBy { if (it.first.contains("google", true)) 0 else 1 }
        .forEach { (pakiet, klasa) ->
            add(
                VoiceKonfig(
                    nazwa = "service / " + pakiet,
                    typ = VoiceProbaTyp.USLUGA,
                    offline = false,
                    podpowiedzi = false,
                    pakiet = pakiet,
                    klasa = klasa,
                )
            )
        }
}

/**
 * Kody SpeechRecognizer sa golymi liczbami; w raporcie ma byc widac, co znaczy "7".
 * Numery sa czescia publicznego API (android.speech.SpeechRecognizer.ERROR_*), wiec moga
 * tu stac wprost — dzieki temu ta funkcja jest czysta i testowalna bez Androida.
 */
fun nazwaBledu(kod: Int): String = when (kod) {
    1 -> "1 NETWORK_TIMEOUT"
    2 -> "2 NETWORK"
    3 -> "3 AUDIO"
    4 -> "4 SERVER"
    5 -> "5 CLIENT"
    6 -> "6 SPEECH_TIMEOUT"
    7 -> "7 NO_MATCH"
    8 -> "8 RECOGNIZER_BUSY"
    9 -> "9 INSUFFICIENT_PERMISSIONS"
    10 -> "10 TOO_MANY_REQUESTS"
    11 -> "11 SERVER_DISCONNECTED"
    12 -> "12 LANGUAGE_NOT_SUPPORTED"
    13 -> "13 LANGUAGE_UNAVAILABLE"
    14 -> "14 CANNOT_CHECK_SUPPORT"
    15 -> "15 CANNOT_LISTEN_TO_DOWNLOAD_EVENTS"
    else -> kod.toString()
}

/**
 * Ktora proba zadzialala. "Zadzialala" = silnik zwrocil JAKIKOLWIEK tekst; czy trafil w slowo,
 * widac w raporcie obok. Chodzi o odpowiedz "czy ta droga w ogole zamienia mowe na tekst",
 * a nie o celnosc — ta jest sprawa dopasowania w grze.
 */
fun ktoraZadzialala(wyniki: List<VoiceWynik>): VoiceWynik? =
    wyniki.firstOrNull { !it.tekst.isNullOrBlank() }

/**
 * Jedno zdanie na koniec: ktora konfiguracja dziala, ze zadna — albo ze przebieg nie doszedl
 * do konca i nie ma z czego orzekac.
 *
 * [pelny] jest tu najwazniejszym parametrem. Przebieg trwa okolo minuty, przez ktora uczen ma
 * tylko mowic, wiec przerwanie NIE jest przypadkiem brzegowym: wystarczy zgaszenie ekranu,
 * przycisk PRZERWIJ albo przelaczenie sie do innej aplikacji. Do 0.9.11 raport z trzech prob
 * konczyl sie tym samym zdaniem co raport z osmiu ("na tym telefonie rozpoznawanie mowy nie
 * dziala"), a przy przerwaniu przed pierwszym zapisem padalo ono po ZERU prob. To nie jest brak
 * odpowiedzi, tylko pewna siebie zla odpowiedz — gorsza od zgloszenia "nadal nie dziala",
 * bo wysyla uzytkownika do pakietow jezyka albo do przelacznika prywatnosci na podstawie niczego.
 *
 * @param pelny czy przebieg objal CALA matryce (patrz [VoiceProbe.skonczone])
 * @param przerwany zdanie na przebieg niepelny — z licznikiem "N z M prob"
 */
fun werdyktMowy(
    wyniki: List<VoiceWynik>,
    dziala: String,
    zadna: String,
    pelny: Boolean = true,
    przerwany: String = "",
): String {
    val w = ktoraZadzialala(wyniki)
    // Dzialajaca konfiguracja jest odpowiedzia takze w przebiegu przerwanym: to fakt zmierzony,
    // a nie wniosek z braku dowodow. Odwrotnie niz zdanie "zadna konfiguracja nie zadzialala".
    if (w != null) return dziala + w.nazwa
    return if (pelny) zadna else przerwany
}

/**
 * Co przebieg zmierzyl o mikrofonie. Stany sa TRZY, nie dwa — i to jest sedno.
 *
 * To jest drugie pytanie tego ekranu i bez niego pierwsze jest nierozstrzygalne: silnik bez
 * modelu polskiego i mikrofon wyciszony przelacznikiem prywatnosci daja DOKLADNIE ten sam obraz
 * ("zadna konfiguracja nic nie rozpoznala"), a naprawa jest zupelnie inna. Ale wywolanie
 * onRmsChanged NIE jest obowiazkowe: czesc uslug wiazanych po ComponentName go nie implementuje
 * i wtedy kazda proba ma maxRms = -inf. Przy dwoch stanach taki przebieg trafial do worka
 * "mikrofon milczal" i raport z pelnym przekonaniem wysylal uzytkownika do przelacznika
 * prywatnosci — czyli tam, gdzie nic nie ma do naprawienia. Raport, ktory ma zastapic
 * zgadywanie, nie moze twierdzic rzeczy, ktorych nie zmierzyl.
 */
enum class SygnalMikrofonu {
    /** Jakas proba zarejestrowala dzwiek ponad dnem skali — mikrofon na pewno slyszy. */
    SLYSZAL,
    /** Poziom byl mierzony i przez caly czas lezal na dnie skali — mikrofon milczal. */
    DNO,
    /** Zaden silnik nie podal poziomu — o mikrofonie nie wynika z tego przebiegu NIC. */
    NIEZMIERZONY,
}

/**
 * Ktory z trzech stanow opisuje ten przebieg. Prog jest ten sam co w [SilenceDetector] —
 * silnik Google raportuje ok. -2,12 dB dla strumienia samych zer.
 */
fun sygnalMikrofonu(
    wyniki: List<VoiceWynik>,
    prog: Float = SilenceDetector.SILENCE_DB,
): SygnalMikrofonu = when {
    wyniki.any { it.maxRms > prog } -> SygnalMikrofonu.SLYSZAL
    wyniki.any { it.maxRms != Float.NEGATIVE_INFINITY } -> SygnalMikrofonu.DNO
    else -> SygnalMikrofonu.NIEZMIERZONY
}

/** Czy w KTOREJKOLWIEK probie mikrofon zarejestrowal dzwiek ponad dnem skali. */
fun mikrofonSlyszal(wyniki: List<VoiceWynik>, prog: Float = SilenceDetector.SILENCE_DB): Boolean =
    sygnalMikrofonu(wyniki, prog) == SygnalMikrofonu.SLYSZAL

/** Dopisek do werdyktu, gdy nic nie zadzialalo: gdzie szukac przyczyny — albo ze nie wiadomo. */
fun dopisekMikrofon(
    wyniki: List<VoiceWynik>,
    slyszal: String,
    cichy: String,
    nieznany: String,
): String = when (sygnalMikrofonu(wyniki)) {
    SygnalMikrofonu.SLYSZAL -> slyszal
    SygnalMikrofonu.DNO -> cichy
    SygnalMikrofonu.NIEZMIERZONY -> nieznany
}

/**
 * Ile znakow rozpoznanego tekstu zostaje w opisie proby.
 *
 * Raport jest wklejany do rozmowy, a tekst w nim to WSZYSTKO, co silnik uslyszal — przy blednym
 * rozpoznaniu moze to byc dowolna wypowiedz z otoczenia, a nie zamowione KOLANO. Do rozstrzygniecia
 * pytania "czy ta droga zamienia mowe na tekst" wystarczy kilkanascie znakow, wiec reszty nie
 * przenosimy. Limit z zapasem na slowo testowe i krotka pomylke silnika.
 */
const val MAX_TEKST_RAPORTU = 24

/** Rozpoznany tekst do raportu: tyle, ile trzeba do oceny proby, i ani znaku wiecej. */
fun przytnijTekst(tekst: String, limit: Int = MAX_TEKST_RAPORTU): String =
    if (tekst.length <= limit) tekst else tekst.take(limit).trimEnd() + "…"

/** Jedna linia opisu proby — ta sama w liscie na ekranie i w raporcie do wklejenia. */
fun opisWyniku(w: VoiceWynik, t: VoiceTestTeksty): String {
    val czesci = mutableListOf<String>()
    czesci += when {
        !w.tekst.isNullOrBlank() -> "„" + przytnijTekst(w.tekst) + "” · " + t.dziala
        w.blad == KOD_BRAK_SILNIKA -> t.brakSilnika
        w.blad == KOD_WYJATEK -> t.brakSilnika + " (start)"
        w.blad != null && w.blad != 0 -> t.blad + " " + nazwaBledu(w.blad)
        else -> t.cisza
    }
    czesci += "%.1f s".format(w.czasMs / 1000.0)
    czesci += if (w.gotowy) t.gotowy else t.brakGotowosci
    czesci += "RMS max " + (if (w.maxRms == Float.NEGATIVE_INFINITY) "—" else "%.1f".format(w.maxRms))
    if (w.czesciowe) czesci += t.czesciowe
    return czesci.joinToString(" · ")
}

/**
 * Caly raport do schowka: naglowek o urzadzeniu, licznik prob, ponumerowane proby, werdykt.
 *
 * Linia "proby: N/M" i slowo PRZERWANY sa tu po to, zeby WKLEJONY raport bronil sie sam: czyta
 * go ktos, kto nie widzial ekranu i nie wie, czy przebieg doszedl do konca. Bez tej linii raport
 * z trzech prob wyglada dokladnie tak samo jak raport z osmiu.
 */
fun raportMowy(
    naglowek: String,
    wyniki: List<VoiceWynik>,
    werdykt: String,
    t: VoiceTestTeksty,
    ile: Int = wyniki.size,
    przerwany: Boolean = false,
): String = buildString {
    append(naglowek)
    append("próby: ").append(wyniki.size).append('/').append(ile)
    if (przerwany) append(" · PRZERWANY")
    append('\n')
    wyniki.forEachIndexed { i, w ->
        append('\n').append(i + 1).append(". ").append(w.nazwa).append(" -> ").append(opisWyniku(w, t))
    }
    append('\n').append('\n').append(werdykt)
}

// ---- warstwa Androida ------------------------------------------------------

/**
 * Uruchamia matryce PO KOLEI, kazda probe najwyzej [PROBA_MS]. Caly ruch idzie glownym watkiem
 * (wymog SpeechRecognizer), kazdy rozpoznawacz jest niszczony zaraz po swojej probie.
 *
 * Spoznione zdarzenia poprzedniej proby (silniki potrafia odbic bledem juz po destroy) odcina
 * [token]: sluchacz zna swoj numer i milczy, gdy proba juz sie skonczyla.
 */
class VoiceProbe(private val context: Context) {

    /** Zakonczone proby — ekran pokazuje je na zywo. */
    var wyniki by mutableStateOf<List<VoiceWynik>>(emptyList())
        private set
    /** Nazwa proby, ktora wlasnie leci (null = zadna). */
    var teraz by mutableStateOf<String?>(null)
        private set
    /** Ile prob ma cala matryca — do postepu "3/7". */
    var ile by mutableStateOf(0)
        private set
    var trwa by mutableStateOf(false)
        private set
    /**
     * Przebieg objal CALA matryce (albo matryca byla pusta — telefon bez silnikow to tez
     * pelna odpowiedz). TYLKO taki przebieg wolno podsumowac zdaniem o tym telefonie.
     */
    var skonczone by mutableStateOf(false)
        private set
    /**
     * Przebieg stanal przed koncem matrycy: PRZERWIJ, wygaszenie ekranu, przejscie do innej
     * aplikacji. Wyniki, ktore zdazyly powstac, zostaja (sa twarda informacja i musza dac sie
     * skopiowac) — ale werdykt i raport musza o tym przerwaniu powiedziec wprost.
     */
    var przerwane by mutableStateOf(false)
        private set
    /**
     * Naglowek raportu policzony RAZ, w chwili startu. Opisuje stan telefonu PRZED probami, wiec
     * chwila jest wlasciwa — a przy okazji znika cztery przejscia przez binder (queryIntentServices,
     * resolveActivity, isRecognitionAvailable, isOnDeviceRecognitionAvailable) przy KAZDEJ
     * rekompozycji ekranu, ktore robilo wolanie naglowek() wprost w kompozycji.
     */
    var naglowekPrzebiegu by mutableStateOf("")
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var matryca: List<VoiceKonfig> = emptyList()
    private var idx = -1
    private var rec: SpeechRecognizer? = null
    private var token = 0

    // Stan BIEZACEJ proby.
    private var start = 0L
    private var ponowienia = 0
    private var gotowy = false
    private var maxRms = Float.NEGATIVE_INFINITY
    private var czesciowe = false
    private var tekst: String? = null
    private var blad: Int? = null

    /** Zaczyna caly przebieg od nowa. Wolane z glownego watku (dotkniecie przycisku). */
    fun start() {
        zwolnij()
        naglowekPrzebiegu = naglowek()
        matryca = budujKonfiguracje(Build.VERSION.SDK_INT, onDevice(), uslugaDomyslna(), uslugi())
        ile = matryca.size
        wyniki = emptyList()
        idx = -1
        skonczone = false
        przerwane = false
        if (matryca.isEmpty()) {
            // Telefon bez jakiegokolwiek silnika mowy — to tez jest odpowiedz, i to jednoznaczna.
            skonczone = true
            logWerdykt()
            return
        }
        trwa = true
        nastepna()
    }

    /** Przerywa przebieg i oddaje mikrofon. Bezpieczne do wolania wielokrotnie. */
    fun zwolnij() {
        // Przebieg, ktory nie doszedl do konca matrycy, jest PRZERWANY — i tak ma sie nazywac.
        // Do 0.9.11 stalo tu "skonczone = true", wiec przerwanie na trzeciej z osmiu prob
        // dawalo raport nieodrozninalny od pelnego, konczacy sie zdaniem "na tym telefonie
        // rozpoznawanie mowy nie dziala". Warunek odsiewa zwolnij() przed pierwszym startem
        // (naglowek powstaje w start) i kolejne wywolania po tym samym przerwaniu — zwolnij()
        // leci i z DisposableEffect, i z obserwatora ON_STOP, i z wejscia w start().
        val wToku = naglowekPrzebiegu.isNotEmpty() && !skonczone && !przerwane
        token += 1
        handler.removeCallbacksAndMessages(null)
        zwolnijRec()
        trwa = false
        teraz = null
        if (wToku) {
            przerwane = true
            logWerdykt()
        }
    }

    private fun zwolnijRec() {
        val r = rec
        rec = null
        try { r?.cancel() } catch (_: Throwable) {}
        try { r?.destroy() } catch (_: Throwable) {}
    }

    private fun nastepna() {
        idx += 1
        if (idx >= matryca.size) {
            trwa = false
            teraz = null
            skonczone = true
            logWerdykt()
            return
        }
        val k = matryca[idx]
        teraz = k.nazwa
        token += 1
        val moj = token
        start = SystemClock.elapsedRealtime()
        ponowienia = 0
        gotowy = false
        maxRms = Float.NEGATIVE_INFINITY
        czesciowe = false
        tekst = null
        blad = null

        val r = utworz(k)
        if (r == null) { zapisz(k, KOD_BRAK_SILNIKA); return }
        rec = r
        try {
            r.setRecognitionListener(sluchacz(moj, k))
            r.startListening(intencja(k))
        } catch (_: Throwable) {
            zapisz(k, KOD_WYJATEK)
            return
        }
        // Twardy limit proby: silnik, ktory milczy, ma tyle samo czasu co ten, ktory gada.
        handler.postDelayed({ if (moj == token) zapisz(k, blad) }, PROBA_MS)
    }

    /** Koniec proby: zapis wyniku, zwolnienie rozpoznawacza, przerwa i nastepna. */
    private fun zapisz(k: VoiceKonfig, kod: Int?) {
        token += 1
        handler.removeCallbacksAndMessages(null)
        wyniki = wyniki + VoiceWynik(
            nazwa = k.nazwa,
            gotowy = gotowy,
            maxRms = maxRms,
            czesciowe = czesciowe,
            tekst = tekst,
            blad = kod,
            czasMs = SystemClock.elapsedRealtime() - start,
        )
        logProba(wyniki.last())
        zwolnijRec()
        // Przerwa daje uslugom czas na rozwiazanie poprzedniego wiazania; bez niej nastepna
        // proba dostawalaby ERROR_RECOGNIZER_BUSY nie ze swojej winy.
        handler.postDelayed({ nastepna() }, PRZERWA_MS)
    }

    /**
     * Jedna linia na probe do `adb logcat -s MaturaVoice` — ten sam tag co sciezka glosowa gry.
     *
     * Schowek nie jest pewna droga: na czesci telefonow czysci go menedzer pamieci, zanim uczen
     * zdazy wkleic, a zaznaczenie dlugiego tekstu palcem tez potrafi sie nie udac. Wtedy z
     * jedynego narzedzia zbudowanego po to, by zdobyc twarde dane, nie zostaje nic. TRESCI
     * wypowiedzi w logu nie ma i byc nie moze — idzie sama jej dlugosc.
     */
    private fun logProba(w: VoiceWynik) {
        Log.i(
            VOICE_TAG,
            "test proba " + w.nazwa +
                " -> " + (if (w.blad != null) "blad " + nazwaBledu(w.blad) else "bez bledu") +
                " · znakow=" + (w.tekst?.length ?: 0) +
                " · gotowy=" + (if (w.gotowy) "tak" else "nie") +
                " · czesciowe=" + (if (w.czesciowe) "tak" else "nie") +
                " · rms=" + (if (w.maxRms == Float.NEGATIVE_INFINITY) "—" else "%.1f".format(w.maxRms)) +
                " · " + w.czasMs + " ms",
        )
    }

    /** Jedna linia na koniec przebiegu: ile prob, czy pelny, co zadzialalo, co z mikrofonem. */
    private fun logWerdykt() {
        Log.i(
            VOICE_TAG,
            "test koniec: proby " + wyniki.size + "/" + ile +
                (if (skonczone) " (pelny)" else " PRZERWANY") +
                " · dziala: " + (ktoraZadzialala(wyniki)?.nazwa ?: "zadna") +
                " · mikrofon: " + sygnalMikrofonu(wyniki),
        )
    }

    /**
     * Cisza w srodku proby (NO_MATCH / SPEECH_TIMEOUT / pusty wynik) nie konczy proby, dopoki
     * zostal czas: uzytkownik mogl jeszcze nie zaczac mowic, a chodzi o uczciwa ocene silnika.
     */
    private fun pustyCykl(k: VoiceKonfig, kod: Int?, moj: Int) {
        val zostalo = PROBA_MS - (SystemClock.elapsedRealtime() - start)
        if (zostalo < PONOWIENIE_MIN_MS || ponowienia >= MAX_PONOWIEN) { zapisz(k, kod); return }
        ponowienia += 1
        handler.postDelayed({
            if (moj != token) return@postDelayed
            val r = rec ?: return@postDelayed
            try {
                r.cancel()
                r.startListening(intencja(k))
            } catch (_: Throwable) {
                zapisz(k, kod)
            }
        }, PONOWIENIE_MS)
    }

    private fun sluchacz(moj: Int, k: VoiceKonfig) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { if (moj == token) gotowy = true }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) { if (moj == token && rmsdB > maxRms) maxRms = rmsdB }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            if (moj != token) return
            val s = pierwszy(partialResults) ?: return
            czesciowe = true
            if (tekst == null) tekst = s
        }

        override fun onResults(results: Bundle?) {
            if (moj != token) return
            val s = pierwszy(results)
            if (s != null && tekst == null) tekst = s
            // Pusty wynik koncowy to to samo co NO_MATCH — czesc silnikow zglasza go wlasnie tak.
            if (tekst == null) pustyCykl(k, blad, moj) else zapisz(k, blad)
        }

        override fun onError(error: Int) {
            if (moj != token) return
            blad = error
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> pustyCykl(k, error, moj)
                else -> zapisz(k, error)
            }
        }
    }

    private fun pierwszy(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull { it.isNotBlank() }

    private fun utworz(k: VoiceKonfig): SpeechRecognizer? = try {
        when (k.typ) {
            VoiceProbaTyp.ON_DEVICE ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } else null
            VoiceProbaTyp.DOMYSLNY -> SpeechRecognizer.createSpeechRecognizer(context)
            VoiceProbaTyp.USLUGA ->
                if (k.pakiet != null && k.klasa != null) {
                    SpeechRecognizer.createSpeechRecognizer(context, ComponentName(k.pakiet, k.klasa))
                } else null
        }
    } catch (_: Throwable) { null }

    private fun intencja(k: VoiceKonfig): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, VOICE_TEST_JEZYK)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            if (k.offline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            if (k.podpowiedzi && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                putStringArrayListExtra(
                    RecognizerIntent.EXTRA_BIASING_STRINGS,
                    ArrayList(if (k.duzyBias) VOICE_TEST_SLOWNIK_DLUGI else VOICE_TEST_SLOWNIK),
                )
            }
        }

    // ---- sonda urzadzenia (te same pytania co VoiceInput.diagnostyka) -------

    private fun onDevice(): Boolean = try {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    } catch (_: Throwable) { false }

    private fun uslugaDomyslna(): Boolean = try {
        SpeechRecognizer.isRecognitionAvailable(context)
    } catch (_: Throwable) { false }

    private fun uslugi(): List<Pair<String, String>> = try {
        context.packageManager
            .queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .mapNotNull { it.serviceInfo?.let { s -> s.packageName to s.name } }
    } catch (_: Throwable) { emptyList() }

    private fun okno(): Boolean = try {
        context.packageManager
            .resolveActivity(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0) != null
    } catch (_: Throwable) { false }

    private fun maZgode(): Boolean = try {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }

    private fun wersja(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (_: Throwable) { "?" }

    /** Naglowek raportu: co to za telefon i co system o sobie mowi PRZED probami. */
    fun naglowek(): String {
        val u = uslugi()
        return buildString {
            append("MATURA ").append(wersja()).append(" — test mowy\n")
            append("Android ").append(Build.VERSION.SDK_INT)
            append(" · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("on-device: ").append(tak(onDevice()))
            append(" · usługa: ").append(tak(uslugaDomyslna()))
            append(" · okno: ").append(tak(okno()))
            append(" · zgoda: ").append(tak(maZgode())).append('\n')
            append("słowo: ").append(VOICE_TEST_SLOWO).append(" · język: ").append(VOICE_TEST_JEZYK).append('\n')
            append("usługi (").append(u.size).append("): ")
            append(if (u.isEmpty()) "brak" else u.joinToString(", ") { it.first })
            append('\n')
        }
    }

    private fun tak(b: Boolean) = if (b) "tak" else "NIE"

    private companion object {
        /** Twardy limit jednej proby. */
        const val PROBA_MS = 8_000L
        /** Przerwa miedzy probami — usluga musi zdazyc sie odwiazac. */
        const val PRZERWA_MS = 350L
        /** Zwloka przed ponowieniem w obrebie tej samej proby. */
        const val PONOWIENIE_MS = 150L
        /** Ponizej tego czasu nie ma juz sensu zaczynac nowego cyklu. */
        const val PONOWIENIE_MIN_MS = 1_200L
        /** Straznik przed karuzela ERROR_RECOGNIZER_BUSY. */
        const val MAX_PONOWIEN = 12
    }
}

/** Etykiety wynikow w jezyku interfejsu — jedno miejsce, uzywane przez ekran i raport. */
fun voiceTestTeksty(str: Str) = VoiceTestTeksty(
    cisza = str.voiceTestSilence,
    blad = str.voiceTestError,
    brakSilnika = str.voiceTestNoEngine,
    gotowy = str.voiceTestReady,
    brakGotowosci = str.voiceTestNoReady,
    czesciowe = str.voiceTestPartial,
    dziala = str.voiceTestWorks,
    zadna = str.voiceTestNone,
)

// ---- ekran -----------------------------------------------------------------

/**
 * Ekran testu. Wzor jak [PrivacyScreen]: ScreenScaffold + LazyColumn z jednym elementem —
 * lista prob jest krotka (najwyzej kilkanascie wierszy), wiec nie ma po co jej wirtualizowac,
 * a jeden element trzyma cala tresc razem przy przewijaniu.
 */
/** Sonda na czas ekranu. Osobna funkcja, zeby test mogl podac wlasna i przepuscic przebieg. */
@Composable
fun rememberVoiceProbe(context: Context = LocalContext.current): VoiceProbe =
    remember(context) { VoiceProbe(context) }

@Composable
fun VoiceTestScreen(
    str: Str,
    onBack: () -> Unit,
    // Sonda jako parametr WYLACZNIE po to, zeby test bez urzadzenia mogl przepuscic przebieg
    // (telefon bez silnika = pusta matryca) i sprawdzic, ze naglowek i przycisk kopiowania
    // naprawde sa na ekranie. Aplikacja podaje domyslna.
    probe: VoiceProbe = rememberVoiceProbe(),
) {
    val context = LocalContext.current
    // Zejscie aplikacji w tlo przerywa przebieg. Sam DisposableEffect lapie tylko wyjscie
    // z kompozycji: gdy uczen w polowie minutowego przebiegu przelaczy sie do innej aplikacji
    // albo zgasi ekran, sonda dalej trzymalaby zwiazana usluge i dalej odliczala 8-sekundowe
    // proby — z mikrofonem, do ktorego Android aplikacji w tle i tak nie da dostepu. Wyszlaby
    // seria pustych prob z rzedu, czyli raport falszywie negatywny, i nikt by tego z raportu
    // nie odczytal. Przerwany przebieg zachowuje dotychczasowe wyniki (patrz VoiceProbe.zwolnij).
    val wlasciciel = LocalLifecycleOwner.current
    DisposableEffect(wlasciciel, probe) {
        val obserwator = LifecycleEventObserver { _, zdarzenie ->
            if (zdarzenie == Lifecycle.Event.ON_STOP) probe.zwolnij()
        }
        wlasciciel.lifecycle.addObserver(obserwator)
        // Wyjscie z ekranu ma oddac mikrofon NATYCHMIAST — inaczej zwiazana usluga zostalaby
        // z otwartym nasluchem, a gra po powrocie dostawalaby ERROR_RECOGNIZER_BUSY.
        onDispose {
            wlasciciel.lifecycle.removeObserver(obserwator)
            probe.zwolnij()
        }
    }

    // Przebieg trwa okolo minuty, przez ktora uczen ma TYLKO mowic — mowa nie resetuje licznika
    // wygaszania ekranu, wiec bez tego telefon gasnie w polowie testu, ON_STOP przerywa przebieg
    // i z ekranu wychodzi raport niepelny. Trzymamy ekran zapalony wylacznie na czas przebiegu.
    val widok = LocalView.current
    DisposableEffect(widok, probe.trwa) {
        widok.keepScreenOn = probe.trwa
        onDispose { widok.keepScreenOn = false }
    }

    val teksty = remember(str) { voiceTestTeksty(str) }
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
            // Zgode bierzemy sami: ekran mozna otworzyc z OPCJI, nigdy nie dotykajac gry.
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
    // Wnioski o TYM TELEFONIE wolno wyciagac tylko z przebiegu, ktory objal cala matryce.
    val pelny = probe.skonczone
    // Gdy nic nie zadzialalo, samo "zadna konfiguracja" jeszcze nie mowi, gdzie szukac.
    // Doklejamy wiec to, czego nie widac inaczej: czy mikrofon w ogole cos slyszal. Dopisek
    // idzie WYLACZNIE do przebiegu pelnego: oba zdania ("brakuje modelu polskiego", "sprawdz
    // przelacznik prywatnosci") sa wnioskiem z tego, ze zawiodly WSZYSTKIE konfiguracje.
    val werdykt = werdyktMowy(
        wyniki,
        str.voiceTestWorksWith,
        teksty.zadna,
        pelny = pelny,
        przerwany = str.voiceTestInterrupted(wyniki.size, probe.ile),
    ) + if (pelny && wyniki.isNotEmpty() && ktoraZadzialala(wyniki) == null) {
        " " + dopisekMikrofon(
            wyniki,
            str.voiceTestMicHeard,
            str.voiceTestMicSilent,
            str.voiceTestMicUnknown,
        )
    } else ""
    // Przerwany test tez ma wynik: to, co zdazylo sie zdarzyc, jest juz twarda informacja
    // i musi dac sie skopiowac — inaczej dlugi przebieg trzeba by przeklikiwac od nowa.
    val gotowe = !probe.trwa && (probe.skonczone || probe.przerwane || wyniki.isNotEmpty())
    val raport = raportMowy(
        probe.naglowekPrzebiegu, wyniki, werdykt, teksty,
        ile = probe.ile,
        przerwany = probe.przerwane,
    )

    fun kopiuj() {
        val tresc = raport
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("MATURA", tresc))
            skopiowane = true
        } catch (_: Throwable) {
            skopiowane = false
        }
    }

    ScreenScaffold(str.voiceTest, onBack, str.back) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Text(
                    str.voiceTestIntro,
                    color = Palette.Bone,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(str.voiceTestRepeat, color = Palette.Muted, fontSize = 12.sp, lineHeight = 17.sp)
                Spacer(Modifier.height(14.dp))

                PrimaryButton(
                    label = when {
                        probe.trwa -> str.voiceTestStop
                        // Przerwany przebieg tez jest przebiegiem: przycisk ma zapraszac do
                        // powtorki, a nie udawac, ze nic sie nie wydarzylo.
                        wyniki.isNotEmpty() || probe.skonczone || probe.przerwane -> str.voiceTestAgain
                        else -> str.voiceTestStart
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (probe.trwa) probe.zwolnij() else uruchom()
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
                }

                if (wyniki.isNotEmpty()) Spacer(Modifier.height(16.dp))
                wyniki.forEach { w -> WynikWiersz(w, teksty) }

                if (gotowe) {
                    Spacer(Modifier.height(14.dp))
                    InfoCard(if (probe.ile == 0) str.voiceTestNothing else werdykt)
                    // Raport i przycisk kopiowania sa TU, a nie pod warunkiem "sa jakies proby".
                    // Telefon bez jakiejkolwiek uslugi rozpoznawania (matryca pusta) i przebieg
                    // przerwany przed pierwszym zapisem to dokladnie te przypadki, w ktorych caly
                    // dowod siedzi w NAGLOWKU: Android, model telefonu, on-device/usluga/okno/zgoda
                    // i lista uslug. Do 0.9.11 wlasnie wtedy nie bylo czego skopiowac i uzytkownik
                    // wracal z jednym zdaniem — czyli z tym samym, co przed calym ekranem.
                    Spacer(Modifier.height(12.dp))
                    GhostButton(str.voiceTestCopy) { kopiuj() }
                    if (skopiowane) {
                        Spacer(Modifier.height(8.dp))
                        Text(str.voiceTestCopied, color = Palette.Accent, fontSize = 12.sp)
                    }
                    if (wyniki.any { !it.tekst.isNullOrBlank() }) {
                        Spacer(Modifier.height(8.dp))
                        // Raport niesie to, co silnik USLYSZAL — przy blednym rozpoznaniu moze to
                        // byc zdanie z otoczenia, a nie zamowione slowo. Uzytkownik ma o tym
                        // wiedziec, zanim wklei raport w rozmowie.
                        Text(str.voiceTestTextNote, color = Palette.Muted, fontSize = 11.sp, lineHeight = 15.sp)
                    }
                    Spacer(Modifier.height(14.dp))
                    // Ten sam tekst co w schowku, do zaznaczenia recznie — schowek na czesci
                    // urzadzen bywa czyszczony przez menedzera pamieci, zanim uczen zdazy wkleic.
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

@Composable
private fun WynikWiersz(w: VoiceWynik, t: VoiceTestTeksty) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Text(w.nazwa, color = Palette.Bone, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(3.dp))
        Text(
            opisWyniku(w, t),
            color = if (!w.tekst.isNullOrBlank()) Palette.Accent else Palette.Muted,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
    }
}
