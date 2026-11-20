package com.matura.app

import com.matura.app.data.BuiltInSets
import com.matura.app.game.Matching
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Zestaw przekrojowy: ma byc losowy, bez powtorek i grywalny tak jak kazdy inny. */
class RandomMixTest {

    @Test fun drawsRequestedNumberOfWords() {
        assertEquals(40, BuiltInSets.randomMix(seed = 1L).entries.size)
        assertEquals(12, BuiltInSets.randomMix(count = 12, seed = 1L).entries.size)
    }

    @Test fun hasNoDuplicateTerms() {
        val e = BuiltInSets.randomMix(count = 200, seed = 7L).entries
        val keys = e.map { Matching.normalize(it.term) }
        assertEquals("losowanie nie moze powtarzac hasel", keys.size, keys.toSet().size)
    }

    @Test fun differentSeedsGiveDifferentDraws() {
        val a = BuiltInSets.randomMix(seed = 1L).entries.map { it.term }
        val b = BuiltInSets.randomMix(seed = 2L).entries.map { it.term }
        assertNotEquals(a, b)
    }

    @Test fun sameSeedIsReproducible() {
        assertEquals(
            BuiltInSets.randomMix(seed = 42L).entries.map { it.term },
            BuiltInSets.randomMix(seed = 42L).entries.map { it.term },
        )
    }

    @Test fun everyEntryIsPlayable() {
        val e = BuiltInSets.randomMix(count = 300, seed = 3L).entries
        assertTrue(e.all { it.term.isNotBlank() && it.definition.isNotBlank() })
    }

    @Test fun drawSpansManyTopics() {
        // przekrojowy znaczy przekrojowy - 40 slow ma dotykac wielu dzialow
        val byTerm = BuiltInSets.all().flatMap { s -> s.entries.map { it.term to s.category } }.toMap()
        val topics = BuiltInSets.randomMix(seed = 11L).entries.mapNotNull { byTerm[it.term] }.toSet()
        assertTrue("dzialow w losowaniu: ${topics.size}", topics.size >= 8)
    }

    @Test fun keepsTheStableIdSoScoresGroupTogether() {
        assertEquals(BuiltInSets.MIX_ID, BuiltInSets.randomMix(seed = 1L).id)
    }
}
