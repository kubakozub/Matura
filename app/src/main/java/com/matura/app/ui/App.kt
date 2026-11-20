package com.matura.app.ui

import androidx.activity.compose.BackHandler
import com.matura.app.data.BuiltInSets
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.matura.app.data.Repository
import com.matura.app.model.GameMode
import com.matura.app.model.Options
import com.matura.app.model.Profile
import com.matura.app.model.SaveData
import com.matura.app.model.WordSet
import com.matura.app.learn.Attempt
import com.matura.app.learn.Srs
import com.matura.app.audio.Pronunciation
import com.matura.app.learn.Clock
import com.matura.app.learn.RodzajKroku
import com.matura.app.learn.Sesja
import com.matura.app.learn.Trudne
import com.matura.app.model.Direction
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

sealed interface Screen {
    data object Start : Screen
    data object Menu : Screen
    data object SetPick : Screen
    data class ModePick(val setId: String) : Screen
    data class Play(val setId: String, val mode: GameMode) : Screen
    data object Sets : Screen
    data class Editor(val setId: String?) : Screen
    data object Options : Screen
    data object Achievements : Screen
    data object Scores : Screen
    data object Privacy : Screen
    data object VoiceTest : Screen

    /**
     * Test SUROWEGO mikrofonu — AudioRecord z pominieciem calej warstwy rozpoznawania mowy.
     *
     * Osobny ekran, a nie czesc [VoiceTest], bo odpowiada na INNE pytanie: tamten mierzy silniki,
     * ten mierzy, czy do aplikacji w ogole dochodzi dzwiek. Gdy kazdy silnik milczy, bez tej
     * odpowiedzi nie da sie odroznic zepsutego silnika od mikrofonu oddajacego same zera.
     */
    data object RawMic : Screen
    data object TrudneSlowa : Screen
    data object DzisiejszaSesja : Screen
}

/**
 * Dokad wraca runda, gdy uczen z niej wyjdzie albo nacisnie DALEJ na ekranie wyniku.
 *
 * Runda uruchomiona z dziennej sesji albo z trudnych slow konczyla sie dotad w MENU: uczen
 * musial sam odszukac ekran, z ktorego przyszedl, zeby zobaczyc, co runda zmienila, i isc dalej.
 * Cel powrotu ustawia KAZDE miejsce, ktore startuje runde, a zwykla gra z GRAJ wraca do menu.
 */
private sealed interface PowrotZRundy {
    data object Menu : PowrotZRundy

    /** [krok]: indeks kroku trasy, z ktorego ruszyla runda. Patrz Sesja.poUkonczonejRundzie. */
    data class DzisiejszaSesja(val krok: Int) : PowrotZRundy

    data object TrudneSlowa : PowrotZRundy
}

/**
 * @param zegar zrodlo czasu dla dnia harmonogramu, sesji i zapisu prob. Parametr z domyslnym
 *   zegarem systemowym, zeby test mogl przestawic dobe bez czekania do polnocy.
 */
