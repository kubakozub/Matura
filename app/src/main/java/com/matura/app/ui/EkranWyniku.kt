package com.matura.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.matura.app.R
import com.matura.app.game.MissedWord

/** Przycisk dolnego paska [EkranWyniku]: napis i to, co robi. */
class PrzyciskWyniku(val etykieta: String, val onClick: () -> Unit)

/**
 * Ekran wyniku rundy na CALE okno: ikona, tytul i punkty u gory, srodek do wypelnienia przez
 * tryb gry, przyciski przypiete do dolu.
 *
 * Skad sie wzial. Koniec gry w trybie klasycznym byl nakladka rysowana w ramce planszy, a
 * plansza dostaje tylko to, co zostanie po klawiaturze. Na telefonie 360 x 640 dp ucinalo to
 * czaszke i oba przyciski, a na 320 x 568 dp przyciskow nie bylo widac wcale i z rundy dalo sie
 * wyjsc tylko systemowym "wstecz". Stad dwie reguly tego ukladu, ktorych nie wolno rozdzielac:
 *
 *  - gorna czesc przewija sie i bierze cala wolna wysokosc, wiec dluga lista w [srodek] nigdy
 *    nie wypycha niczego poza okno;
 *  - przyciski stoja POD przewijaniem, a nie w nim, wiec widac je zawsze i bez szukania.
 *
 * Komponent, a nie kawalek ekranu gry, bo pozostale trzy tryby koncza sie tym samym ukladem
 * (ikona, tytul, punkty, przyciski) i roznia sie tylko tym, co stoi w srodku: statystyki, lista
 * slow do powtorki, a pozniej blok nagrod. Niczego nie zapisuje i nie zna silnika gry.
 *
 * @param opisPunktow punkty dla czytnika ekranu; sama liczba bez nazwy nic mu nie mowi.
 * @param glowny przycisk, ktory ma byc pod kciukiem: zielony, na cala wolna szerokosc.
 * @param drugi przycisk poboczny; null zostawia jeden przycisk.
 * @param uwaga komunikat aplikacji, ktory ma byc widoczny takze tutaj (dzis: nieudany zapis).
 *   Stoi NAD przyciskami, poza przewijaniem. Aplikacja pokazuje go zwykle karta u dolu okna,
 *   a dol tego ekranu to wlasnie przyciski: karta lezala dokladnie na nich i uczen po rundzie
 *   nie widzial ani JESZCZE RAZ, ani wyjscia. Kto podaje [uwaga], nie rysuje juz tamtej karty.
 * @param srodek zawartosc miedzy punktami a przyciskami. Dostaje szerokosc kolumny, zeby
 *   napis rysowany czcionka pikselowa mial do czego zawijac (PixelText sam jej nie zna).
 */
@Composable
fun EkranWyniku(
    ikona: ImageBitmap,
    tytul: String,
    kolorTytulu: Color,
    punkty: Int,
    opisPunktow: String,
    glowny: PrzyciskWyniku,
    modifier: Modifier = Modifier,
    drugi: PrzyciskWyniku? = null,
    uwaga: String? = null,
    srodek: @Composable ColumnScope.(szerokosc: Dp) -> Unit = {},
) {
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(Palette.Background)
            // W aplikacji insety konsumuje juz MaturaApp i ta linia nic nie doklada. Zostaje dla
            // ekranu zlozonego samodzielnie (test, podglad), zeby i tam nic nie weszlo pod paski.
            .safeDrawingPadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        // Na tablecie i w poziomie kolumna nie rozciaga sie od krawedzi do krawedzi: lista par
        // slow szeroka na 1200 dp nie daje sie czytac wierszami.
        val szerokosc = (maxWidth - MARGINES_EKRANU * 2).coerceIn(0.dp, MAKS_SZEROKOSC_KOLUMNY)
        Column(Modifier.fillMaxHeight().width(szerokosc)) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 16.dp, bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                // Krotka tresc stoi na srodku, a nie pod sufitem; dluga i tak zaczyna sie od gory.
                verticalArrangement = Arrangement.Center,
            ) {
                PixelImage(ikona, Modifier.size(52.dp))
                Spacer(Modifier.height(10.dp))
                PixelText(tytul, color = kolorTytulu, glyphHeight = 18.dp, maxWidthDp = szerokosc)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PixelImage(pixel(R.drawable.coin), Modifier.size(26.dp))
                    Spacer(Modifier.width(9.dp))
                    PixelText("$punkty", color = Palette.Gold, glyphHeight = 26.dp, opis = opisPunktow)
                }
                srodek(szerokosc)
            }
            if (uwaga != null) {
                InfoCard(uwaga)
                Spacer(Modifier.height(8.dp))
            }
            // Kreska mowi, ze to, co nad nia, przewija sie pod przyciski, a nie konczy w tym miejscu.
            Box(Modifier.fillMaxWidth().height(2.dp).background(Palette.SurfaceHigh))
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 12.dp)
                    // Oba przyciski maja te sama wysokosc, liczona z wyzszego z nich, i nigdy
                    // mniej niz 48 dp celu dotyku.
                    .height(IntrinsicSize.Min)
                    .heightIn(min = 48.dp),
            ) {
                PrimaryButton(
                    glowny.etykieta,
                    Modifier.weight(1f).fillMaxHeight(),
                    onClick = glowny.onClick,
                )
                if (drugi != null) {
                    Spacer(Modifier.width(10.dp))
                    GhostButton(drugi.etykieta, Modifier.fillMaxHeight(), onClick = drugi.onClick)
                }
            }
        }
    }
}

