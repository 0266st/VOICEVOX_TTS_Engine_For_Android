package dev.ztssst.voicevox_tts

/** 音声特徴量のフレーム範囲 [startInclusive, endExclusive) */
data class FrameRange(val startInclusive: Long, val endExclusive: Long)

/**
 * 1区間の合成にかかる時間のモデル。合成の時間は、区間の長さによらない固定の時間と、音声の長さに比例する時間の和になる。
 */
data class CostModel(val fixedSeconds: Double, val secondsPerAudioSecond: Double) {
    /** 音声 [audioSeconds] 秒ぶんの区間を合成するのにかかる時間（秒） */
    fun renderSeconds(audioSeconds: Double) = fixedSeconds + secondsPerAudioSecond * audioSeconds
}

/**
 * 残りの区間の分け方（[segmentSeconds]、音声の秒数）と、途切れずに再生を始められる最短の時刻（[startSeconds]）。
 * 時刻は、合成を始めたときを0とした秒数。
 */
data class SegmentPlan(val segmentSeconds: List<Double>, val startSeconds: Double)

const val MIN_SEGMENT_SECONDS = 0.5
const val MAX_SEGMENT_SECONDS = 4.0

/** 再生を始めるまでに待つ時間の上限（合成を始めてからの秒数）。端末が実時間より遅いと、途切れない条件を満たすには待つ時間が音声の長さに比例して伸びるので、上限を設ける */
const val MAX_START_SECONDS = 10.0

/**
 * 合成時間の見積もりにかける係数。端末の合成の速さは、発熱などで10〜20%ぶれる。ぶれのぶんを見込まないと、
 * 見積もりより遅い区間があったときに途切れる。大きくするほど途切れにくくなるが、再生を始めるのが遅くなる。
 * 速さがぶれる想定でのシミュレーションで、1.1 だと6秒の文で3割、20秒の文で5割の確率で途切れ、1.2 なら1割と2割に減る。
 */
const val DEFAULT_SAFETY_FACTOR = 1.2

private const val SEARCH_STEP_SECONDS = 0.05
private const val EPSILON = 1e-9
private const val MAX_SEGMENTS = 500

/**
 * 残りの音声 [remainingSeconds] 秒を、途切れずに再生できて、かつ再生をできるだけ早く始められるように区間に分ける。
 *
 * 合成は再生より遅いことがあり、できた区間をすぐ鳴らすと、次の区間ができるまで無音になってしまう。
 * 再生を [SegmentPlan.startSeconds] に始めるとき、k番目の区間の合成が終わる時刻は、その区間より前の音声の長さ
 * （再生できる長さ）に開始時刻を足した時刻より前でなければならない。
 * 区間ごとに固定の時間がかかるので、細かく分けすぎると損をする。かといって大きくすると、その区間ができるまで待たされる。
 * そこで、開始時刻を小さいほうから順に試して、各区間を「間に合う最大の長さ」にしたとき、
 * 最後まで間に合う最初の開始時刻を選ぶ。
 *
 * @param nowSeconds いまの時刻。
 * @param bufferedSeconds 合成済みの音声の長さ。再生を始めていれば、渡した音声の長さ。
 * @param fixedStartSeconds すでに再生を始めているなら、その時刻。このときは時刻を動かせないので、間に合う計画がなければ
 * 最大の長さの区間で進める。
 * @param maxStartSeconds 再生を始める時刻の上限。途切れずに続けられる時刻がこれより遅いなら、この時刻に始める。
 * そのあとは途切れることがあるので、区間ごとの固定の時間が少なくなるよう、最大の長さの区間で進める。
 */
