package com.example.otrdial

/** Editorial associations, never automatic recording deduplication. IDs refer to preserved catalogues. */
object ProgrammeDirectory {
    data class Programme(val name: String, val sourceIds: Set<String>, val directoryIds: Set<String>, val stationIds: Set<String>)
    val entries = listOf(
        Programme("Gunsmoke", setOf("gunsmoke"), setOf("archive-11","otrwesterns-gunsmoke-otrwesterns-com","radio-26-gunsmoke-radio-every-episode"), setOf("aotr-a68303","v13-gunsmoke-24-7","v13-wrcw-gunsmoke","gunsmoke-radio-every-episode")),
        Programme("X Minus One", setOf("xminusone"), setOf("theater-of-mind-x-minus-one-old-time-radio"), emptySet()),
        Programme("Our Miss Brooks", setOf("missbrooks"), emptySet(), emptySet()),
        Programme("Jack Benny", emptySet(), setOf("old-time-retro-the-jack-benny-program-old-time-radio"), emptySet()),
        Programme("Suspense", emptySet(), setOf("archive-12","old-time-retro-suspense-old-time-radio"), setOf("wotr-suspense","v13-suspense-24-7"))
    )
}
