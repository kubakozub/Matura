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
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.matura.app.BuildConfig

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
    /**
     * LANGUAGE_MODEL_WEB_SEARCH zamiast FREE_FORM. Google (issue 280288200) zaleca ten model dla
     * KROTKICH wypowiedzi: silnik sieciowy gubil jednosylabowe slowa (NO_MATCH) przy FREE_FORM.
     * Gra prosi o pojedyncze slowa, wiec ta proba mowi, czy na tym telefonie warto przestawic model.
     */
    val webSearch: Boolean = false,
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
    /** Proba zamknieta w locie przez przerwanie przebiegu ([KOD_PRZERWANE]). */
    val przerwana: String,
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

/**
 * Plik SharedPreferences z OSTATNIM raportem (patrz [VoiceProbe.ostatniRaport]).
 *
 * Wlasny plik, a nie zapis gry: to dane diagnostyczne o telefonie, ktore maja przezyc takze
 * skasowanie postepu, i nie maja po co dotykac modelu danych gry. Nazwa jest publiczna, zeby
 * test mogl zaczac od czystego stanu.
 */
const val VOICE_TEST_PAMIEC = "voice_test_raport"

/**
 * Znaczniki przyciskow dla testu UI.
 *
 * Etykiety przyciskow rysuje czcionka pikselowa na Canvasie ([PixelText]), wiec w drzewie
 * semantyki NIE MA ich tekstu i test nie ma jak ich po napisie znalezc. Bez znacznikow jedyna
 * droga jest liczenie klikalnych wezlow — a wtedy test "drugie dotkniecie nie zabija przebiegu"
 * sprawdzalby przycisk POWROTU zamiast STARTU.
 */
const val TAG_TEST_START = "voiceTestStart"
/** @see TAG_TEST_START */
const val TAG_TEST_STOP = "voiceTestStop"
/** @see TAG_TEST_START */
const val TAG_TEST_KOPIUJ_OSTATNI = "voiceTestCopyLast"

/** Od API 31 istnieje createOnDeviceSpeechRecognizer / isOnDeviceRecognitionAvailable. */
const val SDK_ON_DEVICE = 31
/** Od API 33 dziala EXTRA_BIASING_STRINGS — nizej proba "z podpowiedziami" nie roznilaby sie. */
const val SDK_BIASING = 33

/** Rozpoznawacza nie udalo sie w ogole utworzyc (null albo wyjatek z fabryki). */
const val KOD_BRAK_SILNIKA = -1

/**
 * Proba zamknieta w locie: [VoiceProbe.zwolnij] albo [VoiceProbe.wstrzymaj] zabraly jej mikrofon,
 * zanim silnik cokolwiek powiedzial.
 *
 * Wartosc jest trzecia z wlasnych, UJEMNYCH kodow tego ekranu (po [KOD_BRAK_SILNIKA] = -1 i
 * [KOD_WYJATEK] = -2); SpeechRecognizer.ERROR_* sa dodatnie (1..15), wiec kolizji nie ma.
 *
 * Do 0.9.16 przerwana proba byla po prostu WYRZUCANA (token uniewazlnial sluchacza, wynik nie
 * powstawal) i raport z Motoroli wracal jako "proby: 0/10 · PRZERWANY" — czyli bez ani jednego
 * pomiaru. A wlasnie ta proba jest najcenniejszym, co ten ekran z takiego telefonu zdejmuje:
 * gotowosc po 0,2 s, zero probek RMS i cisza przez ~2 s to jest ODPOWIEDZ, tylko trzeba ja
 * zapisac.
 */
const val KOD_PRZERWANE = -3

/** Ile tagow z listy trafia do raportu — lista jezykow online ma ponad sto pozycji. */
const val MAX_TAGOW_RAPORTU = 12

/**
 * Linia raportu o MODELU on-device dla jezyka testu (odpowiedz checkRecognitionSupport, API 33+).
 *
 * To jest linia, ktora rozstrzyga zgloszenie z Motoroli: "BRAK (do pobrania)" przy pl-PL na
 * telefonie z usluga on-device znaczy, ze milczenie prob on-device to brak pakietu, a nie
 * mikrofon. Listy sa przyciete — raport ma byc do wklejenia, nie do przewijania.
 */
fun opisWsparcia(
    tag: String,
    zainstalowane: List<String>,
    obslugiwane: List<String>,
    wToku: List<String>,
    online: List<String>,
): String {
    val stan = when {
        modelZainstalowany(tag, zainstalowane) -> "JEST"
        modelZainstalowany(tag, wToku) -> "POBIERANY"
        modelZainstalowany(tag, obslugiwane) -> "BRAK (do pobrania)"
        else -> "NIEOBSŁUGIWANY"
    }
    // LICZBA stoi przed kazda lista, bo listy sa przyciete do [MAX_TAGOW_RAPORTU]: po samym
    // "—" nie da sie odroznic pustej odpowiedzi uslugi od listy, ktora sie nie zmiescila, a
    // "zainstalowane(0)" kontra "zainstalowane(1)" to na tym telefonie cala roznica miedzy
    // "brak pakietu polskiego" a "pakiet jest, wiec milczenie ma inna przyczyne".
    fun lista(x: List<String>): String = "(" + x.size + ")" + when {
        x.isEmpty() -> ": —"
        x.size <= MAX_TAGOW_RAPORTU -> ": " + x.joinToString(",")
        else -> ": " + x.take(MAX_TAGOW_RAPORTU).joinToString(",") + ",…"
    }
    return "model on-device " + tag + ": " + stan +
        " · zainstalowane" + lista(zainstalowane) +
        " · obsługiwane" + lista(obslugiwane) +
        " · w toku" + lista(wToku) +
        " · online(" + online.size + ")"
}

/** Przebieg zlecenia pobrania modelu on-device — ekran tlumaczy na [Str]. */
sealed class PobieranieModelu {
    object Brak : PobieranieModelu()
    object Zlecone : PobieranieModelu()
    data class WToku(val procent: Int) : PobieranieModelu()
    object Gotowe : PobieranieModelu()
    data class Blad(val opis: String) : PobieranieModelu()
}
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
        // Ta sama droga, inny model jezyka: porownanie sasiednich wierszy mowi, czy krotkie
        // slowa przechodza lepiej przez WEB_SEARCH (patrz VoiceKonfig.webSearch).
        add(
            VoiceKonfig(
                nazwa = "default / net-ok / web-search",
                typ = VoiceProbaTyp.DOMYSLNY,
                offline = false,
                podpowiedzi = false,
                webSearch = true,
            )
        )
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
    wyniki.filter { !it.tekst.isNullOrBlank() }.maxByOrNull { ocenaProby(it) }

