package dev.ztssst.voicevox_tts

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.IBinder
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

@Suppress("PrivatePropertyName")
class VoicevoxTextToSpeechServiceImplement : TextToSpeechService() {
    private lateinit var ttsService: VoicevoxTTSEngine
    private val TAG: String = "VoicevoxTextToSpeechService"

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "OnCreate Started")
        Log.d(TAG, "filesDir = $filesDir, $assets.locales")
        val modelFile = File(filesDir, "model.vvm")
        modelFile.outputStream().use {
            val input = resources.openRawResource(R.raw.model)
            input.copyTo(it)
            input.close()
        }
        val jtalk = File(filesDir, "open_jtalk_dict.zip")
        jtalk.outputStream().use {
            val input = resources.openRawResource(R.raw.open_jtalk_dict)
            input.copyTo(it)
            input.close()
        }
        Log.d(TAG, "modelFile = $modelFile, jtalk = $jtalk")
        if(File("${filesDir.absolutePath}/open_jtalk_dict").exists()) {
            Log.d(TAG, "open_jtalk_dict already exists, skipping unzip")
            ttsService = VoicevoxTTSEngine(modelFile.absolutePath, "${filesDir.absolutePath}/open_jtalk_dict")
            Log.d(TAG, "OnCreate Finished")
            return
        }
        // zip解凍の処理を別スレッドで行う
        Executors.newSingleThreadExecutor().execute {
            unzipJtalk(jtalk)
            ttsService = VoicevoxTTSEngine(modelFile.absolutePath, "${filesDir.absolutePath}/open_jtalk_dict")
            Log.d(TAG, "OnCreate Finished")
        }
    }

    private fun unzipJtalk(jtalk: File) {
        // jtalk変数はzipファイルを握っています。以下にこのファイルをそのディレクトリに解凍するコードを書いてください。
        val destDir = jtalk.parentFile
        Log.d(TAG, "destDir = $destDir")
        try {
            ZipInputStream(FileInputStream(jtalk)).use { zipInputStream ->
                var zipEntry: ZipEntry? = zipInputStream.nextEntry
                while (zipEntry != null) {
                    Log.d(TAG, "zipEntry = ${zipEntry.name}, isDirectory = ${zipEntry.isDirectory}")
                    if (zipEntry.isDirectory) {
                        val dir = File(destDir, zipEntry.name)
                        dir.mkdirs()
                        Log.d(TAG, "dir = $dir, Successfully Created")
                    } else {
                        val newFile = File(destDir, zipEntry.name)
                        newFile.createNewFile()
                        FileOutputStream(newFile).use { fos ->
                            zipInputStream.copyTo(fos)
                        }
                        Log.d(TAG, "${newFile.absolutePath} Successfully Created")
                    }
                    zipEntry = zipInputStream.nextEntry
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
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
        Log.d("${TAG}->onSynthesizeText", "onSynthesizeText called with arguments: $request, $callback")
        Log.d("${TAG}->onSynthesizeText", "request.charSequenceText = ${request.charSequenceText}")
        val audioData = this.ttsService.synthesis(request.charSequenceText.toString())
        val maxBufferSize: Int = callback.maxBufferSize
        // テキストを音声に変換する処理
        callback.start(24000, AudioFormat.ENCODING_PCM_16BIT, 1)

        var offset = 0
        while (offset < audioData.size) {
            val bytesToSend = Math.min(maxBufferSize, audioData.size - offset)
            val dataChunk = audioData.copyOfRange(offset, offset + bytesToSend)
            callback.audioAvailable(dataChunk, 0, bytesToSend)
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