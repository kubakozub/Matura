package com.matura.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.matura.app.R
import com.matura.app.learn.KrokSesji
import com.matura.app.learn.PlanSesji
import com.matura.app.learn.PodsumowanieSesji
import com.matura.app.learn.PostepSesji
import com.matura.app.learn.RodzajKroku
import com.matura.app.learn.Sesja
import com.matura.app.learn.StanSesji
import com.matura.app.model.UiLanguage

/**
 * "Dzisiejsze 5 minut" — ekran prowadzacy przez codzienna sesje.
 *
 * Ekran nie zna repozytorium i nie liczy czasu sam: plan, stan, podsumowanie i informacja
 * "orientacyjny czas minal" wchodza PARAMETRAMI, a wszystko, co ekran robi, wychodzi
 * lambdami. Dzieki temu da sie go obejrzec w tescie bez Androidowego zapisu, a caly zegar
 * zostaje w jednym miejscu (com.matura.app.learn.Sesja).
 *
 * PRZERWANIE nie jest tu wyjatkiem od reguly, tylko normalnym wyjsciem: postep nauki idzie
 * do zapisu po kazdej odpowiedzi, wiec cofniecie sie z ekranu kosztuje najwyzej miejsce w
 * trasie — i to tez wraca, bo [StanSesji] to zwykle dane.
 */