/**
 * Ile warta jest proba, ktora COS oddala. Do 0.9.17 wybor byl "pierwsza z brzegu" i na telefonie
 * ze zgloszenia dal odpowiedz FALSZYWA: raport zakonczyl sie zdaniem "Dziala konfiguracja:
 * on-device / offline / bias", chociaz ta proba oddala urwane "ko" i zamilkla, a cztery wiersze
 * nizej lezaly konfiguracje, ktore oddaly pelne "kolano" — na silniku, ktory na tym telefonie
 * naprawde dziala. Werdykt wysylal wiec prosto w jedyna zepsuta usluge.
 *
 * Wagi (od najwazniejszej):
 *  - trafienie w ZAMOWIONE slowo — jedyny twardy dowod, ze droga rozpoznaje to, o co prosimy;
 *  - zmierzony poziom dzwieku — silnik, ktory nie oddal ani jednej probki, jest podejrzany
 *    nawet wtedy, gdy cos napisal (tak wlasnie wygladala proba 1 z telefonu);
 *  - brak kodu bledu;
 *  - dlugosc tekstu jako rozstrzygniecie remisu ("kolano" bije "ko").
 */
fun ocenaProby(w: VoiceWynik, zamowione: String = VOICE_TEST_SLOWO): Int {
    val tekst = w.tekst.orEmpty().trim()
    if (tekst.isBlank()) return 0
    var punkty = 1
    if (tekst.equals(zamowione, ignoreCase = true)) punkty += 8
    else if (tekst.contains(zamowione, ignoreCase = true)) punkty += 6
    if (w.maxRms != Float.NEGATIVE_INFINITY) punkty += 4
    if (w.blad == null || w.blad == 0) punkty += 2
    return punkty * 100 + minOf(tekst.length, 99)
}

/**
 * Kto zabral przebiegowi mikrofon.
 *
 * W oknie pierwszej, 8-sekundowej proby [VoiceProbe.zwolnij] ma TRZECH mozliwych wolajacych:
 * przycisk PRZERWIJ, obserwatora ON_STOP (zgaszony ekran, przelaczenie aplikacji) i onDispose
 * ekranu (wyjscie z ekranu). Raport "proby: 0/10 · PRZERWANY" nie odroznial ich od siebie, wiec
 * szukanie przyczyny zaczynalo sie od zgadywania — czyli od tego, co ten ekran mial usunac.
 *
 * [etykieta] idzie do raportu, ktory czyta czlowiek, wiec jest po polsku i z diakrytykami —
 * tak samo jak reszta linii raportu, obok ktorej stoi.
 */
enum class PowodPrzerwania(val etykieta: String) {
    /** Uczen dotknal PRZERWIJ. */
    PRZYCISK("przycisk"),
    /** Aplikacja zeszla w tlo (ON_STOP): zgaszony ekran, powiadomienie, inna aplikacja. */
    TLO("tło"),
    /** Ekran testu zostal opuszczony (onDispose) — przebieg konczy sie razem z nim. */
    WYJSCIE("wyjście"),
    /** Start nowego przebiegu zamknal poprzedni. */
    NOWY_PRZEBIEG("nowy przebieg"),
}

/**
 * Czym, kiedy i na czym przebieg stanal.
 *
 * [czasMs] liczy sie od STARTU PRZEBIEGU, a nie od poczatku proby: w zgloszeniu z Motoroli
 * rozstrzygajace bylo to, ze przerwanie przyszlo w pierwszych sekundach (czyli mniej wiecej
 * wtedy, kiedy konczy sie pytanie o model on-device), a nie po minucie mowienia.
 */
data class Przerwanie(
    val powod: PowodPrzerwania,
    val czasMs: Long,
    /** Numer proby w toku, liczony od 1; null = przerwanie przed pierwsza proba. */
    val numerProby: Int? = null,
    val nazwaProby: String? = null,
)

/**
 * Nawias przy slowie PRZERWANY: kto przerwal, w ktorej sekundzie przebiegu i ktora probe to
 * kosztowalo. Czas ma ten sam format co czas proby ("%.1f s"), zeby obie liczby w raporcie
 * dalo sie porownac bez przeliczania.
 */
fun opisPrzerwania(p: Przerwanie): String = buildString {
    append(p.powod.etykieta)
    append(" · ").append("%.1f s".format(p.czasMs / 1000.0))
    if (p.numerProby != null) {
        append(" · w próbie ").append(p.numerProby)
        if (!p.nazwaProby.isNullOrBlank()) append(" \"").append(p.nazwaProby).append('"')
    }
}

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
        // Przerwana proba NIE jest bledem silnika i nie wolno jej tak opisac — reszta linii
        // (czas, gotowosc, RMS max) zostaje, bo to wlasnie ona niesie pomiar.
        w.blad == KOD_PRZERWANE -> t.przerwana
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
 *
 * @param przerwanie powod, chwila i proba przerwania — patrz [Przerwanie]. Raport z Motoroli
 *   ("proby: 0/10 · PRZERWANY") utknal dokladnie na pytaniu, kto to przerwanie wywolal.
 */
fun raportMowy(
    naglowek: String,
    wyniki: List<VoiceWynik>,
    werdykt: String,
    t: VoiceTestTeksty,
    ile: Int = wyniki.size,
    przerwany: Boolean = false,
    przerwanie: Przerwanie? = null,
): String = buildString {
    append(naglowek)
    append("próby: ").append(wyniki.size).append('/').append(ile)
    if (przerwany) {
        append(" · PRZERWANY")
        // Samo slowo PRZERWANY zatrzymywalo kazda rozmowe o takim raporcie na pytaniu "kto
        // przerwal i kiedy" — a odpowiedz jest po stronie aplikacji i nie kosztuje nic.
        if (przerwanie != null) append(" (").append(opisPrzerwania(przerwanie)).append(')')
    }
    append('\n')
    wyniki.forEachIndexed { i, w ->
        append('\n').append(i + 1).append(". ").append(w.nazwa).append(" -> ").append(opisWyniku(w, t))
    }
    append('\n').append('\n').append(werdykt)
}

/**
 * Czy z TEGO przebiegu wolno orzekac o telefonie.
 *
 * Nie wystarczy "doszedl do konca matrycy": od 0.9.16 przebieg przezywa krotkie zejscie w tlo
 * ([VoiceProbe.wstrzymaj]), wiec do konca moze dojsc przebieg, w ktorym jedna albo dwie proby
 * zostaly zamkniete w locie ([KOD_PRZERWANE]). Taka proba nic nie zmierzyla, a zdanie "zadna
 * konfiguracja nie zadzialala" jest wnioskiem z braku dowodow — wiec nie wolno go wydac, dopoki
 * w przebiegu jest choc jedna dziura.
 */
