package org.futo.voiceinput.shared.whisper

import android.content.Context
import org.futo.voiceinput.shared.types.ModelLoader
import org.futo.voiceinput.shared.types.SpeechModel


class ModelManager(
    val context: Context
) {
    private val loadedModels: HashMap<Any, SpeechModel> = hashMapOf()

    fun obtainModel(model: ModelLoader): SpeechModel {
        val key = model.key(context)
        if (!loadedModels.contains(key)) {
            loadedModels[key] = model.loadModel(context)
        }

        return loadedModels[key]!!
    }

    fun cancelAll() {
        loadedModels.forEach {
            it.value.cancel()
        }
    }

    suspend fun cleanUp() {
        for (model in loadedModels.entries) {
            model.value.cancel()
            model.value.close()
        }

        loadedModels.clear()
    }
}
