package dev.ztssst.voicevox_tts

import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ztssst.voicevox_tts.ui.theme.Voicevox_ttsTheme

/**
 * エンジンの設定画面。入力したテキストをこのエンジンで読み上げ、最初の音声が届くまでの時間を表示する。
 *
 * adb から `am start -n dev.ztssst.voicevox_tts/.MainActivity --es text "..."` で起動すると、
 * そのテキストをすぐに読み上げる。
 */
class MainActivity: ComponentActivity() {
    private val TAG = "VoicevoxLatency"
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pendingText: String? = null

    private var text by mutableStateOf("これは音声合成のサンプルです。ボイスボックスを使用して、読み上げています。")
    private var status by mutableStateOf("エンジンに接続中…")

    // 発話ごとの計測
    private var requestedAt = 0L
    private var firstAudioAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingText = intent.getStringExtra("text")?.also { text = it }

        tts = TextToSpeech(this, { result ->
            ready = result == TextToSpeech.SUCCESS
            status = if (ready) "準備完了" else "エンジンに接続できませんでした"
            if (ready) pendingText?.let { speak(it) }
        }, packageName).apply { setOnUtteranceProgressListener(listener) }

        setContent {
            Voicevox_ttsTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.safeDrawingPadding().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("VOICEVOX TTS Engine", style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("読み上げるテキスト") },
                        )
                        Button(onClick = { speak(text) }) { Text("再生") }
                        Text(status)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    private fun speak(text: String) {
        if (!ready) return
        requestedAt = SystemClock.elapsedRealtime()
        firstAudioAt = 0L
        status = "合成中…"
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utterance-$requestedAt")
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}

        override fun onAudioAvailable(utteranceId: String?, audio: ByteArray?) {
            if (firstAudioAt == 0L) {
                firstAudioAt = SystemClock.elapsedRealtime()
                Log.i(TAG, "first audio: ${firstAudioAt - requestedAt}ms")
            }
        }

        override fun onDone(utteranceId: String?) {
            val doneAt = SystemClock.elapsedRealtime()
            Log.i(TAG, "done: first audio ${firstAudioAt - requestedAt}ms, total ${doneAt - requestedAt}ms")
            status = "最初の音声まで ${firstAudioAt - requestedAt}ms / 再生完了まで ${doneAt - requestedAt}ms"
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            Log.w(TAG, "error")
            status = "エラーが発生しました"
        }
    }
}