fun przebiegRozstrzygajacy(skonczone: Boolean, wyniki: List<VoiceWynik>): Boolean =
    skonczone && wyniki.none { it.blad == KOD_PRZERWANE }

/**
 * Jeden przebieg w calosci — to, z czego sklada sie raport.
 *
 * Ta sama struktura opisuje przebieg BIEZACY i przebieg odtworzony z dysku
 * ([VoiceProbe.ostatniRaport]), dzieki czemu raport sklada sie w JEDNYM miejscu
 * ([raportPrzebiegu]) i nie ma jak sie rozjechac miedzy tymi dwiema drogami.
 */
data class OstatniRaport(
    val naglowek: String,
    val wyniki: List<VoiceWynik>,
    val ile: Int,
    val przerwanie: Przerwanie?,
    val skonczone: Boolean,
)

/**
 * Separator pol w zapisie na dysk. Znak sterujacy US (0x1F) nie moze wyjsc z rozpoznawania mowy
 * ani z nazwy konfiguracji, wiec nie da sie nim rozbic zapisu.
 */
private const val SEP_POLA = ""

/** Separator wynikow (RS, 0x1E) — z tego samego powodu co [SEP_POLA]. */
private const val SEP_WYNIKOW = ""

/** Zapis nie moze zawierac wlasnych separatorow ani konca linii — ucinamy je u zrodla. */
private fun bezSeparatorow(s: String): String =
    s.replace(SEP_POLA, " ").replace(SEP_WYNIKOW, " ").replace('\n', ' ').replace('\r', ' ')

/**
 * Wyniki w jednej linii tekstu, do SharedPreferences.
 *
 * Wlasny format, a nie kotlinx.serialization, z jednego twardego powodu: [VoiceWynik.maxRms] to
 * Float, ktory w normalnym przebiegu wynosi NEGATIVE_INFINITY ("silnik nie podal poziomu"), a
 * JSON nie ma zapisu dla wartosci nieskonczonych — Json bez allowSpecialFloatingPointValues
 * rzuca wyjatkiem dokladnie na tym przypadku, czyli na przebiegu z Motoroli. Drugi powod:
 * zapis raportu diagnostycznego nie ma po co dotykac modelu danych gry (SaveData).
 */
fun zapiszWyniki(wyniki: List<VoiceWynik>): String = wyniki.joinToString(SEP_WYNIKOW) { w ->
    listOf(
        bezSeparatorow(w.nazwa),
        if (w.gotowy) "1" else "0",
        w.maxRms.toString(),
        if (w.czesciowe) "1" else "0",
        bezSeparatorow(w.tekst ?: ""),
        w.blad?.toString() ?: "",
        w.czasMs.toString(),
    ).joinToString(SEP_POLA)
}

/**
 * Odwrotnosc [zapiszWyniki]. Wiersz, ktorego nie da sie odczytac, jest POMIJANY, a nie zamieniany
 * w wyjatek: raport z poprzedniego przebiegu jest dodatkiem i nie ma prawa wywrocic wejscia na
 * ekran — a to wlasnie ekran, na ktory uczen wchodzi, gdy nic innego nie dziala.
 */
fun czytajWyniki(zapis: String): List<VoiceWynik> {
    if (zapis.isEmpty()) return emptyList()
    return zapis.split(SEP_WYNIKOW).mapNotNull { linia ->
        val p = linia.split(SEP_POLA)
        if (p.size != 7) return@mapNotNull null
        VoiceWynik(
            nazwa = p[0],
            gotowy = p[1] == "1",
            maxRms = p[2].toFloatOrNull() ?: Float.NEGATIVE_INFINITY,
            czesciowe = p[3] == "1",
            tekst = p[4].ifEmpty { null },
            blad = p[5].toIntOrNull(),
            czasMs = p[6].toLongOrNull() ?: 0L,
        )
    }
}

/** Przerwanie w jednej linii tekstu; pusty napis = przebieg nie zostal przerwany. */
fun zapiszPrzerwanie(p: Przerwanie?): String = if (p == null) "" else listOf(
    p.powod.name,
    p.czasMs.toString(),
    p.numerProby?.toString() ?: "",
    bezSeparatorow(p.nazwaProby ?: ""),
).joinToString(SEP_POLA)

/**
 * Odwrotnosc [zapiszPrzerwanie]. Nieznana nazwa powodu (zapis ze starszej wersji aplikacji)
 * daje null, a nie wyjatek — patrz [czytajWyniki].
 */
fun czytajPrzerwanie(zapis: String): Przerwanie? {
    val p = zapis.split(SEP_POLA)
    if (p.size != 4) return null
    val powod = PowodPrzerwania.values().firstOrNull { it.name == p[0] } ?: return null
    return Przerwanie(
        powod = powod,
        czasMs = p[1].toLongOrNull() ?: 0L,
        numerProby = p[2].toIntOrNull(),
        nazwaProby = p[3].ifEmpty { null },
    )
}

/**
 * Werdykt jednego przebiegu — ten sam na ekranie i w raporcie do wklejenia.
 *
 * Dopisek o mikrofonie idzie WYLACZNIE do przebiegu rozstrzygajacego: oba zdania ("brakuje
 * modelu polskiego", "sprawdz przelacznik prywatnosci") sa wnioskiem z tego, ze zawiodly
 * WSZYSTKIE konfiguracje, a nie z tego, ze zabraklo czasu.
 */
fun werdyktPrzebiegu(r: OstatniRaport, str: Str, t: VoiceTestTeksty): String {
    val pelny = przebiegRozstrzygajacy(r.skonczone, r.wyniki)
    return werdyktMowy(
        r.wyniki,
        str.voiceTestWorksWith,
        t.zadna,
        pelny = pelny,
        przerwany = str.voiceTestInterrupted(r.wyniki.size, r.ile),
    ) + if (pelny && r.wyniki.isNotEmpty() && ktoraZadzialala(r.wyniki) == null) {
        " " + dopisekMikrofon(
            r.wyniki,
            str.voiceTestMicHeard,
            str.voiceTestMicSilent,
            str.voiceTestMicUnknown,
        )
    } else ""
}

