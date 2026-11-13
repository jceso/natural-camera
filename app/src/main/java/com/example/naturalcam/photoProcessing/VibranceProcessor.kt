package com.example.naturalcam.photoProcessing

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.pow

data class ColorPreset(
    val id: String,
    val name: String,
    val exposureEv: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val vibrance: Float = 0f,
    val warmth: Float = 0f
)

object ColorPresets {
    val natural = ColorPreset(
        id = "natural",
        name = "Natural",
        exposureEv = -0.10f,
        contrast = 1.06f,
        saturation = 1.06f,
        vibrance = 0.30f
    )

    val vivid = ColorPreset(
        id = "vivid",
        name = "Vivace",
        exposureEv = 0.25f,
        contrast = 1.45f,
        saturation = 2.2f,
        vibrance = 1.5f,
        warmth = 1.2f
    )

    val blackAndWhite = ColorPreset(
        id = "black-and-white",
        name = "Bianco e nero",
        contrast = 1.15f,
        saturation = 0f,
        vibrance = 0f
    )

    val goldenHour = ColorPreset(
        id = "golden-hour",
        name = "Golden Hour",
        exposureEv = 0.05f,
        contrast = 1.08f,
        saturation = 1.18f,
        vibrance = 0.25f,
        warmth = 0.55f
    )

    val arctic = ColorPreset(
        id = "arctic",
        name = "Artico",
        exposureEv = 0.05f,
        contrast = 1.08f,
        saturation = 1.12f,
        vibrance = 0.25f,
        warmth = -0.55f
    )

    val retro = ColorPreset(
        id = "retro",
        name = "Retro",
        exposureEv = 0.02f,
        contrast = 0.92f,
        saturation = 0.78f,
        vibrance = 0.10f,
        warmth = 0.35f
    )

    val dreamy = ColorPreset(
        id = "dreamy",
        name = "Sognante",
        exposureEv = 0.12f,
        contrast = 0.88f,
        saturation = 0.92f,
        vibrance = 0.15f,
        warmth = 0.12f
    )

    val pop = ColorPreset(
        id = "pop",
        name = "Pop",
        exposureEv = 0.02f,
        contrast = 1.25f,
        saturation = 1.40f,
        vibrance = 0.65f
    )

    val noir = ColorPreset(
        id = "noir",
        name = "Noir",
        exposureEv = -0.08f,
        contrast = 1.45f,
        saturation = 0f
    )

    val analogHorror = ColorPreset(
        id = "analog-horror",
        name = "Analog Horror",
        exposureEv = -0.20f,
        contrast = 1.45f,
        saturation = 0.45f,
        vibrance = 0.20f,
        warmth = -1.25f
    )

    val all = listOf(
        natural,
        vivid,
        blackAndWhite,
        goldenHour,
        arctic,
        retro,
        dreamy,
        pop,
        noir,
        analogHorror
    )
}

object VibranceProcessor {

    /**
     * Restituisce una nuova bitmap; source non viene modificata.
     *
     * warmth: valori negativi più freddi, positivi più caldi.
     * vibrance: aumenta soprattutto i colori meno saturi.
     */
    fun apply(source: Bitmap, preset: ColorPreset): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)

        source.getPixels(
            pixels,
            0,
            width,
            0,
            0,
            width,
            height
        )

        val exposure = 2f.pow(preset.exposureEv)
        val output = Bitmap.createBitmap(
            width,
            height,
            Bitmap.Config.ARGB_8888
        )

        for (i in pixels.indices) {
            val color = pixels[i]
            val alpha = Color.alpha(color)

            var r = Color.red(color) / 255f
            var g = Color.green(color) / 255f
            var b = Color.blue(color) / 255f

            // Esposizione e contrasto, applicati ai tre canali.
            r = ((r * exposure - 0.5f) * preset.contrast + 0.5f)
            g = ((g * exposure - 0.5f) * preset.contrast + 0.5f)
            b = ((b * exposure - 0.5f) * preset.contrast + 0.5f)

            // Correzione colore semplice: positiva = più calda.
            r += preset.warmth * 0.04f
            b -= preset.warmth * 0.04f

            r = r.coerceIn(0f, 1f)
            g = g.coerceIn(0f, 1f)
            b = b.coerceIn(0f, 1f)

            // Luminanza usata come base per la saturazione.
            val luminance = 0.2126f * r + 0.7152f * g + 0.0722f * b
            val maxChannel = maxOf(r, g, b)
            val minChannel = minOf(r, g, b)
            val currentSaturation =
                if (maxChannel > 0f) {
                    (maxChannel - minChannel) / maxChannel
                } else {
                    0f
                }

            // La vividezza interviene di più sui colori poco saturi.
            val vibranceFactor =
                1f + preset.vibrance * (1f - currentSaturation)
            val saturationFactor = preset.saturation * vibranceFactor

            r = (luminance + (r - luminance) * saturationFactor)
                .coerceIn(0f, 1f)
            g = (luminance + (g - luminance) * saturationFactor)
                .coerceIn(0f, 1f)
            b = (luminance + (b - luminance) * saturationFactor)
                .coerceIn(0f, 1f)

            pixels[i] = Color.argb(
                alpha,
                (r * 255f).toInt(),
                (g * 255f).toInt(),
                (b * 255f).toInt()
            )
        }

        output.setPixels(
            pixels,
            0,
            width,
            0,
            0,
            width,
            height
        )

        return output
    }
}