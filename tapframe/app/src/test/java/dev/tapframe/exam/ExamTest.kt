package dev.tapframe.exam

import dev.tapframe.render.ContentType
import dev.tapframe.render.Dither
import dev.tapframe.render.Settings
import dev.tapframe.render.ToneMap
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ExamTest {

    /** A simulated user who always taps the option closest to [ideal]. Returns the result and quad count. */
    private fun runWith(ideal: Settings, type: ContentType = ContentType.PEOPLE, seed: Int = 1): Pair<Settings, Int> {
        fun distance(s: Settings) = abs(s.brightness - ideal.brightness) / 0.1f + abs(s.contrast - ideal.contrast) / 0.4f +
            abs(s.saturation - ideal.saturation) / 0.25f + abs(s.detail - ideal.detail) / 0.5f + (if (s.method == ideal.method) 0f else 1f) + (if (s.tone == ideal.tone) 0f else 1f)
        val exam = Exam(listOf(type) + ContentType.entries.filter { it != type }, { it.defaults }, Random(seed))
        var quads = 0
        while (true) {
            when (val step = exam.step()) {
                is Exam.Step.Quad -> {
                    quads++
                    assertTrue("exam must end", quads < 10)
                    assertEquals("four options", 4, step.options.size)
                    assertEquals("four distinct options", 4, step.options.toSet().size)
                    exam.choose(step.options.indices.minBy { distance(step.options[it]) })
                }
                is Exam.Step.StyleList -> {
                    quads++
                    assertEquals(ContentType.entries.size, step.options.size)
                    exam.choose(step.options.indices.minBy { distance(step.options[it]) })
                }
                is Exam.Step.Done -> return step.result to quads
            }
        }
    }

    @Test
    fun convergesInSevenQuads() {
        val ideal = Settings(Dither.Method.STUCKI, ToneMap.BALANCED, brightness = 0.1f, contrast = -0.4f, saturation = 1.25f, detail = 1.5f)
        for (seed in 1..5) {
            val (result, quads) = runWith(ideal, seed = seed)
            assertEquals(7, quads)
            assertEquals(ToneMap.BALANCED, result.tone)
            assertEquals(1.5f, result.detail, 1e-4f)
            assertEquals(Dither.Method.STUCKI, result.method)
            assertEquals(0.1f, result.brightness, 1e-4f)
            assertEquals(-0.4f, result.contrast, 1e-4f)
            assertEquals(1.25f, result.saturation, 1e-4f)
        }
    }

    @Test
    fun challengerCanWinAfterTheOpeningQuads() {
        // Clustered dot is not among the people candidates; it can only arrive as a challenger.
        val (result, _) = runWith(Settings(Dither.Method.CLUSTERED, ToneMap.VIVID))
        assertEquals(Dither.Method.CLUSTERED, result.method)
    }

    @Test
    fun keepsCurrentWhenItIsBest() {
        val start = ContentType.PEOPLE.defaults
        val (result, _) = runWith(start)
        assertEquals(start, result)
    }

    @Test
    fun styleListFollowsTheRankingAndSetsTheType() {
        val ranking = listOf(ContentType.LANDSCAPE, ContentType.PEOPLE, ContentType.GRAPHIC, ContentType.PIXEL_ART)
        val exam = Exam(ranking, { it.defaults }, Random(4))
        val list = exam.step() as Exam.Step.StyleList
        assertEquals(ContentType.LANDSCAPE.defaults, list.options[0])
        assertTrue(list.labels[0].contains("most likely"))
        assertEquals(ranking.size, list.labels.size)
        exam.choose(list.labels.indexOfFirst { it.startsWith("Pixel") })
        assertEquals(ContentType.PIXEL_ART, exam.type)
        assertTrue(exam.current.nearest)
    }

    @Test
    fun stepIsStableUntilChosen() {
        val exam = Exam(ContentType.entries, { it.defaults }, Random(3))
        exam.choose(0)
        val a = exam.step()
        assertSame(a, exam.step())
        assertTrue(a is Exam.Step.Quad)
    }

    @Test
    fun staysInRangeAtTheEdges() {
        val start = ContentType.PEOPLE.defaults.copy(brightness = Settings.BRIGHTNESS.endInclusive)
        val exam = Exam(ContentType.entries, { if (it == ContentType.PEOPLE) start else it.defaults }, Random(2))
        exam.choose(0); exam.choose(0); exam.choose(0) // style (People first), tone, method
        val quad = exam.step() as Exam.Step.Quad
        assertTrue(quad.options.all { it.brightness in Settings.BRIGHTNESS })
        assertEquals(4, quad.options.toSet().size)
    }
}
