package com.example.naturalcam.photoProcessing

data class ColorPreset(
    val id: String,
    val code: String,
    val name: String,

    // Regolazioni cromatiche
    val exposureEv: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val vibrance: Float = 0f,
    val warmth: Float = 0f,

    // Regolazioni tonali
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val fade: Float = 0f,

    // Effetti fotografici
    val grain: Float = 0f,
    val vignette: Float = 0f,
    val sharpness: Float = 0f,
    val denoise: Float = 0f
)

object ColorPresets {
    val natural = ColorPreset(
        id = "natural",
        name = "Natural",
        code = "n",
        exposureEv = -0.10f,
        contrast = 1.06f,
        saturation = 1.06f,
        vibrance = 0.30f
    )

    val natural2 = ColorPreset(
        id = "natural2",
        name = "Natural2",
        code = "n2",
        exposureEv = 0.0f,
        contrast = 1.05f,
        saturation = 1.04f,
        vibrance = 0.25f,
        warmth = 0.05f,
        highlights = -0.10f,
        shadows = 0.10f,
        fade = 0.0f,
        grain = 0.0f,
        vignette = 0.05f,
        sharpness = 0.10f,
        denoise = 0.0f
    )

    val retro = ColorPreset(
        id = "retro",
        name = "Retro",
        code = "r",
        exposureEv = 0.02f,
        contrast = 0.92f,
        saturation = 0.78f,
        vibrance = 0.10f,
        warmth = 0.35f
    )

    val vintage = ColorPreset(
        id = "vintage",
        name = "Vintage",
        code = "vtg",
        exposureEv = 0.05f,
        contrast = 0.90f,
        saturation = 0.78f,
        vibrance = 0.15f,
        warmth = 0.65f,
        highlights = -0.20f,
        shadows = 0.15f,
        fade = 0.35f,
        grain = 0.30f,
        vignette = 0.20f,
        sharpness = -0.10f,
        denoise = 0.0f
    )

    val cinematic = ColorPreset(
        id = "cinematic",
        name = "Cinematic",
        code = "cin",
        exposureEv = -0.10f,
        contrast = 1.30f,
        saturation = 0.90f,
        vibrance = 0.30f,
        warmth = 0.15f,
        highlights = -0.25f,
        shadows = -0.15f,
        fade = 0.05f,
        grain = 0.12f,
        vignette = 0.25f,
        sharpness = 0.15f,
        denoise = 0.0f
    )

    val soft = ColorPreset(
        id = "soft",
        name = "Soft",
        code = "sof",
        exposureEv = 0.15f,
        contrast = 0.82f,
        saturation = 0.85f,
        vibrance = 0.20f,
        warmth = 0.15f,
        highlights = -0.30f,
        shadows = 0.20f,
        fade = 0.15f,
        grain = 0.03f,
        vignette = 0.0f,
        sharpness = -0.15f,
        denoise = 0.05f
    )

    val vivid = ColorPreset(
        id = "vivid",
        name = "Vivace",
        code = "v",
        exposureEv = 0.10f,
        contrast = 1.22f,
        saturation = 1.35f,
        vibrance = 0.75f,
        warmth = 0.10f,
        highlights = -0.10f,
        shadows = 0.05f,
        fade = 0.0f,
        grain = 0.0f,
        vignette = 0.05f,
        sharpness = 0.20f,
        denoise = 0.0f
    )

    val blackAndWhite = ColorPreset(
        id = "black-and-white",
        name = "Bianco e nero",
        code = "b/w",
        contrast = 1.15f,
        saturation = 0f
    )

    val blackAndWhite2 = ColorPreset(
        id = "black-and-white2",
        name = "Bianco-nero2",
        code = "bw2",
        exposureEv = 0.0f,
        contrast = 1.20f,
        saturation = 0f,
        vibrance = 0f,
        warmth = 0f,
        highlights = -0.15f,
        shadows = 0.05f,
        fade = 0.05f,
        grain = 0.12f,
        vignette = 0.10f,
        sharpness = 0.10f,
        denoise = 0.0f
    )

    val goldenHour = ColorPreset(
        id = "golden-hour",
        name = "Golden Hour",
        code = "gh",
        exposureEv = 0.10f,
        contrast = 1.08f,
        saturation = 1.15f,
        vibrance = 0.35f,
        warmth = 0.70f,
        highlights = -0.25f,
        shadows = 0.10f,
        fade = 0.05f,
        grain = 0.03f,
        vignette = 0.08f,
        sharpness = 0.05f,
        denoise = 0.0f
    )

    val arctic = ColorPreset(
        id = "arctic",
        name = "Artico",
        code = "arc",
        exposureEv = 0.05f,
        contrast = 1.12f,
        saturation = 1.05f,
        vibrance = 0.30f,
        warmth = -0.75f,
        highlights = -0.15f,
        shadows = -0.05f,
        fade = 0.0f,
        grain = 0.0f,
        vignette = 0.08f,
        sharpness = 0.10f,
        denoise = 0.0f
    )

    val pop = ColorPreset(
        id = "pop",
        name = "Pop",
        code = "p",
        exposureEv = 0.02f,
        contrast = 1.25f,
        saturation = 1.40f,
        vibrance = 0.65f
    )

    val pop2 = ColorPreset(
        id = "pop2",
        name = "Pop2",
        code = "p2",
        exposureEv = 0.05f,
        contrast = 1.25f,
        saturation = 1.40f,
        vibrance = 0.70f,
        warmth = 0.0f,
        highlights = -0.10f,
        shadows = 0.05f,
        fade = 0.0f,
        grain = 0.0f,
        vignette = 0.05f,
        sharpness = 0.15f,
        denoise = 0.0f
    )

    val noir = ColorPreset(
        id = "noir",
        name = "Noir",
        code = "noi",
        exposureEv = -0.12f,
        contrast = 1.45f,
        saturation = 0f,
        vibrance = 0f,
        warmth = 0f,
        highlights = -0.20f,
        shadows = -0.10f,
        fade = 0.0f,
        grain = 0.18f,
        vignette = 0.30f,
        sharpness = 0.15f,
        denoise = 0.0f
    )

    val analogHorror = ColorPreset(
        id = "analog-horror",
        name = "Analog Horror",
        code = "h",
        exposureEv = -0.25f,
        contrast = 1.40f,
        saturation = 0.50f,
        vibrance = 0.15f,
        warmth = -1.0f,
        highlights = -0.30f,
        shadows = -0.15f,
        fade = 0.08f,
        grain = 0.35f,
        vignette = 0.35f,
        sharpness = 0.10f,
        denoise = 0.0f
    )

    val all = listOf(
        natural,
        natural2,
        retro,
        vintage,
        cinematic,
        soft,
        vivid,
        blackAndWhite,
        blackAndWhite2,
        goldenHour,
        arctic,
        pop,
        pop2,
        noir,
        analogHorror
    )

    private val defaultIds = all.map { it.id }.toSet()

    fun isDefault(id: String): Boolean = id in defaultIds
}