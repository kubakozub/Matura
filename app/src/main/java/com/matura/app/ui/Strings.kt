package com.matura.app.ui

import com.matura.app.model.UiLanguage

/**
 * Tiny two-language string table. OPTIONS offers "change language" in the design
 * document, and the app itself is bilingual PL/EN.
 */
class Str(private val lang: UiLanguage) {
    private fun pick(pl: String, en: String) = if (lang == UiLanguage.PL) pl else en

    val appTagline get() = pick("Verbum + Game", "Verbum + Game")
    val playAnonymously get() = pick("GRAJ ANONIMOWO", "PLAY ANONYMOUSLY")
    val offlineNote
        get() = pick(
            "Bez kont i bez logowania — wpisz nazwę i graj. Wyniki zapisują się lokalnie na telefonie.",
            "No accounts, no sign-in — pick a name and play. Scores are stored locally on your device."
        )
    val modeClassic get() = pick("KLASYCZNY", "CLASSIC")
    val modeTrueFalse get() = pick("PRAWDA / FAŁSZ", "TRUE / FALSE")
    val modeMultiple get() = pick("WIELOKROTNY WYBÓR", "MULTIPLE CHOICE")
    val modeTimeAttack get() = pick("NA CZAS", "TIME ATTACK")
    val modeTrueFalseDesc get() = pick("Oceń, czy tłumaczenie pasuje", "Decide if the translation fits")
    val modeMultipleDesc get() = pick("Wybierz poprawne z czterech", "Pick the right one of four")
    val modeTimeAttackDesc get() = pick("Ile zdążysz, zanim czas minie", "How many before the clock runs out")
    val filterAll get() = pick("WSZYSTKIE", "ALL")
    val yourName get() = pick("Twoja nazwa", "Your name")

    val play get() = pick("GRAJ", "PLAY")
    val sets get() = pick("ZESTAWY", "SETS")
    val options get() = pick("OPCJE", "OPTIONS")
    val achievements get() = pick("OSIĄGNIĘCIA", "ACHIEVEMENTS")
    val scores get() = pick("WYNIKI", "SCORES")
    val exit get() = pick("WYJŚCIE", "EXIT")
    val back get() = pick("Wróć", "Back")

    val chooseSet get() = pick("Wybierz zestaw", "Choose a set")
    val chooseMode get() = pick("Wybierz tryb", "Choose a mode")
    val classic get() = pick("CLASSIC", "CLASSIC")
    val classicDesc
        get() = pick(
            "Wpisuj tłumaczenia, zanim potworki dojdą do działka.",
            "Type translations before the monsters reach the cannon."
        )
    val createSet get() = pick("Utwórz zestaw", "Create a set")
    val editSet get() = pick("Edytuj zestaw", "Edit set")
    val setTitle get() = pick("Tytuł zestawu", "Set title")
    val terms get() = pick("Hasła", "Terms")
    val definitions get() = pick("Tłumaczenia", "Definitions")
    val addRow get() = pick("Dodaj wiersz", "Add row")
    val save get() = pick("Zapisz", "Save")
    val delete get() = pick("Usuń", "Delete")
    val confirmDelete get() = pick("Na pewno?", "Sure?")
    val deleteHint
        get() = pick(
            "Wbudowanych zestawów nie da się usunąć. Własne kasujesz koszem — dwa razy, żeby nie zrobić tego przypadkiem.",
            "Built-in sets cannot be removed. Your own go with the bin — twice, so it does not happen by accident."
        )
    val swapLanguages get() = pick("Zamień strony", "Swap sides")
    val builtIn get() = pick("wbudowany", "built-in")
    val entriesLabel get() = pick("haseł", "entries")
    val playedLabel get() = pick("rozegrań", "plays")
    val commaHint
        get() = pick(
            "Kilka tłumaczeń rozdziel przecinkiem — każde będzie zaliczone.",
            "Separate alternative translations with commas — each one is accepted."
        )
    val needTwoEntries
        get() = pick(
            "Zestaw musi mieć przynajmniej jedno wypełnione hasło z tłumaczeniem.",
            "A set needs at least one row with both sides filled in."
        )

    val sound get() = pick("Dźwięk", "Sound")
    val lives get() = pick("Życia", "Lives")
    val texture get() = pick("Tekstury", "Textures")
    val speed get() = pick("Tempo", "Speed")
    val speedSlow get() = pick("WOLNO", "SLOW")
    val speedNormal get() = pick("NORMALNIE", "NORMAL")
    val speedFast get() = pick("SZYBKO", "FAST")
    val speedNote
        get() = pick(
            "Tempo ustawia szybkość potworków. Na WOLNO masz więcej czasu na wpisanie " +
                "dłuższych haseł — punkty liczą się tak samo.",
            "Speed sets how fast monsters walk. On SLOW you get more time for longer " +
                "answers — scoring is unchanged."
        )
    val language get() = pick("Język", "Language")
    val direction get() = pick("Kierunek tłumaczenia", "Translation direction")
    val dirTermToDef get() = pick("hasło → tłumaczenie", "term → definition")
    val dirDefToTerm get() = pick("tłumaczenie → hasło", "definition → term")
    val dirRandom get() = pick("losowo", "random")
    val soundNote
        get() = pick(
            "Dźwięk gra prostymi tonami generowanymi na urządzeniu — nie ma plików audio.",
            "Sound uses simple tones generated on the device — there are no audio assets."
        )

    val score get() = pick("Punkty", "Score")
    val level get() = pick("Poziom", "Level")
    val gameOver get() = pick("KONIEC GRY", "GAME OVER")
    val playAgain get() = pick("Jeszcze raz", "Play again")
    val toMenu get() = pick("Do menu", "To menu")
    val pause get() = pick("Pauza", "Pause")
    val resume get() = pick("Wróć do gry", "Resume")
    val killsLabel get() = pick("Zabicia", "Kills")
    val bestStreakLabel get() = pick("Najdłuższa seria", "Best streak")
    val noScores get() = pick("Brak wyników — zagraj pierwszą grę.", "No scores yet — play your first game.")
    val gamesPlayed get() = pick("Rozegrane gry", "Games played")
    val totalKills get() = pick("Łącznie zabić", "Total kills")
    val bestScore get() = pick("Najlepszy wynik", "Best score")
    val unlocked get() = pick("zdobyte", "unlocked")
    val locked get() = pick("zablokowane", "locked")
    val emptySetWarning
        get() = pick(
            "Ten zestaw nie ma hasła z tłumaczeniem — uzupełnij go w SETS.",
            "This set has no usable rows — fill it in under SETS."
        )
    val topSet get() = pick("Najpopularniejszy zestaw", "Most played set")
    val bestRuns get() = pick("Najlepsze przebiegi", "Best runs")
}
