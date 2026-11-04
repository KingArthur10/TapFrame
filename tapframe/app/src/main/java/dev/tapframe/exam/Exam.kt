package dev.tapframe.exam

import dev.tapframe.render.ContentType
import dev.tapframe.render.Dither
import dev.tapframe.render.Settings
import dev.tapframe.render.ToneMap
import kotlin.random.Random

/**
 * "Eye exam" for tuning one photo: first a scrolling list of every photo style (content type), most
 * likely first, then quads of four options: tone mapping, algorithm, brightness, contrast, detail
 * and color. Each tuning quad holds the current best, one step down, one
 * step up, and a challenger (an untried tone map or algorithm; in the color round, the current
 * settings with hue preservation flipped), in shuffled positions. Pure logic;
 * the UI renders [step] and reports the chosen index back.
 */
class Exam(
    /** Every content type, most likely first (see [ContentType.rank]). */
    private val ranking: List<ContentType>,
    /** Starting settings per content type (defaults or the user's saved profile). */
    private val profile: (ContentType) -> Settings,
    private val random: Random = Random.Default,
    /** Downloaded AI styles (id, label): each is listed after the bundled AI cartoon. */
    private val aiStyles: List<Pair<String, String>> = emptyList(),
) {
    /** The style list's entries: one per content type, plus one per downloaded AI style. */
    private val entries: List<Triple<ContentType, Settings, String>> = ranking.flatMap { t ->
        val base = profile(t)
        if (t == ContentType.CARTOON_AI) listOf(Triple(t, base.copy(aiStyle = Settings.WHITEBOX), t.label)) +
            aiStyles.map { (id, label) -> Triple(t, base.copy(aiStyle = id), "AI style: $label") }
        else listOf(Triple(t, base, t.label))
    }


    sealed class Step {
        /** All photo styles in a scrolling list, most likely first; [labels] name them. */
        class StyleList(val title: String, val round: Int, val rounds: Int, val options: List<Settings>, val labels: List<String>) : Step()

        /** Four options to compare side by side. */
        class Quad(val title: String, val round: Int, val rounds: Int, val options: List<Settings>) : Step()
        class Done(val result: Settings) : Step()
    }

    private enum class Round(val title: String) {
        STYLE("Photo style"), TONE("Tone"), METHOD("Dithering style"), BRIGHTNESS("Brightness"), CONTRAST("Contrast"), DETAIL("Detail"), SATURATION("Color"), DEPTH("Blue depth"),
    }

    private enum class Param(val step: Float, val range: ClosedFloatingPointRange<Float>) {
        BRIGHTNESS(0.1f, Settings.BRIGHTNESS),
        CONTRAST(0.4f, Settings.CONTRAST),
        DETAIL(0.5f, Settings.DETAIL),
        SATURATION(0.25f, Settings.SATURATION),
        DEPTH(0.3f, Settings.BLUE_DEPTH),
    }

    var type: ContentType = ranking.first()
        private set

    var current: Settings = profile(type)
        private set

    private var round = 0
    private var shown: Step? = null
    private val challengedTones = mutableSetOf<ToneMap>()
    private val challengedMethods = mutableSetOf<Dither.Method>()
    // Every tone map was shown in the first quad, so lead with an algorithm challenger.
    private var challengeTone = false

    /** What to show now. Calling it again without a choice returns the same step. */
    fun step(): Step = shown ?: nextStep().also { shown = it }

    /** The user tapped option [index] of the current quad. */
    fun choose(index: Int) {
        current = when (val step = step()) {
            is Step.StyleList -> { type = entries[index].first; step.options[index] }
            is Step.Quad -> step.options[index]
            else -> return
        }
        round++
        shown = null
    }

    private fun nextStep(): Step {
        val rounds = Round.entries
        if (round >= rounds.size) return Step.Done(current)
        var r = rounds[round]
        // Tone mapping does not apply to cartoon effects.
        if (r == Round.TONE && current.effect != dev.tapframe.render.Effect.NONE) { round++; r = rounds[round] }
        if (r == Round.STYLE) {
            val labels = entries.mapIndexed { i, e -> if (i == 0) "${e.third} (most likely)" else e.third }
            return Step.StyleList(r.title, round + 1, rounds.size, entries.map { it.second }, labels)
        }
        val options = when (r) {
            Round.STYLE -> error("handled above")
            Round.TONE -> ToneMap.entries.map { current.copy(tone = it) }
            Round.METHOD -> type.candidates.map { current.copy(method = it) }
            Round.BRIGHTNESS -> tune(Param.BRIGHTNESS)
            Round.CONTRAST -> tune(Param.CONTRAST)
            Round.DETAIL -> tune(Param.DETAIL)
            // The color round's fourth option flips hue preservation instead of a challenger
            // (Vivid ignores it, so it gets a normal challenger).
            Round.SATURATION -> tune(Param.SATURATION, current.copy(keepHue = !current.keepHue).takeIf { current.tone != ToneMap.VIVID })
            Round.DEPTH -> tune(Param.DEPTH)
        }
        return Step.Quad(r.title, round + 1, rounds.size, options.shuffled(random))
    }

    /**
     * Four distinct options: current, one step down and one step up (further steps the other way
     * at a range end), and a challenger; falls back to more steps if no challenger is left.
     */
    private fun tune(p: Param, alternative: Settings? = null): List<Settings> {
        val v = p.get(current)
        val steps = listOf(-1, 1, 2, -2, 3, -3).map { v + it * p.step }
            .filter { it in p.range.start - 1e-4f..p.range.endInclusive + 1e-4f }
            .map { p.set(current, it.coerceIn(p.range)) }
        val options = linkedSetOf(current)
        steps.filter { it != current }.take(2).forEach { options += it }
        (alternative ?: challenger())?.let { options += it }
        steps.forEach { if (options.size < 4) options += it }
        return options.toList()
    }

    /** Alternates between an untried tone map and an untried algorithm, keeping the other settings. */
    private fun challenger(): Settings? {
        val tones = ToneMap.entries.filter { it != current.tone && it !in challengedTones }
        // Never-shown algorithms first ("no dithering" makes no sense for photos), then the grid's runners-up.
        val photo = type == ContentType.PEOPLE || type == ContentType.LANDSCAPE
        val unseen = Dither.Method.entries.filter { it !in type.candidates && !(photo && it == Dither.Method.NONE) }
        val methods = (unseen + type.candidates).filter { it != current.method && it !in challengedMethods }
        val cartoon = current.effect != dev.tapframe.render.Effect.NONE
        val useTone = !cartoon && ((challengeTone && tones.isNotEmpty()) || methods.isEmpty())
        challengeTone = !challengeTone
        return if (useTone) tones.firstOrNull()?.also { challengedTones += it }?.let { current.copy(tone = it) }
        else methods.firstOrNull()?.also { challengedMethods += it }?.let { current.copy(method = it) }
    }

    private fun Param.get(s: Settings) = when (this) {
        Param.BRIGHTNESS -> s.brightness
        Param.CONTRAST -> s.contrast
        Param.DETAIL -> s.detail
        Param.SATURATION -> s.saturation
        Param.DEPTH -> s.blueDepth
    }

    private fun Param.set(s: Settings, v: Float) = when (this) {
        Param.BRIGHTNESS -> s.copy(brightness = v)
        Param.CONTRAST -> s.copy(contrast = v)
        Param.DETAIL -> s.copy(detail = v)
        Param.SATURATION -> s.copy(saturation = v)
        Param.DEPTH -> s.copy(blueDepth = v)
    }
}
