package pl.rampt.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import pl.rampt.app.databinding.ActivityBicieBinding

/** Drugie okno: wykres bicia promieniowego (oś Y) w funkcji kąta (oś X). */
class BicieActivity : AppCompatActivity() {

    private lateinit var b: ActivityBicieBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityBicieBinding.inflate(layoutInflater)
        setContentView(b.root)
        supportActionBar?.title = "$SYM_BICIE Bicie promieniowe"

        val p = DaneWalca.punkty
        val w = DaneWalca.wynik
        if (p.isEmpty() || w == null) { finish(); return }

        rysujWykresBicia(b.chartBicie, p, w)

        b.tvBicieInfo.text =
            "$SYM_BICIE Bicie promieniowe (TIR): %.3f mm   •   zakres kąta: 0–360°"
                .format(java.util.Locale.US, w.bicie)
    }
}