/** Caly raport jednego przebiegu: biezacego albo odtworzonego z dysku. */
fun raportPrzebiegu(r: OstatniRaport, str: Str, t: VoiceTestTeksty): String = raportMowy(
    naglowek = r.naglowek,
    wyniki = r.wyniki,
    werdykt = werdyktPrzebiegu(r, str, t),
    t = t,
    ile = r.ile,
    przerwany = !przebiegRozstrzygajacy(r.skonczone, r.wyniki),
    przerwanie = r.przerwanie,
)

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
     * Co i kiedy przerwalo przebieg — null, dopoki nic go nie przerwalo.
     *
     * Bez tego pola raport konczyl sie slowem "PRZERWANY" i cala dalsza rozmowa szla o to, kto
     * przerwal: uczen przyciskiem, system zejsciem w tlo czy wyjscie z ekranu. Tych trzech
     * wolajacych nie da sie rozroznic po skutku, a po powodzie i chwili — natychmiast.
     */
    var przerwanie by mutableStateOf<Przerwanie?>(null)
        private set
    /**
     * Przebieg czeka na powrot z tla ([wstrzymaj]): matryca, idx, wyniki i naglowek zostaja
     * nietkniete, znika tylko zwiazany rozpoznawacz.
     */
    var wstrzymany by mutableStateOf(false)
        private set
    /**
     * Ile razy przebieg schodzil w tlo. Idzie do naglowka raportu, bo zmienia jego wymowe:
     * przebieg poszatkowany trzema powrotami z tla mierzy takze to, jak system oddaje mikrofon
     * po powrocie, a nie sam silnik mowy.
     */
    var zejsciaWTlo by mutableStateOf(0)
        private set
    /**
     * Naglowek raportu policzony RAZ, w chwili startu. Opisuje stan telefonu PRZED probami, wiec
     * chwila jest wlasciwa — a przy okazji znika cztery przejscia przez binder (queryIntentServices,
     * resolveActivity, isRecognitionAvailable, isOnDeviceRecognitionAvailable) przy KAZDEJ
     * rekompozycji ekranu, ktore robilo wolanie naglowek() wprost w kompozycji.
     */
    var naglowekPrzebiegu by mutableStateOf("")
        private set
    /**
     * Model on-device dla jezyka testu jest OBSLUGIWANY, ale NIE pobrany (checkRecognitionSupport,
     * API 33+) — ekran pokazuje wtedy przycisk pobrania. Odpowiedz na zgloszenie z Motoroli:
     * usluga on-device jest, pakietu polskiego nie ma, wiec on-device milczy.
     */
    var modelDoPobrania by mutableStateOf(false)
        private set
    /** Stan zlecenia pobrania modelu ([pobierzModel]). */
    var pobieranie by mutableStateOf<PobieranieModelu>(PobieranieModelu.Brak)
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var matryca: List<VoiceKonfig> = emptyList()
    private var idx = -1
    private var rec: SpeechRecognizer? = null
    private var token = 0
    /** Chwila startu CALEGO przebiegu — od niej liczy sie [Przerwanie.czasMs]. */
    private var startPrzebiegu = 0L

    // Stan BIEZACEJ proby.
    private var start = 0L
    /**
     * Czy proba spod [idx] ma juz swoj wynik.
     *
     * Miedzy probami stoi [PRZERWA_MS] i przez te chwile [trwa] jest wciaz prawda, a [idx] wciaz
     * pokazuje probe WLASNIE ZAPISANA. Bez tej flagi przerwanie trafiajace dokladnie w te przerwe
     * dopisywaloby te sama probe drugi raz — raz z jej wlasnym kodem, raz jako przerwana.
     */
    private var probaZapisana = true
    private var ponowienia = 0
    private var gotowy = false
    private var maxRms = Float.NEGATIVE_INFINITY
    private var czesciowe = false
    private var tekst: String? = null
    private var blad: Int? = null

    /** Zaczyna caly przebieg od nowa. Wolane z glownego watku (dotkniecie przycisku). */
    fun start() {
        // Poprzedni przebieg konczy sie tu i teraz — z wlasnym powodem, zeby w logu i w raporcie
        // zapisanym na dysku bylo widac, ze zabral go START, a nie system.
        zwolnij(PowodPrzerwania.NOWY_PRZEBIEG)
        naglowekPrzebiegu = naglowek()
        startPrzebiegu = SystemClock.elapsedRealtime()
        matryca = budujKonfiguracje(Build.VERSION.SDK_INT, onDevice(), uslugaDomyslna(), uslugi())
        ile = matryca.size
        wyniki = emptyList()
        idx = -1
        skonczone = false
        przerwane = false
        przerwanie = null
        wstrzymany = false
        zejsciaWTlo = 0
        if (matryca.isEmpty()) {
            // Telefon bez jakiegokolwiek silnika mowy — to tez jest odpowiedz, i to jednoznaczna.
            skonczone = true
            logWerdykt()
            // Caly dowod takiego przebiegu siedzi w naglowku, wiec tym bardziej ma przezyc
            // zamkniecie aplikacji.
            zapiszRaport()
            return
        }
        trwa = true
        modelDoPobrania = false
        pobieranie = PobieranieModelu.Brak
        // Pytanie o model PRZED matryca (API 33+, tylko gdy on-device w ogole jest): odpowiedz
        // trafia do naglowka raportu i rozstrzyga, czy milczenie prob on-device to brak pakietu.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && onDevice()) sprawdzWsparcie { nastepna() }
        else nastepna()
    }

    /** Dopisek do naglowka raportu — takze do logcata, bo schowek nie jest pewna droga. */
    private fun dopiszDoNaglowka(linia: String) {
        naglowekPrzebiegu = naglowekPrzebiegu + linia + '\n'
        Log.i(VOICE_TAG, "test " + linia)
        // Naglowek niesie odpowiedz o modelu on-device — czyli najwazniejsza linie calego
        // raportu. Musi trafic na dysk od razu: przebieg trwa okolo minuty i konczy sie czesto
        // tym, ze system ubija aplikacje w tle.
        zapiszRaport()
    }

    private fun konfigModelu() = VoiceKonfig("model", VoiceProbaTyp.ON_DEVICE, offline = false, podpowiedzi = false)

    /**
     * Pyta usluge on-device o model dla [VOICE_TEST_JEZYK]; odpowiedz (albo jej brak po
     * [WSPARCIE_MS]) idzie do naglowka, a potem rusza matryca. Przerwanie (zwolnij) uniewaznia
     * to pytanie tak samo jak kazda probe — przez [token].
     */
    private fun sprawdzWsparcie(dalej: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            dalej()
            return
        }
        token += 1
        val moj = token
        teraz = "model on-device"
        val r = utworz(konfigModelu())
        if (r == null) {
            dopiszDoNaglowka("model on-device: nie udało się utworzyć rozpoznawacza")
            dalej()
            return
        }
        rec = r
        var gotowe = false
        // Linia jest tu opcjonalna, bo odpowiedz uslugi (onSupportResult/onError) trafia do
        // naglowka SAMA, jeszcze przed tym wywolaniem — patrz komentarz w onSupportResult.
        fun zakoncz(linia: String?) {
            if (gotowe || moj != token) return
            gotowe = true
            handler.removeCallbacksAndMessages(null)
            zwolnijRec()
            if (linia != null) dopiszDoNaglowka(linia)
            // Pierwsza proba wiaze TE SAMA usluge on-device, ktora wlasnie zwolnilismy. Od Androida 15
            // system odlacza usluge natychmiast po ostatnim kliencie, a wiazanie tuz po tym konczy sie
            // 11 SERVER_DISCONNECTED (emulator: 38 ms). Ta sama przerwa co miedzy probami.
            handler.postDelayed({ dalej() }, PRZERWA_MS)
        }
        handler.postDelayed({ zakoncz("model on-device: brak odpowiedzi w " + WSPARCIE_MS + " ms") }, WSPARCIE_MS)
        try {
            r.checkRecognitionSupport(
                intencja(konfigModelu()),
                ContextCompat.getMainExecutor(context),
                object : RecognitionSupportCallback {
                    override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                        // NAJPIERW do naglowka, DOPIERO POTEM zamkniecie proby. Do 0.9.16 szlo to
                        // przez zakoncz(), ktore milczy, gdy przebieg zostal juz przerwany
                        // (moj != token) — a przerwanie w pierwszych ~2,35 s (WSPARCIE_MS + PRZERWA_MS)
                        // jest bardzo prawdopodobne i kasowalo NAJWAZNIEJSZA linie calego raportu:
                        // te, ktora mowi, czy pakiet polskiego modelu w ogole jest na telefonie.
                        // Odpowiedz opisuje TELEFON, a nie ten konkretny przebieg, wiec zostaje
                        // wazna takze wtedy, gdy przebieg juz nie zyje.
                        dopiszDoNaglowka(
                            opisWsparcia(
                                VOICE_TEST_JEZYK,
                                recognitionSupport.installedOnDeviceLanguages,
                                recognitionSupport.supportedOnDeviceLanguages,
                                recognitionSupport.pendingOnDeviceLanguages,
                                recognitionSupport.onlineLanguages,
                            ),
                        )
                        val jest = modelZainstalowany(VOICE_TEST_JEZYK, recognitionSupport.installedOnDeviceLanguages)
                        modelDoPobrania = !jest &&
                            modelZainstalowany(VOICE_TEST_JEZYK, recognitionSupport.supportedOnDeviceLanguages)
                        zakoncz(null)
                    }

                    override fun onError(error: Int) {
                        // Ten sam powod co wyzej: kod bledu uslugi opisuje telefon, nie przebieg.
                        dopiszDoNaglowka("model on-device: błąd " + nazwaBledu(error))
                        zakoncz(null)
                    }
                },
            )
        } catch (t: Throwable) {
            zakoncz("model on-device: wyjątek " + t.javaClass.simpleName)
        }
    }

    /**
     * Zleca pobranie modelu on-device dla jezyka testu (API 33+). Postep i wynik przychodza
     * dopiero na API 34+; na API 33 zostaje samo "zlecono". Nic nie dzieje sie bez dotkniecia
     * przycisku — pakiet jezyka to spore pobranie i decyzja nalezy do ucznia.
     */
    fun pobierzModel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val r = utworz(konfigModelu())
        if (r == null) {
            pobieranie = PobieranieModelu.Blad("brak rozpoznawacza")
            return
        }
        val i = intencja(konfigModelu())
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                pobieranie = PobieranieModelu.Zlecone
                r.triggerModelDownload(
                    i,
                    ContextCompat.getMainExecutor(context),
                    object : ModelDownloadListener {
                        override fun onProgress(completedPercent: Int) {
                            pobieranie = PobieranieModelu.WToku(completedPercent)
                        }
                        override fun onSuccess() {
                            pobieranie = PobieranieModelu.Gotowe
                            modelDoPobrania = false
                            zniszcz(r)
                        }
                        override fun onScheduled() {
                            pobieranie = PobieranieModelu.Zlecone
                        }
                        override fun onError(error: Int) {
                            pobieranie = PobieranieModelu.Blad(nazwaBledu(error))
                            zniszcz(r)
                        }
                    },
                )
            } else {
                r.triggerModelDownload(i)
                pobieranie = PobieranieModelu.Zlecone
                // API 33 nie daje zwrotki; wiazanie zwalniamy po chwili, gdy zlecenie juz poszlo.
                handler.postDelayed({ zniszcz(r) }, 5_000L)
            }
            Log.i(VOICE_TAG, "test pobranie modelu " + VOICE_TEST_JEZYK + " zlecone")
        } catch (t: Throwable) {
            pobieranie = PobieranieModelu.Blad(t.javaClass.simpleName)
            zniszcz(r)
        }
    }

    private fun zniszcz(r: SpeechRecognizer) {
        try { r.destroy() } catch (_: Throwable) {}
    }

    /**
     * Konczy przebieg i oddaje mikrofon. Bezpieczne do wolania wielokrotnie.
     *
     * [powod] nie jest ozdoba: w oknie pierwszej proby tego wywolania moga uzyc az trzy rozne
     * miejsca (przycisk PRZERWIJ, obserwator ON_STOP, onDispose ekranu), a raport konczacy sie
     * samym slowem "PRZERWANY" nie mowil, ktore z nich to bylo.
     */
    fun zwolnij(powod: PowodPrzerwania) {
        // Kto wolal? W buildzie debug slad stosu — tak samo jak w VoiceInput.stop(). Bez niego
        // "PRZERWANY" w raporcie z cudzego telefonu nie ma jak wskazac wolajacego, a wolajacych
        // jest trzech. W wersji ze sklepu nie ma tego ani w logu, ani w koszcie.
        if (BuildConfig.DEBUG && trwa) Log.i(VOICE_TAG, "test zwolnij: wolajacy (" + powod + ")", Throwable("slad"))
        // Przebieg, ktory nie doszedl do konca matrycy, jest PRZERWANY — i tak ma sie nazywac.
        // Do 0.9.11 stalo tu "skonczone = true", wiec przerwanie na trzeciej z osmiu prob
        // dawalo raport nieodrozninalny od pelnego, konczacy sie zdaniem "na tym telefonie
        // rozpoznawanie mowy nie dziala". Warunek odsiewa zwolnij() przed pierwszym startem
        // (naglowek powstaje w start) i kolejne wywolania po tym samym przerwaniu — zwolnij()
        // leci i z DisposableEffect, i z obserwatora ON_STOP, i z wejscia w start().
        val wToku = naglowekPrzebiegu.isNotEmpty() && !skonczone && !przerwane
        // Proba, ktora wlasnie leci, to POMIAR — i na telefonie z tego zgloszenia najcenniejszy,
        // jaki ten ekran zdejmuje: gotowosc po 0,2 s, zero probek RMS, cisza przez ~2 s. Do
        // 0.9.16 zwolnij() wyrzucal ja tokenem, wiec raport wracal jako "proby: 0/10" i nie
        // niosl ani gotowosci, ani czasu, ani braku pomiaru. Zapis MUSI isc PRZED "token += 1":
        // pozniej stan proby jest juz niedostepny dla sluchacza, a wynik nie mialby jak powstac.
        if (trwa && !probaZapisana && idx in matryca.indices) zapisz(matryca[idx], KOD_PRZERWANE)
        token += 1
        handler.removeCallbacksAndMessages(null)
        zwolnijRec()
        trwa = false
        teraz = null
        wstrzymany = false
        if (wToku) {
            przerwane = true
            // Przebieg wstrzymany w tle ma juz zapisany swoj powod i swoja chwile — to WTEDY
            // realnie stanal, a wyjscie z ekranu tylko domyka to, co zaczelo zejscie w tlo.
            // Nadpisanie tego powodem WYJSCIE zgubiloby jedyna prawdziwa odpowiedz.
            if (przerwanie == null) {
                przerwanie = Przerwanie(
                    powod = powod,
                    czasMs = SystemClock.elapsedRealtime() - startPrzebiegu,
                    numerProby = if (idx in matryca.indices) idx + 1 else null,
                    nazwaProby = matryca.getOrNull(idx)?.nazwa,
                )
            }
            logWerdykt()
            zapiszRaport()
        }
    }

    /**
     * Zejscie w tlo (ON_STOP) NIE konczy przebiegu — zawiesza go.
     *
     * Przebieg trwa okolo minuty, a wystarczy powiadomienie albo przypadkowe przelaczenie
     * aplikacji, zeby do 0.9.16 przepadl w calosci: ON_STOP wolal zwolnij() i z minuty mowienia
     * zostawal raport "PRZERWANY", ktory nic nie mowi o telefonie. Mikrofonu w tle i tak nie ma
     * (AudioPolicyService wycisza aplikacje spoza wierzchu), wiec rozpoznawacz trzeba oddac —
     * ale idx, wyniki, matryca i naglowek zostaja i po powrocie przebieg rusza dalej.
     */
    fun wstrzymaj() {
        if (!trwa) return
        // Proba w toku nie przezyje tla, wiec zapisujemy ja jako przerwana — ma ten sam kod co
        // przy PRZERWIJ, bo to jest dokladnie ta sama dziura w danych, tylko chwilowa.
        if (!probaZapisana && idx in matryca.indices) zapisz(matryca[idx], KOD_PRZERWANE)
        token += 1
        handler.removeCallbacksAndMessages(null)
        zwolnijRec()
        trwa = false
        teraz = null
        wstrzymany = true
        zejsciaWTlo += 1
        // Powod i chwila zapisuja sie JUZ TERAZ, a nie dopiero przy wyjsciu z ekranu: jesli
        // system ubije aplikacje w tle, to wlasnie ten zapis jest calym raportem — i ma
        // powiedziec wprost, ze przebieg stanal na tle, a nie na silniku mowy. Powrot
        // ([wznow]) kasuje ten wpis, bo przebieg jednak sie nie skonczyl.
        przerwanie = Przerwanie(
            powod = PowodPrzerwania.TLO,
            czasMs = SystemClock.elapsedRealtime() - startPrzebiegu,
            numerProby = if (idx in matryca.indices) idx + 1 else null,
            nazwaProby = matryca.getOrNull(idx)?.nazwa,
        )
        Log.i(VOICE_TAG, "test wstrzymany w tle (" + zejsciaWTlo + "x) na probie " + (idx + 1) + "/" + ile)
        zapiszRaport()
    }

    /**
     * Powrot na wierzch (ON_START/ON_RESUME): przebieg rusza od NASTEPNEJ proby — ta, ktora
     * trwala w chwili zejscia w tlo, jest juz zapisana jako przerwana i powtarzanie jej tylko
     * wydluzyloby test. Wolanie bez wstrzymania nic nie robi, wiec obserwator cyklu zycia moze
     * je slac przy kazdym wejsciu na ekran.
     */
    fun wznow() {
        if (!wstrzymany) return
        wstrzymany = false
        if (skonczone || przerwane) return
        // Przebieg jednak sie nie skonczyl, wiec wpis o przerwaniu z [wstrzymaj] przestaje byc
        // prawda. Slad po tle zostaje w [zejsciaWTlo] i w probie zapisanej jako przerwana.
        przerwanie = null
        trwa = true
        Log.i(VOICE_TAG, "test wznowiony po tle od proby " + (idx + 2) + "/" + ile)
        // Ta sama przerwa co miedzy probami: usluga musi zdazyc sie odwiazac po cancel(),
        // inaczej pierwsza proba po powrocie dostaje ERROR_RECOGNIZER_BUSY nie ze swojej winy.
        handler.postDelayed({ nastepna() }, PRZERWA_MS)
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
            // Zapis po ostatniej probie mial jeszcze skonczone = false, wiec bez tego wiersza
            // raport odtworzony z dysku podawalby PELNY przebieg za przerwany.
            zapiszRaport()
            return
        }
        val k = matryca[idx]
        teraz = k.nazwa
        token += 1
        val moj = token
        start = SystemClock.elapsedRealtime()
        probaZapisana = false
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
        probaZapisana = true
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
        // Po KAZDEJ probie raport idzie na dysk. Przebieg trwa okolo minuty, a jego najczestszy
        // koniec to nie przycisk, tylko system ubijajacy aplikacje w tle — i wtedy z calego
        // przebiegu nie zostawalo nic do wklejenia.
        zapiszRaport()
        // Proba przerwana nie ma nastepczyni: zamyka ja zwolnij() albo wstrzymaj(), ktore
        // wlasnie konczy albo zawiesza caly przebieg. Bez tego warunku wstrzymanie planowaloby
        // kolejna probe do mikrofonu, ktorego aplikacja w tle i tak nie dostanie.
        if (kod == KOD_PRZERWANE) return
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
                (przerwanie?.let { " (" + opisPrzerwania(it) + ")" } ?: "") +
                (if (zejsciaWTlo > 0) " · tlo " + zejsciaWTlo + "x" else "") +
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
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                if (k.webSearch) RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH else RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
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

    /**
     * Usluga, ktora system uwaza za DOMYSLNY rozpoznawacz mowy
     * (Settings.Secure "voice_recognition_service", nazwa ustawienia jest publiczna i stala).
     *
     * Na telefonie z tego zgloszenia nie ma aplikacji Google, a android.speech.RecognitionService
     * rejestruje takze com.anthropic.claude — wiec systemowy fallback mogl oddac DOMYSLNEGO
     * SpeechRecognizera wlasnie jemu. Lista uslug w naglowku tego nie rozstrzygala: pokazuje
     * wszystkich kandydatow, a nie tego wybranego. Bez tej nazwy proby "default / *" opisuja
     * NIEWIADOMO CZYJ silnik.
     */
    private fun domyslnyRozpoznawacz(): String = try {
        val s = Settings.Secure.getString(context.contentResolver, "voice_recognition_service")
        if (s.isNullOrBlank()) "brak" else s
    } catch (_: Throwable) { "?" }

    /**
     * Pakiet, ktory obsluguje SYSTEMOWE OKNO mowy (resolveActivity dla ACTION_RECOGNIZE_SPEECH).
     *
     * To druga, niezalezna droga gry do rozpoznawania i moze nalezec do zupelnie innej aplikacji
     * niz [domyslnyRozpoznawacz] — a "okno: tak" mowilo tylko tyle, ze ktos je obsluguje.
     */
    private fun pakietOkna(): String = try {
        context.packageManager
            .resolveActivity(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0)
            ?.activityInfo?.packageName ?: "brak"
    } catch (_: Throwable) { "?" }

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
            // DWIE nazwy, ktorych nie widac nigdzie indziej: kogo system uwaza za domyslny
            // rozpoznawacz i czyje jest systemowe okno mowy. Na telefonie bez aplikacji Google
            // to one rozstrzygaja, czyj silnik odpowiada za proby "default / *".
            append("domyślny rozpoznawacz: ").append(domyslnyRozpoznawacz()).append('\n')
            append("okno mowy: ").append(pakietOkna()).append('\n')
            append("usługi (").append(u.size).append("): ")
            append(if (u.isEmpty()) "brak" else u.joinToString(", ") { it.first })
            append('\n')
        }
    }

    /**
     * Naglowek do raportu: to, co policzyl [naglowek] w chwili startu, plus linia o zejsciach
     * w tlo. Liczba zejsc zmienia wymowe calego przebiegu (patrz [zejsciaWTlo]), a nie da sie
     * jej policzyc przed startem, wiec doklejamy ja dopiero tutaj.
     */
    fun naglowekRaportu(): String =
        if (zejsciaWTlo == 0) naglowekPrzebiegu else naglowekPrzebiegu + "tło: " + zejsciaWTlo + "x\n"

    /**
     * Raport na dysk po kazdej probie i po kazdym przerwaniu.
     *
     * apply(), a nie commit(): to jest glowny watek i ta sama petla, ktora obsluguje mikrofon —
     * commit() wpina tu synchroniczny zapis pliku. apply() oddaje zapis watkowi w tle i system
     * i tak domyka go przy zatrzymaniu aktywnosci, czyli dokladnie w chwili, przed ktora ten
     * zapis ma bronic.
     */
    private fun zapiszRaport() {
        try {
            context.getSharedPreferences(VOICE_TEST_PAMIEC, Context.MODE_PRIVATE).edit()
                .putString(KLUCZ_NAGLOWEK, naglowekRaportu())
                .putString(KLUCZ_WYNIKI, zapiszWyniki(wyniki))
                .putInt(KLUCZ_ILE, ile)
                .putString(KLUCZ_PRZERWANIE, zapiszPrzerwanie(przerwanie))
                .putBoolean(KLUCZ_SKONCZONE, skonczone)
                .apply()
        } catch (_: Throwable) {
            // Brak zapisu nie moze przerwac przebiegu: raport w schowku jest droga glowna,
            // a ten zapis tylko zapasowa.
        }
    }

    /**
     * Ostatni raport zapisany na dysku — null, gdy tego telefonu jeszcze nie testowano.
     *
     * Po to, zeby przebieg ubity w tle nie przepadal: uczen wraca na ekran i kopiuje wynik
     * zamiast przeklikiwac cala minute od nowa.
     */
    fun ostatniRaport(): OstatniRaport? = try {
        val p = context.getSharedPreferences(VOICE_TEST_PAMIEC, Context.MODE_PRIVATE)
        val zapisanyNaglowek = p.getString(KLUCZ_NAGLOWEK, "").orEmpty()
        if (zapisanyNaglowek.isEmpty()) null else OstatniRaport(
            naglowek = zapisanyNaglowek,
            wyniki = czytajWyniki(p.getString(KLUCZ_WYNIKI, "").orEmpty()),
            ile = p.getInt(KLUCZ_ILE, 0),
            przerwanie = czytajPrzerwanie(p.getString(KLUCZ_PRZERWANIE, "").orEmpty()),
            skonczone = p.getBoolean(KLUCZ_SKONCZONE, false),
        )
    } catch (_: Throwable) { null }

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
        /** Ile czekamy na odpowiedz o model on-device, zanim ruszy matryca. */
        const val WSPARCIE_MS = 2_000L

        // Klucze zapisanego raportu. Same krotkie nazwy — plik jest prywatny dla aplikacji
        // i nie ma innego czytelnika niz [ostatniRaport].
        const val KLUCZ_NAGLOWEK = "naglowek"
        const val KLUCZ_WYNIKI = "wyniki"
        const val KLUCZ_ILE = "ile"
        const val KLUCZ_PRZERWANIE = "przerwanie"
        const val KLUCZ_SKONCZONE = "skonczone"
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
    przerwana = str.voiceTestAborted,
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
    // Zejscie aplikacji w tlo ZAWIESZA przebieg. Sam DisposableEffect lapie tylko wyjscie
    // z kompozycji: gdy uczen w polowie minutowego przebiegu przelaczy sie do innej aplikacji
    // albo zgasi ekran, sonda dalej trzymalaby zwiazana usluge i dalej odliczala 8-sekundowe
    // proby — z mikrofonem, do ktorego Android aplikacji w tle i tak nie da dostepu. Wyszlaby
    // seria pustych prob z rzedu, czyli raport falszywie negatywny, i nikt by tego z raportu
    // nie odczytal. Do 0.9.16 ON_STOP konczyl caly przebieg, wiec jedno powiadomienie kosztowalo
    // minute mowienia i zostawialo raport "PRZERWANY", ktory nic nie mowi o telefonie.
    val wlasciciel = LocalLifecycleOwner.current
    DisposableEffect(wlasciciel, probe) {
        val obserwator = LifecycleEventObserver { _, zdarzenie ->
            when (zdarzenie) {
                Lifecycle.Event.ON_STOP -> probe.wstrzymaj()
                // Powrot na wierzch wznawia od nastepnej proby. ON_RESUME jest tu razem z
                // ON_START, bo obserwator dostaje oba, a wznow() bez wstrzymania nic nie robi.
                Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME -> probe.wznow()
                else -> {}
            }
        }
        wlasciciel.lifecycle.addObserver(obserwator)
        // Wyjscie z EKRANU to co innego niz zejscie w tlo: konczy przebieg i ma oddac mikrofon
        // NATYCHMIAST — inaczej zwiazana usluga zostalaby z otwartym nasluchem, a gra po powrocie
        // dostawalaby ERROR_RECOGNIZER_BUSY.
        onDispose {
            wlasciciel.lifecycle.removeObserver(obserwator)
            probe.zwolnij(PowodPrzerwania.WYJSCIE)
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
    // Raport z POPRZEDNIEGO przebiegu, odczytany raz przy wejsciu na ekran. Przebieg trwa okolo
    // minuty i bardzo czesto konczy go system, ubijajac aplikacje w tle — do 0.9.16 nie zostawalo
    // wtedy nic i uczen musial przeklikac wszystko od nowa.
    val poprzedni = remember(probe) { probe.ostatniRaport() }

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
    // Caly przebieg w jednym pudelku — ta sama struktura opisuje przebieg biezacy i ten
    // odtworzony z dysku, wiec werdykt i raport skladaja sie DLA OBU w jednym miejscu.
    val biezacy = OstatniRaport(
        naglowek = probe.naglowekRaportu(),
        wyniki = wyniki,
        ile = probe.ile,
        przerwanie = probe.przerwanie,
        skonczone = probe.skonczone,
    )
    val werdykt = werdyktPrzebiegu(biezacy, str, teksty)
    // Przerwany test tez ma wynik: to, co zdazylo sie zdarzyc, jest juz twarda informacja
    // i musi dac sie skopiowac — inaczej dlugi przebieg trzeba by przeklikiwac od nowa.
    // Przebieg wstrzymany (uczen wrocil z tla) nie jest skonczony i nie ma co podsumowywac.
    val gotowe = !probe.trwa && !probe.wstrzymany &&
        (probe.skonczone || probe.przerwane || wyniki.isNotEmpty())
    val raport = raportPrzebiegu(biezacy, str, teksty)

    fun kopiuj(tresc: String) {
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

                // START i PRZERWIJ to DWA rozne przyciski. Do 0.9.16 byl jeden, ktory w trakcie
                // przebiegu zmienial sie w PRZERWIJ — a uczen, ktory po dotknieciu START nie
                // widzi zadnej reakcji (silnik z tego zgloszenia milczy!), dotyka go po raz
                // drugi. Drugie dotkniecie zabijalo wlasnie zaczety przebieg i raport wracal
                // pusty: "proby: 0/10 · PRZERWANY".
                PrimaryButton(
                    label = when {
                        probe.trwa -> str.voiceTestRunning
                        // Przerwany przebieg tez jest przebiegiem: przycisk ma zapraszac do
                        // powtorki, a nie udawac, ze nic sie nie wydarzylo.
                        wyniki.isNotEmpty() || probe.skonczone || probe.przerwane -> str.voiceTestAgain
                        else -> str.voiceTestStart
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TAG_TEST_START),
                    // W trakcie przebiegu glowny przycisk jest MARTWY, a nie "przerywajacy".
                    enabled = !probe.trwa,
                ) {
                    uruchom()
                }

                if (probe.trwa) {
                    Spacer(Modifier.height(10.dp))
                    // Mniejszy i osobny: przerwanie ma byc mozliwe, ale nie ma stac dokladnie
                    // tam, gdzie przed chwila byl START.
                    GhostButton(str.voiceTestStop, Modifier.testTag(TAG_TEST_STOP)) {
                        probe.zwolnij(PowodPrzerwania.PRZYCISK)
                    }
                }

                // Raport z poprzedniego przebiegu — tylko dopoki ten ekran nie ma wlasnego
                // wyniku, bo od tej chwili KOPIUJ RAPORT nizej jest wazniejszy.
                if (poprzedni != null && wyniki.isEmpty() && !probe.trwa &&
                    !probe.skonczone && !probe.przerwane
                ) {
                    Spacer(Modifier.height(10.dp))
                    GhostButton(str.voiceTestCopyLast, Modifier.testTag(TAG_TEST_KOPIUJ_OSTATNI)) {
                        kopiuj(raportPrzebiegu(poprzedni, str, teksty))
                    }
                    if (skopiowane) {
                        Spacer(Modifier.height(8.dp))
                        Text(str.voiceTestCopied, color = Palette.Accent, fontSize = 12.sp)
                    }
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

                // MODEL DO POBRANIA: usluga on-device obsluguje polski, ale pakietu nie ma.
                // To jedyna naprawa, ktora uczen moze zrobic sam, wiec stoi tuz pod wynikami.
                if (probe.modelDoPobrania && !probe.trwa) {
                    Spacer(Modifier.height(12.dp))
                    InfoCard(str.voiceTestDownloadHint)
                    Spacer(Modifier.height(8.dp))
                    GhostButton(str.voiceTestDownloadModel) { probe.pobierzModel() }
                }
                val stanPobierania = when (val p = probe.pobieranie) {
                    PobieranieModelu.Brak -> ""
                    PobieranieModelu.Zlecone -> str.voiceTestDownloadScheduled
                    is PobieranieModelu.WToku -> str.voiceTestDownloadProgress.format(p.procent)
                    PobieranieModelu.Gotowe -> str.voiceTestDownloadDone
                    is PobieranieModelu.Blad -> str.voiceTestDownloadFailed.format(p.opis)
                }
                if (stanPobierania.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(stanPobierania, color = Palette.Accent, fontSize = 12.sp)
                }

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
                    GhostButton(str.voiceTestCopy) { kopiuj(raport) }
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
