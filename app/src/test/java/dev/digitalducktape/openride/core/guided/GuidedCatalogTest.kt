package dev.digitalducktape.openride.core.guided

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidedCatalogTest {

    private val sample = GuidedClass(
        id = "abcdefghijk",
        title = "t",
        instructor = "i",
        durationSec = 600,
        type = "HIIT",
        cues = listOf(Cue(0, "Get ready"), Cue(30, "Sprint", listOf(100, 120), listOf(35, 45)), Cue(90, "Recover")),
    )

    @Test
    fun `the cue in force is the last one started, and the next is the first still to come`() {
        assertEquals("Get ready", sample.cueAt(29)?.label)
        assertEquals("Sprint", sample.cueAt(30)?.label)
        assertEquals("Recover", sample.cueAt(599)?.label)
        assertEquals("Sprint", sample.nextCue(0)?.label)
        assertNull(sample.nextCue(90))
        assertEquals(100..120, sample.cueAt(45)?.cadenceRange)
        assertNull(sample.cueAt(5)?.resistanceRange)
    }

    @Test
    fun `parse drops classes the ride screen can't use`() {
        val text = """
            {"classes": [
              {"id": "good_id-123", "title": "a", "instructor": "i", "durationSec": 60, "type": "HIIT",
               "cues": [{"t": 0, "label": "Go"}, {"t": 10, "label": "Stop"}]},
              {"id": "bad'id<x>", "title": "b", "instructor": "i", "durationSec": 60, "type": "HIIT", "cues": []},
              {"id": "unordered12", "title": "c", "instructor": "i", "durationSec": 60, "type": "HIIT",
               "cues": [{"t": 10, "label": "Go"}, {"t": 5, "label": "Stop"}]}
            ]}
        """.trimIndent()
        assertEquals(listOf("good_id-123"), GuidedCatalog.parse(text).classes.map { it.id })
    }

    /**
     * The bundled catalog is the one hand-written file here: every sheet in it must parse, keep
     * every class, and hold targets a rider can actually follow (tools/guided/README.md).
     */
    @Test
    fun `every bundled cue sheet is well formed`() {
        val text = File("src/main/assets/${GuidedRepository.ASSET_PATH}").readText()
        val raw = Regex("\"id\"\\s*:").findAll(text).count()
        val catalog = GuidedCatalog.parse(text)
        assertEquals("a class failed the id or order check", raw, catalog.classes.size)
        assertEquals("duplicate class ids", catalog.classes.size, catalog.classes.map { it.id }.toSet().size)

        for (c in catalog.classes) {
            val where = "${c.id} (${c.title})"
            assertTrue("$where: first cue must be at 0", c.cues.firstOrNull()?.t == 0)
            assertTrue("$where: cue past the end", c.cues.all { it.t < c.durationSec })
            assertTrue("$where: blank label", c.cues.all { it.label.isNotBlank() })
            // The ride's top bar fits "label · next label in 0:12" only with short labels.
            c.cues.firstOrNull { it.label.length > 28 }?.let { throw AssertionError("$where: label too long: ${it.label}") }
            for (cue in c.cues) {
                cue.cadence?.let {
                    val r = cue.cadenceRange
                    assertTrue("$where @${cue.t}: cadence $it", r != null && r.first >= 40 && r.last <= 130)
                }
                cue.resistance?.let {
                    val r = cue.resistanceRange
                    assertTrue("$where @${cue.t}: resistance $it", r != null && r.first >= 0 && r.last <= 100)
                }
            }
        }
    }
}
