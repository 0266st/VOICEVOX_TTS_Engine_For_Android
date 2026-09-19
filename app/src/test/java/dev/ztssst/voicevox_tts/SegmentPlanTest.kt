package dev.ztssst.voicevox_tts

import org.junit.Assert.assertEquals
import org.junit.Test

class SegmentPlanTest {
    private val frameRate = 24000.0 / 256.0

    @Test
    fun firstSegmentIsShorterThanTheRest() {
        val plan = planSegments(1000, firstSeconds = 1.0, nextSeconds = 3.0, frameRate = frameRate).toList()
        // 1秒 = 94フレーム、3秒 = 281フレーム
        assertEquals(
            listOf(FrameRange(0, 94), FrameRange(94, 375), FrameRange(375, 656), FrameRange(656, 937), FrameRange(937, 1000)),
            plan,
        )
    }

    @Test
    fun segmentsCoverEverythingWithoutGapsOrOverlap() {
        for (total in listOf(1L, 93L, 94L, 95L, 375L, 376L, 5000L)) {
            val plan = planSegments(total, 1.0, 3.0, frameRate).toList()
            assertEquals(0L, plan.first().startInclusive)
            assertEquals(total, plan.last().endExclusive)
            plan.zipWithNext().forEach { (a, b) -> assertEquals(a.endExclusive, b.startInclusive) }
        }
    }

    @Test
    fun shorterThanTheFirstSegmentIsOneRange() {
        assertEquals(listOf(FrameRange(0, 50)), planSegments(50, 1.0, 3.0, frameRate).toList())
    }

    @Test
    fun emptyAudioHasNoSegments() {
        assertEquals(emptyList<FrameRange>(), planSegments(0, 1.0, 3.0, frameRate).toList())
    }

    @Test
    fun noDelayWhenNothingRemains() {
        assertEquals(0.0, startDelaySeconds(1.0, emptyList()), 1e-9)
    }

    @Test
    fun fastDeviceCanStartRightAway() {
        // 3秒の音声を1秒で合成できるなら、1秒分たまっていれば途切れない
        val remaining = List(3) { PlannedSegment(audioSeconds = 3.0, renderSeconds = 1.0) }
        assertEquals(0.0, startDelaySeconds(1.0, remaining), 1e-9)
    }

    @Test
    fun slowDeviceHasToWait() {
        // 3秒の音声の合成に3.4秒かかるのに、1秒分しかたまっていないと、待たずに始めれば途切れる。
        // 1区間目は3.4 - 1.0 = 2.4秒、2区間目は6.8 - 4.0 = 2.8秒の遅れなので、2.8秒待てばよい
        val remaining = List(2) { PlannedSegment(audioSeconds = 3.0, renderSeconds = 3.4) }
        assertEquals(2.8, startDelaySeconds(1.0, remaining), 1e-9)
        // 4秒分たまっていれば、次の区間が間に合う。その後は3.4秒で3秒分できるので、少しずつ余裕が減るだけ
        assertEquals(0.0, startDelaySeconds(4.0, remaining), 1e-9)
    }

    @Test
    fun delayIsSetByTheWorstSegment() {
        // 3.5秒で3秒分できる区間が続くと、遅れが0.5秒ずつたまり、後ろの区間ほど厳しくなる
        val remaining = List(10) { PlannedSegment(audioSeconds = 3.0, renderSeconds = 3.5) }
        // k番目の区間ができるのは 3.5k 秒後で、それまでに再生できるのは 4 + 3(k-1) 秒。差が最大なのは最後の10番目
        assertEquals(35.0 - (4.0 + 27.0), startDelaySeconds(4.0, remaining), 1e-9)
        assertEquals(0.0, startDelaySeconds(9.0, remaining), 1e-9)
    }

    @Test
    fun shortFirstSegmentOnSlowDevice() {
        // この端末での実測に近い値（1秒 → 1.4秒、3秒 → 3.1秒、1.86秒 → 2.1秒）。
        // 最初の区間ができた時点で、2区間目は3.1秒後にできるが、再生できるのは1秒分だけ
        val remaining = listOf(PlannedSegment(3.0, 3.1), PlannedSegment(1.86, 2.1))
        // 2区間目: 3.1 - 1.0 = 2.1 秒、3区間目: 5.2 - 4.0 = 1.2 秒 → 2.1秒待てば途切れない
        assertEquals(2.1, startDelaySeconds(1.0, remaining), 1e-9)
    }
}
