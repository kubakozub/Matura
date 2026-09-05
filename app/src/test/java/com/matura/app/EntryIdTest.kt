package com.matura.app

import com.matura.app.data.BuiltInSets
import com.matura.app.learn.EntryId
import com.matura.app.model.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Tozsamosc wpisu. Kazdy test odpowiada jednemu wymaganiu z zalozen: identyfikator ma
 * przezyc poprawke tlumaczenia, rozroznic znaczenia, polaczyc to samo znaczenie w roznych
 * zestawach i nie zderzyc sie z wpisami wlasnymi ucznia.
 */
class EntryIdTest {

    // --- katalog wbudowany -----------------------------------------------------------------

    @Test
    fun `korekta tlumaczenia nie zmienia identyfikatora`() {
        // Trzy poprawki z Etapu 1. Stare brzmienie prowadzi do tego samego identyfikatora,
        // bo sens jest ZAMROZONY w manifescie, a stare brzmienia sa w nim aliasami.
        assertEquals(
            EntryId.builtIn("schoolmate", "kolega ze szkoły, koleżanka ze szkoły"),
            EntryId.builtIn("schoolmate", "rówieśnik"),
        )
        assertEquals(
            EntryId.builtIn("boarding school", "szkoła z internatem"),
            EntryId.builtIn("boarding school", "internat"),
        )
        assertEquals(
            EntryId.builtIn("single ticket", "bilet w jedną stronę"),
            EntryId.builtIn("single ticket", "bilet jednorazowy"),
        )
    }

    @Test
    fun `rozne znaczenia tego samego hasla maja rozne identyfikatory`() {
        assertNotEquals(
            EntryId.builtIn("patient", "cierpliwy"),
            EntryId.builtIn("patient", "pacjent"),
        )

        // To samo, ale na prawdziwym katalogu: kazde haslo o kilku sensach musi miec
        // tyle identyfikatorow, ile sensow.
        val wpisy = BuiltInSets.all().flatMap { it.entries }
        val sensy = wpisy.groupBy({ EntryId.normalize(it.term) }, { EntryId.normalize(it.definition) })
            .mapValues { (_, v) -> v.toSet() }
        val wieloznaczne = sensy.filter { it.value.size > 1 }.keys
        assertTrue("katalog nie ma hasel wieloznacznych - test bylby pusty", wieloznaczne.isNotEmpty())

        for (haslo in wieloznaczne) {
            val identyfikatory = wpisy
                .filter { EntryId.normalize(it.term) == haslo }
                .map { it.id }
                .toSet()
            assertEquals("'$haslo' ma sensy sklejone w jeden identyfikator", sensy.getValue(haslo).size, identyfikatory.size)
        }
    }

    @Test
    fun `to samo znaczenie w roznych zestawach ma ten sam identyfikator`() {
        val wpisy = BuiltInSets.all().flatMap { s -> s.entries.map { s.id to it } }
        val poTresci = wpisy.groupBy { (_, e) -> EntryId.contentKey(e.term, e.definition) }
        val wKilkuZestawach = poTresci.filterValues { it.map { (setId, _) -> setId }.toSet().size > 1 }

        assertTrue(
            "katalog nie powtarza zadnego hasla miedzy zestawami - test bylby pusty",
            wKilkuZestawach.isNotEmpty(),
        )
        for ((klucz, lista) in wKilkuZestawach) {
            assertEquals(
                "'$klucz' ma rozne identyfikatory w roznych zestawach",
                1,
                lista.map { (_, e) -> e.id }.toSet().size,
            )
        }
    }

    @Test
    fun `kazdy wpis katalogu ma identyfikator zgodny z trescia`() {
        // Generator (_matura-apk-tools/generuj_identyfikatory.py) liczy skrot w Pythonie.
        // Ten test jest jedynym miejscem, ktore pilnuje, ze obie implementacje normalizacji
        // i skrotu daja to samo - rozjazd nie mialby zadnego innego objawu.
        for (set in BuiltInSets.all()) {
            for (e in set.entries) {
                assertTrue("${set.title}: '${e.term}' bez identyfikatora", EntryId.isBuiltIn(e.id))
                assertEquals(
                    "${set.title}: '${e.term}' ma identyfikator niezgodny z trescia",
                    EntryId.builtIn(e.term, e.definition),
                    e.id,
                )
            }
        }
    }

    // --- normalizacja ----------------------------------------------------------------------

    @Test
    fun `normalizacja nie zdejmuje diakrytykow`() {
        // Zdjecie ogonkow zlaloby w jedno slowa, ktore znacza co innego - a wtedy uczen
        // dostawalby postep jednego za drugie.
        assertNotEquals(EntryId.normalize("ładować"), EntryId.normalize("lądować"))
        assertNotEquals(EntryId.builtIn("load", "ładować"), EntryId.builtIn("land", "lądować"))
    }

    @Test
    fun `normalizacja zbija biale znaki i wielkosc liter`() {
        assertEquals(
            EntryId.builtIn("Music", "muzyka"),
            EntryId.builtIn("  music \t ", "muzyka"),
        )
        assertEquals(
            EntryId.builtIn("get dressed", "ubierać się"),
            EntryId.builtIn("get  dressed", "ubierać  się"),
        )
    }

