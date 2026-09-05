package com.matura.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.matura.app.learn.KategoriaDzialu
import com.matura.app.learn.Podsumowanie
import com.matura.app.learn.PostepDzialu
import com.matura.app.learn.TekstyDzialu
import com.matura.app.learn.TekstyRundy
import com.matura.app.learn.WynikRundy

/** Kolor kategorii dzialu. Zielen jest tylko dla utrwalonych — nagroda ma byc jedna. */
private fun kolorKategorii(kategoria: KategoriaDzialu): Color = when (kategoria) {
    KategoriaDzialu.NOWE -> Palette.Dim
    KategoriaDzialu.DO_POWTORKI -> Palette.Gold
    KategoriaDzialu.W_NAUCE -> Palette.Cyan
    KategoriaDzialu.UTRWALONE -> Palette.Accent
}

/**
 * Blok "co ta runda zrobila dla nauki", do postawienia OBOK wyniku gry na ekranie koncowym.
 *
 * To jest KOMPONENT, a nie ekran: nie ma wlasnego tla na caly ekran, nie zna repozytorium i
 * niczego nie zapisuje. Wolajacy podaje policzone liczby ([Podsumowanie.rundy],
 * [Podsumowanie.dzialu]) i gotowe teksty w swoim jezyku — dzieki temu ten sam blok wchodzi we
 * wszystkie cztery tryby, a kazdy z nich zachowuje swoj wlasny uklad punktow i przyciskow.
 *
 * Punkty gry i nauka sa tu rozdzielone celowo. Wynik nad tym blokiem mowi, jak poszla ROZGRYWKA
 * (liczy sie takze szybkosc i celnosc), a ten blok mowi, co zostalo z tego w pamieci. Zlanie
 * ich w jedna liczbe daloby wskaznik, ktory rosnie od grania, a nie od uczenia sie.
 *
 * @param naglowek krotki tytul bloku, czcionka pikselowa (np. "NAUKA").
 * @param postep rozklad dzialu; null chowa cala te czesc — tryb, ktory losuje slowa z wielu
 *   zestawow, nie ma jednego dzialu do pokazania i lepiej niech nie pokazuje zadnego.
 * @param slowaDoPoprawki gotowe do wyswietlenia slowa (np. sam termin), najwyzej kilka.
 * @param onPopraw start krotkiej poprawki. Bez niego blok poprawki sie nie pokaze.
 * @param onPomin rezygnacja z poprawki. WYMAGANY razem z [onPopraw]: propozycja poprawki, ktorej
 *   nie da sie odrzucic, zamienia sie w kare za bledy. Pominiecie NICZEGO nie zapisuje — nie
 *   powstaje zadna [com.matura.app.learn.Attempt], wiec ani wynik gry, ani pudelka slow nie
 *   ruszaja sie o milimetr.
 */
@Composable
fun PodsumowanieRundy(
    wynik: WynikRundy,
    teksty: TekstyRundy,
    naglowek: String,
    modifier: Modifier = Modifier,
    postep: PostepDzialu? = null,
    tekstyDzialu: TekstyDzialu? = null,
    slowaDoPoprawki: List<String> = emptyList(),
    naglowekPoprawki: String = "",
    etykietaPoprawki: String = "",
    etykietaPominiecia: String = "",
    onPopraw: (() -> Unit)? = null,
    onPomin: (() -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Palette.Surface)
            .border(2.dp, Palette.SurfaceHigh)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        PixelText(naglowek, color = Palette.Accent, glyphHeight = 9.dp)
        Spacer(Modifier.height(9.dp))

        // Zdania sklada czysta logika, wiec test sprawdza je bez uruchamiania Compose.
        // Zwykla czcionka, nie pikselowa: to sa cale zdania po polsku, a 5x7 pikseli czyta
        // sie dobrze w etykiecie i meczy w akapicie.
        Podsumowanie.linie(wynik, teksty).forEachIndexed { i, linia ->
            if (i > 0) Spacer(Modifier.height(5.dp))
            Text(linia, color = Palette.Bone, fontSize = 13.sp, lineHeight = 18.sp)
        }

        if (postep != null && tekstyDzialu != null && postep.wszystkie > 0) {
            Spacer(Modifier.height(13.dp))
            PostepDzialuBlok(postep, tekstyDzialu)
        }

        if (slowaDoPoprawki.isNotEmpty() && onPopraw != null && onPomin != null) {
            Spacer(Modifier.height(13.dp))
            PoprawkaBlok(
                slowa = slowaDoPoprawki,
                naglowek = naglowekPoprawki,
                etykietaPoprawki = etykietaPoprawki,
                etykietaPominiecia = etykietaPominiecia,
                onPopraw = onPopraw,
                onPomin = onPomin,
            )
        }
    }
}

