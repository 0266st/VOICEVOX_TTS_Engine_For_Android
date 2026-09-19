package dev.ztssst.voicevox_tts

import kotlin.math.roundToLong

/** 音声特徴量のフレーム範囲 [startInclusive, endExclusive) */
data class FrameRange(val startInclusive: Long, val endExclusive: Long)

/**
 * 全体が [totalFrames] フレームの音声を、先頭の1つだけ [firstSeconds] 秒、残りは [nextSeconds] 秒ずつの範囲に分ける。
 *
 * 最初の範囲を短くすると、最初の音が鳴るまでの時間が短くなる。
 * 1秒あたりのフレーム数は [frameRate]（VOICEVOX COREでは24000/256 = 93.75）。
 */
fun planSegments(
    totalFrames: Long,
    firstSeconds: Double,
    nextSeconds: Double,
    frameRate: Double,
): Sequence<FrameRange> = sequence {
    val first = (firstSeconds * frameRate).roundToLong().coerceAtLeast(1)
    val next = (nextSeconds * frameRate).roundToLong().coerceAtLeast(1)
    var start = 0L
    var length = first
    while (start < totalFrames) {
        val end = minOf(start + length, totalFrames)
        yield(FrameRange(start, end))
        start = end
        length = next
    }
}

/** これから合成する区間。音声の長さと、合成にかかると見込む時間（どちらも秒） */
data class PlannedSegment(val audioSeconds: Double, val renderSeconds: Double)

/**
 * 残りの区間をすべて途切れずに再生するために、再生を始めるのをあとどれだけ待つ必要があるか（秒）を返す。0なら、いま始めてよい。
 *
 * 合成は再生より遅いことがあり、できた区間をすぐ鳴らすと、次の区間ができるまで無音になってしまう。
 * [bufferedSeconds] は、合成済みでまだ再生していない音声の長さ。[remaining] は、これから順に合成する区間で、
 * 各区間の合成が終わる時刻（いまを0とする）が、そこまでに再生できる音声の終わりより前になっていれば途切れない。
 * 再生を始めるのを待つと、そのぶん再生できる音声の終わりも後ろにずれる。
 */
fun startDelaySeconds(bufferedSeconds: Double, remaining: List<PlannedSegment>): Double {
    var readyAt = 0.0 // 区間ができあがる時刻
    var playableUntil = bufferedSeconds // 待たずに始めたときに、音声が途切れずに続く時刻の限界
    var delay = 0.0
    for (segment in remaining) {
        readyAt += segment.renderSeconds
        delay = maxOf(delay, readyAt - playableUntil)
        playableUntil += segment.audioSeconds
    }
    return delay
}
