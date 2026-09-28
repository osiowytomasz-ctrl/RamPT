package pl.rampt.app

import android.content.Intent
import android.os.Bundle
import android.text.SpannableStringBuilder
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import pl.rampt.app.databinding.ActivityWalcowoscBinding
import java.util.Locale

/** Ekran analizy walcowości: pokazuje ZMIERZONE dane (nie generuje symulacji). */
class WalcowoscActivity : AppCompatActivity() {

    private lateinit var b: ActivityWalcowoscBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityWalcowoscBinding.inflate(layoutInflater)
        setContentView(b.root)

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, bars.bottom)
            insets
        }

        b.btnGeneruj.text = "Zamknij"
        b.btnGeneruj.setOnClickListener { finish() }
        b.btnBicie.text = "$SYM_BICIE Wykres bicia →"
        b.btnBicie.setOnClickListener {
            if (DaneWalca.punkty.isNotEmpty())
                startActivity(Intent(this, BicieActivity::class.java))
        }
        b.btnRaport.setOnClickListener { startActivity(Intent(this, RaportActivity::class.java)) }

        pokazWynik()
    }

    private fun pokazWynik() {
        val p = DaneWalca.punkty
        val w = DaneWalca.wynik
        if (p.isEmpty() || w == null) {
            b.tvMetryki.text = "Brak danych pomiaru."
            b.polar.wyczysc()
            return
        }
        b.polar.ustawDaneWyp(DaneWalca.pelne, DaneWalca.wypelnienie, w)
        b.tvMetryki.text = sformatuj(w)
    }

    private fun sformatuj(w: WynikAnalizy): CharSequence {
        val us = Locale.US
        fun mm(v: Float) = "%.3f mm".format(us, v)
        fun deg(v: Float) = "${"%.0f".format(us, v)}°"
        fun row(label: String, value: String) = "%-13s%s".format(us, label, value)
        val sb = SpannableStringBuilder()
        fun linia(s: CharSequence) { sb.append(s); sb.append("\n") }
        linia(row("Punktow:", "${w.liczbaPunktow}"))
        linia(row("R sredni:", mm(w.promienSredni)))
        linia(row("R max:", "${mm(w.promienMax)}  @ ${deg(w.katMax)}"))
        linia(row("R min:", "${mm(w.promienMin)}  @ ${deg(w.katMin)}"))
        linia(wierszZOcena("$SYM_BICIE Bicie:", w.bicie, DaneWalca.tolerancjaBicia))
        linia(wierszZOcena("$SYM_WALCOWOSC Walcowosc:", w.okraglosc, DaneWalca.tolerancjaWalcowosc))
        linia(wierszOwalnosciNorma(w))
        linia(row("Mimosrod:", "${mm(w.ekscentrycznosc)}  @ ${deg(w.kierunekPrzesuniecia)}"))
        linia(row("Owalizacja:", "${mm(w.owalizacja)}  (os ${deg(w.osOwalu)})"))
        if (DanePrzelamania.pomiary.isNotEmpty()) linia(wierszPrzelamania())
        return sb
    }
}