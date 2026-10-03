package com.ywemay.robotcar.control

/** How an eye is drawn. */
enum class EyeShape {
    /** Soft vertical pill — the resting eye. Squashes to a thin lid on a blink. */
    CAPSULE,

    /** Round and wide, with an optional dark pupil — surprise / excitement. */
    ROUND,

    /** Upward arc ( ∩ ) — the "closed, happy" eye. */
    ARC,
}

/** Mouth archetype. */
enum class MouthShape {
    /** Gentle upward curve. */
    SMILE,

    /** Wide, deep upward curve. */
    GRIN,

    /** Neutral horizontal line. */
    FLAT,

    /** Downward curve ( ∩ ). */
    FROWN,

    /** Open "O" — surprise / excitement. */
    OPEN,

    /** Small lopsided curve off to one side. */
    SMIRK,
}

/**
 * The car's moods.
 *
 * An emotion is *pure presentation*: it never touches the serial wire and never
 * moves a motor. It exists so the face can say something about the car's state —
 * and, because it lives on [CarControl], so that the remote web page can change
 * it and the on-device dashboard can see it happen.
 *
 * The geometry below is deliberately declarative. [com.ywemay.robotcar.ui.
 * FaceIllustration] reads these fields and draws; it contains no per-emotion
 * `when`. Adding a mood is adding a row here, nothing else — and both the
 * Compose face and the web button grid are generated from this one list.
 *
 * Everything is plain Kotlin, no Compose types, so the shared command hub stays
 * UI-free: [tint] is a raw ARGB value that the Compose layer turns into a Color
 * and the web layer renders as `#rrggbb`.
 */
enum class Emotion(
    /** Stable identifier used by the HTTP API and the page — never localised. */
    val slug: String,
    /** Human label for the UI. */
    val label: String,
    /** Single-codepoint emoji for the web/dashboard buttons. */
    val emoji: String,
    /** ARGB colour for eyes, mouth and halo. */
    val tint: Long,
    val eyeShape: EyeShape = EyeShape.CAPSULE,
    /** Base openness of the eyes; 1 = as drawn, <1 = lidded. */
    val eyeOpen: Float = 1f,
    /** Brow tilt: >0 drops the inner ends (angry), <0 lifts them (worried). */
    val browTilt: Float = 0f,
    /** Brow lift: >0 raises both brows uniformly (surprised). */
    val browLift: Float = 0f,
    /** Pupil radius as a fraction of the eye radius; 0 = no pupil. */
    val pupil: Float = 0f,
    val mouth: MouthShape = MouthShape.SMILE,
    /** Soft pink cheek blobs. */
    val blush: Boolean = false,
    /** A single teardrop under the left eye. */
    val tear: Boolean = false,
    /** Shut the left eye; the right one stays as drawn. */
    val wink: Boolean = false,
) {
    NEUTRAL(
        slug = "neutral", label = "Neutral", emoji = "\uD83D\uDE10", tint = 0xFF7FE8FF,
        eyeShape = EyeShape.CAPSULE, mouth = MouthShape.SMILE,
    ),
    HAPPY(
        slug = "happy", label = "Happy", emoji = "\uD83D\uDE00", tint = 0xFF7CFFD8,
        eyeShape = EyeShape.ARC, mouth = MouthShape.GRIN,
    ),
    LOVE(
        slug = "love", label = "Love", emoji = "\uD83D\uDE0D", tint = 0xFFFF8FC7,
        eyeShape = EyeShape.ARC, mouth = MouthShape.SMILE, blush = true,
    ),
    EXCITED(
        slug = "excited", label = "Excited", emoji = "\uD83E\uDD29", tint = 0xFFFFE066,
        eyeShape = EyeShape.ROUND, eyeOpen = 1.15f, pupil = 0.30f,
        mouth = MouthShape.GRIN, blush = true,
    ),
    SURPRISED(
        slug = "surprised", label = "Surprised", emoji = "\uD83D\uDE2E", tint = 0xFFB79CFF,
        eyeShape = EyeShape.ROUND, eyeOpen = 1.28f, browLift = 0.16f, pupil = 0.36f,
        mouth = MouthShape.OPEN,
    ),
    SAD(
        slug = "sad", label = "Sad", emoji = "\uD83D\uDE22", tint = 0xFF7FA8E8,
        eyeShape = EyeShape.CAPSULE, eyeOpen = 0.86f, browTilt = -0.42f,
        mouth = MouthShape.FROWN, tear = true,
    ),
    ANGRY(
        slug = "angry", label = "Angry", emoji = "\uD83D\uDE20", tint = 0xFFFF7A6B,
        eyeShape = EyeShape.CAPSULE, eyeOpen = 0.60f, browTilt = 0.58f,
        mouth = MouthShape.FLAT,
    ),
    SLEEPY(
        slug = "sleepy", label = "Sleepy", emoji = "\uD83D\uDE34", tint = 0xFF6E8CA0,
        eyeShape = EyeShape.CAPSULE, eyeOpen = 0.30f, mouth = MouthShape.FLAT,
    ),
    WINK(
        slug = "wink", label = "Wink", emoji = "\uD83D\uDE09", tint = 0xFF7FE8FF,
        eyeShape = EyeShape.CAPSULE, mouth = MouthShape.SMIRK, wink = true,
    ),
    ;

    companion object {
        /** The mood the car wears when nothing else has been asked of it. */
        val DEFAULT: Emotion = NEUTRAL

        /**
         * Resolve a wire value ("happy", "HAPPY", " happy ") to an emotion.
         * Returns null for anything unknown so callers can answer with a helpful
         * 400 instead of silently defaulting.
         */
        fun fromSlug(raw: String?): Emotion? {
            val needle = raw?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.slug == needle }
        }
    }
}
