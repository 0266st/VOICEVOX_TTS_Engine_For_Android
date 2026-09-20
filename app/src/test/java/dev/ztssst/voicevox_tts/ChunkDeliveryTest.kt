package dev.ztssst.voicevox_tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class ChunkDeliveryTest {
    private val bytesPerSecond = 48_000
    private val queue = LinkedBlockingQueue<DeliveryEvent>()
    private val token = CancellationToken().also { t -> t.onCancel { queue.offer(DeliveryEvent.Cancelled) } }
    private val firstDeliveredAt = AtomicLong(0)
    private val delivered = mutableListOf<Int>() // 渡したチャンクの、先頭のバイト（識別用）

    private fun now() = System.nanoTime() / 1_000_000

    private fun chunk(id: Int, seconds: Double = 1.0, readyAt: Long = now()) =
        DeliveryEvent.Chunk(ByteArray((seconds * bytesPerSecond).toInt()) { if (it == 0) id.toByte() else 0 }, readyAt)

    private fun deliver(onGap: (Long) -> Unit = {}, onChunk: (ByteArray) -> Boolean = { delivered += it[0].toInt(); true }) =
        deliverChunks(queue, token, ::now, firstDeliveredAt, bytesPerSecond, onChunk, onGap)

    @Test(timeout = 5_000)
    fun doneDeliversEverythingInOrderAtOnce() {
        queue.put(chunk(1)); queue.put(chunk(2)); queue.put(DeliveryEvent.Done)
        assertTrue(deliver())
        assertEquals(listOf(1, 2), delivered)
        assertTrue(firstDeliveredAt.get() != 0L)
    }

    @Test(timeout = 5_000)
    fun doesNotDeliverBeforeTheStartTimeButDoesOnceEverythingIsDone() {
        queue.put(chunk(1)); queue.put(DeliveryEvent.StartAt(now() + 60_000)) // 開始の時刻は、ずっと先
        val finished = CountDownLatch(1)
        Thread { deliver(); finished.countDown() }.start()
        Thread.sleep(150)
        assertTrue("開始の時刻より前に渡してしまった", delivered.isEmpty())
        queue.put(DeliveryEvent.Done) // 全部できたら、開始の時刻を待たずに渡す
        assertTrue(finished.await(2, TimeUnit.SECONDS))
        assertEquals(listOf(1), delivered)
    }

    @Test(timeout = 5_000)
    fun deliversWhenTheStartTimeComes() {
        queue.put(chunk(1)); queue.put(DeliveryEvent.StartAt(now() + 250))
        val startedAt = now()
        val deliveredAt = AtomicLong(0)
        val finished = CountDownLatch(1)
        Thread {
            deliver(onChunk = { deliveredAt.compareAndSet(0, now()); delivered += it[0].toInt(); token.cancel(); true })
            finished.countDown()
        }.start()
        assertTrue(finished.await(3, TimeUnit.SECONDS))
        assertEquals(listOf(1), delivered)
        assertTrue("開始の時刻より早く渡した: ${deliveredAt.get() - startedAt}ms", deliveredAt.get() - startedAt >= 230)
    }

    @Test(timeout = 5_000)
    fun cancelWakesUpTheWaitForTheStartTime() {
        // 開始の時刻が、ずっと先。ここで待っているあいだに止められたら、すぐに抜ける（Copilot の指摘した不具合）
        queue.put(chunk(1)); queue.put(DeliveryEvent.StartAt(now() + 60_000))
        val result = AtomicBoolean(true)
        val finished = CountDownLatch(1)
        Thread { result.set(deliver()); finished.countDown() }.start()
        Thread.sleep(150)
        val cancelledAt = now()
        token.cancel()
        assertTrue("止めても、待ちから抜けない", finished.await(2, TimeUnit.SECONDS))
        assertFalse(result.get())
        assertTrue("抜けるのが遅い: ${now() - cancelledAt}ms", now() - cancelledAt < 1_000)
        assertTrue("止めたあとに、渡してしまった", delivered.isEmpty())
    }

    @Test(timeout = 5_000)
    fun aTokenCancelledBeforeStartingReturnsFalseImmediately() {
        queue.put(chunk(1)); queue.put(DeliveryEvent.Done)
        token.cancel()
        assertFalse(deliver())
        assertTrue(delivered.isEmpty())
    }

    @Test(timeout = 5_000)
    fun stopsWhenOnChunkReturnsFalse() {
        queue.put(chunk(1)); queue.put(chunk(2)); queue.put(chunk(3)); queue.put(DeliveryEvent.Done)
        assertFalse(deliver(onChunk = { delivered += it[0].toInt(); it[0].toInt() < 2 }))
        assertEquals(listOf(1, 2), delivered)
    }

    @Test(timeout = 5_000)
    fun aFailureIsRethrown() {
        queue.put(DeliveryEvent.Failed(IllegalStateException("boom")))
        try {
            deliver()
            throw AssertionError("IllegalStateException was expected")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
    }

    @Test(timeout = 5_000)
    fun reportsAGapWhenTheNextChunkIsReadyAfterThePreviousOneFinishedPlaying() {
        // 最初の1秒ぶんを、時刻 1000 に渡し始めた。2つ目は、時刻 4000 にできた。1秒ぶんは、2000 で再生し終わっているので、2000ms の途切れ
        firstDeliveredAt.set(1000)
        queue.put(chunk(1, seconds = 1.0, readyAt = 1000)); queue.put(chunk(2, seconds = 1.0, readyAt = 4000)); queue.put(DeliveryEvent.Done)
        val gaps = mutableListOf<Long>()
        assertTrue(deliver(onGap = { gaps += it }))
        assertEquals(listOf(2000L), gaps)
    }

    @Test(timeout = 5_000)
    fun noGapWhenTheNextChunkIsReadyBeforeThePreviousOneFinishedPlaying() {
        firstDeliveredAt.set(1000)
        queue.put(chunk(1, seconds = 3.0, readyAt = 1000)); queue.put(chunk(2, seconds = 1.0, readyAt = 3500)); queue.put(DeliveryEvent.Done)
        val gaps = mutableListOf<Long>()
        assertTrue(deliver(onGap = { gaps += it }))
        assertEquals(emptyList<Long>(), gaps)
    }

    @Test
    fun cancelRunsTheRegisteredActionOnce() {
        val count = AtomicInteger(0)
        val t = CancellationToken()
        t.onCancel { count.incrementAndGet() }
        t.cancel(); t.cancel()
        assertEquals(1, count.get())
        assertTrue(t.isCancelled)
    }

    @Test
    fun onCancelRunsImmediatelyWhenAlreadyCancelled() {
        val t = CancellationToken()
        t.cancel()
        val count = AtomicInteger(0)
        t.onCancel { count.incrementAndGet() }
        assertEquals(1, count.get())
    }
}
