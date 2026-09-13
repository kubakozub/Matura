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
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
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
import com.matura.app.BuildConfig

/** Ktory silnik faktycznie obsluguje nasluch. */
enum class VoiceMode { UNAVAILABLE, ON_DEVICE, ONLINE }

/**
 * Ile podpowiedzi slownikowych gra wysyla do silnika (EXTRA_BIASING_STRINGS).
 *
 * Stala jest publiczna, bo ekran testu mowy musi wysylac liste DOKLADNIE tej dlugosci: hipoteza
 * "dluga lista podpowiedzi dlawi silnik bez zadnego bledu" jest nierozstrzygalna, gdy test
 * sprawdza szesc slow, a gra wysyla piecdziesiat.
 */
const val MAX_BIASING_WORDS = 50

/**
 * JEDEN tag logu na cala sprawe mowy — sciezke glosowa gry i ekran testu.
 *
 * Sesja z podpietym telefonem oglada oba przez `adb logcat -s MaturaVoice`. Tag jest publiczny
 * wlasnie po to, zeby ekran testu nie mial wlasnego: dwa tagi znaczylyby, ze polowa sladu ginie
 * temu, kto zna tylko jeden z nich.
 */
const val VOICE_TAG = "MaturaVoice"

/**
 * Najkrotszy odstep miedzy przestawieniami jezyka silnika w trakcie rundy ([VoiceInput.przelaczJezyk]).
 *
 * Kazde przestawienie to cancel() + startListening(): bez odstepu seria trafien w trybie losowym
 * potrafi wywolac karuzele ERROR_RECOGNIZER_BUSY i migotanie paska miedzy "SŁUCHAM…" a podpisem.
 */