/**
 * Rozklad dzialu: pasek proporcji plus cztery plakietki z LICZBAMI SLOW.
 *
 * Pasek jest tylko ksztaltem tych samych czterech liczb — nie ma przy nim zadnego procentu ani
 * oceny. Procent na tym ekranie zaczalby zyc jak prognoza wyniku matury, a z pudelek Leitnera
 * takiej prognozy uczciwie wyprowadzic sie nie da.
 */
@Composable
private fun PostepDzialuBlok(postep: PostepDzialu, teksty: TekstyDzialu) {
    PixelText(teksty.naglowek, color = Palette.Muted, glyphHeight = 8.dp)
    Spacer(Modifier.height(7.dp))

    Row(
        Modifier
            .fillMaxWidth()
            .height(10.dp)
            .border(2.dp, Palette.Ink)
    ) {
        // Segment o wadze zero jest w Compose bledem, wiec puste kategorie po prostu znikaja
        // z paska — a ich liczba (zero) i tak stoi ponizej na plakietce.
        Segment(postep.nowe, KategoriaDzialu.NOWE)
        Segment(postep.doPowtorki, KategoriaDzialu.DO_POWTORKI)
        Segment(postep.wNauce, KategoriaDzialu.W_NAUCE)
        Segment(postep.utrwalone, KategoriaDzialu.UTRWALONE)
    }

    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth()) {
        Pill("${teksty.nowe} ${postep.nowe}", kolorKategorii(KategoriaDzialu.NOWE))
        Spacer(Modifier.width(8.dp))
        Pill("${teksty.wNauce} ${postep.wNauce}", kolorKategorii(KategoriaDzialu.W_NAUCE))
    }
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth()) {
        Pill("${teksty.doPowtorki} ${postep.doPowtorki}", kolorKategorii(KategoriaDzialu.DO_POWTORKI))
        Spacer(Modifier.width(8.dp))
        Pill("${teksty.utrwalone} ${postep.utrwalone}", kolorKategorii(KategoriaDzialu.UTRWALONE))
    }
}

@Composable
private fun RowScope.Segment(ile: Int, kategoria: KategoriaDzialu) {
    if (ile > 0) {
        Box(
            Modifier
                .weight(ile.toFloat())
                .fillMaxHeight()
                .background(kolorKategorii(kategoria))
        )
    }
}

/**
 * Propozycja krotkiej poprawki. Dwa przyciski o rownej wadze — POMIN nie jest schowany pod
 * krzyzykiem, bo rezygnacja ma byc tak samo latwa jak zgoda.
 */
@Composable
private fun PoprawkaBlok(
    slowa: List<String>,
    naglowek: String,
    etykietaPoprawki: String,
    etykietaPominiecia: String,
    onPopraw: () -> Unit,
    onPomin: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.Ink)
            .border(2.dp, Palette.Gold)
            .padding(horizontal = 11.dp, vertical = 10.dp)
    ) {
        PixelText(naglowek, color = Palette.Gold, glyphHeight = 8.dp)
        Spacer(Modifier.height(6.dp))
        Text(
            slowa.joinToString("  ·  "),
            color = Palette.Bone,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )
        Spacer(Modifier.height(11.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            PrimaryButton(etykietaPoprawki, Modifier.weight(1f), onClick = onPopraw)
            Spacer(Modifier.width(10.dp))
            GhostButton(etykietaPominiecia, Modifier.weight(1f), onClick = onPomin)
        }
    }
}
