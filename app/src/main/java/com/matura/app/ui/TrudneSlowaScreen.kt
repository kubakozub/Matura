package com.matura.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.matura.app.R
import com.matura.app.learn.DzialFiltr
import com.matura.app.learn.PowodTrudnosci
import com.matura.app.learn.Trudne
import com.matura.app.learn.TrudneSlowo
import com.matura.app.model.Direction
import com.matura.app.model.WordProgress
import com.matura.app.model.WordSet

/**
 * Teksty ekranu. Ekran NIE zna klasy [Str] z tego samego powodu, dla ktorego nie zna
 * repozytorium: ma sie dac obejrzec i przetestowac bez reszty aplikacji. Warstwa nawigacji
 * sklada te strukture z [Str] w jednym miejscu.
 *
 * Zadne pole nie ma wartosci domyslnej. Domyslna byla by polska, a wtedy zapomniane
 * podlaczenie jednego pola nie wywalilo by kompilacji, tylko po cichu wyswietlilo polski
 * napis uczniowi, ktory wybral angielski.
 */
data class TrudneTeksty(
    val tytul: String,
    val wroc: String,
    val wszystkieDzialy: String,
    val mojeZestawy: String,
    val kierunekHasloZnaczenie: String,
    val kierunekZnaczenieHaslo: String,
    val etykietaLiczby: String,
    val powodZalegle: String,
    val powodCofniete: String,
    val powodPomylki: String,
    val powodPodpowiedzi: String,
    val etykietaPomylki: String,
    val etykietaPodpowiedzi: String,
    val etykietaPoTerminie: String,
    val terminDzis: String,
    val wymowa: String,
    val cwicz: String,
    val cwiczWszystkie: String,
    val pustoBrakHistorii: String,
    val pustoNicTrudnego: String,
)

/**
 * "Moje trudne slowa" — jedno miejsce, w ktorym uczen widzi, co mu nie wchodzi, i moze to
 * od razu przecwiczyc.
 *
 * Ekran dostaje WSZYSTKO parametrem: katalog, mape postepu, dzien harmonogramu i teksty.
 * Do repozytorium nie siega zaden ekran w tej aplikacji i ten tez nie bedzie — druga
 * instancja Repository ma wlasny bufor i nadpisywalaby postep zapisany przez gre.
 *
 * @param dzis dzien harmonogramu (Srs.dayOf) ustalony RAZ przez wolajacego
 * @param onWymowa dostaje strone do przeczytania na glos (Trudne.stronaDoWymowy)
 * @param onCwiczWszystkie null chowa przycisk — dopoki nie ma dokad z nim isc, lepiej go nie ma
 */