fun planSegments(
    remainingSeconds: Double,
    model: CostModel,
    nowSeconds: Double,
    bufferedSeconds: Double,
    fixedStartSeconds: Double? = null,
    minSegmentSeconds: Double = MIN_SEGMENT_SECONDS,
    maxSegmentSeconds: Double = MAX_SEGMENT_SECONDS,
    maxStartSeconds: Double = MAX_START_SECONDS,
): SegmentPlan {
    require(maxSegmentSeconds >= 2 * minSegmentSeconds) { "maxSegmentSeconds must be at least twice minSegmentSeconds" }
    if (remainingSeconds <= EPSILON) return SegmentPlan(emptyList(), fixedStartSeconds ?: nowSeconds)

    fun build(startSeconds: Double): List<Double>? {
        var now = nowSeconds
        var buffered = bufferedSeconds
        var remaining = remainingSeconds
        val segments = ArrayList<Double>()
        while (remaining > EPSILON) {
            // 間に合う最大の長さ: now + fixed + perSecond * length <= start + buffered
            var length = minOf((startSeconds + buffered - now - model.fixedSeconds) / model.secondsPerAudioSecond, maxSegmentSeconds)
            if (length < minSegmentSeconds && length < remaining) return null
            // 短すぎる端数は残さない。ただし、上限は超えない。残り全部が上限に収まるなら、全部を1区間にする。
            // 収まらないなら、この区間を短くして、最低限の長さの端数を残す
            if (remaining - length < minSegmentSeconds) {
                length = if (remaining <= maxSegmentSeconds) remaining else remaining - minSegmentSeconds
            }
            length = minOf(length, remaining)
            val doneAt = now + model.renderSeconds(length)
            if (doneAt > startSeconds + buffered + EPSILON) return null
            segments += length
            now = doneAt
            buffered += length
            remaining -= length
            if (segments.size > MAX_SEGMENTS) return null
        }
        return segments
    }

    if (fixedStartSeconds != null) {
        return SegmentPlan(build(fixedStartSeconds) ?: evenSegments(remainingSeconds, minSegmentSeconds, maxSegmentSeconds), fixedStartSeconds)
    }

    // 解が見つかる十分大きな時刻か、待つ時間の上限まで、小さいほうから順に試す
    val enough = nowSeconds + model.fixedSeconds * (remainingSeconds / maxSegmentSeconds + 2) + model.secondsPerAudioSecond * remainingSeconds + 5
    val limit = minOf(enough, maxOf(maxStartSeconds, nowSeconds))
    var start = nowSeconds
    while (start <= limit + EPSILON) {
        build(start)?.let { return SegmentPlan(it, start) }
        start += SEARCH_STEP_SECONDS
    }
    return SegmentPlan(evenSegments(remainingSeconds, minSegmentSeconds, maxSegmentSeconds), limit)
}

/** 最大の長さの区間で分ける。短すぎる端数は残さないよう、その手前の区間を短くする（上限は超えない） */
private fun evenSegments(remainingSeconds: Double, minSegmentSeconds: Double, maxSegmentSeconds: Double): List<Double> {
    val segments = ArrayList<Double>()
    var remaining = remainingSeconds
    while (remaining > EPSILON) {
        val length = when {
            remaining <= maxSegmentSeconds -> remaining
            remaining - maxSegmentSeconds < minSegmentSeconds -> remaining - minSegmentSeconds
            else -> maxSegmentSeconds
        }
        segments += length
        remaining -= length
    }
    return segments
}

/**
 * 端末の合成の速さを、実測から見積もる。
 *
 * 基準は F-52E での実測（1区間 0.5秒 + 音声1秒あたり 0.86秒）。実測した時間が基準の何倍だったかを覚えておき、
 * 次の区間の合成時間の見積もりに使う。基準は、区間の長さによる時間の違いを表すだけで、端末が違ってもだいたい同じ形になる。
 * 見積もりが外れても途切れにくいように、[safetyFactor] をかけて長めに見積もる。
 */
class CostEstimator(
    private val reference: CostModel = CostModel(fixedSeconds = 0.5, secondsPerAudioSecond = 0.86),
    private val safetyFactor: Double = DEFAULT_SAFETY_FACTOR,
) {
    private var averageRatio = 1.0
    private var observed = false

    /** 次の区間から使う、合成時間のモデル */
    @Synchronized
    fun model(): CostModel {
        val ratio = averageRatio * safetyFactor
        return CostModel(reference.fixedSeconds * ratio, reference.secondsPerAudioSecond * ratio)
    }

    /** 音声 [audioSeconds] 秒ぶんの区間の合成に [renderSeconds] 秒かかったことを覚える */
    @Synchronized
    fun observe(audioSeconds: Double, renderSeconds: Double) {
        if (audioSeconds < MIN_OBSERVED_AUDIO_SECONDS) return // 短すぎる区間は、固定の時間のぶれが大きい
        val ratio = renderSeconds / reference.renderSeconds(audioSeconds)
        // 直近の1区間だけが遅くても、待つ時間が大きく動かないように、指数移動平均にする
        averageRatio = if (observed) 0.5 * averageRatio + 0.5 * ratio else ratio
        observed = true
    }

    private companion object {
        const val MIN_OBSERVED_AUDIO_SECONDS = 0.3
    }
}
