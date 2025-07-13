package dev.ztssst.voicevox_tts

import android.util.Log
import jp.hiroshiba.voicevoxcore.OpenJtalk
import jp.hiroshiba.voicevoxcore.Synthesizer
import jp.hiroshiba.voicevoxcore.VoiceModel

class VoicevoxTTSEngine(voiceModelPath: String, openJtalkDictPath: String){
    private val synthesizer: Synthesizer
    @Suppress("PrivatePropertyName")
    private val TAG = "VoicevoxTTSService"

    init {
        val model = VoiceModel(voiceModelPath)
        val jtalk = OpenJtalk(openJtalkDictPath)
        val synthesizer = Synthesizer.builder(jtalk).build()
        synthesizer.loadVoiceModel(model)
        this.synthesizer = synthesizer
        Log.d(TAG, "VoicevoxTTSService Initialized")
    }

    fun synthesis(text: String, speakerId: Int = 14): ByteArray{ // 冥鳴ひまりでやるので、defaultのstyleIdは14
        Log.d("${TAG}->Synthesizer", "Synthesis started (isGPUMode = ${synthesizer.isGpuMode})")
        val synthStartTime = System.currentTimeMillis()
        val data = synthesizer.tts(text, speakerId).execute()
        Log.d("${TAG}->Synthesizer", "Synthesis finished: data.size = ${data.size}")
        Log.d("${TAG}->Synthesizer", "Synthesis elapsed: ${System.currentTimeMillis() - synthStartTime}ms")
        return data
    }
}