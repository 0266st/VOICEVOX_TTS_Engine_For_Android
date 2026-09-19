package dev.ztssst.voicevox_tts

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import jp.hiroshiba.voicevoxcore.exceptions.AnalyzeTextException
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

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
        resources.openRawResource(R.raw.open_jtalk_dict).use { unzip(it, filesDir) }
        stamp.writeText(installed)
        Log.d(TAG, "resources copied to $filesDir")
    }

    private fun unzip(input: InputStream, destDir: File) {
        ZipInputStream(input).use { zipInputStream ->
            var zipEntry: ZipEntry? = zipInputStream.nextEntry
            while (zipEntry != null) {
                val newFile = File(destDir, zipEntry.name)
                if (zipEntry.isDirectory) {
                    newFile.mkdirs()
                } else {
                    newFile.parentFile?.mkdirs()
                    newFile.outputStream().use { zipInputStream.copyTo(it) }
                }
                zipEntry = zipInputStream.nextEntry
            }
        }
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
        } catch (e: ExecutionException) {
            Log.e("${TAG}->onSynthesizeText", "initialization failed", e.cause)
            callback.error(TextToSpeech.ERROR_SERVICE)
            return
        }

        callback.start(VoicevoxTTSEngine.SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
        val maxBufferSize = callback.maxBufferSize
        val completed = try {
            // 合成できた区間から順に渡す。再生は最初の区間ができた時点で始まる
            engine.synthesizeStreaming(request.charSequenceText.toString()) { pcm ->
                var offset = 0
                while (offset < pcm.size) {
                    val length = minOf(maxBufferSize, pcm.size - offset)
                    // onStop() などで止められると ERROR が返ってくるので、そこで打ち切る
                    if (callback.audioAvailable(pcm, offset, length) != TextToSpeech.SUCCESS) return@synthesizeStreaming false
                    offset += length
                }
                true
            }
        } catch (e: AnalyzeTextException) {
            // 記号や絵文字だけのテキストなど、読み上げるものがなかった。エラーにはせず、無音で終える
            Log.d("${TAG}->onSynthesizeText", "nothing to speak: ${e.message}")
            callback.done()
            return
        } catch (e: Exception) {
            Log.e("${TAG}->onSynthesizeText", "synthesis failed", e)
            callback.error(TextToSpeech.ERROR_SYNTHESIS)
            return
        }

        if (completed) callback.done()
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