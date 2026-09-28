package pl.rampt.app

import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Jeden punkt pomiaru walcowości: kąt [°] i promień [mm]. */
data class PomiarWalca(val katDeg: Float, val promien: Float)

/** Wynik analizy okrągłości / walcowości. */
data class WynikAnalizy(
    val liczbaPunktow: Int,
    val promienSredni: Float,
    val promienMax: Float, val katMax: Float,
    val promienMin: Float, val katMin: Float,
    val bicie: Float,              // TIR = max - min (zawiera mimośród)
    val okraglosc: Float,          // odchyłka okrągłości (po usunięciu mimośrodu)
    val ekscentrycznosc: Float,    // mimośród = amplituda 1. harmonicznej
    val kierunekPrzesuniecia: Float, // kierunek mimośrodu [°]
    val owalizacja: Float,         // 2 x amplituda 2. harmonicznej
    val osOwalu: Float             // kierunek osi owalu [°], okres 180°
)

/** Znaki rysunkowe wg ISO 1101 / ASME Y14.5 (blok Unicode Miscellaneous Technical). */
const val SYM_BICIE = "⌰"       // ⌰ bicie (circular/total runout)
const val SYM_WALCOWOSC = "⌭"   // ⌭ walcowość (cylindricity)

/** Współdzielony bufor między ekranami (prostsze niż serializacja przez Intent). */
object DaneWalca {
    var punkty: List<PomiarWalca> = emptyList()
    var wynik: WynikAnalizy? = null
    var pelne: FloatArray = FloatArray(360) { Float.NaN }        // wartości (po wypełnieniu); NaN poza zakresem
    var wypelnienie: BooleanArray = BooleanArray(360)            // true = przedział wypełniony sąsiadem
    var tolerancjaBicia: Float? = null                            // [mm] wpisana na ekranie głównym
    var tolerancjaWalcowosc: Float? = null                        // [mm] wpisana na ekranie głównym
    var promienZadany: Float? = null                              // [mm] promień nominalny ustawiony "Ustaw R"
    var grubosc: Float? = null                                    // [mm] grubość blachy płaszcza
    var typKonstrukcji: TypKonstrukcji = TypKonstrukcji.CISNIENIOWY // steruje tabelą progu owalności
}

/**
 * Owalność wg PN-EN 13445-4, kl. 5.4.2: O[%] = 2*(Dmax-Dmin)/(Dmax+Dmin)*100.
 * Liczona wprost z promieni (czynnik 2 z definicji D=2R się upraszcza).
 */
fun owalnoscProc(w: WynikAnalizy): Float =
    2f * (w.promienMax - w.promienMin) / (w.promienMax + w.promienMin) * 100f

/** Próg dopuszczalnej owalności zależny od e/D (PN-EN 13445-4, kl. 5.4.2). */
fun progOwalnosciProc(gruboscMm: Float, promienNominalnyMm: Float): Float {
    val eD = gruboscMm / (2f * promienNominalnyMm)
    return if (eD < 0.01f) 1.5f else 1.0f
}

/** Próg owalności w [mm] (bicie) wg wybranego typu konstrukcji + opis źródła normy do wyświetlenia. */
fun progOwalnosciMm(gruboscMm: Float, promienNominalnyMm: Float, typ: TypKonstrukcji): Pair<Float, String> {
    val us = Locale.US
    return when (typ) {
        TypKonstrukcji.CISNIENIOWY -> {
            val progProc = progOwalnosciProc(gruboscMm, promienNominalnyMm)
            (progProc / 100f * promienNominalnyMm) to "PN-EN 13445-4, ${"%.1f".format(us, progProc)}% owalności"
        }
        TypKonstrukcji.MAGAZYNOWY -> {
            val dM = 2f * promienNominalnyMm / 1000f
            val prog = when { dM < 12f -> 13f; dM < 45f -> 19f; dM < 75f -> 25f; else -> 32f }
            prog to "API 650 / PN-EN 14015, D=${"%.1f".format(us, dM)} m"
        }
    }
}

