package pro.noodle.mobile.core.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/** Коэффициенты биквада (RBJ cookbook), a0 нормирован в 1: [b0, b1, b2, a1, a2]. */
object EqMath {
    fun peaking(fs: Double, f: Double, gainDb: Double, q: Double): DoubleArray {
        val a = 10.0.pow(gainDb / 40.0); val w = 2 * PI * f / fs; val al = sin(w) / (2 * q); val c = cos(w)
        return norm(1 + al * a, -2 * c, 1 - al * a, 1 + al / a, -2 * c, 1 - al / a)
    }

    fun lowShelf(fs: Double, f: Double, gainDb: Double): DoubleArray {
        val a = 10.0.pow(gainDb / 40.0); val w = 2 * PI * f / fs; val c = cos(w); val al = sin(w) / 2 * sqrt(2.0); val t = 2 * sqrt(a) * al
        return norm(a * ((a + 1) - (a - 1) * c + t), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - t),
            (a + 1) + (a - 1) * c + t, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - t)
    }

    fun highShelf(fs: Double, f: Double, gainDb: Double): DoubleArray {
        val a = 10.0.pow(gainDb / 40.0); val w = 2 * PI * f / fs; val c = cos(w); val al = sin(w) / 2 * sqrt(2.0); val t = 2 * sqrt(a) * al
        return norm(a * ((a + 1) + (a - 1) * c + t), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - t),
            (a + 1) - (a - 1) * c + t, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - t)
    }

    private fun norm(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
        doubleArrayOf(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)

    /** Амплитудная характеристика одного биквада в дБ на частоте f. */
    fun magDb(c: DoubleArray, f: Double, fs: Double): Double {
        val w = 2 * PI * f / fs; val cw = cos(w); val c2 = cos(2 * w)
        val num = c[0] * c[0] + c[1] * c[1] + c[2] * c[2] + 2 * (c[0] * c[1] + c[1] * c[2]) * cw + 2 * c[0] * c[2] * c2
        val den = 1 + c[3] * c[3] + c[4] * c[4] + 2 * (c[3] + c[3] * c[4]) * cw + 2 * c[4] * c2
        return 10 * log10(max(num, 1e-20) / max(den, 1e-20))
    }

    /** 12 слотов: 10 полос + low shelf (120 Гц) + high shelf (8 кГц); null — слот не нужен (0 дБ). */
    fun filters(s: EqSettings, fs: Double): Array<DoubleArray?> {
        val out = arrayOfNulls<DoubleArray>(12)
        for (i in EQ_BAND_FREQS.indices) {
            val g = s.bands.getOrElse(i) { 0f }
            val f = EQ_BAND_FREQS[i].toDouble()
            if (abs(g) > 0.01f && f < fs * 0.45) out[i] = peaking(fs, f, g.toDouble(), s.q.toDouble())
        }
        if (abs(s.bass) > 0.01f) out[10] = lowShelf(fs, 120.0, s.bass.toDouble())
        if (abs(s.treble) > 0.01f && 8000.0 < fs * 0.45) out[11] = highShelf(fs, 8000.0, s.treble.toDouble())
        return out
    }

    /** Суммарная кривая эквалайзера (с предусилением) на частоте f — для рисования графика. */
    fun responseDb(s: EqSettings, f: Double, fs: Double = 48000.0, filters: Array<DoubleArray?> = filters(s, fs)): Double {
        var sum = s.preamp.toDouble()
        for (c in filters) if (c != null) sum += magDb(c, f, fs)
        return sum
    }
}

/** Простой ревербератор в стиле Freeverb: 4 гребёнки + 2 all-pass на канал. */
internal class MiniReverb(fs: Int) {
    private class Comb(size: Int) { val buf = FloatArray(size); var idx = 0; var store = 0f }
    private class AllPass(size: Int) { val buf = FloatArray(size); var idx = 0 }
    private val k = fs / 44100.0
    private val lengths = intArrayOf(1116, 1188, 1277, 1356)
    private val combsL = Array(4) { Comb(max(8, (lengths[it] * k).toInt())) }
    private val combsR = Array(4) { Comb(max(8, ((lengths[it] + 23) * k).toInt())) }
    private val apL = arrayOf(AllPass(max(8, (556 * k).toInt())), AllPass(max(8, (441 * k).toInt())))
    private val apR = arrayOf(AllPass(max(8, (579 * k).toInt())), AllPass(max(8, (464 * k).toInt())))
    var feedback = 0.84f
    var damp = 0.25f
    var outL = 0f; var outR = 0f

    private fun comb(c: Comb, x: Float): Float {
        val o = c.buf[c.idx]
        c.store = o * (1 - damp) + c.store * damp
        c.buf[c.idx] = x + c.store * feedback
        if (++c.idx >= c.buf.size) c.idx = 0
        return o
    }

    private fun allpass(a: AllPass, x: Float): Float {
        val b = a.buf[a.idx]
        val o = -x + b
        a.buf[a.idx] = x + b * 0.5f
        if (++a.idx >= a.buf.size) a.idx = 0
        return o
    }

    fun process(l: Float, r: Float) {
        val x = (l + r) * 0.015f
        var sl = 0f; var sr = 0f
        for (i in 0 until 4) { sl += comb(combsL[i], x); sr += comb(combsR[i], x) }
        for (a in apL) sl = allpass(a, sl)
        for (a in apR) sr = allpass(a, sr)
        outL = sl; outR = sr
    }
}

/** Вся цепочка обработки одного стерео-отсчёта. Параметры подхватывает configure(), состояние фильтров сохраняется. */
internal class EqEngine(private val fs: Int) {
    private class Slot { var b0 = 1.0; var b1 = 0.0; var b2 = 0.0; var a1 = 0.0; var a2 = 0.0; var on = false; var l1 = 0.0; var l2 = 0.0; var r1 = 0.0; var r2 = 0.0 }

