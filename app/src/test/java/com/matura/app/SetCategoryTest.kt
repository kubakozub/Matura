package com.matura.app

import com.matura.app.data.BuiltInSets
import com.matura.app.model.WordSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Foldery wyboru zestawu wyprowadzamy z tytulu, wiec warto pilnowac, ze KAZDY
 *  wbudowany zestaw da sie przypisac i ze nie zostaje folder-sierota. */
class SetCategoryTest {

    private val builtIn: List<WordSet> get() = BuiltInSets.all()

    @Test fun everyBuiltInSetHasACategory() {
        val orphans = builtIn.filter { it.category == null }
        assertTrue("zestawy bez tematu: ${orphans.map { it.title }}", orphans.isEmpty())
    }

    /**
     * Nazwy dzialow sa KLUCZEM do ikon w SetPickScreen.topicIcon(). Zmiana nazwy
     * w BuiltInSets po cichu cofnelaby folder do zapasowej gwiazdki, wiec pilnujemy
     * pelnej listy, a nie tylko jej rozmiaru.
     */
    @Test fun categoriesMatchTheMaturaSyllabus() {
        val expected = setOf(
            "Człowiek", "Miejsce zamieszkania", "Edukacja", "Praca", "Życie prywatne",
            "Żywienie", "Zakupy", "Podróże", "Kultura", "Sport", "Zdrowie",
            "Nauka i technika", "Przyroda", "Życie społeczne",
        )
        val cats = builtIn.mapNotNull { it.category }.toSet()
        assertEquals(14, cats.size)
        assertEquals("dzialy bez ikony: ${cats - expected}", expected, cats)
    }

    @Test fun shortTitleDropsTheFolderName() {
        val s = builtIn.first { it.title.contains('·') }
        assertNotNull(s.category)
        assertTrue(s.shortTitle.isNotBlank())
        assertTrue("skrocony tytul nie moze zawierac separatora", !s.shortTitle.contains('·'))
        assertEquals(s.title, "${s.category} · ${s.shortTitle}")
    }

    @Test fun ownSetsHaveNoCategory() {
        val own = WordSet(id = "x", title = "Moja lista z korepetycji", builtIn = false)
        assertEquals(null, own.category)
        assertEquals("Moja lista z korepetycji", own.shortTitle)
    }

    @Test fun noFolderIsAccidentallyHuge() {
        val bySize = builtIn.groupBy { it.category }.mapValues { it.value.size }
        assertTrue("folder nie powinien miec wiecej niz 20 zestawow: $bySize",
            bySize.values.all { it <= 20 })
    }
}
