package dev.ztssst.voicevox_tts

import android.util.Log
import jp.hiroshiba.voicevoxcore.blocking.Onnxruntime
import jp.hiroshiba.voicevoxcore.blocking.OpenJtalk
import jp.hiroshiba.voicevoxcore.blocking.Synthesizer
import jp.hiroshiba.voicevoxcore.blocking.VoiceModelFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VoicevoxTTSEngine(voiceModelPath: String, openJtalkDictPath: String){
    private val synthesizer: Synthesizer
    @Suppress("PrivatePropertyName")
    private val TAG = "VoicevoxTTSEngine"

    init {
        // jniLibs に同梱した libvoicevox_onnxruntime.so を読み込む
        val ort = Onnxruntime.loadOnce().perform()
        val jtalk = OpenJtalk(openJtalkDictPath)
        val synthesizer = Synthesizer.builder(ort, jtalk).build()
        VoiceModelFile(voiceModelPath).use { synthesizer.loadVoiceModel(it).perform() }
        this.synthesizer = synthesizer
        Log.d(TAG, "VoicevoxTTSEngine Initialized")
    }

    /** 16-bit PCM（24kHz, モノラル）を返す */
    fun synthesis(text: String, styleId: Int = 14): ByteArray{ // 冥鳴ひまりでやるので、defaultのstyleIdは14
        Log.d("${TAG}->Synthesizer", "Synthesis started (isGPUMode = ${synthesizer.isGpuMode})")
        val synthStartTime = System.currentTimeMillis()
        val wav = synthesizer.tts(text, styleId).perform()
        Log.d("${TAG}->Synthesizer", "Synthesis finished: wav.size = ${wav.size}")
        Log.d("${TAG}->Synthesizer", "Synthesis elapsed: ${System.currentTimeMillis() - synthStartTime}ms")
        return pcmFromWav(wav)
    }

    /** WAVのヘッダを読み飛ばして data チャンクの中身だけを取り出す */
    private fun pcmFromWav(wav: ByteArray): ByteArray {
        val buf = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(12) // "RIFF" <size> "WAVE"
        while (buf.remaining() >= 8) {
            val id = String(wav, buf.position(), 4, Charsets.US_ASCII)
            buf.position(buf.position() + 4)
            val size = buf.int
            if (id == "data") return wav.copyOfRange(buf.position(), buf.position() + size)
            buf.position(buf.position() + size + (size and 1))
        }
        throw IllegalArgumentException("data chunk not found in WAV")
    }
}