@Composable
fun DzisiejszaSesjaScreen(
    jezyk: UiLanguage,
    plan: PlanSesji,
    stan: StanSesji,
    podsumowanie: PodsumowanieSesji,
    /** Wynik Sesja.czasNaKoniec — liczy go wolajacy, bo tylko on ma zegar. */
    czasNaKoniec: Boolean = false,
    /** Czy weszlismy do przerwanej sesji, a nie do nowej. Zmienia wylacznie podpis. */
    wznowiona: Boolean = false,
    onPrzerwij: () -> Unit,
    /** Uruchom biezacy krok. Rodzaj i [KrokSesji.spokojny] mowia wolajacemu, co odpalic. */
    onKrok: (KrokSesji) -> Unit,
    onDalej: () -> Unit,
    onZakoncz: () -> Unit,
) {
    // Jezyk zamiast gotowego Str, bo teksty sesji zyja na razie w tym pliku (patrz [SesjaStr]);
    // gdy przeniosa sie do Strings.kt, zostanie tu jeden parametr str: Str.
    val str = remember(jezyk) { Str(jezyk) }
    val t = remember(jezyk) { SesjaStr(jezyk) }

    val krok = Sesja.biezacy(stan, plan)
    val postep = Sesja.postep(stan, plan)

    ScreenScaffold(t.sessionTitle, onPrzerwij, str.back) {
        LazyColumn(Modifier.fillMaxSize()) {

            item {
                NaglowekSesji(t = t, plan = plan, wznowiona = wznowiona)
                Spacer(Modifier.height(12.dp))
            }

            if (!plan.pusty) {
                item {
                    PasekPostepu(t = t, plan = plan, stan = stan, postep = postep)
                    Spacer(Modifier.height(12.dp))
                }
            }

            items(plan.kroki.size) { i ->
                WierszKroku(
                    t = t,
                    krok = plan.kroki[i],
                    zrobiony = stan.zakonczona || i < stan.krok,
                    biezacy = !stan.zakonczona && i == stan.krok,
                )
            }

            item { Spacer(Modifier.height(14.dp)) }

            if (czasNaKoniec && !stan.zakonczona) {
                item {
                    // Podpowiedz, nie decyzja: sesja sama sie nie konczy i nigdy nie ucina
                    // rozpoczetej odpowiedzi.
                    InfoCard(t.sessionTimeUp)
                    Spacer(Modifier.height(12.dp))
                }
            }

            item {
                // Brak biezacego kroku znaczy "sesja zakonczona" — wtedy tez pokazujemy
                // podsumowanie, bo to jedyna tresc, ktora po zakonczeniu ma sens.
                if (krok == null || krok.rodzaj == RodzajKroku.PODSUMOWANIE) {
                    KartaPodsumowania(
                        t = t,
                        plan = plan,
                        podsumowanie = podsumowanie,
                        onZakoncz = onZakoncz,
                    )
                } else {
                    PrimaryButton(
                        label = t.sessionStart,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onKrok(krok) },
                    )
                }
            }

            // "DALEJ" istnieje osobno od uruchomienia kroku, bo krok konczy sie na INNYM
            // ekranie (powtorka, runda gry) i uczen wraca tutaj — musi miec czym przejsc
            // do nastepnego punktu trasy, takze wtedy, gdy krok chce pominac.
            if (krok != null && krok.rodzaj != RodzajKroku.PODSUMOWANIE) {
                item {
                    Spacer(Modifier.height(8.dp))
                    GhostButton(
                        label = t.sessionContinue,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onDalej,
                    )
                }
            }

            item {
                Spacer(Modifier.height(14.dp))
                InfoCard(t.sessionPauseNote)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** Naglowek: po co tu jestesmy i ile to mniej wiecej potrwa. */
@Composable
private fun NaglowekSesji(t: SesjaStr, plan: PlanSesji, wznowiona: Boolean) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Palette.Surface)
            .border(3.dp, Palette.SurfaceHigh)
            .padding(14.dp)
    ) {
        Column {
            if (wznowiona) {
                Pill(t.sessionResumed, Palette.Cyan)
                Spacer(Modifier.height(10.dp))
            }
            Text(t.sessionIntro, color = Palette.Muted, fontSize = 12.sp, lineHeight = 17.sp)
            if (plan.pusty) {
                Spacer(Modifier.height(10.dp))
                Text(t.sessionNothingDue, color = Palette.Bone, fontSize = 13.sp, lineHeight = 18.sp)
                return@Column
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PixelImage(pixel(R.drawable.icon_clock), Modifier.size(18.dp))
                Spacer(Modifier.width(9.dp))
                // Minuty licza sie z planu, a plan z liczby slow — zadna liczba na tym ekranie
                // nie jest wpisana z reki. Zaokraglenie w gore, zeby szacunek nie obiecywal
                // mniej, niz wychodzi.
                val minuty = (plan.orientacyjneSekundy + 59) / 60
                PixelText(t.sessionEstimate.replace("%s", minuty.toString()), glyphHeight = 10.dp)
            }
            Spacer(Modifier.height(8.dp))
            Text(t.sessionEstimateNote, color = Palette.Muted, fontSize = 11.sp, lineHeight = 16.sp)
            if (plan.bezHistorii) {
                Spacer(Modifier.height(8.dp))
                Text(
                    t.sessionStarterSet.replace("%s", plan.nowe.size.toString()),
                    color = Palette.Accent,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
        }
    }
}

/** Ktory krok i ile slow za nami. Bez procentow — same liczby, ktore da sie sprawdzic. */
@Composable
private fun PasekPostepu(t: SesjaStr, plan: PlanSesji, stan: StanSesji, postep: PostepSesji) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PixelText(
                t.sessionStepLabel
                    .replace("%1", postep.krokNumer.toString())
                    .replace("%2", postep.krokow.toString()),
                color = Palette.Accent,
                glyphHeight = 10.dp,
            )
            PixelText(
                "${postep.celeZrobione} / ${postep.celeRazem}",
                color = Palette.Muted,
                glyphHeight = 9.dp,
            )
        }
        Spacer(Modifier.height(7.dp))
        Row(Modifier.fillMaxWidth()) {
            plan.kroki.forEachIndexed { i, _ ->
                val wypelniony = stan.zakonczona || i < stan.krok
                val teraz = !stan.zakonczona && i == stan.krok
                Box(
                    Modifier
                        .weight(1f)
                        .height(8.dp)
                        .background(
                            when {
                                wypelniony -> Palette.Accent
                                teraz -> Palette.AccentDim
                                else -> Palette.SurfaceHigh
                            }
                        )
                )
                if (i < plan.kroki.lastIndex) Spacer(Modifier.width(4.dp))
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(t.sessionWordsDone, color = Palette.Muted, fontSize = 11.sp)
    }
}

@Composable
private fun WierszKroku(t: SesjaStr, krok: KrokSesji, zrobiony: Boolean, biezacy: Boolean) {
    // Krok jeszcze nieprzerobiony mial nazwe w Palette.Dim: na tle Palette.Surface z alfa
    // 0.45 nad tlem strony (efektywnie #131624) to 3,03:1, czyli ponizej progu WCAG AA dla
    // tekstu (4,5:1). Palette.Muted daje na tym samym tle 5,19:1. Wygaszenia nie tracimy:
    // roznice "zrobiony / biezacy / przed nami" niesie ikona (checkmark, cel albo jej brak),
    // grubosc ramki i alfa tla — a nie sam kolor liter.
    val ink = when {
        biezacy -> Palette.Bone
        else -> Palette.Muted
    }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .background(if (biezacy) Palette.Surface else Palette.Surface.copy(alpha = 0.45f))
            .border(if (biezacy) 3.dp else 2.dp, if (biezacy) Palette.SurfaceHigh else Palette.Ink)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (zrobiony) {
                PixelImage(pixel(R.drawable.checkmark), Modifier.size(18.dp))
                Spacer(Modifier.width(11.dp))
            } else if (biezacy) {
                PixelImage(pixel(R.drawable.icon_target), Modifier.size(18.dp))
                Spacer(Modifier.width(11.dp))
            } else {
                Spacer(Modifier.width(29.dp))
            }
            Column(Modifier.weight(1f)) {
                PixelText(t.nazwaKroku(krok.rodzaj), color = ink, glyphHeight = 10.dp, maxWidthDp = 190.dp)
                Spacer(Modifier.height(4.dp))
                Text(t.opisKroku(krok.rodzaj), color = Palette.Muted, fontSize = 11.sp, lineHeight = 15.sp)
            }
            // Spokojny wariant pokazujemy tylko przy rundzie gry: powtorka zyc nie ma z
            // definicji, wiec plakietka przy kazdym kroku bylaby szumem, a nie informacja.
            if (krok.rodzaj == RodzajKroku.GRA && krok.spokojny) {
                Spacer(Modifier.width(8.dp))
                Pill(t.sessionCalm, Palette.Cyan)
            } else if (krok.cele.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                PixelText("${krok.cele.size}", color = Palette.Muted, glyphHeight = 9.dp)
            }
        }
    }
}

