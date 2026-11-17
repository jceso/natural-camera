package com.example.naturalcam.photoProcessing

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.UUID

class PresetStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "presets.json"))

    suspend fun load(): List<ColorPreset> = withContext(Dispatchers.IO) {
        if (!file.baseFile.exists())
            ColorPresets.all.also { saveOnIo(it) }
        else {
            try {
                decode(file.openRead().bufferedReader().use { it.readText() })
            } catch (_: Exception) {
                // Se il file è danneggiato, riparte dai preset predefiniti.
                ColorPresets.all.also { saveOnIo(it) }
            }
        }
    }

    suspend fun save(presets: List<ColorPreset>) = withContext(Dispatchers.IO) {
        require(presets.map { it.id }.distinct().size == presets.size) {
            "Ogni preset deve avere un ID univoco"
        }
        require(presets.all { it.name.isNotBlank() }) {
            "Il nome del preset non può essere vuoto"
        }

        saveOnIo(presets)
    }

    private fun decode(json: String): List<ColorPreset> {
        val root = JSONObject(json)
        val array = root.getJSONArray("presets")

        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    ColorPreset(
                        id = item.getString("id"),
                        name = item.getString("name"),
                        code = item.getString("code"),
                        exposureEv = item.getDouble("exposureEv").toFloat(),
                        contrast = item.getDouble("contrast").toFloat(),
                        saturation = item.getDouble("saturation").toFloat(),
                        vibrance = item.getDouble("vibrance").toFloat(),
                        warmth = item.getDouble("warmth").toFloat()
                    )
                )
            }
        }
    }

    private fun saveOnIo(presets: List<ColorPreset>) {
        val array = JSONArray()

        presets.forEach { preset ->
            array.put(
                JSONObject().apply {
                    put("id", preset.id)
                    put("name", preset.name)
                    put("exposureEv", preset.exposureEv.toDouble())
                    put("contrast", preset.contrast.toDouble())
                    put("saturation", preset.saturation.toDouble())
                    put("vibrance", preset.vibrance.toDouble())
                    put("warmth", preset.warmth.toDouble())
                }
            )
        }

        val root = JSONObject().put("version", 1)
                               .put("presets", array)

        val output = file.startWrite()
        try {
            output.write(root.toString(2).toByteArray(Charsets.UTF_8))
            output.flush()
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    companion object {
        fun newCustomPresetId(): String = UUID.randomUUID().toString()
    }
}