    // --- wpisy wlasne ----------------------------------------------------------------------

    @Test
    fun `wpisy wlasne nie koliduja z katalogiem`() {
        val katalog = BuiltInSets.all().flatMap { it.entries }.map { it.id }.toSet()

        // Nawet gdy uczen wpisze DOKLADNIE to samo, co jest w katalogu.
        val zTresci = EntryId.userIdFor("user-1", "mature", "dojrzały")
        assertFalse(zTresci in katalog)
        assertTrue(EntryId.isUser(zTresci))
        assertFalse(EntryId.isBuiltIn(zTresci))

        val rnd = Random(42)
        val losowe = List(200) { EntryId.newUserId(rnd) }
        assertTrue(losowe.none { it in katalog })
        assertTrue(losowe.all { EntryId.isUser(it) })
        assertEquals("losowe identyfikatory sie powtarzaja", losowe.size, losowe.toSet().size)
    }

    @Test
    fun `identyfikator wlasny z migracji jest wyliczalny i osobny dla kazdego zestawu`() {
        // Migracja biegnie przy kazdym starcie az do pierwszego zapisu, wiec musi dawac
        // za kazdym razem to samo.
        assertEquals(
            EntryId.userIdFor("user-1", "dog", "pies"),
            EntryId.userIdFor("user-1", "dog", "pies"),
        )
        assertNotEquals(
            EntryId.userIdFor("user-1", "dog", "pies"),
            EntryId.userIdFor("user-2", "dog", "pies"),
        )
        // Dwa identyczne wiersze w jednym zestawie to dwa wpisy, nie jeden.
        assertNotEquals(
            EntryId.userIdFor("user-1", "dog", "pies", occurrence = 0),
            EntryId.userIdFor("user-1", "dog", "pies", occurrence = 1),
        )
    }

    @Test
    fun `ksztalt identyfikatora jest sprawdzalny`() {
        assertTrue(EntryId.isValid(EntryId.builtIn("dog", "pies")))
        assertTrue(EntryId.isValid(EntryId.newUserId(Random(1))))
        assertFalse(EntryId.isValid(""))
        assertFalse(EntryId.isValid("w123"))
        assertFalse(EntryId.isValid("x0123456789ab"))
        assertFalse("wielkie litery to nie jest nasz format", EntryId.isValid("wABCDEF012345"))
    }

    // --- uzgadnianie po edycji --------------------------------------------------------------

    @Test
    fun `edytor moze przestawic wiersze bez utraty tozsamosci`() {
        val przed = listOf(
            Entry("dog", "pies", "u000000000001"),
            Entry("cat", "kot", "u000000000002"),
            Entry("bird", "ptak", "u000000000003"),
        )
        val po = EntryId.reconcile(
            przed,
            listOf(Entry("bird", "ptak"), Entry("dog", "pies"), Entry("cat", "kot")),
        )

        assertEquals(listOf("u000000000003", "u000000000001", "u000000000002"), po.map { it.id })
    }

    @Test
    fun `poprawka tresci w tym samym wierszu zachowuje tozsamosc`() {
        val przed = listOf(Entry("dog", "pies", "u000000000001"))

        val poLiterowce = EntryId.reconcile(przed, listOf(Entry("dog", "pies, piesek")))
        assertEquals("u000000000001", poLiterowce.single().id)

        // Przycisk "<>" w edytorze zamienia kolumny miejscami - to wciaz ten sam wiersz.
        val poZamianie = EntryId.reconcile(przed, listOf(Entry("pies", "dog")))
        assertEquals("u000000000001", poZamianie.single().id)
    }

    @Test
    fun `usuniety wiersz nie oddaje tozsamosci sasiadowi`() {
        val przed = listOf(
            Entry("dog", "pies", "u000000000001"),
            Entry("cat", "kot", "u000000000002"),
        )

        val po = EntryId.reconcile(przed, listOf(Entry("cat", "kot"), Entry("fish", "ryba")))

        assertEquals("u000000000002", po[0].id)
        assertNotEquals("u000000000001", po[1].id)
        assertTrue(EntryId.isUser(po[1].id))
    }

    @Test
    fun `uzgadnianie w zestawie wbudowanym liczy identyfikator z tresci`() {
        val po = EntryId.reconcile(emptyList(), listOf(Entry("mature", "dojrzały")), builtInSet = true)

        assertEquals(EntryId.builtIn("mature", "dojrzały"), po.single().id)
        assertEquals(
            BuiltInSets.all().flatMap { it.entries }.first { it.term == "mature" }.id,
            po.single().id,
        )
    }

    @Test
    fun `wiersz, ktory przyszedl juz z identyfikatorem, zatrzymuje swoj`() {
        val przed = listOf(Entry("dog", "pies", "u000000000001"))

        val po = EntryId.reconcile(przed, listOf(Entry("dog", "pies", "u0000000000ff")))

        assertEquals("u0000000000ff", po.single().id)
    }
}