/** Wiersz owalności wg normy: bez R/grubości pokazuje samą wartość bicia, z nimi — pełną ocenę OK/PRZEKROCZONO. */
fun wierszOwalnosciNorma(w: WynikAnalizy): CharSequence {
    val us = Locale.US
    val r = DaneWalca.promienZadany
    val e = DaneWalca.grubosc
    val podstawa = "%-13s%s".format(us, "Owalnosc:", "%.3f mm".format(us, w.bicie))
    if (r == null || e == null) {
        val sufiks = "   (brak R lub grubosci - brak progu wg normy)"
        return SpannableStringBuilder(podstawa).apply {
            append(sufiks)
            setSpan(ForegroundColorSpan(0xFF9E9E9E.toInt()), podstawa.length, podstawa.length + sufiks.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
    val (progMm, zrodlo) = progOwalnosciMm(e, r, DaneWalca.typKonstrukcji)
    val ok = w.bicie <= progMm
    val sufiks = "   ${if (ok) "OK" else "PRZEKROCZONO"} (prog ${"%.3f".format(us, progMm)} mm, $zrodlo)"
    return SpannableStringBuilder(podstawa).apply {
        append(sufiks)
        val kolor = if (ok) 0xFF2E7D32.toInt() else 0xFFE53935.toInt()
        val od = podstawa.length
        setSpan(ForegroundColorSpan(kolor), od, od + sufiks.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(StyleSpan(Typeface.BOLD), od, od + sufiks.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

/** Wiersz wyniku z oceną tolerancji (jeśli podana): koloruje "OK"/"PRZEKROCZONO". */
fun wierszZOcena(label: String, wartoscMm: Float, tolerancjaMm: Float?): CharSequence {
    val us = Locale.US
    val wartoscTxt = "%.3f mm".format(us, wartoscMm)
    val podstawa = "%-13s%s".format(us, label, wartoscTxt)
    if (tolerancjaMm == null) return podstawa
    val ok = wartoscMm <= tolerancjaMm
    val sufiks = "   ${if (ok) "OK" else "PRZEKROCZONO"} (tol ${"%.3f".format(us, tolerancjaMm)} mm)"
    return SpannableStringBuilder(podstawa).apply {
        append(sufiks)
        val kolor = if (ok) 0xFF2E7D32.toInt() else 0xFFE53935.toInt()
        val od = podstawa.length
        setSpan(ForegroundColorSpan(kolor), od, od + sufiks.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(StyleSpan(Typeface.BOLD), od, od + sufiks.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
/** Rysuje wykres bicia promieniowego (odchyłka promienia od średniej w funkcji kąta) na przekazanym LineChart. */
fun rysujWykresBicia(chart: LineChart, p: List<PomiarWalca>, w: WynikAnalizy) {
    val entries = p.map { Entry(it.katDeg, it.promien - w.promienSredni) }
    val ds = LineDataSet(entries, "Odchyłka promienia [mm]").apply {
        setDrawCircles(false); lineWidth = 2.5f; color = 0xFF1E88E5.toInt()
    }
    chart.apply {
        data = LineData(ds)
        description.isEnabled = false
        legend.isEnabled = false
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.axisMinimum = 0f
        xAxis.axisMaximum = 360f
        axisRight.isEnabled = false

        val maxL = LimitLine(w.promienMax - w.promienSredni, "max").apply {
            lineColor = 0xFFE53935.toInt(); lineWidth = 1.5f
        }
        val minL = LimitLine(w.promienMin - w.promienSredni, "min").apply {
            lineColor = 0xFF43A047.toInt(); lineWidth = 1.5f
        }
        axisLeft.removeAllLimitLines()
        axisLeft.addLimitLine(maxL)
        axisLeft.addLimitLine(minL)
        invalidate()
    }
}

object AnalizaWalca {

    /**
     * Rozpina zebrane próbki (kąt -> promień) na pełną tablicę 360 przedziałów.
     * Puste przedziały (nieobrócone / pominięte kąty) wypełnia wartością najbliższego
     * zebranego sąsiada (kołowo) i oznacza je w drugiej tablicy jako wypełnione.
     */
    fun wypelnijSasiadem(dane: Map<Int, Float>): Pair<FloatArray, BooleanArray> {
        val wart = FloatArray(360) { Float.NaN }
        val wyp = BooleanArray(360)
        for ((dg, v) in dane) wart[dg] = v
        for (dg in 0 until 360) {
            if (!wart[dg].isNaN()) continue
            for (offset in 1..180) {
                val lewy = (dg - offset + 360) % 360
                val prawy = (dg + offset) % 360
                if (!wart[lewy].isNaN()) { wart[dg] = wart[lewy]; wyp[dg] = true; break }
                if (!wart[prawy].isNaN()) { wart[dg] = wart[prawy]; wyp[dg] = true; break }
            }
        }
        return wart to wyp
    }

    /**
     * Generuje syntetyczny pomiar płaszcza walca: promień nominalny + mimośród
     * (1. harm.) + owalizacja (2. harm.) + trójkątność (3. harm.) + szum.
     * Każde wywołanie daje inne (losowe) parametry.
     */
    fun generuj(r0: Float = 100f, n: Int = 72): List<PomiarWalca> {
        val rnd = java.util.Random()
        val e   = 0.05f + rnd.nextFloat() * 0.25f     // mimośród 0.05..0.30 mm
        val fe  = rnd.nextFloat() * 360f              // kierunek mimośrodu
        val ov  = 0.02f + rnd.nextFloat() * 0.13f     // owal (amplituda radialna)
        val fo  = rnd.nextFloat() * 180f              // oś owalu
        val tri = rnd.nextFloat() * 0.04f             // trójkątność
        val szum = 0.008f

        val out = ArrayList<PomiarWalca>(n)
        for (i in 0 until n) {
            val th = 360f * i / n
            val a  = Math.toRadians(th.toDouble())
            val ae = Math.toRadians((th - fe).toDouble())
            val ao = Math.toRadians((2f * (th - fo)).toDouble())
            val at = Math.toRadians((3f * th).toDouble())
            var r = r0
            r += (e  * cos(ae)).toFloat()
            r += (ov * cos(ao)).toFloat()
            r += (tri * cos(at)).toFloat()
            r += (rnd.nextFloat() - 0.5f) * 2f * szum
            out.add(PomiarWalca(th, r))
        }
        return out
    }

    /** Analiza harmoniczna profilu promienia. */
    fun analizuj(p: List<PomiarWalca>): WynikAnalizy {
        val n = p.size
        val mean = p.map { it.promien }.average().toFloat()
        val maxP = p.maxByOrNull { it.promien }!!
        val minP = p.minByOrNull { it.promien }!!
        val bicie = maxP.promien - minP.promien

        // współczynniki 1. i 2. harmonicznej (znormalizowane -> amplitudy)
        var a1 = 0.0; var b1 = 0.0; var a2 = 0.0; var b2 = 0.0
        for (pt in p) {
            val th = Math.toRadians(pt.katDeg.toDouble())
            val r = pt.promien.toDouble()
            a1 += r * cos(th);       b1 += r * sin(th)
            a2 += r * cos(2 * th);   b2 += r * sin(2 * th)
        }
        a1 *= 2.0 / n; b1 *= 2.0 / n; a2 *= 2.0 / n; b2 *= 2.0 / n

        // mimośród = amplituda 1. harmonicznej; kierunek = jej faza
        val ekscentr = hypot(a1, b1).toFloat()
        var kierunek = Math.toDegrees(atan2(b1, a1)).toFloat()
        if (kierunek < 0) kierunek += 360f

        // owalizacja = 2 x amplituda 2. harmonicznej; oś ma okres 180°
        val owalAmp = hypot(a2, b2).toFloat()
        val owalizacja = 2f * owalAmp
        var osOwalu = (Math.toDegrees(atan2(b2, a2)) / 2.0).toFloat()
        if (osOwalu < 0) osOwalu += 180f

        // odchyłka okrągłości = rozstęp profilu po usunięciu mimośrodu (1. harm.)
        var maxRes = -1e9f; var minRes = 1e9f
        for (pt in p) {
            val th = Math.toRadians(pt.katDeg.toDouble())
            val h1 = (a1 * cos(th) + b1 * sin(th)).toFloat()
            val res = pt.promien - mean - h1
            if (res > maxRes) maxRes = res
            if (res < minRes) minRes = res
        }
        val okraglosc = maxRes - minRes

        return WynikAnalizy(
            liczbaPunktow = n,
            promienSredni = mean,
            promienMax = maxP.promien, katMax = maxP.katDeg,
            promienMin = minP.promien, katMin = minP.katDeg,
            bicie = bicie,
            okraglosc = okraglosc,
            ekscentrycznosc = ekscentr,
            kierunekPrzesuniecia = kierunek,
            owalizacja = owalizacja,
            osOwalu = osOwalu
        )
    }
}
