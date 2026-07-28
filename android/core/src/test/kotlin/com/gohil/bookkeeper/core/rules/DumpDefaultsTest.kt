package com.gohil.bookkeeper.core.rules

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Writes the shipped defaults to build/ so the app's asset can be generated from the single
 * source of truth in [CategoryRules.defaults] rather than hand-maintained alongside it.
 */
class DumpDefaultsTest {

    @Test
    fun `defaults round-trip through JSON`() {
        val json = CategoryRules.defaults().toJson()
        assertEquals(CategoryRules.defaults(), CategoryRules.fromJson(json))

        File("build/generated-assets").apply { mkdirs() }
            .resolve("categories.json")
            .writeText(json)
    }
}