const val ODSTEP_JEZYKA_MS = 3_000L

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
 * telefonu) albo [onBrakZgody] (ustawienia aplikacji). Do tego DWA limity czasu — watchdog na
 * silnik, ktory nie odpowiada wcale, i straznik cyklu na silnik, ktory zglosil gotowosc i zamilkl
 * — oraz wykrywanie gluchego mikrofonu z poziomu RMS ([SilenceDetector]).
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
    var tekstCiszy: String = "Mikrofon milczy — wyłączony lub zajęty?"
    /** Krotka informacja, ze zmieniamy silnik — inaczej pauza wyglada jak zawieszenie. */
    var tekstInnySilnik: String = "próbuję innego silnika mowy…"
    /** Komunikat, gdy zaden silnik nie rozpoznaje mowy w tym jezyku. */
    var tekstBrakMowy: String = "Nie rozpoznaję mowy — wpisz odpowiedź"

    // Komunikaty konca drabinki ([friendlyError]). Do 0.9.11 byly wpisane w kod po polsku,
    // wiec uczen w angielskiej wersji interfejsu dostawal je po polsku — a jeden z nich mowil
    // na dodatek o pakiecie "PL", chociaz blad dotyczyl tagu, o ktory silnik byl pytany.
    // Droga jest ta sama co dla tekstCiszy: ekran podstawia tlumaczenia, bo VoiceInput
    // nie ma dostepu do Str (komunikat powstaje asynchronicznie, poza kompozycja).
    /** Koniec drabinki przez siec (kody 1/2). */
    var tekstBrakSieci: String = "Brak internetu dla głosu — wpisz odpowiedź"
    /** Brak pakietu mowy (12/13) DLA ZNANEGO jezyka; "%s" zastepuje [tekstJezykPolski]/[tekstJezykAngielski]. */
    var tekstBrakPakietuJezyka: String = "Brak pakietu mowy: %s — wpisz odpowiedź"
    /** To samo dla tagu, ktorego nie umiemy nazwac po ludzku — surowy BCP 47 nic uczniowi nie mowi. */
    var tekstBrakPakietu: String = "Brak pakietu mowy — wpisz odpowiedź"
    /** Watchdog: silnik przyjal zlecenie i milczy; "%s" po kolei — kod bledu i liczba uslug. */
    var tekstSilnikMilczy: String = "Silnik mowy milczy (%s/%s) — wpisz odpowiedź"
    /** Kazda inna awaria konczaca drabinke; "%s" jak wyzej. */
    var tekstGlosNiedostepny: String = "Głos niedostępny (%s/%s) — wpisz odpowiedź"
    /** Silnik odmowil z powodu braku zgody (kod 9); "%s" to kod. */
    var tekstBrakZgody: String = "Brak zgody na mikrofon (%s)"
    /** Nazwy jezykow w postaci, ktora wchodzi do [tekstBrakPakietuJezyka] (mianownik po dwukropku). */
    var tekstJezykPolski: String = "polski"
    var tekstJezykAngielski: String = "angielski"

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
    //
    // Od 0.9.16 offline wymuszamy WYLACZNIE na on-device z potwierdzonym modelem (patrz
    // sprawdzModel). Silnik domyslny (aplikacja Google) sam siega po model offline, gdy go ma;
    // EXTRA_PREFER_OFFLINE na telefonie bez pobranego pakietu konczyl sie odmowa albo cisza —
    // czyli pierwszy szczebel drabinki byl najbardziej zawodny z calej listy.
    private var preferOffline = false
    private var attemptedOnline = false

    /**
     * Czy on-device ma MODEL dla tagu jezyka — odpowiedz SpeechRecognizer.checkRecognitionSupport
     * (API 33+). Klucz: tag ("pl-PL"); brak klucza = jeszcze nie pytano. Wypelniane sonda w tle
     * ([sprawdzModeleWTle]) i przed pierwszym wiazaniem sesji ([start]).
     *
     * SEDNO ZGLOSZENIA Z MOTOROLI (Android 16): usluga on-device JEST (isOnDeviceRecognitionAvailable
     * = true), przyjmuje zlecenie z pl-PL, melduje gotowosc i milczy — bez bledu, bez probki poziomu.
     * Tak wyglada on-device bez pobranego modelu tego jezyka. Zamiast czekac, az straznik to zlapie,
     * pytamy o model PRZED startem i bez modelu idziemy od razu do silnika domyslnego.
     */
    private val modelOnDevice = HashMap<String, Boolean>()
    /**
     * Bledy przejsciowe (CLIENT / SERVER / siec) POD RZAD na silniku, ktory w tej sesji juz oddal
     * tekst. Taki silnik nie traci szczebla za czkawke po cancel()+startListening ani za chwilowy
     * brak zasiegu — ponawiamy, ale skonczona liczbe razy (MAX_PRZEJSCIOWE).
     */
    private var bledyPrzejsciowe = 0

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
    /**
     * Czy biezacy silnik zamienil w tej sesji cokolwiek na TEKST (wynik czesciowy albo koncowy).
     *
     * To nie jest to samo co [dzialal], ktory zapala sie juz na samej gotowosci — a silnik bez
     * modelu jezyka zglasza gotowosc i sypie poziomem mikrofonu dokladnie tak jak sprawny.
     * Tylko oddany tekst jest dowodem pracy, wiec tylko on chroni silnik przed degradacja
     * przez straznika cyklu (patrz [zejscPoMilczacymCyklu]).
     */
    private var oddalWynik = false
    /** Kierunek gry (TERM_TO_DEF / DEF_TO_TERM / RANDOM) — tylko do logu i diagnostyki. */
    private var kierunek = ""
    /** Chwila ostatniego startu nasluchu — od niej liczy sie odstep miedzy zmianami jezyka. */
    private var ostatniStart = 0L
    /** Watchdog: czekamy na PIERWSZE zdarzenie od silnika po startListening. */
    private var czekamNaSilnik = false

    /**
     * Numer WYPOWIEDZI. Rosnie przy kazdym startListening, czyli raz na cykl rozpoznawania.
     * Wynik czesciowy i koncowy tej samej wypowiedzi maja ten sam numer - na tym opiera sie
     * zasada "jedna wypowiedz zalicza slowo najwyzej raz" (patrz [zaliczycWypowiedz]).
     * Spozniony wynik poprzedniego cyklu nosi numer sprzed restartu i tez nie dolozy punktu.
     */
    var wypowiedz = 0
        private set

    /**
     * Czy w BIEZACYM cyklu przyszla choc jedna probka poziomu dzwieku (albo poczatek mowy,
     * albo wynik czesciowy). Zerowane przy kazdym uzbrojeniu strażnika dzwieku.
     */
    private var wchodziDzwiek = false

    /** Czy probowalismy juz tagu jezyka bez regionu (szczebel drabinki, raz na sesje). */
    private var probowanyJezykBezRegionu = false

    /**
     * Stan przebiegu - wyliczany, nie przechowywany (patrz [stanSesji]). Widoczny w diagnostyce,
     * zeby "co sie teraz dzieje" dalo sie odczytac bez skladania go z ciagu zdarzen.
     */
    val stan: VoiceStan
        get() = stanSesji(wantListening, czekamNaSilnik, dzialal, wyczerpany)
    private var bledyAudio = 0
    /** Puste cykle (NO_MATCH / SPEECH_TIMEOUT) POD RZAD na silniku, ktory jeszcze nic nie
     *  rozpoznal. Silnik bez modelu jezyka zwraca dokladnie to — i tylko to. */
    private var pusteCykle = 0
    private var bledyBusy = 0
    private val cisza = SilenceDetector()
    /** Dziennik zdarzen do diagnostyki (nie zapisuje tresci wypowiedzi). */
    private val dziennik = VoiceLog()
    /** Po pierwszej eskalacji nie wysylamy juz podpowiedzi slownikowych — patrz buildIntent. */
    private var bezBiasingu = false
    /** Czy w biezacej serii pustych cykli mikrofon w ogole cos slyszal. */
    private var slyszalMowe = false

    /**
     * Numer pokolenia silnika. Rosnie przy KAZDEJ zmianie silnika oraz przy stop()/release(),
     * a kazdy sluchacz pamieta swoj numer — patrz [sluchacz]. Bez tego spozniony sygnal od
     * silnika, ktory juz zniszczylismy (klasycznie ERROR_CLIENT tuz po destroy/cancel), liczyl
     * sie NOWEMU silnikowi: jedna eskalacja zjadala dwa szczeble drabinki naraz i konczyla sie
     * "wyczerpane" w ulamku sekundy, a spozniona gotowosc kasowala juz ustawiony komunikat.
     * Z ekranu wygladalo to dokladnie tak, jak brzmi zgloszenie: wlacza sie, wylacza i nic.
     */
    private var pokolenie = 0
    /** Znacznik zaplanowanego wznowienia nasluchu — patrz [Wznowienie]. */
    private val wznowienie = Wznowienie()
    /** Licznik probek RMS w biezacym cyklu (tylko do przerzedzania logu). */
    private var probekRms = 0
    /** Najglosniejsza probka, ktora trafila do logu — zeby nie logowac kazdego drgnienia. */
    private var logMaxRms = Float.NEGATIVE_INFINITY

    /**
     * Log sciezki glosowej. Pelny slad (start, gotowosc, poziom mikrofonu, wyniki, kazde zejscie
     * drabinka) leci TYLKO w buildzie debug: w wersji ze sklepu onRmsChanged sypalby linia
     * kilka razy na sekunde przez caly nasluch. Ostrzezenia ([ostrzez]) zostaja w obu wersjach —
     * jest ich kilka na rozgrywke, a bywaja jedynym sladem awarii z telefonu, ktorego nie mamy.
     */
    private fun log(m: String) { if (BuildConfig.DEBUG) Log.i(TAG, m) }

    private fun ostrzez(m: String) { Log.w(TAG, m) }

    private fun dostepneSilniki(): List<ComponentName> = try {
        context.packageManager
            .queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .mapNotNull { it.serviceInfo?.let { s -> ComponentName(s.packageName, s.name) } }
            .sortedBy { if (it.packageName.contains("google", true)) 0 else 1 }
    } catch (_: Throwable) { emptyList() }

    /**
     * Ktora usluga rozpoznawania jest ustawiona jako domyslna w systemie. Czytamy WYLACZNIE do
     * diagnostyki — bez tej nazwy raport z telefonu mowi tylko, ze jakas usluga jest, a nie ktora.
     */
    private fun domyslnaUsluga(): String = try {
        android.provider.Settings.Secure.getString(
            context.contentResolver, "voice_recognition_service",
        ).orEmpty().substringBefore('/')
    } catch (_: Throwable) { "" }

    private fun sprawdzOkno(): Boolean = try {
        context.packageManager.resolveActivity(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0) != null
    } catch (_: Throwable) { false }

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Czy na tym urzadzeniu w ogole jest jakikolwiek silnik mowy.
     *
     * On-device liczy sie dopiero od API 33: tam da sie ZAPYTAC o model jezyka (checkRecognitionSupport),
     * a bez tego pytania on-device bez pobranego pakietu to silnik, ktory melduje gotowosc i milczy.
     * Na Androidzie 12 (API 31–32) zaczynamy od silnika domyslnego — on sam uzywa modelu offline,
     * jesli go ma.
     */
    fun probe() {
        mode = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context) -> VoiceMode.ON_DEVICE
            SpeechRecognizer.isRecognitionAvailable(context) -> VoiceMode.ONLINE
            else -> VoiceMode.UNAVAILABLE
        }
        preferOffline = mode == VoiceMode.ON_DEVICE
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
        // Odpowiedz o modelu on-device ma czekac na dotkniecie paska, a nie odwrotnie.
        sprawdzModeleWTle()
        // Wyczerpana drabinka wraca do gry tylko, gdy cos sie naprawde zmienilo: inna lista
        // uslug albo inny tryb z sondy. Samo dotkniecie nie jest zmiana.
        // Blokada znika, gdy: zmienila sie lista uslug albo tryb, przyczyna byla przejsciowa
        // (siec, serwer, cichy silnik przy watchdogu) albo po prostu minelo dosc czasu. Trwale
        // zostaja tylko awarie strukturalne (np. brak pakietu jezyka) i tylko do konca rozgrywki.
        val przejsciowy = wyczerpanyKod in setOf(
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER,
            ERROR_WATCHDOG,
        )
        val ostyglo = android.os.SystemClock.elapsedRealtime() - wyczerpanyOd > BLOKADA_MS
        if (wyczerpany && (nowe.toSet() != stare.toSet() || mode != trybPrzed || przejsciowy || ostyglo)) {
            wyczerpany = false
            silnikIdx = -1
            attemptedOnline = false
            // Skoro zawiodly WSZYSTKIE szczeble, nie ma powodu zostawac na ostatnim: nowa sesja
            // zaczyna od trybu z sondy (on-device z modelem, gdy jest). Bez tego on-device
            // porzucony raz — choc to jedyny silnik z pobranym modelem — nie wracal do konca ekranu.
            zwolnijRecognizer()
            probe()
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
        try { recognizer?.destroy() } catch (t: Throwable) {
            // destroy() na usludze, ktora juz odpadla, potrafi rzucic DeadObjectException.
            // Instancje i tak porzucamy, ale slad zostaje: bez niego "mikrofon nie wraca po
            // wyjsciu z ekranu" nie ma w logu zadnego punktu zaczepienia.
            ostrzez("destroy() rzucil " + t.javaClass.simpleName)
        }
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
            // NAZWA domyslnej uslugi, nie samo tak/nie. Raport z telefonu uzytkownika pokazal
            // trzy zarejestrowane uslugi rozpoznawania, w tym jedna spoza Google — bez tej linii
            // nie da sie odroznic "silnik Google milczy" od "domyslna jest zupelnie inna usluga".
            append("\n · domyślna: ").append(domyslnaUsluga().ifEmpty { "brak" })
            append(" · okno: ").append(if (oknoDostepne) "tak" else "NIE").append('\n')
            append("silniki (").append(silniki.size).append("): ")
            append(if (silniki.isEmpty()) "brak" else silniki.joinToString(", ") { it.packageName })
            append('\n').append("próbowano: ")
            append(if (probowane.isEmpty()) "—" else probowane.joinToString(", "))
            append('\n').append("ostatni błąd: ").append(ostatniBlad)
            append(" · puste cykle: ").append(pusteCykle)
            append(" · stan: ").append(stan.name.lowercase())
            append(" · wypowiedź: ").append(wypowiedz)
            append(" · znak życia: ").append(if (dzialal) "tak" else "NIE")
            append(" · rozpoznał tekst: ").append(if (oddalWynik) "tak" else "NIE")
            // "Znak zycia" to gotowosc albo poziom mikrofonu; "rozpoznal cos" to oddany TEKST.
            // Rozroznienie jest sednem tego zgloszenia: silnik bez modelu jezyka daje pierwsze
            // i nigdy drugiego, a do 0.9.11 diagnostyka pokazywala jedno pod nazwa drugiego.
            append(" · rozpoznał coś: ").append(if (oddalWynik) "tak" else "NIE")
            append('\n').append("język: ").append(language)
            append(" · kierunek: ").append(if (kierunek.isEmpty()) "—" else kierunek)
            append(" · podpowiedzi: ").append(ilePodpowiedzi())
            // Odpowiedz checkRecognitionSupport dla obu tagow gry. "brak" przy pl-PL na telefonie
            // z usluga on-device to dokladnie ta sytuacja, w ktorej do 0.9.15 silnik milczal.
            append('\n').append("model on-device: ")
            append(JEZYKI_GRY.joinToString(" · ") { it + " " + modelOpis(it) })
            append('\n').append("— przebieg —\n").append(dziennik.tekst())
            append(" · drabinka: ").append(if (wyczerpany) "wyczerpana" else "ok")
            append(" · rms max: ")
            append(if (cisza.maxRms == Float.NEGATIVE_INFINITY) "—" else "%.1f".format(cisza.maxRms))
            append(" · wyciszony: ").append(if (mikrofonWyciszony()) "tak" else "nie")
        }
    }

    /** Nazwa silnika, ktory wlasnie probujemy — do komunikatu diagnostycznego. */
    val silnikOpis: String
        get() = silniki.getOrNull(silnikIdx)?.packageName?.substringAfterLast('.') ?: "domyślny"

    // EXTRA_LANGUAGE_PREFERENCE i EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE celowo NIE sa tu
    // dodawane. Wbrew nazwom nie sa to dodatki do zlecenia rozpoznawania: dokumentacja
    // RecognizerIntent opisuje pierwszy jako KLUCZ w Bundle ZWRACANYM przez rozgloszenie
    // ACTION_GET_LANGUAGE_DETAILS ("current language preference this user has specified"),
    // a drugi jako flage TEGO rozgloszenia ("only the current language preference is needed
    // in the response"). W intencji ACTION_RECOGNIZE_SPEECH sa martwe — jezyk ustawia
    // EXTRA_LANGUAGE (tag BCP 47) i on juz tu jest. Dokladanie ich udawaloby poprawke.
    private fun buildIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_ALTERNATIVES)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        // Prosimy o rozpoznanie bez sieci — ale tylko dopoki silnik to akceptuje.
        // Gdy odmowi (ERROR_CLIENT), escalate() wylacza offline i dzwiek moze pojsc siecia.
        if (preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        // Podpowiedzi slownikowe TYLKO dla on-device z potwierdzonym modelem: tam sa udokumentowane
        // i tam pomagaja. Silnik domyslny (aplikacja Google, zwykle przez siec) dostaje zlecenie
        // w najprostszej, najszerzej sprawdzonej postaci — a celnosc pojedynczego slowa zalatwia
        // i tak dopasowanie do zamknietej planszy (VoiceMatch). Po pierwszej eskalacji podpowiedzi
        // znikaja takze na on-device: dluga lista potrafi byc powodem, dla ktorego silnik
        // przestaje zwracac wyniki (bez zadnego bledu).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && mode == VoiceMode.ON_DEVICE &&
            biasing.isNotEmpty() && !bezBiasingu
        ) {
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
        ostrzez("watchdog: silnik " + silnikOpis + " milczy " + WATCHDOG_MS + " ms — schodze drabinka")
        ostatniBlad = ERROR_WATCHDOG
        zanotujProbe(silnikOpis + ":" + ERROR_WATCHDOG)
        if (!escalate()) wyczerpane(friendlyError(ERROR_WATCHDOG))
    }

    private fun uzbrojWatchdog() {
        czekamNaSilnik = true
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_MS)
        // Drugi straznik pilnuje CALEGO cyklu i nie schodzi z posterunku po gotowosci.
        uzbrojStraznikaCyklu()
        log("watchdog " + WATCHDOG_MS + " ms, straznik cyklu " + CYKL_MS +
            " ms (silnik " + silnikOpis + ")")
    }

    // STRAZNIK CYKLU. Watchdog wyzej pilnuje tylko PIERWSZEGO zdarzenia i schodzi z posterunku,
    // gdy silnik da znak zycia ([zyje] wola onReadyForSpeech, onBeginningOfSpeech i onRmsChanged).
    // Od tej chwili — do 0.9.11 — nie bylo juz ZADNEGO limitu czasu. Silnik, ktory przyjmuje
    // startListening, zglasza gotowosc (albo tylko strumien RMS) i nigdy nie oddaje ani wyniku,
    // ani bledu, wieszal nasluch na zawsze: pasek swiecil "SŁUCHAM…", drabinka stala, status byl
    // pusty. To jest co do slowa zgloszenie z Motoroli ("wlacza sie, zadnego bledu, brak reakcji
    // na slowa") — i jedyna awaria z tej listy, ktorej NIE widac ani w logu, ani w diagnostyce.
    // Straznik daje kazdemu cyklowi twardy limit CYKL_MS i konczy go tak samo jak kod bledu:
    // zejsciem drabinka, a na jej koncu oddaniem decyzji ekranowi.
    private val straznikCyklu = Runnable {
        if (!wantListening) return@Runnable
        czekamNaSilnik = false
        // Silnik, ktory w tej sesji cokolwiek juz oddal, jest sprawny — dlugi cykl bez wyniku
        // znaczy wtedy tyle, ze uczen sie zastanawia. Degradacja takiego silnika zabralaby mu
        // szczebel drabinki i (przez useEngine) slownik zestawu do konca sesji, a w logu
        // wygladalaby identycznie jak awaria, ktora straznik ma lapac. Cykl po prostu wznawiamy.
        if (!zejscPoMilczacymCyklu(oddalWynik)) {
            log("straznik cyklu: " + silnikOpis + " milczy " + CYKL_MS +
                " ms, ale w tej sesji juz rozpoznawal — wznawiam cykl zamiast schodzic")
            dziennik.dodaj(System.currentTimeMillis(), "cisza cyklu (" + silnikOpis + ") — wznawiam")
            restartSoon()
            return@Runnable
        }
        ostrzez("straznik cyklu: silnik " + silnikOpis + " nie oddal ani wyniku, ani bledu przez " +
            CYKL_MS + " ms i nic w tej sesji nie rozpoznal — schodze drabinka")
        dziennik.dodaj(System.currentTimeMillis(), "cisza cyklu (" + silnikOpis + ") — schodzę")
        ostatniBlad = ERROR_WATCHDOG
        zanotujProbe(silnikOpis + ":cisza-cyklu")
        // Uczen ma widziec, ze silnik sie zmienil — tak samo jak w galeziach PUSTY_CYKL
        // i BRAK_JEZYKA. Bez tego pasek milczy przez cala zmiane silnika i wyglada jak zawis.
        if (escalate()) status = tekstInnySilnik
        else wyczerpane(friendlyError(ERROR_WATCHDOG))
    }

    /** Uzbrajane przy KAZDYM startListening (patrz [uzbrojWatchdog]) i przy wyniku czesciowym. */
    // STRAZNIK DZWIEKU. Sygnatura awarii z telefonu uzytkownika: "gotowy" po 0,3 s i ZERO
    // probek poziomu przez nastepne 7,5 s. Sprawny silnik raportuje poziom kilkadziesiat razy
    // na sekunde od pierwszej chwili, wiec brak choc jednej probki nie jest cisza gracza —
    // to znaczy, ze do silnika nie wchodzi dzwiek.
    //
    // Straznik cyklu (15 s) teoretycznie by to zlapal, ale praktycznie nie: uzytkownik przerywa
    // wczesniej. Ten daje odpowiedz w DZWIEK_MS, czyli zanim ktokolwiek zdazy sie zniechecic.
    private val straznikDzwieku = Runnable {
        if (!wantListening || wchodziDzwiek) return@Runnable
        // Silnik, ktory w tej sesji juz cos rozpoznal, ma prawo milczec — milczy gracz.
        if (oddalWynik) return@Runnable
        ostrzez("straznik dzwieku: " + silnikOpis + " zglosil gotowosc, ale przez " + DZWIEK_MS +
            " ms nie oddal ani jednej probki poziomu — schodze drabinka")
        dziennik.dodaj(System.currentTimeMillis(), "brak dźwięku z mikrofonu")
        ostatniBlad = ERROR_BEZ_DZWIEKU
        zanotujProbe(silnikOpis + ":" + ERROR_BEZ_DZWIEKU)
        if (escalate()) status = tekstInnySilnik
        else wyczerpane(friendlyError(ERROR_BEZ_DZWIEKU))
    }

    /** Uzbrajane po gotowosci silnika — patrz [straznikDzwieku]. */
    private fun uzbrojStraznikaDzwieku() {
        wchodziDzwiek = false
        handler.removeCallbacks(straznikDzwieku)
        handler.postDelayed(straznikDzwieku, DZWIEK_MS)
    }

    /**
     * Dzwiek dochodzi. Wolane z onRmsChanged, onBeginningOfSpeech i z niepustego wyniku
     * czesciowego — kazde z nich osobno dowodzi, ze silnik cos slyszy.
     */
    private fun dzwiekDochodzi() {
        wchodziDzwiek = true
        handler.removeCallbacks(straznikDzwieku)
    }

    private fun uzbrojStraznikaCyklu() {
        handler.removeCallbacks(straznikCyklu)
        handler.postDelayed(straznikCyklu, CYKL_MS)
    }

    /** Cykl skonczony (wynik albo blad) — nowy cykl uzbroi straznika z powrotem. */
    private fun zdejmijStraznikaCyklu() {
        handler.removeCallbacks(straznikCyklu)
    }

    /** Ile alternatyw przyszlo w wyniku. Do logu — TRESCI wypowiedzi nie logujemy nigdy. */
    private fun ileAlternatyw(b: Bundle?): Int =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.size ?: -1

    /**
     * Dlugosc pierwszej alternatywy w znakach. Tyle wystarczy, zeby w logu odroznic
     * "cos przyszlo" od "przyszla pusta lista" — a sama tresc zostaje w aplikacji.
     */
    private fun dlugoscPierwszej(b: Bundle?): Int =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.length ?: -1

    /**
     * Hipotezy z Bundle silnika, przepuszczone przez JEDNA polityke ([uzyteczneHipotezy]).
     * Wynik czesciowy i koncowy czytamy tak samo — inaczej "nic nie przyszlo" znaczyloby
     * co innego w kazdym z callbackow, a to wlasnie ta rozbieznosc dala silnikowi oddajacemu
     * same puste ciagi nieskonczony kredyt zaufania.
     */
    private fun hipotezy(b: Bundle?): List<String> =
        uzyteczneHipotezy(b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))

    /** Silnik dal znak zycia — watchdog schodzi z posterunku do nastepnego startu. */
    private fun zyje() {
        czekamNaSilnik = false
        handler.removeCallbacks(watchdog)
    }

    /**
     * Sluchacz JEDNEGO silnika: pamieta [pokolenie], w ktorym powstal, i odrzuca wszystko,
     * co przyjdzie po zmianie silnika albo po wylaczeniu nasluchu. Do 0.9.10 sluchacz byl
     * jeden na cala klase, wiec spozniony sygnal od zniszczonego silnika trafial na konto
     * nowego — patrz opis przy [pokolenie].
     */
    private fun sluchacz(pok: Int): RecognitionListener = object : RecognitionListener {

        /** Sygnal od silnika, ktorego juz nie uzywamy, albo po wylaczeniu nasluchu. */
        private fun obcy(): Boolean = pok != pokolenie || !wantListening

        override fun onReadyForSpeech(params: Bundle?) {
            // Spozniona gotowosc od zniszczonego silnika kasowala status ustawiony przez
            // wyczerpane() — na ekranie zostawala pustka zamiast komunikatu o awarii.
            if (obcy()) {
                log("onReadyForSpeech pominiety (pokolenie " + pok + "/" + pokolenie + ")")
                return
            }
            log("onReadyForSpeech silnik=" + silnikOpis)
            dziennik.dodaj(System.currentTimeMillis(), "gotowy")
            // Gotowosc NIE zeruje licznika ERROR_AUDIO: silnik z mikrofonem zajetym przez inna
            // aplikacje potrafi zglaszac onReadyForSpeech i zaraz ERROR_AUDIO, w kolko — licznik
            // nigdy nie doszedlby do limitu. Zeruje go dopiero dowod, ze dzwiek plynie (wynik
            // albo pelny cykl nasluchu bez mowy).
            zyje(); dzialal = true
            // Gotowosc zdjela straznik uruchomienia, wiec od tej chwili pilnuje krotki straznik
            // dzwieku. Bez niego jedynym limitem zostaje straznik cyklu (15 s) — dluzej, niz
            // ktokolwiek czeka, patrzac na napis "mikrofon gotowy".
            uzbrojStraznikaDzwieku()
            // Podpowiedz o gluchym mikrofonie ma zostac, dopoki cos nie wejdzie — nasluch trwa dalej.
            if (!cisza.active && bledyAudio == 0 && !mikrofonWyciszony()) status = ""
        }
        override fun onBeginningOfSpeech() {
            if (obcy()) return
            zyje(); dzwiekDochodzi()
            log("onBeginningOfSpeech")
        }

        override fun onRmsChanged(rmsdB: Float) {
            if (obcy()) return
            zyje(); dzwiekDochodzi(); cisza.onRms(rmsdB)
            // Silnik raportuje poziom kilkadziesiat razy na sekunde — do logcata idzie co
            // RMS_CO probka albo wyrazne nowe maksimum. Poziom jest KLUCZOWY dla tego
            // zgloszenia: rozstrzyga, czy do silnika w ogole cokolwiek wchodzi.
            probekRms += 1
            val noweMaksimum = rmsdB > logMaxRms + 1f
            if (noweMaksimum) logMaxRms = rmsdB
            if (noweMaksimum || probekRms % RMS_CO == 0) {
                log("onRmsChanged #" + probekRms + " " + "%.1f".format(rmsdB) +
                    " dB (max " + "%.1f".format(cisza.maxRms) + ")")
            }
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            if (obcy()) return
            log("onEndOfSpeech (probek RMS " + probekRms + ", max " + "%.1f".format(cisza.maxRms) + ")")
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            if (obcy()) return
            // Znak zycia jest BEZWARUNKOWY — silnik faktycznie odpowiada, wiec watchdog
            // pierwszego zdarzenia nie ma juz czego pilnowac. Ale HISTORIA NIEPOWODZEN
            // (dzialal, pusteCykle) czeka na TRESC: silnik bez modelu jezyka potrafi na kazdy
            // cykl oddac jeden PUSTY wynik czesciowy i zaraz NO_MATCH. Zerowanie licznika tutaj
            // kasowalo mu wtedy kazdy cykl, wiec progi PUSTE_Z_MOWA / PUSTE_BEZ_MOWY nie byly
            // osiagalne NIGDY i drabinka stala w miejscu przy zapalonym pasku.
            zyje()
            // Do logu tylko LICZBA alternatyw. Tresci wypowiedzi nie logujemy nigdy.
            log("onPartialResults alternatyw=" + ileAlternatyw(partialResults))
            val uzyteczne = hipotezy(partialResults)
            if (uzyteczne.isEmpty()) return
            dzialal = true
            pusteCykle = 0
            // Silnik, ktory sypie wynikami czesciowymi Z TRESCIA, PRACUJE: straznik cyklu dostaje
            // nowy limit, zeby dluga wypowiedz nie wygladala jak zawis i nie zjadala szczebla.
            uzbrojStraznikaCyklu()
            deliver(uzyteczne, final = false)
        }

        override fun onResults(results: Bundle?) {
            if (obcy()) {
                log("onResults pominiety (pokolenie " + pok + "/" + pokolenie + ")")
                return
            }
            // `dzialal` to znak zycia i tyle — dowodem rozpoznania jest oddalWynik, ustawiany
            // w deliver() dopiero dla listy Z TRESCIA. Pusty onResults nie moze uchodzic za sukces.
            zyje(); zdejmijStraznikaCyklu(); dzialal = true
            log("onResults alternatyw=" + ileAlternatyw(results) +
                " dlugosc pierwszej=" + dlugoscPierwszej(results))
            val uzyteczne = hipotezy(results)
            if (uzyteczne.isEmpty()) {
                // Pusty wynik koncowy to to samo co NO_MATCH — czesc silnikow zglasza brak
                // dopasowania wlasnie tak, bez zadnego kodu bledu. Idzie wiec ta sama droga,
                // lacznie z eskalacja; wpis w dzienniku nie moze brzmiec "wynik", gdy wyniku
                // nie bylo, bo wtedy diagnostyka klamie akurat tam, gdzie ma pomoc.
                pustyCykl(SpeechRecognizer.ERROR_NO_MATCH, "pusty wynik")
                return
            }
            dziennik.dodaj(System.currentTimeMillis(), "wynik")
            pusteCykle = 0
            slyszalMowe = false
            deliver(uzyteczne, final = true)
            restartSoon()
        }

        override fun onError(error: Int) {
            // Po stop() ORAZ po zmianie silnika usluga potrafi jeszcze odbic bledem (klasycznie
            // ERROR_CLIENT po cancel/destroy). Bez tego strazu spozniony kod schodzil drabinke
            // za NOWY silnik — dwa szczeble na jedna awarie — i gasil glos na cala rozgrywke.
            if (obcy()) {
                log("onError " + nazwaBledu(error) + " spozniony (pokolenie " +
                    pok + "/" + pokolenie + ") — pomijam")
                return
            }
            zyje()
            // Blad konczy cykl tak samo jak wynik. Straznika zdejmujemy TU, a nie w zyje():
            // gotowosc i strumien RMS nie sa dowodem, ze silnik cokolwiek odda.
            zdejmijStraznikaCyklu()
            val reakcja = reakcjaNaBlad(error)
            ostrzez("onError " + nazwaBledu(error) + " -> " + reakcja + " silnik=" + silnikOpis +
                " offline=" + preferOffline + " znakZycia=" + dzialal +
                " oddalTekst=" + oddalWynik + " puste=" + pusteCykle)
            when (reakcja) {
                // Cisza i brak dopasowania sa normalne przy ciaglym nasluchu. Ale dwa puste cykle
                // z rzedu przy RMS na dnie to mikrofon, ktory nic nie slyszy — podpowiadamy
                // (przelacznik w ustawieniach szybkich, inna aplikacja), nasluch trwa dalej.
                ReakcjaNaBlad.PUSTY_CYKL -> pustyCykl(error, "pusto (" + error + ")")

                // Zajety silnik: kilka ponowien ma sens (poprzednia sesja jeszcze sie sprzata),
                // ale nie w nieskonczonosc z zapalonym "SŁUCHAM…" — po limicie traktujemy to jak
                // kazda inna awarie i schodzimy drabinka nizej.
                ReakcjaNaBlad.ZAJETY -> {
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
                ReakcjaNaBlad.ZGODA -> {
                    ostatniBlad = error
                    if (maZgode()) {
                        zanotujProbe(silnikOpis + ":" + error)
                        if (!escalate()) wyczerpane(friendlyError(error))
                    } else {
                        status = zloz(tekstBrakZgody, error)
                        stop()
                        onBrakZgody?.invoke()
                    }
                }

                // Blad nagrywania (3): mikrofon zajety przez inna aplikacje albo chwilowo niedostepny.
                // Jedna szybka ponowka; potem podpowiedz i rzadsze proby, az mikrofon sie zwolni
                // albo uczen sam wylaczy nasluch. Limit, zeby nie mielic w nieskonczonosc.
                ReakcjaNaBlad.NAGRYWANIE -> {
                    ostatniBlad = error
                    bledyAudio += 1
                    when {
                        bledyAudio == 1 -> scheduleRestart(AUDIO_RETRY_MS)
                        bledyAudio < MAX_AUDIO_RETRIES -> { status = tekstCiszy; scheduleRestart(AUDIO_RETRY_SLOW_MS) }
                        else -> { status = tekstCiszy; stop() }
                    }
                }

                // BRAK PAKIETU JEZYKA (12 LANGUAGE_NOT_SUPPORTED / 13 LANGUAGE_UNAVAILABLE).
                // Na Androidzie 13+ tak wyglada telefon, na ktorym nie ma POBRANEGO polskiego:
                // silnik jest, tylko nie ma czym rozpoznawac. Do 0.9.10 kody te wpadaly do worka
                // "kazdy inny blad" — drabinka schodzila, ale niosla ze soba to, co wlasnie
                // zostalo odrzucone: wymuszony tryb offline i pelna liste podpowiedzi. Najpierw
                // wiec zdejmujemy podpowiedzi (offline zdejmie sam szczebel 2 w escalate),
                // a dopiero potem schodzimy. Komunikat pokazujemy DOPIERO na koncu drabinki.
                ReakcjaNaBlad.BRAK_JEZYKA -> {
                    ostatniBlad = error
                    zanotujProbe(silnikOpis + ":" + error)
                    bezBiasingu = true
                    if (escalate()) status = tekstInnySilnik
                    else wyczerpane(friendlyError(error))
                }

                // Kazdy inny blad (ERROR_CLIENT=5, ERROR_SERVER, siec…) najpierw probujemy
                // obejsc, schodzac na nastepny silnik. Dopiero gdy drabinka sie skonczy —
                // pokazujemy komunikat i oddajemy decyzje ekranowi.
                ReakcjaNaBlad.INNY -> {
                    ostatniBlad = error
                    // Silnik SPRAWDZONY (w tej sesji oddal juz tekst) nie traci szczebla za jeden
                    // CLIENT / SERVER / blad sieci: to zwykle czkawka po cancel()+startListening
                    // (czesc uslug odbija wtedy ERROR_CLIENT) albo chwilowy brak zasiegu. Zejscie
                    // drabinka kosztowaloby go szczebel i slownik do konca sesji — dokladnie temu,
                    // komu glos wlasnie dzialal. Nowe wiazanie i ponowienie, skonczona liczbe razy.
                    if (oddalWynik && bledyPrzejsciowe < MAX_PRZEJSCIOWE) {
                        bledyPrzejsciowe += 1
                        ostrzez("blad " + nazwaBledu(error) + " na sprawdzonym silniku " + silnikOpis +
                            " — ponowienie " + bledyPrzejsciowe + "/" + MAX_PRZEJSCIOWE + " bez zejscia drabinka")
                        dziennik.dodaj(System.currentTimeMillis(), "ponowienie po " + error)
                        odtworzRecognizer()
                        scheduleRestart(PRZEJSCIOWY_MS)
                    } else {
                        zanotujProbe(silnikOpis + ":" + error)
                        if (!escalate()) wyczerpane(friendlyError(error))
                    }
                }
            }
        }
    }

    /**
     * Cykl, w ktorym silnik przyjal dzwiek i NIE oddal tresci. JEDNA droga dla dwoch objawow
     * tej samej awarii:
     *  - kod bledu NO_MATCH / SPEECH_TIMEOUT (galaz [ReakcjaNaBlad.PUSTY_CYKL]),
     *  - pusty wynik KONCOWY — czesc silnikow zglasza brak dopasowania wlasnie tak, bez zadnego
     *    kodu bledu (ekran testu mowy czyta to identycznie — patrz [VoiceProbe]).
     *
     * Do 0.9.11 druga droga nie istniala: pusty final zerowal licznik pustych cykli, zapisywal
     * sie w dzienniku jako "wynik" i konczyl zwyklym restartem. Silnik oddajacy same puste listy
     * nie schodzil wiec drabinka NIGDY — pasek swiecil "SŁUCHAM…", watchdog byl zdjety przez
     * zyje(), straznik cyklu zdjety w onResults, a licznik stal na zerze. Nie ma powodu, zeby
     * te dwie drogi kiedykolwiek sie rozjechaly, wiec jest jedna funkcja, a nie kopia kodu.
     *
     * @param kod kod do historii prob i do [ostatniBlad]; pusty wynik podstawia ERROR_NO_MATCH,
     *        bo znaczy dokladnie to samo
     * @param powod poczatek wpisu w dzienniku — ma odrozniac "pusto (7)" od "pusty wynik",
     *        inaczej diagnostyka mowi o bledzie, ktorego nie bylo
     */
    private fun pustyCykl(kod: Int, powod: String) {
        bledyAudio = 0
        pusteCykle += 1
        // Mowa, nie szum: prog MOWA_DB, nie prog gluchego mikrofonu (patrz SilenceDetector).
        if (cisza.maxRms > SilenceDetector.MOWA_DB) slyszalMowe = true
        dziennik.dodaj(
            System.currentTimeMillis(),
            powod + ", RMS " + "%.1f".format(cisza.maxRms) + ", cykl " + pusteCykle,
        )
        if (cisza.onEmptyCycle()) status = tekstCiszy
        // Cisza jest normalna TYLKO na silniku, ktory juz raz cos rozpoznal. Silnik, ktory nie
        // rozpoznal jeszcze niczego i kolejny raz z rzedu mowi "nic nie slyszalem", w praktyce
        // nie ma modelu tego jezyka albo odmawia pracy offline (zgloszenie z Motoroli: przycisk
        // dziala, zero bledow, zero reakcji na mowe). Zamiast krecic sie w kolko schodzimy nizej.
        // oddalWynik, nie dzialal: patrz komentarz przy eskalowacPoPustym.
        if (eskalowacPoPustym(oddalWynik, pusteCykle, slyszalMowe)) {
            pusteCykle = 0
            ostatniBlad = kod
            zanotujProbe(silnikOpis + ":nic")
            ostrzez("puste cykle bez rozpoznania — schodze drabinka")
            if (escalate()) status = tekstInnySilnik
            else wyczerpane(tekstBrakMowy)
            return
        }
        restartSoon()
    }

    /** Kolejny szczebel fallbacku. Zwraca true, jesli ponowiono nasluch innym silnikiem. */
    /**
     * Kody, ktore znacza dokladnie jedno: silnik nie ma modelu dla podanego tagu jezyka.
     * Liczby, a nie stale z [SpeechRecognizer]: ERROR_LANGUAGE_NOT_SUPPORTED (12) i
     * ERROR_LANGUAGE_UNAVAILABLE (13) doszly w API 33, a reszta pliku tez adresuje je wprost
     * (patrz friendlyError i ReakcjaNaBlad.BRAK_JEZYKA) - trzymamy jedna konwencje.
     */
    private fun brakModeluJezyka(kod: Int): Boolean = kod == 12 || kod == 13

    private fun escalate(): Boolean {
        // Eskalacja po wylaczonym nasluchu nie ma sensu, ale MUSI byc widoczna w logu: to jest
        // miejsce, w ktorym drabinka cicho staje, gdy kolejnosc wywolan jest zla (patrz start()).
        if (!wantListening) {
            ostrzez("escalate: nasluch juz wylaczony — nie schodze")
            return false
        }
        // 1) on-device odmawia -> sprobuj silnika sieciowego (zwykle Google), bez offline.
        if (mode == VoiceMode.ON_DEVICE && !attemptedOnline &&
            SpeechRecognizer.isRecognitionAvailable(context)
        ) {
            attemptedOnline = true
            log("escalate 1/3: on-device -> sieciowy (bez offline)")
            useEngine(VoiceMode.ONLINE, offline = false)
            scheduleRestart(60L)
            return true
        }
        // 2) silnik sieciowy z wymuszonym offline -> ponow bez offline (pozwol na siec).
        //    Do 0.9.10 ten szczebel TYLKO przestawial flage: nie tworzyl nowego rozpoznawacza
        //    (a czesc silnikow czyta EXTRA_PREFER_OFFLINE raz, przy pierwszym startListening),
        //    nie kasowal podpowiedzi slownikowych i nie zerowal licznikow. Na telefonie, ktory
        //    startuje w trybie ONLINE — czyli na wiekszosci — obiecane "wylaczamy podpowiedzi
        //    po pierwszej eskalacji" nie dzialo sie wiec nigdy. Teraz idzie ta sama droga
        //    co reszta drabinki, przez useEngine().
        if (mode == VoiceMode.ONLINE && preferOffline) {
            log("escalate 2/3: sieciowy offline -> sieciowy przez siec")
            useEngine(VoiceMode.ONLINE, offline = false)
            scheduleRestart(60L)
            return true
        }
        // 2b) silnik nie ma modelu DLA TEGO TAGU -> sprobuj tego samego jezyka bez regionu.
        //     "en-US" bywa odrzucane przez silnik, ktory ma wylacznie "en" albo "en-GB", i wraca
        //     wtedy ERROR_LANGUAGE_UNAVAILABLE, chociaz angielski rozpoznaje. Jeden szczebel,
        //     jedna proba na sesje - nie przegladamy listy regionow.
        if (!probowanyJezykBezRegionu && brakModeluJezyka(ostatniBlad)) {
            val prostszy = jezykBezRegionu(language)
            if (prostszy.isNotEmpty()) {
                probowanyJezykBezRegionu = true
                log("escalate 2b: jezyk " + language + " -> " + prostszy)
                dziennik.dodaj(System.currentTimeMillis(), "j\u0119zyk bez regionu -> " + prostszy)
                language = prostszy
                // Nowy tag = nowa ocena silnika, tak samo jak w przelaczJezyk().
                dzialal = false
                oddalWynik = false
                pusteCykle = 0
                slyszalMowe = false
                scheduleRestart(60L)
                return true
            }
        }
        // 3) domyslny silnik odmawia -> przechodzimy po KONKRETNYCH uslugach z urzadzenia,
        //    zaczynajac od Google. To jest szczebel, ktorego brakowalo: na czesci telefonow
        //    (Samsung) domyslna usluga nalezy do producenta i nie obsluguje aplikacji trzecich,
        //    a usluga Google siedzi obok i dziala, tylko trzeba sie z nia zwiazac po nazwie.
        if (silniki.isEmpty()) silniki = dostepneSilniki()
        if (silnikIdx + 1 < silniki.size) {
            silnikIdx += 1
            log("escalate 3/3: usluga " + (silnikIdx + 1) + "/" + silniki.size +
                " = " + silniki[silnikIdx].packageName)
            useEngine(VoiceMode.ONLINE, offline = false)
            scheduleRestart(80L)
            return true
        }
        ostrzez("escalate: drabinka wyczerpana (uslug: " + silniki.size + ")")
        return false
    }

    /**
     * Ten sam szczebel, NOWE wiazanie: stary rozpoznawacz idzie do kosza razem ze swoimi
     * spoznionymi sygnalami (nowe pokolenie), a wskazniki oceny silnika zostaja — to nadal
     * ten sam, sprawdzony silnik.
     *
     * Nowy rozpoznawacz powstaje DOPIERO przy wznowieniu ([scheduleRestart]), nie tutaj: od
     * Androida 15 system odlacza usluge natychmiast po zniszczeniu ostatniego klienta, a wiazanie
     * tuz po tym konczy sie ERROR_SERVER_DISCONNECTED (11) — czyli kolejnym bledem przejsciowym,
     * ktory zjadalby ponowienia na wyscigu, a nie na prawdziwej awarii.
     */
    private fun odtworzRecognizer() {
        pokolenie += 1
        zwolnijRecognizer()
        log("odtworzRecognizer silnik=" + silnikOpis + " pokolenie=" + pokolenie + " — nowe wiazanie przy wznowieniu")
    }

    /** Przelaczenie silnika: niszczy obecny recognizer i tworzy nowy pod nowy tryb. */
    private fun useEngine(newMode: VoiceMode, offline: Boolean) {
        bezBiasingu = true   // od drugiego podejscia liczy sie to, zeby w ogole dzialalo
        // Nowe pokolenie PRZED destroy(): od tej chwili sygnaly starego silnika (ERROR_CLIENT
        // po zniszczeniu przychodzi z opoznieniem) trafiaja do kosza, a nie na konto nowego.
        pokolenie += 1
        dziennik.dodaj(System.currentTimeMillis(), "silnik -> " + (if (newMode == VoiceMode.ON_DEVICE) "on-device" else "sieciowy") + (if (offline) " (offline)" else ""))
        try { recognizer?.destroy() } catch (t: Throwable) {
            // Stary silnik i tak porzucamy, ale wyjatek zostawia slad: seria takich linii to
            // silnik, ktory rozpada sie przy kazdej zmianie szczebla.
            ostrzez("useEngine: destroy() rzucil " + t.javaClass.simpleName)
        }
        mode = newMode
        preferOffline = offline
        dzialal = false
        oddalWynik = false
        pusteCykle = 0
        // Nowy silnik nie dziedziczy obserwacji o mikrofonie z poprzedniego cyklu.
        slyszalMowe = false
        bledyPrzejsciowe = 0
        recognizer = create()?.also { it.setRecognitionListener(sluchacz(pokolenie)) }
        log("useEngine tryb=" + mode + " offline=" + preferOffline + " silnik=" + silnikOpis +
            " pokolenie=" + pokolenie + " rozpoznawacz=" + (if (recognizer == null) "NULL" else "ok"))
        // create() = null nie konczy tu niczym: zaplanowany restart zobaczy brak rozpoznawacza
        // i zejdzie o kolejny szczebel (patrz scheduleRestart) — nigdy nie zostaje cisza.
        if (recognizer == null) ostrzez("useEngine: brak rozpoznawacza — restart zejdzie nizej")
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
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> tekstBrakSieci
        // LANGUAGE_UNAVAILABLE / NOT_SUPPORTED. Do 0.9.11 stalo tu "PL" na sztywno, choc blad
        // dotyczy TAGU, o ktory silnik byl wlasnie pytany — a gra startuje rownie czesto
        // "en-US" (jezyk odpowiedzi ustala ekran) i probuje jeszcze tagu bez regionu.
        13, 12 -> {
            val jezyk = jezykDoKomunikatu(language, tekstJezykPolski, tekstJezykAngielski)
            if (jezyk.isEmpty()) tekstBrakPakietu else zloz(tekstBrakPakietuJezyka, jezyk)
        }
        ERROR_WATCHDOG -> zloz(tekstSilnikMilczy, error, silniki.size)
        else -> zloz(tekstGlosNiedostepny, error, silniki.size)
    }

    /**
     * Podstawienie do szablonu komunikatu. Szablony przychodza z ekranu wraz z tlumaczeniem,
     * wiec literowka w "%s" nie moze wywrocic nasluchu wyjatkiem formatera — w najgorszym razie
     * uczen zobaczy zdanie bez kodu bledu, a to nadal lepsze niz zgaszony glos.
     */
    private fun zloz(szablon: String, vararg co: Any?): String =
        try { szablon.format(*co) } catch (_: Throwable) { szablon }

    /**
     * Sukces cyklu: silnik oddal TRESC. Lista jest juz przefiltrowana ([uzyteczneHipotezy]),
     * wiec do ekranu nie ida puste ciagi — inaczej PlayScreen wypisywalby pod paskiem puste
     * cudzyslowy, a VoiceMatch probowalby dopasowywac nic do odpowiedzi na planszy.
     */
    private fun deliver(uzyteczne: List<String>, final: Boolean) {
        if (uzyteczne.isEmpty()) return
        // TU, a nie przy gotowosci: silnik wlasnie zamienil mowe na tekst. Jedyny twardy dowod,
        // ze pracuje — i jedyne, co chroni go przed zejsciem drabinka po milczacym cyklu.
        oddalWynik = true
        cisza.onSpeech()
        bledyAudio = 0 // wynik = dzwiek plynie; dopiero to kasuje serie ERROR_AUDIO
        bledyBusy = 0
        bledyPrzejsciowe = 0
        onHeard?.invoke(uzyteczne, final)
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
        // Znacznik wznowienia. removeCallbacksAndMessages nie dosiega akcji, ktora zdazyla juz
        // wyjsc z kolejki, a poleganie na tym, ze KAZDA odroczona akcja sama sprawdzi
        // wantListening, jest milczaca umowa — wystarczy jedna nowa sciezka, ktora o tym
        // zapomni, i nasluch wskrzesza sie po wyjsciu z ekranu. Znacznik robi z tego warunek,
        // ktory uniewaznia stop()/release() i ktory da sie sprawdzic testem bez urzadzenia.
        val znacznik = wznowienie.zaplanuj()
        handler.postDelayed({
            if (!wznowienie.aktualne(znacznik) || !wantListening) {
                log("restart pominiety — nasluch wylaczony albo zamowienie uniewaznione")
                return@postDelayed
            }
            wznowienie.wykonano(znacznik)
            // Brak rozpoznawacza to zwykle nowe wiazanie po odtworzRecognizer(); tworzymy je tu,
            // po zwloce. Dopiero gdy i to sie nie uda (np. on-device ponizej Androida 12), dalej
            // po drabince.
            if (recognizer == null) {
                recognizer = create()?.also { it.setRecognitionListener(sluchacz(pokolenie)) }
                log("restart: nowe wiazanie silnik=" + silnikOpis + " rozpoznawacz=" +
                    (if (recognizer == null) "NULL" else "ok"))
            }
            val r = recognizer
            if (r == null) {
                ostrzez("restart: brak rozpoznawacza — schodze drabinka")
                if (!escalate()) wyczerpane(friendlyError(ostatniBlad))
                return@postDelayed
            }
            cisza.onCycleStart()
            probekRms = 0
            logMaxRms = Float.NEGATIVE_INFINITY
            log("startListening silnik=" + silnikOpis + " jezyk=" + language +
                " offline=" + preferOffline + " podpowiedzi=" + ilePodpowiedzi())
            try {
                r.cancel()
                wypowiedz += 1
                r.startListening(buildIntent())
            } catch (t: Throwable) {
                // Wyjatek przy starcie nie daje zadnego zdarzenia zwrotnego — watchdog ponizej
                // potraktuje to jak martwy silnik i zejdzie na nastepny szczebel.
                ostrzez("startListening rzucil " + t.javaClass.simpleName + " — czeka watchdog")
            }
            uzbrojWatchdog()
        }, delayMs)
    }

    /** Ile podpowiedzi slownikowych faktycznie poleci do silnika — do logu i diagnostyki. */
    private fun ilePodpowiedzi(): Int =
        if (bezBiasingu || mode != VoiceMode.ON_DEVICE || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) 0
        else minOf(biasing.size, MAX_BIASING_WORDS)

    /**
     * @param kierunek kierunek gry z OPCJI — nie wplywa na nic, ale bez niego z raportu telefonu
     *        nie da sie odczytac, ktory przypadek zaszedl: przy Direction.RANDOM jezyk wybiera
     *        heurystyka planszy, przy pozostalych bierze sie wprost z kierunku.
     */
    fun start(
        language: String,
        biasing: List<String>,
        kierunek: String = "",
        onHeard: (List<String>, Boolean) -> Unit,
    ) {
        // Czy w chwili wejscia cos jeszcze bylo w toku - patrz cancel() nizej.
        val bylNasluch = listening || czekamNaSilnik
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
        oddalWynik = false
        // Nowa sesja zaczyna od PELNEGO zlecenia. bezBiasingu ustawia useEngine() przy pierwszej
        // eskalacji i do 0.9.11 nie wracalo juz nigdy: po jednej wpadce w dowolnej rundzie slownik
        // zestawu znikal do konca zycia ekranu gry, mimo ze drabinka i tryb wracaly do punktu
        // wyjscia. Po przerobieniu szczebla 2 na useEngine() dotyczylo to takze telefonow
        // startujacych w trybie ONLINE, czyli wiekszosci. Nie da sie odroznic "podpowiedzi
        // zdlawily silnik" od "ten silnik byl zepsuty", wiec kazde wlaczenie sprawdza to od nowa
        // — kosztem najwyzej jednej eskalacji.
        bezBiasingu = false
        // Nowe wlaczenie nasluchu to nowy przebieg: licznik pustych cykli, sygnal o mowie
        // i dziennik zaczynaja od zera, zeby diagnostyka pokazywala TE probe, a nie poprzednia.
        pusteCykle = 0
        slyszalMowe = false
        dziennik.reset(System.currentTimeMillis())
        // Kierunek i tag jezyka w PIERWSZEJ linii dziennika: raport z telefonu ma sam rozstrzygac,
        // czy zaszedl przypadek "zly jezyk w trybie losowym", czy zupelnie inna awaria.
        dziennik.dodaj(
            System.currentTimeMillis(),
            "start (" + language + (if (kierunek.isEmpty()) "" else ", " + kierunek) + ", " +
                (if (mode == VoiceMode.ON_DEVICE) "on-device" else "sieciowy") +
                (if (preferOffline) ", offline" else "") + ")",
        )
        this.language = language
        this.biasing = biasing
        this.kierunek = kierunek
        this.onHeard = onHeard
        ostatniStart = android.os.SystemClock.elapsedRealtime()
        log("start jezyk=" + language + " kierunek=" + kierunek + " tryb=" + mode + " offline=" + preferOffline +
            " podpowiedzi=" + ilePodpowiedzi() + " silnik=" + silnikOpis +
            " uslug=" + silniki.size + " sdk=" + Build.VERSION.SDK_INT)
        // wantListening MUSI byc ustawione, ZANIM sprobujemy stworzyc rozpoznawacz: escalate()
        // nie schodzi drabinka przy wylaczonym nasluchu, wiec w starej kolejnosci null z create()
        // szedl prosto w wyczerpane() — caly glos gasl na pierwszym silniku, z ktorym akurat nie
        // dalo sie zwiazac, bez proby nastepnego i bez slowa w logu.
        wantListening = true
        listening = true
        bledyBusy = 0
        bledyPrzejsciowe = 0
        status = ""
        bledyAudio = 0
        cisza.reset()
        probekRms = 0
        logMaxRms = Float.NEGATIVE_INFINITY
        // Zaden restart zamowiony przez poprzednia sesje nie ma wchodzic w te.
        wznowienie.anuluj()
        pokolenie += 1
        probowanyJezykBezRegionu = false
        // JEDEN AKTYWNY PRZEBIEG. Drugie dotkniecie paska, rekompozycja ekranu albo start
        // zamowiony tuz po poprzednim trafialy w rozpoznawacz z OTWARTA sesja, a startListening
        // bez cancel() konczy sie wtedy ERROR_RECOGNIZER_BUSY: nasluch wchodzil w serie ponowien
        // zamiast sluchac. Wznowienie w scheduleRestart robilo cancel() od poczatku - start(),
        // czyli droga wejsciowa z przycisku, nie robil tego nigdy.
        if (bylNasluch) {
            log("start: poprzednia sesja jeszcze zyla - cancel() przed startem")
            try {
                recognizer?.cancel()
            } catch (t: Throwable) {
                ostrzez("start: cancel() rzucil " + t.javaClass.simpleName)
            }
        }
        // MODEL ON-DEVICE. Na API 33+ pytamy silnik on-device, czy ma pobrany model TEGO jezyka,
        // ZANIM zwiazemy sie z nim na sesje. Bez tego pytania on-device bez pakietu melduje
        // gotowosc i milczy (zgloszenie z Motoroli). Sonda w tle (refresh) zwykle juz odpowiedziala;
        // tu czekamy tylko, gdy odpowiedzi jeszcze nie ma — najwyzej MODEL_MS.
        if (mode == VoiceMode.ON_DEVICE && recognizer == null && !modelSprawdzony(language)) {
            czekamNaSilnik = true   // pasek: "uruchamiam mikrofon…"
            log("start: sprawdzam model on-device dla " + language)
            val pok = pokolenie
            sprawdzModel(language, przejmij = true) {
                if (pok != pokolenie || !wantListening) {
                    log("start: sesja zmieniona w trakcie sprawdzania modelu — nie startuje")
                    return@sprawdzModel
                }
                czekamNaSilnik = false
                uruchomNasluch()
            }
            return
        }
        uruchomNasluch()
    }

    /** Druga polowa [start]: wiazanie z silnikiem i startListening. */
    private fun uruchomNasluch() {
        if (!wantListening) return
        if (mode == VoiceMode.ON_DEVICE && modelOnDevice[language] == false) {
            // Usluga on-device jest, modelu tego jezyka nie ma: on-device milczalby. Bez czekania
            // na straznika idziemy do silnika domyslnego — ten sam uzyje modelu offline, gdy tylko
            // zostanie pobrany, a do tego czasu rozpozna przez siec.
            ostrzez("model on-device dla " + language + " nie jest pobrany — start od silnika domyslnego")
            dziennik.dodaj(System.currentTimeMillis(), "bez modelu " + language + " -> sieciowy")
            zwolnijRecognizer()
            mode = VoiceMode.ONLINE
            preferOffline = false
            attemptedOnline = true
            silnikIdx = -1
        }
        if (recognizer == null) recognizer = create()
        val r = recognizer
        if (r == null) {
            ostrzez("start: create() = null przy trybie " + mode + " — schodze drabinka")
            if (!escalate()) wyczerpane(friendlyError(ostatniBlad))
            return
        }
        r.setRecognitionListener(sluchacz(pokolenie))
        // Wyciszenie programowe (AudioManager.isMicrophoneMute) widac od razu — nie trzeba czekac
        // na dwa puste cykle. Przelacznika prywatnosci mikrofonu (Android 12+) publiczne API NIE
        // zdradza: SensorPrivacyManager ma jawnie tylko supportsSensorToggle(), stan jest @SystemApi
        // — ten przypadek lapie dopiero SilenceDetector po RMS.
        if (mikrofonWyciszony()) status = tekstCiszy
        try {
            wypowiedz += 1
            r.startListening(buildIntent())
        } catch (t: Throwable) {
            ostrzez("start: startListening rzucil " + t.javaClass.simpleName + " — schodze drabinka")
            if (!escalate()) wyczerpane(friendlyError(ostatniBlad))
            return
        }
        uzbrojWatchdog()
    }

    /**
     * Zmiana tagu jezyka W TRAKCIE nasluchu — wznowienie tej samej sesji, a nie nowa.
     *
     * W trybie losowym plansza zmienia sklad po kazdym zabitym potworze, wiec przewaga jezykowa
     * potrafi sie odwrocic i ekran musi przestawic silnik. Do 0.9.11 robil to przez stop() +
     * start(), a [start] zaczyna wszystko od zera: kasuje dziennik zdarzen, status i licznik
     * pustych cykli. Dziennik jest JEDYNYM zrodlem nakladki diagnostycznej (dlugie przytrzymanie
     * paska), wiec przelaczenie potrafilo skasowac dokladnie to, po co uczen ja otwieral —
     * a przy okazji cofalo drabinke i mrugalo paskiem.
     *
     * Tu zmienia sie WYLACZNIE tag jezyka: dziennik dostaje wpis, a nie reset; drabinka, status
     * i licznik pustych cykli zostaja. [ODSTEP_JEZYKA_MS] od ostatniego startu chroni przed seria
     * cancel()+startListening() (ERROR_RECOGNIZER_BUSY), gdy przewaga chwieje sie przy kolejnych
     * trafieniach.
     *
     * @return true, jesli jezyk faktycznie sie zmienil (false = ten sam jezyk, nasluch wylaczony
     *         albo za wczesnie po poprzednim starcie — wtedy wolajacy ma sprobowac pozniej)
     */
    fun przelaczJezyk(nowy: String): Boolean {
        if (!wantListening || nowy == language) return false
        val teraz = android.os.SystemClock.elapsedRealtime()
        if (teraz - ostatniStart < ODSTEP_JEZYKA_MS) {
            log("przelaczJezyk " + nowy + " odlozony — od ostatniego startu " + (teraz - ostatniStart) + " ms")
            return false
        }
        language = nowy
        ostatniStart = teraz
        // Zmiana jezyka to dla OCENY silnika nowa sesja: silnik, ktory rozpoznawal po angielsku,
        // po przestawieniu na pl-PL moze nie miec modelu i zamilknac. Gdyby wskazniki zostaly z
        // poprzedniego jezyka (dzialal/oddalWynik = true), zaden straznik nie zszedlby drabinka
        // nizej i nasluch krecilby sie w kolko — dokladnie objaw, ktory naprawiamy.
        // Dziennik, drabinka i status zostaja nietkniete: to nadal ten sam przebieg.
        dzialal = false
        oddalWynik = false
        pusteCykle = 0
        slyszalMowe = false
        cisza.reset()
        probowanyJezykBezRegionu = false
        dziennik.dodaj(System.currentTimeMillis(), "język -> " + nowy)
        log("przelaczJezyk -> " + nowy + " (silnik " + silnikOpis + ", ten sam przebieg)")
        // On-device bez modelu NOWEGO jezyka milczalby (patrz sprawdzModel) — od razu silnik
        // domyslny, zamiast czekac na straznika dzwieku.
        if (mode == VoiceMode.ON_DEVICE && modelOnDevice[nowy] == false) {
            log("przelaczJezyk: brak modelu on-device dla " + nowy + " — silnik sieciowy")
            useEngine(VoiceMode.ONLINE, offline = false)
        }
        // Nowe pokolenie odcina zdarzenia cyklu w starym jezyku; sluchacz musi je znac, inaczej
        // wszystko, co odda biezacy rozpoznawacz, trafiloby do kosza jako "obce".
        pokolenie += 1
        recognizer?.setRecognitionListener(sluchacz(pokolenie))
        scheduleRestart(ZMIANA_JEZYKA_MS)
        return true
    }

    // ---- MODEL ON-DEVICE (API 33+) --------------------------------------------------------

    /** Czy o model dla tego tagu juz pytalismy w tej sesji. */
    private fun modelSprawdzony(tag: String): Boolean = modelOnDevice.containsKey(tag)

    /** Opis wyniku sprawdzenia — do diagnostyki. */
    private fun modelOpis(tag: String): String = when (modelOnDevice[tag]) {
        true -> "zainstalowany"
        false -> "brak"
        null -> "nie sprawdzano"
    }

    /**
     * Pyta silnik on-device o model dla [tag] i wola [dalej] z glownego watku — po odpowiedzi,
     * po bledzie albo po limicie [MODEL_MS] (usluga potrafi nie odpowiedziec wcale). Odpowiedz
     * laduje w [modelOnDevice]; brak odpowiedzi liczy sie jako "brak" — lepiej zaczac od silnika
     * domyslnego niz od tego, ktory moze milczec.
     *
     * @param przejmij true = gdy model jest, rozpoznawacz z tej sondy zostaje rozpoznawaczem sesji
     *        (oszczedza drugie wiazanie); false = sonda w tle, rozpoznawacz zawsze zwalniamy
     */
    private fun sprawdzModel(tag: String, przejmij: Boolean, ponowienie: Boolean = false, dalej: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            modelOnDevice[tag] = false
            dalej()
            return
        }
        val r = try {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } catch (t: Throwable) {
            ostrzez("sprawdzenie modelu: createOnDeviceSpeechRecognizer rzucil " + t.javaClass.simpleName)
            null
        }
        if (r == null) {
            modelOnDevice[tag] = false
            dalej()
            return
        }
        val pok = pokolenie
        var limit: Runnable? = null
        var rozstrzygniete = false
        fun zakoncz(jest: Boolean, opis: String) {
            if (rozstrzygniete) return
            rozstrzygniete = true
            limit?.let { handler.removeCallbacks(it) }
            modelOnDevice[tag] = jest
            log("model on-device (" + tag + "): " + (if (jest) "JEST" else "BRAK") + " — " + opis)
            dziennik.dodaj(
                System.currentTimeMillis(),
                "model " + tag + ": " + (if (jest) "jest" else "brak"),
            )
            val zostaje = przejmij && jest && pok == pokolenie && wantListening && recognizer == null
            if (zostaje) recognizer = r
            else try { r.destroy() } catch (_: Throwable) {}
            dalej()
        }
        limit = Runnable { zakoncz(false, "brak odpowiedzi w " + MODEL_MS + " ms") }
        handler.postDelayed(limit, MODEL_MS)
        try {
            r.checkRecognitionSupport(
                zapytanieOModel(tag),
                ContextCompat.getMainExecutor(context),
                object : RecognitionSupportCallback {
                    override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                        val jest = modelZainstalowany(tag, recognitionSupport.installedOnDeviceLanguages)
                        zakoncz(
                            jest,
                            "zainstalowane=" + recognitionSupport.installedOnDeviceLanguages +
                                " obslugiwane=" + recognitionSupport.supportedOnDeviceLanguages +
                                " w toku=" + recognitionSupport.pendingOnDeviceLanguages +
                                " online=" + recognitionSupport.onlineLanguages,
                        )
                    }

                    override fun onError(error: Int) {
                        // 11 SERVER_DISCONNECTED tuz po poprzednim zwolnieniu tej uslugi to wyscig
                        // wiazania (Android 15+), nie odpowiedz o modelu — jedno ponowienie po przerwie.
                        if (error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED && !ponowienie && !rozstrzygniete) {
                            rozstrzygniete = true
                            limit?.let { handler.removeCallbacks(it) }
                            try { r.destroy() } catch (_: Throwable) {}
                            log("model on-device (" + tag + "): 11 przy sprawdzaniu — ponawiam po przerwie")
                            handler.postDelayed({ sprawdzModel(tag, przejmij, ponowienie = true, dalej = dalej) }, ODSTEP_WIAZANIA_MS)
                            return
                        }
                        zakoncz(false, "blad " + nazwaBledu(error))
                    }
                },
            )
        } catch (t: Throwable) {
            zakoncz(false, "checkRecognitionSupport rzucil " + t.javaClass.simpleName)
        }
    }

    /** Zlecenie, o ktore pytamy: ten sam model i tag co w [buildIntent], bez reszty dodatkow. */
    private fun zapytanieOModel(tag: String): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
        }

    /** Czy sonda w tle zostala juz zlecona — raz na zycie obiektu. */
    private var sondaWTleZlecona = false

    /**
     * Sonda w tle dla obu tagow gry — zeby dotkniecie paska nie czekalo na odpowiedz. Nie wchodzi
     * w droge sesji, ktora juz trwa (ta ma wlasne pytanie w [start]).
     */
    private fun sprawdzModeleWTle() {
        if (sondaWTleZlecona || mode != VoiceMode.ON_DEVICE) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        sondaWTleZlecona = true
        val tagi = JEZYKI_GRY.filter { !modelSprawdzony(it) }
        fun kolejny(i: Int) {
            if (i >= tagi.size || wantListening || modelSprawdzony(tagi[i])) {
                if (i < tagi.size && modelSprawdzony(tagi[i])) kolejny(i + 1)
                return
            }
            // Kazde pytanie to osobne wiazanie tej samej uslugi; przerwa chroni przed wyscigiem
            // odlaczenia (11 SERVER_DISCONNECTED), patrz odtworzRecognizer.
            sprawdzModel(tagi[i], przejmij = false) { handler.postDelayed({ kolejny(i + 1) }, ODSTEP_WIAZANIA_MS) }
        }
        kolejny(0)
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
        ostrzez("wyczerpane: koniec drabinki, ostatni blad " + nazwaBledu(ostatniBlad) +
            " — oddaje decyzje ekranowi")
        stop()
        // Status USTAWIAMY PO stop(): stop() zwieksza pokolenie, wiec spozniona gotowosc od
        // gasnacego silnika nie ma juz jak wyczyscic komunikatu (robila to przez onReadyForSpeech).
        status = komunikat
        onExhausted?.invoke()
    }

    fun stop() {
        log("stop (nasluch byl " + listening + ")")
        // Kto wylaczyl nasluch? W buildzie debug slad stosu — bez niego "stop" w logu z telefonu
        // nie mowi, czy to pauza, zejscie w tlo, dotkniecie paska czy koniec drabinki.
        if (BuildConfig.DEBUG && listening) Log.i(TAG, "stop: wolajacy", Throwable("slad"))
        dziennik.dodaj(System.currentTimeMillis(), "stop")
        wantListening = false
        listening = false
        czekamNaSilnik = false
        // Zaplanowany restart traci waznosc, a nowe pokolenie odcina spoznione sygnaly silnika
        // — inaczej nasluch potrafil odzyc juz po wyjsciu z ekranu.
        wznowienie.anuluj()
        pokolenie += 1
        handler.removeCallbacksAndMessages(null)
        // Samo cancel(): stopListening() po cancel() trafia w usluge bez sesji, a ta odpowiada
        // ERROR_CLIENT. Ten spozniony blad wracal do nas juz PO stop() i konczyl sie zamknieciem
        // calej drabinki (wyczerpany=true) mimo ze uczen tylko wylaczyl nasluch.
        try { recognizer?.cancel() } catch (t: Throwable) {
            ostrzez("stop: cancel() rzucil " + t.javaClass.simpleName)
        }
    }

    fun release() {
        log("release")
        stop()
        zwolnijRecognizer()
        onHeard = null
    }

    private companion object {
        /**
         * JEDEN tag na cala sciezke glosowa. Sesja z podpietym telefonem oglada ja przez
         * `adb logcat -s MaturaVoice` i widzi po kolei: start, gotowosc, poziom mikrofonu,
         * wyniki, kody bledow z nazwami, kazde zejscie drabinka, watchdog i stop.
         * Tresci wypowiedzi w logu nie ma i byc nie moze — tylko liczby i kody.
         *
         * Pelny slad leci TYLKO z buildu debug (patrz [log]); w wersji ze sklepu zostaja same
         * ostrzezenia — kilka linii na rozgrywke zamiast dwoch na sekunde z onRmsChanged.
         */
        const val TAG = VOICE_TAG
        /** Ile razy z rzedu wolno uslyszec "silnik zajety", zanim uznamy to za awarie. */
        const val MAX_BUSY_RETRIES = 5
        /** Jak dlugo wyczerpana drabinka zostaje zamknieta (ms). */
        const val BLOKADA_MS = 25_000L
        /** Co ktora probka RMS trafia do logu (silnik raportuje kilkadziesiat razy na sekunde). */
        const val RMS_CO = 25
        const val MAX_ALTERNATIVES = 6
        const val MAX_PROBOWANE = 12
        /** Ile czekamy na PIERWSZE zdarzenie od silnika po startListening. */
        const val WATCHDOG_MS = 6_000L
        /**
         * Twardy limit JEDNEGO cyklu nasluchu: po tylu ms bez wyniku i bez bledu silnik uchodzi
         * za zawieszony. Silnik Google konczy cykl samej ciszy po ok. 10 s, wiec 15 s nie przerywa
         * niczego, co jeszcze pracuje — a kazdy wynik czesciowy liczy limit od nowa.
         */
        const val CYKL_MS = 15_000L
        /** Zwloka przed wznowieniem nasluchu po zmianie tagu jezyka. */
        const val ZMIANA_JEZYKA_MS = 150L
        const val AUDIO_RETRY_MS = 400L
        const val AUDIO_RETRY_SLOW_MS = 1_500L
        const val MAX_AUDIO_RETRIES = 6
        /** Syntetyczny kod dla watchdoga — poza zakresem kodow SpeechRecognizer (1–15). */
        const val ERROR_WATCHDOG = 99

        /** Ile czekamy na odpowiedz checkRecognitionSupport, zanim uznamy model za brakujacy. */
        const val MODEL_MS = 1_500L
        /** Przerwa miedzy zwolnieniem a ponownym wiazaniem TEJ SAMEJ uslugi (wyscig odlaczenia na Androidzie 15+). */
        const val ODSTEP_WIAZANIA_MS = 400L
        /** Ile bledow przejsciowych z rzedu wolno SPRAWDZONEMU silnikowi, zanim zejdziemy drabinka. */
        const val MAX_PRZEJSCIOWE = 3
        /** Zwloka przed ponowieniem po bledzie przejsciowym — nowe wiazanie potrzebuje chwili. */
        const val PRZEJSCIOWY_MS = 350L

        /**
         * Silnik zglosil gotowosc, ale nie oddal ani jednej probki poziomu dzwieku.
         * Wlasny kod, osobny od kodow Androida — patrz [straznikDzwieku].
         */
        const val ERROR_BEZ_DZWIEKU = 98

        /**
         * Ile czekamy po gotowosci na PIERWSZY znak, ze dzwiek wchodzi.
         *
         * Dwie i pol sekundy, bo tyle mniej wiecej trwa nabranie powietrza i pierwsze slowo,
         * a sprawny silnik raportuje poziom jeszcze zanim gracz otworzy usta. Krocej byloby
         * ryzykowne na wolnym telefonie, dluzej — nie do zniesienia dla gracza.
         */
        const val DZWIEK_MS = 2_500L
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