@Composable
fun TrudneSlowaScreen(
    teksty: TrudneTeksty,
    zestawy: List<WordSet>,
    postep: Map<String, WordProgress>,
    dzis: Int,
    onBack: () -> Unit,
    onWymowa: (String) -> Unit,
    onCwicz: (TrudneSlowo) -> Unit,
    kierunekPoczatkowy: Direction = Direction.TERM_TO_DEF,
    onCwiczWszystkie: ((List<TrudneSlowo>) -> Unit)? = null,
) {
    var kierunek by remember(kierunekPoczatkowy) {
        mutableStateOf(Trudne.kierunekDoListy(kierunekPoczatkowy))
    }
    var wybranyDzial by remember { mutableStateOf<DzialFiltr>(DzialFiltr.Wszystkie) }

    // Katalog liczymy raz na kierunek, a nie przy kazdym przerysowaniu: przy 100 zestawach
    // to kilka tysiecy wpisow, a przelaczanie dzialu ma byc natychmiastowe.
    val wszystkie = remember(zestawy, postep, kierunek, dzis) {
        Trudne.wszystkie(zestawy, postep, kierunek, dzis)
    }
    val dzialy = remember(zestawy, wszystkie) { Trudne.dzialy(zestawy, wszystkie) }
    // Po zmianie kierunku wybrany dzial moze juz nie miec ani jednego trudnego slowa. Wtedy
    // jego zakladka znika z paska i uczen nie mialby czym odznaczyc filtra — ekran udawalby,
    // ze nie ma nic do powtorki.
    val dzial = if (wybranyDzial == DzialFiltr.Wszystkie || wybranyDzial in dzialy) {
        wybranyDzial
    } else {
        DzialFiltr.Wszystkie
    }
    val lista = remember(wszystkie, dzial) { Trudne.filtruj(wszystkie, dzial) }
    // Lokalna kopia, zeby wywolanie w srodku przycisku nie zalezalo od sprytu kompilatora
    // przy rzutowaniu nullowalnej lambdy przechwyconej w domkniecie.
    val cwiczWszystkie = onCwiczWszystkie

    ScreenScaffold(teksty.tytul, onBack, teksty.wroc) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth()) {
                ChipFiltra(teksty.kierunekHasloZnaczenie, kierunek == Direction.TERM_TO_DEF) {
                    kierunek = Direction.TERM_TO_DEF
                }
                Spacer(Modifier.width(8.dp))
                ChipFiltra(teksty.kierunekZnaczenieHaslo, kierunek == Direction.DEF_TO_TERM) {
                    kierunek = Direction.DEF_TO_TERM
                }
            }

            if (dzialy.size > 1) {
                Spacer(Modifier.height(8.dp))
                // Dzialow bywa czternascie, wiec pasek jedzie w bok. Pionowa lista zakladek
                // zjadlaby pol ekranu, ktory ma pokazywac slowa.
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    ChipFiltra(teksty.wszystkieDzialy, dzial == DzialFiltr.Wszystkie) {
                        wybranyDzial = DzialFiltr.Wszystkie
                    }
                    dzialy.forEach { d ->
                        Spacer(Modifier.width(8.dp))
                        ChipFiltra(nazwaDzialu(d, teksty), dzial == d) { wybranyDzial = d }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            if (lista.isEmpty()) {
                // Pusto z dwoch zupelnie roznych powodow, wiec i zdania sa dwa: bez ani jednej
                // rozegranej proby nie ma z czego wyliczyc trudnosci, a przy pelnej historii
                // pusta lista to dobra wiadomosc, nie awaria.
                InfoCard(if (postep.isEmpty()) teksty.pustoBrakHistorii else teksty.pustoNicTrudnego)
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Text(
                            "${teksty.etykietaLiczby}: ${lista.size}",
                            color = Palette.Muted,
                            fontSize = 12.sp,
                        )
                        if (cwiczWszystkie != null) {
                            Spacer(Modifier.height(8.dp))
                            PrimaryButton(teksty.cwiczWszystkie, Modifier.fillMaxWidth()) {
                                cwiczWszystkie(lista)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                    // Klucz to identyfikator wpisu, a nie pozycja: [Trudne.filtruj] gwarantuje,
                    // ze kazdy wpis jest na liscie raz.
                    items(lista, key = { it.entry.id }) { slowo ->
                        KartaTrudnegoSlowa(
                            slowo = slowo,
                            teksty = teksty,
                            onWymowa = { onWymowa(slowo.doWymowy) },
                            onCwicz = { onCwicz(slowo) },
                        )
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun KartaTrudnegoSlowa(
    slowo: TrudneSlowo,
    teksty: TrudneTeksty,
    onWymowa: () -> Unit,
    onCwicz: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(Palette.Surface)
            .border(3.dp, Palette.SurfaceHigh)
            .padding(14.dp)
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    PixelText(
                        slowo.pytanie,
                        color = Palette.Bone,
                        glyphHeight = 12.dp,
                        maxWidthDp = 200.dp,
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(slowo.odpowiedz, color = Palette.Muted, fontSize = 13.sp, lineHeight = 17.sp)
                }
                Spacer(Modifier.width(10.dp))
                // Ikona jest mala, ale cel dotykowy nie: 20 dp obrazka + 2x13 dp wypelnienia
                // to 46 dp, czyli tyle, ile ma najmniejszy klawisz w tej aplikacji.
                Box(
                    Modifier
                        .background(Palette.Ink)
                        .border(2.dp, Palette.SurfaceHigh)
                        .clickable { onWymowa() }
                        .semantics { contentDescription = teksty.wymowa }
                        .padding(13.dp)
                ) {
                    PixelImage(pixel(R.drawable.icon_sound_on), Modifier.size(20.dp))
                }
            }

            Spacer(Modifier.height(10.dp))
            Pill(tekstPowodu(slowo.powod, teksty), kolorPowodu(slowo.powod))

            val opis = szczegoly(slowo, teksty)
            if (opis.isNotEmpty()) {
                Spacer(Modifier.height(7.dp))
                Text(opis, color = Palette.Dim, fontSize = 11.sp, lineHeight = 15.sp)
            }

            Spacer(Modifier.height(3.dp))
            Text(zrodlo(slowo), color = Palette.Dim, fontSize = 11.sp)

            Spacer(Modifier.height(11.dp))
            GhostButton(teksty.cwicz) { onCwicz() }
        }
    }
}

@Composable
private fun ChipFiltra(label: String, selected: Boolean, onClick: () -> Unit) {
    // Etykiety maja w sobie strzalki ("haslo -> tlumaczenie") i polskie znaki, ktorych paczka
    // pikselowa nie ma w komplecie, wiec tu — inaczej niz w chipach OPCJI — idzie font systemu.
    Box(
        Modifier
            .background(if (selected) Palette.Accent else Palette.Surface)
            .border(2.dp, if (selected) Palette.Ink else Palette.SurfaceHigh)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            label,
            color = if (selected) Palette.Ink else Palette.Muted,
            fontSize = 12.sp,
            maxLines = 1,
        )
    }
}

private fun nazwaDzialu(dzial: DzialFiltr, teksty: TrudneTeksty): String = when (dzial) {
    DzialFiltr.Wszystkie -> teksty.wszystkieDzialy
    DzialFiltr.Wlasne -> teksty.mojeZestawy
    is DzialFiltr.Nazwany -> dzial.nazwa
}

private fun tekstPowodu(powod: PowodTrudnosci, teksty: TrudneTeksty): String = when (powod) {
    PowodTrudnosci.ZALEGLA_POWTORKA -> teksty.powodZalegle
    PowodTrudnosci.COFNIETE_PO_BLEDZIE -> teksty.powodCofniete
    PowodTrudnosci.POMYLKI -> teksty.powodPomylki
    PowodTrudnosci.PODPOWIEDZI -> teksty.powodPodpowiedzi
}

/** Kolor niesie te sama informacje co napis, wiec jest dodatkiem, a nie jedynym sygnalem. */
private fun kolorPowodu(powod: PowodTrudnosci): Color = when (powod) {
    PowodTrudnosci.ZALEGLA_POWTORKA -> Palette.Gold
    PowodTrudnosci.COFNIETE_PO_BLEDZIE -> Palette.Danger
    PowodTrudnosci.POMYLKI -> Palette.Danger
    PowodTrudnosci.PODPOWIEDZI -> Palette.Cyan
}

/**
 * Druga linia karty: pozostale powody i LICZBY, ktore je potwierdzaja. Kazda pochodzi
 * z zapisanego postepu — ekran niczego nie szacuje ani nie zaokragla.
 */
private fun szczegoly(slowo: TrudneSlowo, teksty: TrudneTeksty): String {
    val czesci = ArrayList<String>(4)
    slowo.powody.drop(1).forEach { czesci += tekstPowodu(it, teksty) }
    if (slowo.postep.mistakes > 0) czesci += "${teksty.etykietaPomylki}: ${slowo.postep.mistakes}"
    if (slowo.postep.hints > 0) czesci += "${teksty.etykietaPodpowiedzi}: ${slowo.postep.hints}"
    when {
        slowo.zaleglosc > 0 -> czesci += "${teksty.etykietaPoTerminie}: ${slowo.zaleglosc}"
        slowo.zaleglosc == 0 -> czesci += teksty.terminDzis
    }
    return czesci.joinToString(" · ")
}

/** Skad slowo pochodzi — po to, zeby uczen wiedzial, gdzie go szukac w zestawach. */
private fun zrodlo(slowo: TrudneSlowo): String =
    slowo.dzial?.let { "$it · ${slowo.zestawTytul}" } ?: slowo.zestawTytul