@Composable
fun MaturaApp(
    repo: Repository,
    onExitApp: () -> Unit,
    zegar: Clock = remember { Clock { System.currentTimeMillis() } },
) {
    var data by remember { mutableStateOf(repo.load()) }
    var stack by remember { mutableStateOf(listOf<Screen>(Screen.Start)) }
    var mixSet by remember { mutableStateOf<com.matura.app.model.WordSet?>(null) }

    val screen = stack.last()
    val str = remember(data.options.uiLanguage) { Str(data.options.uiLanguage) }

    fun push(s: Screen) { stack = stack + s }
    fun pop() { if (stack.size > 1) stack = stack.dropLast(1) }
    fun replaceTop(s: Screen) { stack = stack.dropLast(1) + s }
    // Czy OSTATNI zapis padl. Repository czysci lastSaveError po udanym zapisie, wiec ta
    // flaga sama gasnie, gdy dysk znowu przyjmie dane — nie trzeba jej odklikiwac.
    var bladZapisu by remember { mutableStateOf(false) }

    // Jedyne gardlo odczytu stanu po zmianie. Dlatego TU, a nie w 13 wywolaniach repo:
    // kazde z nich konczy sie refresh(), a te, ktore go celowo pomijaja (zapis proby w
    // trakcie rundy), i tak domykaja sie przez refresh() w finish().
    fun refresh() {
        data = repo.load()
        bladZapisu = repo.lastSaveError != null
    }

    // Dzien harmonogramu dla calej aplikacji.
    //
    // Byl liczony RAZ na zycie Activity, a proces potrafi przezyc noc w tle. Rano sesja i trudne
    // slowa pracowaly wtedy na wczoraj: dzisiejsze powtorki nie byly proponowane, wznawiala sie
    // wczorajsza niedokonczona sesja, a o slowach zaleglych dzis ekran mowil "nastepna powtorka
    // jutro". Teraz to stan odswiezany w dwoch chwilach: gdy aplikacja wraca na wierzch
    // (ON_START nizej) i gdy uczen otwiera z menu sesje albo trudne slowa.
    //
    // Miedzy tymi chwilami stoi w miejscu i to tez jest celowe: polnoc UTC nie przestawia list
    // pod palcami ucznia, ktory wlasnie na nie patrzy, ani trasy, z ktorej wlasnie wyszla runda.
    var dzisiaj by remember { mutableIntStateOf(Srs.dayOf(zegar)) }
    val wlascicielCyklu = LocalLifecycleOwner.current
    DisposableEffect(wlascicielCyklu, zegar) {
        val obserwator = LifecycleEventObserver { _, zdarzenie ->
            if (zdarzenie == Lifecycle.Event.ON_START) dzisiaj = Srs.dayOf(zegar)
        }
        wlascicielCyklu.lifecycle.addObserver(obserwator)
        onDispose { wlascicielCyklu.lifecycle.removeObserver(obserwator) }
    }
    // Klasa Pronunciation istniala juz wczesniej, ale nie miala ANI JEDNEGO wolajacego —
    // "Trudne slowa" sa pierwszym ekranem, ktory czyta haslo na glos. Instancja zyje tak dlugo
    // jak aplikacja i zwalnia zasoby przy wyjsciu z kompozycji.
    val kontekst = LocalContext.current
    val wymowa = remember(kontekst) { Pronunciation(kontekst) }
    DisposableEffect(wymowa) { onDispose { wymowa.release() } }
    // CZYM RUNDA ROZNI SIE OD ZWYKLEJ GRY Z "GRAJ". Piec rzeczy, ustawianych zawsze razem przez
    // [rodzajRundy], zeby zadne miejsce startu rundy nie zostawilo ktorejs po poprzedniej.
    //
    // Spokojna: potworek przy dziale nie zabiera zycia (GameConfig.calm). Tak ida rundy, w ktorych
    // uczen dopiero poznaje slowa: krok sesji z nowymi slowami i cwiczenie trudnych slow.
    var rundaSpokojna by remember { mutableStateOf(false) }
    // Skonczona: zamyka sie sama, gdy kazde slowo dostalo poprawna odpowiedz (GameConfig.finite).
    var rundaSkonczona by remember { mutableStateOf(false) }
    // Kierunek, w ktorym runda ma byc zagrana; null znaczy "wedlug ustawien ucznia". Sesja i lista
    // trudnych slow licza postep w JEDNYM kierunku, wiec runda nie moze losowac go slowo po slowie.
    var kierunekRundy by remember { mutableStateOf<Direction?>(null) }
    var powrotZRundy by remember { mutableStateOf<PowrotZRundy>(PowrotZRundy.Menu) }
    // Plan sesji, z ktorej wyszla biezaca runda; null dla rundy uruchomionej skadinad.
    //
    // Zyje tu, a nie na ekranie sesji, bo ekran sesji jest na czas rundy poza kompozycja.
    // Bez tego dzienna sesja nie dostawala z rundy ani jednej odpowiedzi: pasek postepu stal
    // na zerze przez cala sesje, a podsumowanie pokazywalo "Powtorzone: 0 · Nowe: 0" po
    // przerobieniu calej trasy.
    var planSesji by remember { mutableStateOf<com.matura.app.learn.PlanSesji?>(null) }
    fun rodzajRundy(
        spokojna: Boolean,
        skonczona: Boolean,
        kierunek: Direction?,
        powrot: PowrotZRundy,
        plan: com.matura.app.learn.PlanSesji?,
    ) {
        rundaSpokojna = spokojna
        rundaSkonczona = skonczona
        kierunekRundy = kierunek
        powrotZRundy = powrot
        planSesji = plan
    }
    fun zwyklaRunda() = rodzajRundy(false, false, null, PowrotZRundy.Menu, null)
    // Kierunek listy trudnych slow, z ktorej uczen ruszyl na cwiczenie: po rundzie wraca na te
    // sama liste, a nie na te z ustawien. null (wejscie z menu) znaczy "wedlug ustawien".
    var kierunekTrudnych by remember { mutableStateOf<Direction?>(null) }

    BackHandler(enabled = stack.size > 1) { pop() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
            // Jedyne miejsce, ktore odsuwa tresc od chromu systemu - stad w dol
            // insety sa juz skonsumowane i ekrany moga liczyc wysokosc normalnie.
            // Pasek stanu: bez tego zegarek systemowy lezal na sercach i amunicji.
            // Pasek nawigacji: bez tego dolny przycisk w MENU, OPCJACH, SETS i
            // edytorze wpadal pod pasek gestow - klawiatura radzila sobie sama,
            // reszta ekranow nie.
            // IME: StartScreen i edytor maja prawdziwe pola tekstowe, wiec przy
            // otwartej klawiaturze systemowej tresc musi sie podniesc.
            // Wyciecie w ekranie: w poziomie sprite'y nie wchodza pod kamere.
            .safeDrawingPadding()
    ) {
        when (val s = screen) {
            Screen.Start -> StartScreen(
                str = str,
                profile = data.profile,
                onPlayAnonymously = { name ->
                    repo.setProfile(data.profile.copy(name = name, anonymous = true))
                    refresh()
                    replaceTop(Screen.Menu)
                },
            )

            Screen.Menu -> MenuScreen(
                str = str,
                profile = data.profile,
                onDaily = {
                    zwyklaRunda()
                    dzisiaj = Srs.dayOf(zegar)
                    push(Screen.DzisiejszaSesja)
                },
                onHardWords = {
                    kierunekTrudnych = null
                    dzisiaj = Srs.dayOf(zegar)
                    push(Screen.TrudneSlowa)
                },
                onPlay = { zwyklaRunda(); push(Screen.SetPick) },
                onSets = { push(Screen.Sets) },
                onOptions = { push(Screen.Options) },
                onAchievements = { push(Screen.Achievements) },
                onScores = { push(Screen.Scores) },
                onExit = onExitApp,
            )

            Screen.SetPick -> SetPickScreen(
                str = str,
                sets = data.sets,
                onBack = { pop() },
                onPick = { set ->
                    // Zestaw losowy nie jest zapisany w danych - trzymamy wylosowana
                    // partie tutaj, zeby ModePick i Play widzialy DOKLADNIE ta sama.
                    if (set.id == BuiltInSets.MIX_ID) mixSet = set
                    push(Screen.ModePick(set.id))
                },
            )

            is Screen.ModePick -> ModePickScreen(
                str = str,
                set = if (s.setId == BuiltInSets.MIX_ID) mixSet else data.sets.firstOrNull { it.id == s.setId },
                onBack = { pop() },
                onPickMode = { mode -> replaceTop(Screen.Play(s.setId, mode)) },
            )

            is Screen.Play -> {
                val set = if (s.setId == BuiltInSets.MIX_ID) mixSet else data.sets.firstOrNull { it.id == s.setId }
                if (set == null) {
                    pop()
                } else {
                    // Wszystkie tryby maja ta sama sygnature ekranu; tryb doklejamy
                    // do rekordu tutaj, wiec same ekrany nie musza go znac.
                    // Wyjscie w POLOWIE rundy tez musi przeladowac dane: odpowiedzi poszly juz
                    // do zapisu (postep i slad w dziennej sesji), a ekran sesji czyta je wprost
                    // z `data`. Bez tego uczen, ktory wyszedl z rundy przyciskiem, wracal do
                    // sesji z licznikiem sprzed rundy. Odswiezenie jest tu bezpieczne, bo gra
                    // wlasnie sie konczy - w TRAKCIE rundy byloby zabojcze, patrz komentarz
                    // przy zapiszProbe.
                    //
                    // Runda wraca TAM, SKAD RUSZYLA (patrz PowrotZRundy). Ta sama droga obsluguje
                    // systemowe "wstecz", wyjscie z ekranu wyniku i przycisk DALEJ.
                    val exit: () -> Unit = {
                        refresh()
                        stack = when (powrotZRundy) {
                            PowrotZRundy.Menu -> listOf(Screen.Menu)
                            is PowrotZRundy.DzisiejszaSesja -> listOf(Screen.Menu, Screen.DzisiejszaSesja)
                            PowrotZRundy.TrudneSlowa -> listOf(Screen.Menu, Screen.TrudneSlowa)
                        }
                    }
                    // JEDNO wejscie zapisu proby dla wszystkich czterech trybow. Ekrany dostaja
                    // zwykla lambde, wiec zadne z nich nie siega do repozytorium samo i kazde
                    // zostaje testowalne bez Androida.
                    //
                    // refresh() po zapisie jest CELOWO pominiete: przeladowanie SaveData w trakcie
                    // rundy podmienialoby `data.options` i mape postepu, a te sa kluczami remember
                    // w ekranach gry — czyli kazda poprawna odpowiedz kasowalaby trwajaca gre.
                    // Ekran wyniku i tak czyta swiezy stan, bo finish() wola refresh().
                    //
                    // Runda z dziennej sesji domyka petle TU: Attempt niesie juz i identyfikator
                    // wpisu, i wynik, a stan sesji lezy w zapisie (ekran sesji jest zdjety ze
                    // stosu). Slad idzie tym samym zapisem co postep — osobne setSesja
                    // podwajaloby zapis calego dokumentu po kazdej odpowiedzi.
                    val zapiszProbe: (Attempt) -> Unit = { proba ->
                        val plan = planSesji
                        repo.recordAttempt(
                            proba,
                            zegar.nowMs(),
                            if (plan == null) null else { stan ->
                                Sesja.zaliczOdpowiedz(stan, plan, proba.entryId, proba.outcome)
                            },
                        )
                    }
                    // Dzien harmonogramu ustalamy RAZ, przy wejsciu do rundy. Gdyby liczyl sie na
                    // biezaco, przekroczenie polnocy UTC w trakcie gry przestawiloby plan rundy.
                    // Osobny od dnia aplikacji: runda bierze dzien z chwili, w ktorej ruszyla.
                    val dzienRundy = Srs.dayOf(zegar)
                    val finish: (com.matura.app.model.ScoreRecord) -> Unit = { record ->
                        // Rundy spokojnej nie da sie przegrac, wiec jej wynik nie staje obok
                        // prawdziwych gier ani nie odblokowuje odznak za punkty i serie.
                        repo.recordGame(record.copy(mode = s.mode), set.id, doRankingu = !rundaSpokojna)
                        refresh()
                    }
                    // UKONCZONA runda sesji sama przesuwa trase o krok: uczen wraca na ekran sesji
                    // i ma juz przed soba nastepny punkt, zamiast szukac przycisku DALEJ. Runda
                    // przegrana albo porzucona tu nie trafia (PlayScreen wola to tylko po
                    // ukonczeniu), wiec jej krok zostaje biezacy. Plan jest tym, z ktorym runda
                    // ruszyla, a krok tym, z ktorego ruszyla: druga ukonczona runda tego samego
                    // kroku (JESZCZE RAZ) niczego juz nie przesuwa.
                    val ukonczona: () -> Unit = {
                        val powrot = powrotZRundy
                        val plan = planSesji
                        val zapisany = repo.load().sesja
                        if (powrot is PowrotZRundy.DzisiejszaSesja && plan != null && zapisany != null) {
                            val po = Sesja.poUkonczonejRundzie(zapisany, plan, powrot.krok)
                            if (po != zapisany) {
                                repo.setSesja(po)
                                refresh()
                            }
                        }
                    }
                    // Runda idzie w kierunku, dla ktorego ulozono plan sesji albo liste trudnych
                    // slow. Przy ustawieniu "losowo" silnik rzucal moneta dla kazdego slowa, wiec
                    // polowa odpowiedzi ladowala pod drugim kierunkiem: sesja ich nie widziala
                    // i planowala te same slowa od nowa jako nigdy nieprobowane.
                    val opcjeRundy = kierunekRundy?.let { data.options.copy(direction = it) } ?: data.options
                    when (s.mode) {
                        GameMode.CLASSIC -> PlayScreen(
                            set = set, options = opcjeRundy, str = str,
                            playerName = data.profile.name, onExit = exit, onFinished = finish,
                            // Gra moze sama wlaczyc klawiature telefonu (awaryjna droga glosu);
                            // zapis ta sama droga co z ekranu OPCJE, wiec zostaje na kolejne gry.
                            // Kierunek wraca przy tym do ustawienia ucznia: gra oddaje opcje
                            // RUNDY, a kierunek rundy z sesji nie jest jego wyborem.
                            onOptionsChange = { o: Options ->
                                repo.setOptions(o.copy(direction = data.options.direction))
                                refresh()
                            },
                            progress = data.progress,
                            today = dzienRundy,
                            calm = rundaSpokojna,
                            finite = rundaSkonczona,
                            onCompleted = ukonczona,
                            // Z rundy, ktora jest krokiem sesji albo cwiczeniem, idzie sie DALEJ
                            // (tam, skad przyszla), a nie do menu.
                            onContinue = if (powrotZRundy == PowrotZRundy.Menu) null else exit,
                            // Komunikat o nieudanym zapisie pokazuje tu ekran gry, a nie karta
                            // na dole okna (patrz koniec tej funkcji).
                            uwaga = if (bladZapisu) str.saveFailed else null,
                            onAttempt = zapiszProbe,
                        )
                        GameMode.TRUE_FALSE -> TrueFalseScreen(
                            set = set, options = data.options, str = str,
                            playerName = data.profile.name, onExit = exit, onFinished = finish,
                            onAttempt = zapiszProbe,
                        )
                        GameMode.MULTIPLE_CHOICE -> MultipleChoiceScreen(
                            set = set, options = data.options, str = str,
                            playerName = data.profile.name, onExit = exit, onFinished = finish,
                            onAttempt = zapiszProbe,
                        )
                        GameMode.TIME_ATTACK -> TimeAttackScreen(
                            set = set, options = data.options, str = str,
                            playerName = data.profile.name, onExit = exit, onFinished = finish,
                            onAttempt = zapiszProbe,
                        )
                    }
                }
            }

            Screen.TrudneSlowa -> TrudneSlowaScreen(
                teksty = TrudneTeksty(
                    tytul = str.hardWords,
                    wroc = str.back,
                    wszystkieDzialy = str.filterAll,
                    mojeZestawy = str.ownSets,
                    kierunekHasloZnaczenie = str.dirTermToDef,
                    kierunekZnaczenieHaslo = str.dirDefToTerm,
                    etykietaLiczby = str.hardWordsCount,
                    powodZalegle = str.hardReasonDue,
                    powodCofniete = str.hardReasonBackToStart,
                    powodPomylki = str.hardReasonMistakes,
                    powodPodpowiedzi = str.hardReasonHints,
                    etykietaPomylki = str.hardCountMistakes,
                    etykietaPodpowiedzi = str.hardCountHints,
                    etykietaPoTerminie = str.hardOverdueDays,
                    terminDzis = str.hardDueToday,
                    wymowa = str.hardListen,
                    cwicz = str.hardPractice,
                    cwiczWszystkie = str.hardPracticeAll,
                    pustoBrakHistorii = str.hardEmptyNoHistory,
                    pustoNicTrudnego = str.hardEmptyNothingHard,
                ),
                zestawy = data.sets,
                postep = data.progress,
                dzis = dzisiaj,
                onBack = { pop() },
                onWymowa = { slowo -> wymowa.play(slowo, data.options.soundEnabled) },
                // Lista otwiera sie w kierunku ucznia ("losowo" rozstrzyga ta sama regula, ktora
                // ma lista), a po cwiczeniu w tym, w ktorym uczen ja zostawil.
                kierunekPoczatkowy = kierunekTrudnych ?: Trudne.kierunekDoListy(data.options.direction),
                onCwicz = { trudne ->
                    // Jedno slowo do poprawki gramy w malym towarzystwie. Samo wracaloby na
                    // plansze, gdy tylko zgasnie jego odpowiedz, i uczen wpisywalby je z pamieci
                    // chwili. W towarzystwie wraca po slowach, ktore jeszcze czekaja; ile to
                    // naprawde daje, mowi SLOW_W_CWICZENIU.
                    // Do rundy ida tylko slowa, ktore da sie wpisac (patrz doRundyPisanej): runda
                    // cwiczenia konczy sie dopiero odpowiedziami, wiec slowo nie do wpisania
                    // trzymaloby w niej ucznia bez konca.
                    mixSet = BuiltInSets.randomMix().let { mix ->
                        mix.copy(
                            entries = doRundyPisanej(
                                (listOf(trudne.entry) + mix.entries).distinct(),
                                trudne.kierunek,
                                data.options.systemKeyboard,
                            ).take(SLOW_W_CWICZENIU),
                        )
                    }
                    kierunekTrudnych = trudne.kierunek
                    // Cwiczenie trudnego slowa to NIE jest krok sesji (plan = null) - inaczej
                    // odpowiedzi z niego doliczalyby sie do dzisiejszej trasy. Gra sie je
                    // w kierunku listy, na ktora uczen patrzy: to w nim to slowo jest trudne.
                    rodzajRundy(
                        spokojna = true,
                        skonczona = true,
                        kierunek = trudne.kierunek,
                        powrot = PowrotZRundy.TrudneSlowa,
                        plan = null,
                    )
                    push(Screen.Play(BuiltInSets.MIX_ID, GameMode.CLASSIC))
                },
                onCwiczWszystkie = { lista ->
                    // Cala lista jest w jednym kierunku, wiec wystarczy zapytac pierwsze slowo.
                    val kierunekListy = lista.firstOrNull()?.kierunek
                    mixSet = BuiltInSets.randomMix().copy(
                        entries = doRundyPisanej(
                            lista.map { it.entry },
                            kierunekListy ?: Direction.TERM_TO_DEF,
                            data.options.systemKeyboard,
                        ).take(20),
                    )
                    kierunekTrudnych = kierunekListy
                    rodzajRundy(
                        spokojna = true,
                        skonczona = true,
                        kierunek = kierunekListy,
                        powrot = PowrotZRundy.TrudneSlowa,
                        plan = null,
                    )
                    push(Screen.Play(BuiltInSets.MIX_ID, GameMode.CLASSIC))
                },
            )

            Screen.DzisiejszaSesja -> {
                // Kierunek losowy to USTAWIENIE, nie kierunek — sesja musi go rozstrzygnac,
                // zanim ulozy plan (Sesja.zaplanuj wprost tego pilnuje).
                val kierunek = if (data.options.direction == Direction.RANDOM) {
                    Direction.TERM_TO_DEF
                } else {
                    data.options.direction
                }
                // Slownik sesji to slowa, na ktore runda umie przyjac odpowiedz. Rundy sesji
                // koncza sie dopiero odpowiedziami, wiec slowo, ktorego nie da sie wpisac
                // klawiatura ucznia (patrz doRundyPisanej), nie wchodzi na trase: runda z nim nie
                // konczylaby sie nigdy, a licznik dnia czekalby na nie do wieczora.
                val idki = remember(data.sets, kierunek, data.options.systemKeyboard) {
                    doRundyPisanej(data.sets.flatMap { it.entries }, kierunek, data.options.systemKeyboard)
                        .map { it.id }.filter { it.isNotBlank() }
                }
                // Plan bierze sie z TRASY zapisanej w stanie sesji, a nie z biezacego harmonogramu:
                // slowo odpowiedziane w rundzie nie znika z trasy i nic nowego na nia nie wchodzi
                // (patrz Sesja.planNaDzis). Na swiezo plan powstaje tylko wtedy, gdy dzisiejszej
                // sesji jeszcze nie ma.
                val plan = remember(data.sesja, data.progress, idki, kierunek, dzisiaj) {
                    Sesja.planNaDzis(data.sesja, data.progress, idki, kierunek, dzisiaj)
                }
                var stanSesji by remember(plan) {
                    mutableStateOf(Sesja.wznow(data.sesja, plan, zegar))
                }
                // WZNOWIONA mowi o chwili WEJSCIA na ekran: czy czekala tu sesja, w ktorej uczen
                // cos juz zrobil. Liczona raz, bo inaczej zapalalaby sie po pierwszym DALEJ.
                val wznowiona = remember(dzisiaj) { Sesja.czyWznawialna(data.sesja, dzisiaj) }
                DzisiejszaSesjaScreen(
                    jezyk = data.options.uiLanguage,
                    plan = plan,
                    stan = stanSesji,
                    podsumowanie = Sesja.podsumuj(plan, stanSesji, data.progress, dzisiaj),
                    czasNaKoniec = Sesja.czasNaKoniec(stanSesji, plan, zegar),
                    wznowiona = wznowiona,
                    // Przerwanie ZAPISUJE stan: brief wymaga, zeby przerwa nie kasowala nauki.
                    // Sesji nietknietej nie ma po co zapisywac: zapis zamrozilby trase od samego
                    // obejrzenia ekranu, a uczen niczego jeszcze na niej nie zrobil. Stan, ktory
                    // lezy juz w zapisie co do pola (kazdy krok zapisuje sie od razu), tez nie
                    // idzie drugi raz na dysk.
                    onPrzerwij = {
                        if (Sesja.czyRuszona(stanSesji) && stanSesji != repo.load().sesja) {
                            repo.setSesja(stanSesji)
                            refresh()
                        }
                        pop()
                    },
                    onKrok = { krok ->
                        if (krok.rodzaj == RodzajKroku.POWTORKA || krok.rodzaj == RodzajKroku.GRA) {
                            val cele = krok.cele.toSet()
                            // To samo slowo bywa w kilku zestawach naraz; na plansze idzie raz.
                            val wpisy = data.sets.flatMap { it.entries }
                                .filter { it.id in cele }
                                .distinctBy { it.id }
                            // Trasa z zapisu ma wlasny kierunek, a slownik sesji odsiano wyzej dla
                            // kierunku z ustawien. Gdy uczen zmienil go w ciagu dnia, te dwa sie
                            // roznia, wiec o tym, co da sie wpisac, pytamy jeszcze raz, juz dla
                            // kierunku, w ktorym runda naprawde pojdzie.
                            val doWpisania = doRundyPisanej(wpisy, plan.kierunek, data.options.systemKeyboard)
                            mixSet = BuiltInSets.randomMix().copy(entries = doWpisania.take(20))
                            // Runda sesji konczy sie sama po przerobieniu slow kroku, jest spokojna
                            // tam, gdzie plan tak mowi, i idzie w kierunku planu. Plan jedzie razem
                            // z nia: bez niego zapiszProbe nie wie, ktore slowo bylo powtorka,
                            // a ktore nowe, i sesja nie policzy niczego.
                            rodzajRundy(
                                spokojna = krok.spokojny,
                                skonczona = true,
                                kierunek = plan.kierunek,
                                powrot = PowrotZRundy.DzisiejszaSesja(stanSesji.krok),
                                plan = plan,
                            )
                            // Od tego zapisu sesja jest zaczeta, a jej trasa zamrozona.
                            repo.setSesja(stanSesji)
                            push(Screen.Play(BuiltInSets.MIX_ID, GameMode.CLASSIC))
                        } else {
                            stanSesji = Sesja.dalej(stanSesji, plan)
                            repo.setSesja(stanSesji)
                            refresh()
                        }
                    },
                    // refresh() po zapisie, bo `data.sesja` to zrodlo planu i stanu przy nastepnym
                    // wejsciu na ekran: bez niego krok pominiety przyciskiem DALEJ wracal jako
                    // biezacy, gdy uczen wyszedl systemowym "wstecz" i otworzyl sesje ponownie.
                    onDalej = {
                        stanSesji = Sesja.dalej(stanSesji, plan)
                        repo.setSesja(stanSesji)
                        refresh()
                    },
                    onZakoncz = {
                        stanSesji = Sesja.zakoncz(stanSesji, plan)
                        // Skonczona sesja przestaje byc "przerwana" — inaczej jutro proponowaloby
                        // sie wznowienie czegos, co juz sie domknelo.
                        repo.setSesja(null)
                        refresh()
                        pop()
                    },
                )
            }

            Screen.Sets -> SetsScreen(
                str = str,
                sets = data.sets,
                onBack = { pop() },
                onCreate = { push(Screen.Editor(null)) },
                onEdit = { set -> push(Screen.Editor(set.id)) },
                onDelete = { set -> repo.deleteSet(set.id); refresh() },
            )

            is Screen.Editor -> SetEditorScreen(
                str = str,
                existing = s.setId?.let { id -> data.sets.firstOrNull { it.id == id } },
                onBack = { pop() },
                onSave = { set -> repo.upsertSet(set); refresh(); pop() },
            )

            Screen.Privacy -> PrivacyScreen(str = str, onBack = { pop() })

            // Ekran diagnostyczny: przechodzi cala matryce silnikow mowy i mowi, ktora
            // konfiguracja dziala na TYM telefonie. Wchodzi sie z OPCJI.
            Screen.VoiceTest -> VoiceTestScreen(str = str, onBack = { pop() })
            Screen.RawMic -> RawMicTestScreen(str = str, onBack = { pop() })

            Screen.Options -> OptionsScreen(
                str = str,
                onPrivacy = { push(Screen.Privacy) },
                onVoiceTest = { push(Screen.VoiceTest) },
                onRawMic = { push(Screen.RawMic) },
                options = data.options,
                onBack = { pop() },
                onChange = { o: Options -> repo.setOptions(o); refresh() },
            )

            Screen.Achievements -> AchievementsScreen(
                str = str,
                profile = data.profile,
                onBack = { pop() },
            )

            Screen.Scores -> ScoresScreen(
                str = str,
                data = data,
                onBack = { pop() },
            )
        }

        // Nad kazdym ekranem, bo zapis pada niezaleznie od tego, gdzie uczen wtedy jest.
        // Na dole, zeby nie zaslaniac planszy ani nagłowka z zyciami.
        //
        // Wyjatek: tryb klasyczny dostaje ten komunikat parametrem i pokazuje go sam. Jego ekran
        // wyniku ma przyciski przypiete do dolu okna, wiec karta stad lezala dokladnie na nich:
        // przy pelnym dysku pierwszy nieudany zapis wychodzi na jaw wlasnie po rundzie i uczen
        // nie widzial ani JESZCZE RAZ, ani wyjscia. O tym, czy stoi plansza, czy wynik, wie tylko
        // ekran gry.
        val komunikatWGrze = screen.let { it is Screen.Play && it.mode == GameMode.CLASSIC }
        if (bladZapisu && !komunikatWGrze) {
            InfoCard(
                str.saveFailed,
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp),
            )
        }
    }
}

