package pl.rampt.app

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class Punkt(val xm: Float, val ym: Float)

data class Uklad(
    val ox: Float, val oy: Float,
    val ux: Float, val uy: Float,
    val nx: Float, val ny: Float,
    val odchylkaOd90: Float,
    val rmsA: Float, val rmsB: Float
) {
    fun przelicz(xm: Float, ym: Float): Pair<Float, Float> {
        val dx = xm - ox
        val dy = ym - oy
        return Pair(dx * ux + dy * uy, dx * nx + dy * ny)
    }
}

object Bazowanie {

    private data class Prosta(
        val cx: Float, val cy: Float,
        val ux: Float, val uy: Float,
        val rms: Float
    )

    private fun dopasuj(p: List<Punkt>): Prosta {
        val n = p.size
        val cx = p.sumOf { it.xm.toDouble() } / n
        val cy = p.sumOf { it.ym.toDouble() } / n
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (pt in p) {
            val dx = pt.xm - cx; val dy = pt.ym - cy
            sxx += dx * dx; syy += dy * dy; sxy += dx * dy
        }
        val th = 0.5 * atan2(2 * sxy, sxx - syy)
        val lmin = (sxx + syy) / 2 - sqrt(((sxx - syy) / 2) * ((sxx - syy) / 2) + sxy * sxy)
        val rms = sqrt((if (lmin > 0) lmin else 0.0) / n)
        return Prosta(cx.toFloat(), cy.toFloat(), cos(th).toFloat(), sin(th).toFloat(), rms.toFloat())
    }

    fun zbazuj(a: List<Punkt>, b: List<Punkt>): Uklad? {
        if (a.size < 2 || b.size < 2) return null
        val la = dopasuj(a)
        val lb = dopasuj(b)

        val den = la.ux * lb.uy - la.uy * lb.ux
        if (abs(den) < 1e-6f) return null

        val s = ((lb.cx - la.cx) * lb.uy - (lb.cy - la.cy) * lb.ux) / den
        val ox = la.cx + s * la.ux
        val oy = la.cy + s * la.uy

        var ux = la.ux; var uy = la.uy
        val mx = a.map { (it.xm - ox) * ux + (it.ym - oy) * uy }.average()
        if (mx < 0) { ux = -ux; uy = -uy }

        var nx = -uy; var ny = ux
        val my = b.map { (it.xm - ox) * nx + (it.ym - oy) * ny }.average()
        if (my < 0) { nx = -nx; ny = -ny }

        val cosg = la.ux * lb.ux + la.uy * lb.uy
        val kat = Math.toDegrees(acos(abs(cosg).coerceIn(0f, 1f).toDouble())).toFloat()
        val odch = abs(90f - kat)

        return Uklad(ox, oy, ux, uy, nx, ny, odch, la.rms, lb.rms)
    }

    /** Wstępny układ z samej krawędzi A: X wzdłuż A, Y = prostopadła przez środek obrotu. */
    fun ukladKrawedzA(a: List<Punkt>): Uklad? {
        if (a.size < 2) return null
        val la = dopasuj(a)
        val ux = la.ux; val uy = la.uy
        val proj = la.cx * ux + la.cy * uy          // rzut środka ciężkości na kierunek A
        val ox = la.cx - proj * ux                  // stopa prostopadłej z (0,0) na linię A
        val oy = la.cy - proj * uy
        return Uklad(ox, oy, ux, uy, -uy, ux, 0f, la.rms, 0f)
    }

    data class Okrag(val cx: Float, val cy: Float, val r: Float)

    /** Dopasowanie okręgu (algebraiczne, Kåsa) do ≥3 punktów. */
    fun dopasujOkrag(p: List<Punkt>): Okrag? {
        val n = p.size
        if (n < 3) return null
        val xm = p.sumOf { it.xm.toDouble() } / n
        val ym = p.sumOf { it.ym.toDouble() } / n
        var suu = 0.0; var suv = 0.0; var svv = 0.0
        var suuu = 0.0; var svvv = 0.0; var suvv = 0.0; var svuu = 0.0
        for (pt in p) {
            val u = pt.xm - xm; val v = pt.ym - ym
            suu += u * u; suv += u * v; svv += v * v
            suuu += u * u * u; svvv += v * v * v; suvv += u * v * v; svuu += v * u * u
        }
        val det = suu * svv - suv * suv
        if (kotlin.math.abs(det) < 1e-9) return null
        val b1 = 0.5 * (suuu + suvv); val b2 = 0.5 * (svvv + svuu)
        val uc = (b1 * svv - b2 * suv) / det
        val vc = (suu * b2 - suv * b1) / det
        val cx = (xm + uc).toFloat()
        val cy = (ym + vc).toFloat()
        val r = kotlin.math.sqrt(uc * uc + vc * vc + (suu + svv) / n).toFloat()
        return Okrag(cx, cy, r)
    }

    /** Układ z okręgu: początek = środek okręgu, oś X w stronę punktu kierunkowego. */
    fun ukladOkrag(okrag: Okrag, kierunek: Punkt): Uklad? {
        var ux = kierunek.xm - okrag.cx
        var uy = kierunek.ym - okrag.cy
        val len = kotlin.math.hypot(ux.toDouble(), uy.toDouble()).toFloat()
        if (len < 1e-4f) return null
        ux /= len; uy /= len
        return Uklad(okrag.cx, okrag.cy, ux, uy, -uy, ux, 0f, 0f, 0f)
    }
}