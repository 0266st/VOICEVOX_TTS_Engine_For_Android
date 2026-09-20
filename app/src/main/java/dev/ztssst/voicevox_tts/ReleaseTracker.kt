package dev.ztssst.voicevox_tts

import java.lang.ref.PhantomReference
import java.lang.ref.Reference
import java.lang.ref.ReferenceQueue

/**
 * 手放したオブジェクトが、ファイナライザまで含めて、本当に解放されるまで待つ。
 *
 * Synthesizer などは close() を持たず、ファイナライザでネイティブのメモリを解放する。参照を手放して GC を促しても、
 * ファイナライザは別のスレッドで、あとから動くので、メモリが戻るまでには時間がかかる。[PhantomReference] は、
 * ファイナライザが終わって、オブジェクトが回収できるようになってから届くので、これを待てば、解放されたことが分かる。
 */
class ReleaseTracker {
    private val queue = ReferenceQueue<Any>()
    private val pending = HashSet<Reference<*>>() // 参照そのものが、先に回収されてしまわないよう、持っておく

    /** [createReference] に、渡された [ReferenceQueue] で PhantomReference を作らせて、解放を見張る。オブジェクトは、強い参照で持たない */
    fun watch(createReference: (ReferenceQueue<Any>) -> PhantomReference<Any>) {
        val reference = createReference(queue)
        synchronized(pending) { pending += reference }
    }

    /**
     * 見張っているものが、すべて解放されるまで、GC を促しながら待つ。[timeoutMillis] までに終わらなければ、false を返す。
     * ファイナライザを持つオブジェクトは、ファイナライザが動いたあとの GC で、はじめて回収されるので、GC は繰り返し促す。
     */
    fun awaitReleased(timeoutMillis: Long, gc: () -> Unit = { System.gc() }): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (true) {
            if (synchronized(pending) { pending.isEmpty() }) return true
            val remainingMillis = (deadline - System.nanoTime()) / 1_000_000
            if (remainingMillis <= 0) return false
            gc()
            val released = queue.remove(minOf(POLL_MILLIS, remainingMillis))
            if (released != null) synchronized(pending) { pending -= released }
        }
    }

    private companion object {
        const val POLL_MILLIS = 300L
    }
}
