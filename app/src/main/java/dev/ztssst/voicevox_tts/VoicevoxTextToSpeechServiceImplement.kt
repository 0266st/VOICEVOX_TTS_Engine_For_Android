package dev.ztssst.voicevox_tts

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future

@Suppress("PrivatePropertyName")
class VoicevoxTextToSpeechServiceImplement : TextToSpeechService() {
    private val TAG: String = "VoicevoxTextToSpeechService"
    // モデルのコピーや辞書の解凍に時間がかかるので、初期化はバックグラウンドで行い、合成時に完了を待つ
    private val initExecutor = Executors.newSingleThreadExecutor()
    private lateinit var ttsEngine: Future<VoicevoxTTSEngine>

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "OnCreate Started")
        ttsEngine = initExecutor.submit<VoicevoxTTSEngine> {
            prepareResources()
            VoicevoxTTSEngine(File(filesDir, "model.vvm").absolutePath, File(filesDir, "open_jtalk_dict").absolutePath)
                .also { Log.d(TAG, "Initialization Finished") }
        }
        Log.d(TAG, "OnCreate Finished")
    }

    override fun onDestroy() {
        initExecutor.shutdown()
        super.onDestroy()
    }

    /** res/raw のモデルと辞書を filesDir に展開する。アプリが更新されたときだけやり直す */
    private fun prepareResources() {
        val stamp = File(filesDir, "resources.stamp")
        val installed = packageManager.getPackageInfo(packageName, 0).lastUpdateTime.toString()
        if (stamp.exists() && stamp.readText() == installed) {
            Log.d(TAG, "resources are up to date, skipping copy")
            return
        }
        stamp.delete()
        resources.openRawResource(R.raw.model).use { input ->
            File(filesDir, "model.vvm").outputStream().use { input.copyTo(it) }
        }
        val dictDir = File(filesDir, "open_jtalk_dict")
        dictDir.deleteRecursively()
        resources.openRawResource(R.raw.open_jtalk_dict).use { unzipSafely(it, filesDir) }
        stamp.writeText(installed)
        Log.d(TAG, "resources copied to $filesDir")
    }

    override fun onIsLanguageAvailable(lang: String, country: String, variant: String): Int {
        Log.d("${TAG}->onIsLanguageAvailable", "onIsLanguageAvailable called with arguments: $lang, $country, $variant")
        // 言語が利用可能かどうかを返す
        if ("jpn" == lang) {
            Log.d("${TAG}->onLoadLanguage", "return TextToSpeech.LANG_AVAILABLE")
            return TextToSpeech.LANG_AVAILABLE
        }
        Log.d("${TAG}->onLoadLanguage", "return TextToSpeech.LANG_NOT_SUPPORTED")
        return TextToSpeech.LANG_NOT_SUPPORTED
    }

    override fun onGetLanguage(): Array<String> { // used on api level 18 or before
        Log.d("${TAG}->onGetLanguage", "onGetLanguage called")
        return arrayOf("jpn")
    }

    override fun onLoadLanguage(lang: String, country: String, variant: String): Int {
        Log.d("${TAG}->onLoadLanguage", "onLoadLanguage called with arguments: $lang, $country, $variant")
        // 言語データのロード処理
        if ("jpn" == lang) {
            Log.d("${TAG}->onLoadLanguage", "return TextToSpeech.LANG_AVAILABLE")
            return TextToSpeech.LANG_AVAILABLE
        }
        Log.d("${TAG}->onLoadLanguage", "return TextToSpeech.LANG_NOT_SUPPORTED")
        return TextToSpeech.LANG_NOT_SUPPORTED
    }

    override fun onGetVoices(): List<Voice> {
        Log.d("${TAG}->onGetVoices", "onGetVoices called")
        val arr = ArrayList<Voice>()
        // Voice(name: String!, locale: Locale!, quality: Int, latency: Int, requiresNetworkConnection: Boolean, features: MutableSet<String!>!)
        arr.add(Voice("冥鳴ひまり", Locale.JAPANESE, 1, 1, false, mutableSetOf(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS))) // TODO:どうにかしてSynthesizerかもしくはVoiceModelなんかからStyleId系を持ってきたい
        Log.d("${TAG}->onGetVoices", "return arr = $arr")
        return arr
    }

    override fun onStop() {
        Log.d("${TAG}->onStop", "onStop called")
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        Log.d("${TAG}->onSynthesizeText", "request.charSequenceText = ${request.charSequenceText}")
        val engine = try {
            ttsEngine.get()
        } catch (e: InterruptedException) {
            // 初期化を待っているあいだに、合成のスレッドが止められた。割り込みの印を戻して、エラーを返す
            Thread.currentThread().interrupt()
            Log.w("${TAG}->onSynthesizeText", "interrupted while waiting for initialization")
            callback.error(TextToSpeech.ERROR_SERVICE)
            return
        } catch (e: ExecutionException) {
            Log.e("${TAG}->onSynthesizeText", "initialization failed", e.cause)
            callback.error(TextToSpeech.ERROR_SERVICE)
            return
        }
        val audioData = try {
            engine.synthesis(request.charSequenceText.toString())
        } catch (e: Exception) {
            Log.e("${TAG}->onSynthesizeText", "synthesis failed", e)
            callback.error(TextToSpeech.ERROR_SYNTHESIS)
            return
        }
        val maxBufferSize: Int = callback.maxBufferSize
        callback.start(24000, AudioFormat.ENCODING_PCM_16BIT, 1)

        var offset = 0
        while (offset < audioData.size) {
            val bytesToSend = minOf(maxBufferSize, audioData.size - offset)
            // onStop() などで止められると ERROR が返ってくるので、そこで打ち切る（done() も呼ばない）
            if (callback.audioAvailable(audioData, offset, bytesToSend) != TextToSpeech.SUCCESS) return
            offset += bytesToSend
        }

        callback.done()
    }

    override fun onIsValidVoiceName(voiceName: String?): Int {
        Log.d("${TAG}->onIsValidVoiceName", "onIsValidVoiceName called with arguments: $voiceName")
        return TextToSpeech.SUCCESS
    }

    override fun onLoadVoice(voiceName: String?): Int {
        Log.d("${TAG}->onLoadVoice", "onLoadVoice called with arguments: $voiceName")
        return TextToSpeech.SUCCESS
    }

    override fun onGetDefaultVoiceNameFor(
        lang: String?,
        country: String?,
        variant: String?
    ): String {
        Log.d("${TAG}->onGetDefaultVoiceNameFor", "onGetDefaultVoiceNameFor called with arguments: $lang, $country, $variant")
        return "冥鳴ひまり"
    }

}