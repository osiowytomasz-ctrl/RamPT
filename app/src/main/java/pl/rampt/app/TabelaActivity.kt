package pl.rampt.app

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class TabelaActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val us = Locale.US
        val wart = DaneWalca.pelne
        val wyp = DaneWalca.wypelnienie
        val w = DaneWalca.wynik

        val minR = w?.promienMin ?: 0f
        val maxR = w?.promienMax ?: 1f
        val rozp = (maxR - minR).takeIf { it > 1e-6f } ?: 1f

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }

        var zebrane = 0; var wypCnt = 0
        for (dg in 0 until 360) if (!wart[dg].isNaN()) { zebrane++; if (wyp[dg]) wypCnt++ }

        root.addView(TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 13f
            setPadding(0, 0, 0, dp(8))
            text = "Zebrane: $zebrane/360   •   wypełnione sąsiadem: $wypCnt\n" +
                    "kolor: min=zielony  środek=żółty  max=czerwony"
        })

        root.addView(wiersz("przedział", "r [mm]", "", Color.TRANSPARENT, true))

        for (dg in 0 until 360) {
            val v = wart[dg]
            if (v.isNaN()) {
                root.addView(wiersz("%3d°–%3d°".format(us, dg, dg + 1), "—", "", 0x11000000, false))
            } else {
                val t = (v - minR) / rozp
                val bg = skala(t)
                val tag = if (wyp[dg]) "≈ sąsiad" else ""
                root.addView(wiersz("%3d°–%3d°".format(us, dg, dg + 1),
                    "%.3f".format(us, v), tag, bg, false))
            }
        }

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun wiersz(lewo: String, srodek: String, prawo: String, bg: Int, header: Boolean): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        fun cell(txt: String, weight: Float, grav: Int, tlo: Int, bold: Boolean): TextView =
            TextView(this).apply {
                text = txt
                typeface = if (bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
                textSize = 12f
                gravity = grav
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setBackgroundColor(tlo)
                if (tlo != 0 && jasnosc(tlo) < 130) setTextColor(Color.WHITE) else setTextColor(0xFF202020.toInt())
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
            }
        row.addView(cell(lewo, 2f, Gravity.START, Color.TRANSPARENT, header))
        row.addView(cell(srodek, 2f, Gravity.END, bg, header))
        row.addView(cell(prawo, 2f, Gravity.START, Color.TRANSPARENT, header))
        return row
    }

    private fun skala(tt: Float): Int {
        val t = tt.coerceIn(0f, 1f)
        val r: Int; val g: Int
        if (t < 0.5f) { val u = t / 0.5f; r = (0 + 255 * u).toInt(); g = (170 + 30 * u).toInt() }
        else { val u = (t - 0.5f) / 0.5f; r = (255 - 40 * u).toInt(); g = (200 - 200 * u).toInt() }
        return Color.rgb(r, g, 0)
    }

    private fun jasnosc(c: Int) = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}