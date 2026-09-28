package pl.rampt.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Wykres polarny (kołowy) profilu okrągłości.
 * Odchyłki od promienia średniego są POWIĘKSZONE, żeby były widoczne.
 */
class PolarRoundnessView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var punkty: List<PomiarWalca> = emptyList()
    private var brakujace: List<Int> = emptyList()
    private var wypelnioneDeg: List<Int> = emptyList()
    private var wypVal: FloatArray = FloatArray(0)
    private var mean = 0f; private var minR = 0f; private var maxR = 0f
    private var kierunek = 0f

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private val pProfil = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(2.2f); color = 0xFF1565C0.toInt()
        strokeJoin = Paint.Join.ROUND
    }
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0x261565C0                 // niebieski ~15% alfa
    }
    private val pBaza = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1.2f); color = 0xFF9E9E9E.toInt()
        pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(6f)), 0f)
    }
    private val pMinMax = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1f); color = 0x55000000
    }
    private val pGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(0.8f); color = 0x22000000
    }
    private val pKier = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(2.4f); color = 0xFFE53935.toInt()
    }
    private val pDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0xFF1565C0.toInt()
    }
    private val pKierDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0xFFE53935.toInt()
    }
    private val pBrak = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1.4f); color = 0xFFD50000.toInt()
    }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF777777.toInt(); textSize = dp(11f)
    }
    private val pWyp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0xFFFF9800.toInt()      // pomarańczowy = wypełnione sąsiadem
    }
    private val pWypTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF57C00.toInt(); textSize = dp(11f)
    }

    fun ustawDane(p: List<PomiarWalca>, w: WynikAnalizy) {
        punkty = p; wypelnioneDeg = emptyList()
        mean = w.promienSredni; minR = w.promienMin; maxR = w.promienMax
        kierunek = w.kierunekPrzesuniecia
        invalidate()
    }

    /** Wartości po wypełnieniu + flagi; profil ciągły, wypełnione oznaczone na pomarańczowo. */
    fun ustawDaneWyp(wart: FloatArray, wyp: BooleanArray, w: WynikAnalizy) {
        val pkt = ArrayList<PomiarWalca>()
        val fills = ArrayList<Int>()
        for (dg in 0 until 360) {
            val v = wart[dg]
            if (!v.isNaN()) { pkt.add(PomiarWalca(dg.toFloat(), v)); if (wyp[dg]) fills.add(dg) }
        }
        punkty = pkt; wypelnioneDeg = fills; wypVal = wart
        mean = w.promienSredni; minR = w.promienMin; maxR = w.promienMax
        kierunek = w.kierunekPrzesuniecia
        invalidate()
    }

    /** Pełna tablica 360 przedziałów: rysuje profil z zebranych i czerwone piki dla NaN. */
    fun ustawDanePelne(tab: FloatArray, w: WynikAnalizy) {
        val pkt = ArrayList<PomiarWalca>()
        var maxIdx = -1
        for (dg in 0 until 360) {
            val v = tab[dg]
            if (!v.isNaN()) { pkt.add(PomiarWalca(dg.toFloat(), v)); maxIdx = dg }
        }
        val brak = ArrayList<Int>()
        for (dg in 0 until maxIdx) if (tab[dg].isNaN()) brak.add(dg)   // tylko POMINIĘTE (poniżej najwyższego)
        punkty = pkt; brakujace = brak
        mean = w.promienSredni; minR = w.promienMin; maxR = w.promienMax
        kierunek = w.kierunekPrzesuniecia
        invalidate()
    }

    fun wyczysc() {
        punkty = emptyList(); brakujace = emptyList(); wypelnioneDeg = emptyList()
        invalidate()
    }


    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val R = min(width, height) * 0.26f
        val Rout = R * 1.5f

        // siatka kątowa co 30°
        for (deg in 0 until 360 step 30) {
            val a = Math.toRadians(deg.toDouble())
            canvas.drawLine(
                cx, cy,
                cx + (Rout * cos(a)).toFloat(),
                cy - (Rout * sin(a)).toFloat(), pGrid
            )
        }

        if (punkty.isEmpty()) {
            canvas.drawText("Brak danych – naciśnij „Start\" lub „Symulacja\"",
                dp(12f), cy, pText)
            return
        }

        val maxDev = max(abs(maxR - mean), abs(minR - mean)).coerceAtLeast(1e-4f)
        val mag = (R * 0.55f) / maxDev

        // okręgi referencyjne
        canvas.drawCircle(cx, cy, R + (maxR - mean) * mag, pMinMax)   // opisany
        canvas.drawCircle(cx, cy, R + (minR - mean) * mag, pMinMax)   // wpisany
        canvas.drawCircle(cx, cy, R, pBaza)                           // średni

        // czerwone piki: niezebrane przedziały, od środka do okręgu wartości minimalnej
        if (brakujace.isNotEmpty()) {
            val rMin = R + (minR - mean) * mag
            for (dg in brakujace) {
                val a = Math.toRadians(dg.toDouble())
                canvas.drawLine(cx, cy,
                    cx + (rMin * cos(a)).toFloat(),
                    cy - (rMin * sin(a)).toFloat(), pBrak)
            }
        }

        // profil: wypełnienie + obrys
        val path = Path()
        punkty.forEachIndexed { i, pt ->
            val a = Math.toRadians(pt.katDeg.toDouble())
            val rr = R + (pt.promien - mean) * mag
            val x = cx + (rr * cos(a)).toFloat()
            val y = cy - (rr * sin(a)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        canvas.drawPath(path, pFill)
        canvas.drawPath(path, pProfil)

        // punkt maksymalnego promienia
        run {
            val pt = punkty.maxByOrNull { it.promien }!!
            val a = Math.toRadians(pt.katDeg.toDouble())
            val rr = R + (maxR - mean) * mag
            canvas.drawCircle(cx + (rr * cos(a)).toFloat(),
                cy - (rr * sin(a)).toFloat(), dp(4f), pDot)
        }

        // strzałka kierunku mimośrodu
        val kr = Math.toRadians(kierunek.toDouble())
        val kx = cx + (R * 1.15f * cos(kr)).toFloat()
        val ky = cy - (R * 1.15f * sin(kr)).toFloat()
        canvas.drawLine(cx, cy, kx, ky, pKier)
        canvas.drawCircle(kx, ky, dp(4f), pKierDot)
        // wypełnione sąsiadem – pomarańczowe kropki na profilu + legenda
        if (wypelnioneDeg.isNotEmpty() && wypVal.isNotEmpty()) {
            for (dg in wypelnioneDeg) {
                val v = wypVal[dg]
                val a = Math.toRadians(dg.toDouble())
                val rr = R + (v - mean) * mag
                canvas.drawCircle(cx + (rr * cos(a)).toFloat(),
                    cy - (rr * sin(a)).toFloat(), dp(2.6f), pWyp)
            }
            canvas.drawText("wypelnione sasiadem: ${wypelnioneDeg.size}", dp(6f), dp(14f), pWypTxt)
        }
        // opisy kątów (przycięte do wnętrza, by 90°/270° się mieściły)
        val gora = (cy - Rout - dp(6f)).coerceAtLeast(dp(12f))
        val dol  = (cy + Rout + dp(14f)).coerceAtMost(height - dp(4f))
        canvas.drawText("0°", (cx + Rout + dp(4f)).coerceAtMost(width - dp(20f)), cy + dp(4f), pText)
        canvas.drawText("90°", cx - dp(9f), gora, pText)
        canvas.drawText("180°", (cx - Rout - dp(30f)).coerceAtLeast(dp(2f)), cy + dp(4f), pText)
        canvas.drawText("270°", cx - dp(13f), dol, pText)

        canvas.drawText("odchyłki powiększone", dp(6f), height - dp(6f), pText)
    }
}