private val MARGINES_EKRANU = 16.dp
private val MAKS_SZEROKOSC_KOLUMNY = 480.dp

/**
 * Lista slow, ktore doszly do dziala: haslo po lewej, oczekiwana odpowiedz po prawej.
 *
 * To jest tresc, dla ktorej ekran wyniku w ogole istnieje. Sam wynik mowi, jak poszla
 * rozgrywka; lista mowi, czego sie z niej nauczyc, zanim uczen zagra jeszcze raz.
 *
 * Zwykla czcionka, nie pikselowa: odpowiedz bywa dluga i ma przecinki miedzy wariantami
 * ("kolega ze szkoły, koleżanka ze szkoły"), a wiersz ma sie zawijac. Strony rozroznia MIEJSCE
 * (lewa i prawa kolumna), kolor jest tylko dodatkiem.
 *
 * Pusta lista to dobra wiadomosc i tak jest pokazana: jednym zdaniem zamiast pustej ramki.
 */
@Composable
fun ListaDoPowtorki(slowa: List<MissedWord>, str: Str, modifier: Modifier = Modifier) {
    if (slowa.isEmpty()) {
        Text(
            str.nothingMissed,
            color = Palette.Bone,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            textAlign = TextAlign.Center,
            modifier = modifier.fillMaxWidth(),
        )
        return
    }
    // Miejsce na licznik trzymamy w KAZDYM wierszu, gdy ma go choc jeden: inaczej prawa kolumna
    // zaczynalaby sie w wierszach z licznikiem gdzie indziej niz w pozostalych.
    val zLicznikiem = slowa.any { it.times > 1 }
    Column(
        modifier
            .fillMaxWidth()
            .background(Palette.Surface)
            .border(2.dp, Palette.SurfaceHigh)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        PixelText(str.toReview, color = Palette.Gold, glyphHeight = 9.dp)
        Spacer(Modifier.height(4.dp))
        slowa.forEachIndexed { i, slowo ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.SurfaceHigh))
            Row(
                Modifier
                    .fillMaxWidth()
                    // Jeden wezel dla czytnika ekranu: haslo, odpowiedz i licznik czytane razem.
                    .semantics(mergeDescendants = true) {}
                    .padding(vertical = 7.dp),
            ) {
                Text(
                    slowo.prompt,
                    color = Palette.Bone,
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    slowo.solution,
                    color = Palette.Gold,
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier.weight(1f),
                )
                if (zLicznikiem) {
                    Box(Modifier.width(34.dp), contentAlignment = Alignment.TopEnd) {
                        if (slowo.times > 1) {
                            Text(
                                "×${slowo.times}",
                                color = Palette.Muted,
                                fontSize = 13.sp,
                                lineHeight = 19.sp,
                                textAlign = TextAlign.End,
                                modifier = Modifier.semantics {
                                    contentDescription = str.missedTimes(slowo.times)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
