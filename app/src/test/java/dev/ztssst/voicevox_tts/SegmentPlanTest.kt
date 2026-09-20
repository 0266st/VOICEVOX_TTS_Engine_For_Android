package dev.ztssst.voicevox_tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentPlanTest {
    // F-52E での実測に近い値。1区間 0.5秒 + 音声1秒あたり 0.86秒
    private val slowDevice = CostModel(fixedSeconds = 0.5, secondsPerAudioSecond = 0.86)
    private val fastDevice = CostModel(fixedSeconds = 0.2, secondsPerAudioSecond = 0.3)

    /** 計画どおりに合成したとき、各区間が「再生できる音声の終わり」より前にできているか。一番厳しい区間の余裕（秒）を返す */
    private fun minSlack(plan: SegmentPlan, model: CostModel, now: Double = 0.0, buffered: Double = 0.0): Double {
        var t = now
        var audio = buffered
        var slack = Double.MAX_VALUE
        for (length in plan.segmentSeconds) {
            t += model.renderSeconds(length)
            slack = minOf(slack, plan.startSeconds + audio - t)
            audio += length
        }
        return slack
    }

    /** 区間を [first] 秒、以降 [next] 秒ずつに固定したときの、途切れずに始められる最短の時刻 */
    private fun fixedPlanStart(total: Double, first: Double, next: Double, model: CostModel, now: Double = 0.0): Double {
        var t = now
        var audio = 0.0
        var start = 0.0
        var length = first
        while (audio < total - 1e-9) {
            val c = minOf(length, total - audio)
            t += model.renderSeconds(c)
            start = maxOf(start, t - audio)
            audio += c
            length = next
        }
        return start
    }

    @Test
    fun segmentsCoverTheRemainingAudioExactly() {
        for (total in listOf(0.3, 1.0, 2.0, 6.0, 20.0, 40.0, 120.0)) {
            val plan = planSegments(total, slowDevice, nowSeconds = 0.15, bufferedSeconds = 0.0)
            assertEquals(total, plan.segmentSeconds.sum(), 1e-6)
        }
    }

    @Test
    fun everySegmentIsWithinTheLimits() {
        val plan = planSegments(40.0, slowDevice, nowSeconds = 0.15, bufferedSeconds = 0.0)
        assertTrue(plan.segmentSeconds.all { it <= MAX_SEGMENT_SECONDS + 1e-9 })
        assertTrue(plan.segmentSeconds.all { it >= MIN_SEGMENT_SECONDS - 1e-9 })
    }

    @Test
    fun theStartTimeIsEnoughToNeverRunOut() {
        for (model in listOf(slowDevice, fastDevice)) {
            for (total in listOf(2.0, 6.0, 20.0, 40.0)) {
                val plan = planSegments(total, model, nowSeconds = 0.15, bufferedSeconds = 0.0)
                assertTrue("slack=${minSlack(plan, model, now = 0.15)}", minSlack(plan, model, now = 0.15) >= -1e-6)
            }
        }
    }

    @Test
    fun theStartTimeIsTheEarliestOne() {
        // 探索の刻み（0.05秒）より早く始めると、同じ区間の分け方のままでは、どこかの区間が間に合わない
        val plan = planSegments(20.0, slowDevice, nowSeconds = 0.15, bufferedSeconds = 0.0)
        val earlier = plan.startSeconds - 0.1
        var t = 0.15
        var audio = 0.0
        var allInTime = true
        for (length in plan.segmentSeconds) {
            t += slowDevice.renderSeconds(length)
            if (t > earlier + audio) allInTime = false
            audio += length
        }
        assertTrue(!allInTime)
    }

    @Test
    fun beatsFixedSizeSegmentsForLongText() {
        // この端末では、最初の1秒だけ短くして以降3秒にする固定の分け方より、長い文で早く始められる
        for (total in listOf(6.0, 20.0, 40.0)) {
            val plan = planSegments(total, slowDevice, nowSeconds = 0.15, bufferedSeconds = 0.0)
            val fixed = fixedPlanStart(total, first = 1.0, next = 3.0, model = slowDevice, now = 0.15)
            assertTrue("total=$total planned=${plan.startSeconds} fixed=$fixed", plan.startSeconds < fixed - 0.5)
        }
    }

    @Test
    fun longerTextNeverStartsEarlier() {
        var previous = 0.0
        for (total in listOf(1.0, 2.0, 4.0, 8.0, 16.0, 32.0, 64.0)) {
            val start = planSegments(total, slowDevice, nowSeconds = 0.15, bufferedSeconds = 0.0).startSeconds
            assertTrue("total=$total start=$start previous=$previous", start >= previous - 1e-9)
            previous = start
        }
    }

    @Test
    fun fastDeviceStartsMuchEarlier() {
        val slow = planSegments(20.0, slowDevice, nowSeconds = 0.15, bufferedSeconds = 0.0).startSeconds
        val fast = planSegments(20.0, fastDevice, nowSeconds = 0.15, bufferedSeconds = 0.0).startSeconds
        assertTrue("slow=$slow fast=$fast", fast < 1.0 && fast < slow / 3)
    }

    @Test
    fun aShortTextIsOneSegment() {
        val plan = planSegments(0.6, slowDevice, nowSeconds = 0.1, bufferedSeconds = 0.0)
        assertEquals(listOf(0.6), plan.segmentSeconds)
        assertEquals(0.1 + slowDevice.renderSeconds(0.6), plan.startSeconds, 0.06)
    }

    @Test
    fun waitingIsCappedWhenTheDeviceIsSlowerThanRealTime() {
        // 実時間の1.5倍かかる端末では、途切れずに続けるには待つ時間が音声の長さに比例して伸びる。上限で打ち切る
        val model = CostModel(fixedSeconds = 0.75, secondsPerAudioSecond = 1.3)
        val plan = planSegments(40.0, model, nowSeconds = 0.15, bufferedSeconds = 0.0)
        assertEquals(MAX_START_SECONDS, plan.startSeconds, 0.06)
        assertEquals(40.0, plan.segmentSeconds.sum(), 1e-6)
        // 途切れることは避けられないので、固定の時間が減るよう最大の長さで進める
        assertTrue(plan.segmentSeconds.dropLast(1).all { it == MAX_SEGMENT_SECONDS })
    }

    @Test
    fun ifTheFirstSegmentAlreadyTookLongerThanTheCapItStartsNow() {
        val plan = planSegments(20.0, slowDevice, nowSeconds = 9.5, bufferedSeconds = 4.0)
        assertEquals(9.5, plan.startSeconds, 0.06)
    }

    @Test
    fun nothingLeftMeansNoSegments() {
        val plan = planSegments(0.0, slowDevice, nowSeconds = 3.0, bufferedSeconds = 5.0)
        assertEquals(emptyList<Double>(), plan.segmentSeconds)
    }

    @Test
    fun replanningWithMoreBufferedAudioAllowsAnEarlierStart() {
        // 3秒ぶん合成し終わっているぶん、続きの区間が間に合いやすくなる
        val fresh = planSegments(10.0, slowDevice, nowSeconds = 0.15, bufferedSeconds = 0.0)
        val ahead = planSegments(10.0, slowDevice, nowSeconds = 0.15, bufferedSeconds = 3.0)
        assertTrue(ahead.startSeconds < fresh.startSeconds)
    }

    @Test
    fun afterPlaybackStartedTheStartTimeIsKept() {
        // 再生は 4.0秒に始まっていて、いまは 6.0秒。音声は 4秒ぶん渡してある
        val plan = planSegments(10.0, slowDevice, nowSeconds = 6.0, bufferedSeconds = 4.0, fixedStartSeconds = 4.0)
        assertEquals(4.0, plan.startSeconds, 0.0)
        assertEquals(10.0, plan.segmentSeconds.sum(), 1e-6)
    }

    @Test
    fun ifTheRestCannotKeepUpItFallsBackToTheBiggestSegments() {
        // もう遅れていて、どう分けても間に合わない。区間ごとの固定の時間を減らすため、最大の長さで進める
        val plan = planSegments(10.0, slowDevice, nowSeconds = 20.0, bufferedSeconds = 1.0, fixedStartSeconds = 2.0)
        assertEquals(listOf(4.0, 4.0, 2.0), plan.segmentSeconds)
    }

    @Test
    fun estimatorStartsFromTheReferenceModel() {
        val model = CostEstimator().model()
        assertEquals(0.5 * DEFAULT_SAFETY_FACTOR, model.fixedSeconds, 1e-9)
        assertEquals(0.86 * DEFAULT_SAFETY_FACTOR, model.secondsPerAudioSecond, 1e-9)
    }

    @Test
    fun estimatorFollowsASlowDevice() {
        val estimator = CostEstimator()
        // 基準の2倍かかった
        estimator.observe(audioSeconds = 3.0, renderSeconds = 2 * (0.5 + 0.86 * 3.0))
        val model = estimator.model()
        assertEquals(2 * 0.5 * DEFAULT_SAFETY_FACTOR, model.fixedSeconds, 1e-9)
        assertEquals(2 * 0.86 * DEFAULT_SAFETY_FACTOR, model.secondsPerAudioSecond, 1e-9)
    }

    @Test
    fun estimatorIsNotThrownOffByOneSlowChunk() {
        val estimator = CostEstimator()
        repeat(4) { estimator.observe(3.0, 1 * (0.5 + 0.86 * 3.0)) } // 基準どおり
        estimator.observe(3.0, 1.5 * (0.5 + 0.86 * 3.0)) // 1区間だけ1.5倍かかった
        val ratio = estimator.model().secondsPerAudioSecond / (0.86 * DEFAULT_SAFETY_FACTOR)
        // 直近の1区間には引きずられず、平均に近づくだけ（1.25倍）
        assertEquals(1.25, ratio, 1e-9)
    }

    @Test
    fun estimatorIgnoresVeryShortSegments() {
        val estimator = CostEstimator()
        estimator.observe(audioSeconds = 0.1, renderSeconds = 5.0)
        assertEquals(0.5 * DEFAULT_SAFETY_FACTOR, estimator.model().fixedSeconds, 1e-9)
    }
}
