package com.example.naturalcam.photoProcessing

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.pow
import kotlin.math.sqrt

object PhotoProcessor {

    fun apply(source: Bitmap, preset: ColorPreset): Bitmap {
        val width = source.width
        val height = source.height

        val input = IntArray(width * height)
        source.getPixels(input, 0, width, 0, 0, width, height)

        // Passaggio spaziale opzionale.
        val pixels = if (preset.sharpness != 0f || preset.denoise > 0f)
                        applySpatialEffects(input, width, height, preset)
                    else
                        input

        val output = IntArray(width * height)

        val exposure = 2f.pow(preset.exposureEv.coerceIn(-2f, 2f))
        val contrast = preset.contrast.coerceIn(0.5f, 1.8f)
        val saturation = preset.saturation.coerceIn(0f, 2f)
        val vibrance = preset.vibrance.coerceIn(-1f, 1.5f)
        val warmth = preset.warmth.coerceIn(-1f, 1f)
        val highlights = preset.highlights.coerceIn(-1f, 1f)
        val shadows = preset.shadows.coerceIn(-1f, 1f)
        val fade = preset.fade.coerceIn(0f, 1f)
        val grain = preset.grain.coerceIn(0f, 1f)
        val vignette = preset.vignette.coerceIn(0f, 1f)

        val centerX = width / 2f
        val centerY = height / 2f

        val maxDistance = sqrt(centerX * centerX + centerY * centerY).coerceAtLeast(1f)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val color = pixels[index]

                val alpha = Color.alpha(color)

                var r = Color.red(color) / 255f
                var g = Color.green(color) / 255f
                var b = Color.blue(color) / 255f

                // 1. Esposizione e contrasto.
                r = (r * exposure - 0.5f) * contrast + 0.5f
                g = (g * exposure - 0.5f) * contrast + 0.5f
                b = (b * exposure - 0.5f) * contrast + 0.5f

                // 2. Temperatura colore.
                r += warmth * 0.04f
                b -= warmth * 0.04f

                r = r.coerceIn(0f, 1f)
                g = g.coerceIn(0f, 1f)
                b = b.coerceIn(0f, 1f)

                // 3. Luminanza.
                val luminance = 0.2126f * r +
                                0.7152f * g +
                                0.0722f * b

                // 4. Alte luci.
                val highlightMask = smoothStep(0.35f, 0.90f, luminance)

                val highlightAdjustment = highlights * highlightMask * 0.25f

                r += highlightAdjustment
                g += highlightAdjustment
                b += highlightAdjustment

                // 5. Ombre.
                val shadowMask = 1f - smoothStep(0.05f, 0.55f, luminance)
                val shadowAdjustment = shadows * shadowMask * 0.25f

                r += shadowAdjustment
                g += shadowAdjustment
                b += shadowAdjustment

                // 6. Fade: solleva i neri e ammorbidisce i bianchi.
                if (fade > 0f) {
                    val fadeAmount = fade * 0.12f

                    r = r * (1f - fadeAmount) + fadeAmount * 0.65f
                    g = g * (1f - fadeAmount) + fadeAmount * 0.65f
                    b = b * (1f - fadeAmount) + fadeAmount * 0.65f
                }

                r = r.coerceIn(0f, 1f)
                g = g.coerceIn(0f, 1f)
                b = b.coerceIn(0f, 1f)

                // 7. Saturazione e vibrance.
                val maxChannel = maxOf(r, g, b)
                val minChannel = minOf(r, g, b)

                val currentSaturation =
                    if (maxChannel > 0f)
                        (maxChannel - minChannel) / maxChannel
                    else
                        0f

                val vibranceFactor = 1f + vibrance * (1f - currentSaturation)
                val saturationFactor = saturation * vibranceFactor

                val currentLuminance = 0.2126f * r +
                                       0.7152f * g +
                                       0.0722f * b

                r = currentLuminance + (r - currentLuminance) * saturationFactor
                g = currentLuminance + (g - currentLuminance) * saturationFactor
                b = currentLuminance + (b - currentLuminance) * saturationFactor

                // 8. Vignettatura.
                if (vignette > 0f) {
                    val dx = (x - centerX) / maxDistance
                    val dy = (y - centerY) / maxDistance

                    val distance = sqrt(dx * dx + dy * dy)
                    val mask = smoothStep(0.30f, 0.95f, distance)

                    val factor = 1f - vignette * mask * 0.65f

                    r *= factor
                    g *= factor
                    b *= factor
                }

                // 9. Grana deterministica.
                if (grain > 0f) {
                    val noise = pixelNoise(x, y) * grain * 0.055f

                    r += noise
                    g += noise
                    b += noise
                }

                output[index] = Color.argb(alpha,
                    (r.coerceIn(0f, 1f) * 255f).toInt(),
                    (g.coerceIn(0f, 1f) * 255f).toInt(),
                    (b.coerceIn(0f, 1f) * 255f).toInt())
            }
        }

        return Bitmap.createBitmap(
            output,
            width,
            height,
            Bitmap.Config.ARGB_8888
        )
    }

    private fun smoothStep(
        edge0: Float,
        edge1: Float,
        value: Float
    ): Float {
        val t = (
                (value - edge0) / (edge1 - edge0)
                ).coerceIn(0f, 1f)

        return t * t * (3f - 2f * t)
    }

    private fun pixelNoise(x: Int, y: Int): Float {
        var hash = x * 374761393 + y * 668265263

        hash = (hash xor (hash ushr 13)) * 1274126177
        hash = hash xor (hash ushr 16)

        return ((hash and 0xFFFF) / 32767.5f) - 1f
    }

    private fun applySpatialEffects(
        input: IntArray,
        width: Int,
        height: Int,
        preset: ColorPreset
    ): IntArray {
        val output = IntArray(input.size)

        val denoise = preset.denoise.coerceIn(0f, 1f)
        val sharpness = preset.sharpness.coerceIn(-1f, 1f)

        for (y in 0 until height) {
            for (x in 0 until width) {

                val index = y * width + x
                val center = input[index]

                val a = Color.alpha(center)

                val cr = Color.red(center).toFloat()
                val cg = Color.green(center).toFloat()
                val cb = Color.blue(center).toFloat()

                var sumR = 0f
                var sumG = 0f
                var sumB = 0f
                var totalWeight = 0f

                // Filtro gaussiano 3x3.
                for (dy in -1..1) {
                    for (dx in -1..1) {

                        val nx = (x + dx).coerceIn(0, width - 1)
                        val ny = (y + dy).coerceIn(0, height - 1)

                        val neighbor = input[ny * width + nx]

                        val weight = when {
                            dx == 0 && dy == 0 -> 4f
                            dx == 0 || dy == 0 -> 2f
                            else -> 1f
                        }

                        sumR += Color.red(neighbor) * weight
                        sumG += Color.green(neighbor) * weight
                        sumB += Color.blue(neighbor) * weight

                        totalWeight += weight
                    }
                }

                val blurR = sumR / totalWeight
                val blurG = sumG / totalWeight
                val blurB = sumB / totalWeight

                // Denoise: avvicina i pixel alla media locale.
                // Sharpness: enfatizza le differenze locali.
                val resultR = cr + (blurR - cr) * denoise +
                            (cr - blurR) * sharpness

                val resultG = cg + (blurG - cg) * denoise +
                            (cg - blurG) * sharpness

                val resultB = cb + (blurB - cb) * denoise +
                            (cb - blurB) * sharpness

                output[index] = Color.argb(a,
                    resultR.toInt().coerceIn(0, 255),
                    resultG.toInt().coerceIn(0, 255),
                    resultB.toInt().coerceIn(0, 255))
            }
        }

        return output
    }
}