/**
 * Ile slow ma runda cwiczenia JEDNEGO trudnego slowa: ono samo i kilka z losowej mieszanki.
 *
 * Runda cwiczenia jest skonczona, czyli trwa, dopoki uczen nie odpowie na kazde slowo. Dawne
 * dwadziescia (wypelniacz dla rundy bez konca) znaczyloby dzis kilka minut i dziewietnascie
 * obcych hasel do zaliczenia po to, zeby poprawic jedno. Szesc daje krotka runde.
 *
 * Trudne slowo, ktore doszlo do dziala, wraca po slowach, ktore jeszcze czekaja na wyjscie,
 * a nigdy wczesniej, niz zgasnie jego odpowiedz na planszy (tego pilnuje silnik). Towarzystwo
 * nie gwarantuje wiec dlugiej przerwy: gdy uczen odpowiedzial juz na wszystkie pozostale slowa,
 * trudne wraca zaraz po zgaszeniu odpowiedzi.
 */
private const val SLOW_W_CWICZENIU = 6

internal fun SaveData.defaultProfile(): Profile = profile

internal fun List<WordSet>.playable(): List<WordSet> =
    // Wiersz jest grywalny tylko, gdy OBIE strony po normalizacji nie sa puste — inaczej
    // silnik dostalby haslo bez akceptowanej odpowiedzi (samo interpunkcja) i utknal.
    filter { set ->
        set.entries.any {
            com.matura.app.game.Matching.normalize(it.term).isNotEmpty() &&
                com.matura.app.game.Matching.normalize(it.definition).isNotEmpty()
        }
    }