/**
 * Podsumowanie i termin nastepnej powtorki. Kazda liczba pochodzi z zapisanego postepu albo
 * z tego, co uczen w tej sesji naprawde zrobil — patrz com.matura.app.learn.Sesja.podsumuj.
 */
@Composable
private fun KartaPodsumowania(
    t: SesjaStr,
    plan: PlanSesji,
    podsumowanie: PodsumowanieSesji,
    onZakoncz: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(Palette.Surface)
                .border(3.dp, Palette.SurfaceHigh)
                .padding(14.dp)
        ) {
            Column {
                StatRow(t.sessionSummaryReviewed, "${podsumowanie.powtorzone}", pixel(R.drawable.checkmark))
                StatRow(t.sessionSummaryNew, "${podsumowanie.nowe}", pixel(R.drawable.icon_star))
                StatRow(t.sessionSummaryPlanned, "${podsumowanie.zaplanowane}", pixel(R.drawable.icon_target))
                Spacer(Modifier.height(10.dp))
                val dni = podsumowanie.nastepnaPowtorkaZaDni
                Text(
                    when {
                        plan.pusty && dni == null -> t.sessionNothingDueNote
                        dni == null -> t.sessionNoNext
                        dni <= 1 -> t.sessionNextTomorrow
                        else -> t.sessionNextInDays.replace("%s", dni.toString())
                    },
                    color = Palette.Bone,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
                if (podsumowanie.naJutro > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        t.sessionTomorrowCount.replace("%s", podsumowanie.naJutro.toString()),
                        color = Palette.Muted,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        PrimaryButton(
            label = t.sessionFinish,
            modifier = Modifier.fillMaxWidth(),
            onClick = onZakoncz,
        )
    }
}

/**
 * Teksty ekranu sesji, PL/EN.
 *
 * Mieszkaja tutaj, a nie w [Str], bo Strings.kt nalezy w tym etapie do innego zakresu prac.
 * Ksztalt jest CELOWO taki sam jak w [Str] (prywatne pick, wlasciwosci get()), wiec
 * przeniesienie tych wlasciwosci do wspolnej tablicy to przeklejenie linii bez zmiany
 * wywolan — nazwy sa juz docelowe.
 *
 * Wzorce "%s" / "%1" / "%2" wypelnia ekran liczbami z planu i z podsumowania.
 */
class SesjaStr(private val lang: UiLanguage) {
    private fun pick(pl: String, en: String) = if (lang == UiLanguage.PL) pl else en

    // Czcionka pikselowa ma polskie znaki, ale nie ma nawiasow ani cudzyslowow — etykiety
    // rysowane PixelText trzymaja sie liter, cyfr i . , ! ? : / - _ +
    val sessionTitle get() = pick("DZISIEJSZE 5 MINUT", "TODAY'S 5 MINUTES")
    val sessionIntro
        get() = pick(
            "Krótka trasa na dziś: powtórka zaplanowanych słów, runda gry na tym samym słownictwie i podsumowanie.",
            "A short route for today: the words due now, a game round on the same vocabulary, and a summary.",
        )
    val sessionEstimate get() = pick("ORIENTACYJNIE %s MIN", "ROUGHLY %s MIN")
    val sessionEstimateNote
        get() = pick(
            "To szacunek, a nie stoper. Rozpoczęta odpowiedź zawsze idzie do końca, a sesję kończysz Ty.",
            "That is an estimate, not a timer. An answer you started always finishes, and you decide when to stop.",
        )
    val sessionStarterSet
        get() = pick(
            "Pierwszy dzień z tym słownictwem — zaczynamy od %s słów.",
            "First day with this vocabulary — we start with %s words.",
        )
    val sessionResumed get() = pick("WZNOWIONA", "RESUMED")
    val sessionStepLabel get() = pick("KROK %1 Z %2", "STEP %1 OF %2")
    val sessionWordsDone get() = pick("Słowa przerobione w tej sesji", "Words covered in this session")

    val sessionStepReview get() = pick("POWTÓRKA", "REVIEW")
    val sessionStepGame get() = pick("RUNDA GRY", "GAME ROUND")
    val sessionStepContext get() = pick("SŁOWO W ZDANIU", "WORD IN CONTEXT")
    val sessionStepSummary get() = pick("PODSUMOWANIE", "SUMMARY")

    /**
     * OPISY KROKOW MUSZA ZGADZAC SIE Z App.kt: krok POWTORKA i krok GRA uruchamiaja DOKLADNIE
     * to samo — runde KLASYCZNA na tym samym slownictwie (jedna galaz `push(Screen.Play(...))`),
     * a jedyna roznica jest liczba zyc (spokojny krok dostaje ich dziewiec).
     *
     * Do 0.9.18 stalo tu "spokojnie i bez zyc" oraz "tym razem w grze", czyli obietnica dwoch
     * roznych mechanik i rundy bez zyc. Uczen dostawal zwykla strzelanke z sercami w HUD,
     * a po dziewieciu przegapionych potworkach — czaszke z napisem KONIEC GRY.
     */
    val sessionStepReviewDesc
        get() = pick(
            "Słowa zaplanowane na dziś, spokojnie — runda z zapasem dziewięciu żyć.",
            "The words due today, taken calmly — a round with nine lives to spare.",
        )
    val sessionStepGameDesc
        get() = pick(
            "To samo słownictwo jeszcze raz, w kolejnej rundzie gry.",
            "The same vocabulary once more, in another game round.",
        )
    val sessionStepContextDesc
        get() = pick(
            "Słowo w zdaniu, a nie w oderwaniu od kontekstu.",
            "The word inside a sentence, not on its own.",
        )
    val sessionStepSummaryDesc
        get() = pick(
            "Co zrobione i kiedy wracają te słowa.",
            "What you covered and when these words come back.",
        )

    /**
     * Plakietka przy spokojnej rundzie gry. Mowi LICZBE, bo tyle wlasnie robi kod: App.kt daje
     * spokojnej rundzie `lives = maxOf(options.lives, 9)`, a suwak zyc w OPCJACH konczy sie na
     * dziewieciu — wiec spokojna runda ma ich zawsze dokladnie dziewiec.
     *
     * Do 0.9.18 pisalo tu "BEZ ZYC", chociaz runda miala serca w HUD i konczyla sie ekranem
     * KONIEC GRY po utracie ostatniego zycia (GameEngine ustawia gameOver przy lives <= 0).
     * Napis ma isc za kodem: to wydanie ma byc bezpieczne, wiec zmieniamy plakietke, a nie
     * zachowanie silnika tuz przed wysylka.
     */
    val sessionCalm get() = pick("9 ŻYĆ", "9 LIVES")
    val sessionStart get() = pick("ZACZYNAM", "START")
    val sessionContinue get() = pick("DALEJ", "NEXT")
    val sessionFinish get() = pick("KONIEC NA DZIŚ", "DONE FOR TODAY")
    val sessionPauseNote
        get() = pick(
            "Możesz wyjść w każdej chwili. Każda odpowiedź jest już zapisana, a sesja poczeka tu na Ciebie.",
            "You can leave at any time. Every answer is already saved, and the session waits for you here.",
        )
    val sessionTimeUp
        get() = pick(
            "Orientacyjne pięć minut minęło. To dobry moment, żeby skończyć — albo grać dalej, jeśli masz ochotę.",
            "The rough five minutes are up. A good moment to stop — or to keep going, if you feel like it.",
        )
    val sessionNothingDue
        get() = pick(
            "Na dziś nic nie zalega.",
            "Nothing is due today.",
        )
    val sessionNothingDueNote
        get() = pick(
            "Wszystkie słowa mają termin w przyszłości. Nic nie przepadło — wróć jutro.",
            "Every word is scheduled for later. Nothing is lost — come back tomorrow.",
        )

    val sessionSummaryReviewed get() = pick("Powtórzone", "Reviewed")
    val sessionSummaryNew get() = pick("Nowe", "New")
    val sessionSummaryPlanned get() = pick("Zaplanowane na dziś", "Planned for today")
    val sessionNextTomorrow get() = pick("Następna powtórka: jutro.", "Next review: tomorrow.")
    val sessionNextInDays get() = pick("Następna powtórka za %s dni.", "Next review in %s days.")
    val sessionNoNext
        get() = pick(
            "Te słowa wciąż czekają na powtórkę.",
            "These words are still waiting for a review.",
        )
    val sessionTomorrowCount
        get() = pick(
            "Jutro na powtórkę: %s z nich.",
            "Due tomorrow: %s of them.",
        )

    fun nazwaKroku(rodzaj: RodzajKroku): String = when (rodzaj) {
        RodzajKroku.POWTORKA -> sessionStepReview
        RodzajKroku.GRA -> sessionStepGame
        RodzajKroku.KONTEKST -> sessionStepContext
        RodzajKroku.PODSUMOWANIE -> sessionStepSummary
    }

    fun opisKroku(rodzaj: RodzajKroku): String = when (rodzaj) {
        RodzajKroku.POWTORKA -> sessionStepReviewDesc
        RodzajKroku.GRA -> sessionStepGameDesc
        RodzajKroku.KONTEKST -> sessionStepContextDesc
        RodzajKroku.PODSUMOWANIE -> sessionStepSummaryDesc
    }
}