    private val slots = Array(12) { Slot() }
    private val reverbUnit = MiniReverb(fs)
    private val surroundBuf = FloatArray(max(16, (fs * 0.022).toInt())); private var surroundIdx = 0; private var surroundLp = 0f
    private val crossBufL = FloatArray(max(4, (fs * 0.0003).toInt() + 2)); private val crossBufR = FloatArray(crossBufL.size); private var crossIdx = 0
    private var crossLpL = 0f; private var crossLpR = 0f
    private val lpSur = (1 - exp(-2 * PI * 7000.0 / fs)).toFloat()
    private val lpCross = (1 - exp(-2 * PI * 700.0 / fs)).toFloat()
    private var env = 0.0
    private val atk = 1 - exp(-1.0 / (0.010 * fs)); private val rel = 1 - exp(-1.0 / (0.160 * fs))

    @Volatile var enabled = true
    private var mode = ChannelMode.STEREO
    private var invert = false
    private var width = 1f; private var surround = 0f; private var cross = 0f
    private var pre = 1.0; private var outGain = 1.0
    private var gl = 1.0; private var gr = 1.0
    private var comp = 0f; private var rev = 0f; private var limiter = true

    var l = 0f; var r = 0f

    fun configure(s: EqSettings) {
        enabled = s.enabled
        mode = s.channelMode; invert = s.invertPhase
        width = s.width; surround = s.surround; cross = s.crossfeed
        pre = 10.0.pow(s.preamp / 20.0); outGain = 10.0.pow(s.outputGain / 20.0)
        gl = if (s.balance > 0) 1.0 - s.balance else 1.0
        gr = if (s.balance < 0) 1.0 + s.balance else 1.0
        comp = s.compressor; rev = s.reverb; limiter = s.limiter
        reverbUnit.feedback = 0.70f + 0.28f * s.reverbRoom
        val coefs = EqMath.filters(s, fs.toDouble())
        for (i in slots.indices) {
            val c = coefs[i]; val sl = slots[i]
            if (c == null) { sl.on = false } else { sl.b0 = c[0]; sl.b1 = c[1]; sl.b2 = c[2]; sl.a1 = c[3]; sl.a2 = c[4]; sl.on = true }
        }
    }

    fun process(inL: Float, inR: Float) {
        var x = inL.toDouble(); var y = inR.toDouble()
        when (mode) {
            ChannelMode.MONO -> { val m = (x + y) * 0.5; x = m; y = m }
            ChannelMode.LEFT -> y = x
            ChannelMode.RIGHT -> x = y
            ChannelMode.REVERSE -> { val t = x; x = y; y = t }
            ChannelMode.STEREO -> {}
        }
        if (invert) y = -y

        if (width != 1f || surround > 0f) {
            val m = (x + y) * 0.5
            var side = (x - y) * 0.5 * width
            if (surround > 0f) {
                val delayed = surroundBuf[surroundIdx]
                surroundBuf[surroundIdx] = m.toFloat()
                if (++surroundIdx >= surroundBuf.size) surroundIdx = 0
                surroundLp += lpSur * (delayed - surroundLp)
                side *= 1.0 + surround * 0.6
                val dec = surroundLp * 0.45 * surround
                x = m + side + dec; y = m - side - dec
            } else { x = m + side; y = m - side }
        }
        if (cross > 0f) {
            val dl = crossBufL[crossIdx]; val dr = crossBufR[crossIdx]
            crossBufL[crossIdx] = x.toFloat(); crossBufR[crossIdx] = y.toFloat()
            if (++crossIdx >= crossBufL.size) crossIdx = 0
            crossLpL += lpCross * (dr - crossLpL); crossLpR += lpCross * (dl - crossLpR)
            val k = 0.5 * cross
            x = (x + k * crossLpL) / (1 + k); y = (y + k * crossLpR) / (1 + k)
        }
        x *= pre; y *= pre
        for (s in slots) if (s.on) {
            val ox = s.b0 * x + s.l1; s.l1 = s.b1 * x - s.a1 * ox + s.l2; s.l2 = s.b2 * x - s.a2 * ox; x = ox
            val oy = s.b0 * y + s.r1; s.r1 = s.b1 * y - s.a1 * oy + s.r2; s.r2 = s.b2 * y - s.a2 * oy; y = oy
        }
        if (comp > 0f) {
            val level = max(abs(x), abs(y))
            env += (if (level > env) atk else rel) * (level - env)
            val envDb = 20 * log10(env + 1e-9)
            val thr = -12.0 - 18.0 * comp; val ratio = 1.0 + 5.0 * comp
            if (envDb > thr) {
                val red = (envDb - thr) * (1 - 1 / ratio)
                val g = 10.0.pow((-red + red * 0.4) / 20.0)
                x *= g; y *= g
            }
        }
        if (rev > 0f) {
            reverbUnit.process(x.toFloat(), y.toFloat())
            x += reverbUnit.outL * rev * 0.9; y += reverbUnit.outR * rev * 0.9
        }
        x *= gl * outGain; y *= gr * outGain
        if (limiter) { x = soft(x); y = soft(y) }
        l = x.coerceIn(-1.0, 1.0).toFloat(); r = y.coerceIn(-1.0, 1.0).toFloat()
    }

    private fun soft(v: Double): Double {
        val a = abs(v)
        return if (a <= 0.9) v else (0.9 + 0.1 * tanh((a - 0.9) / 0.1)) * (if (v < 0) -1.0 else 1.0)
    }
}
