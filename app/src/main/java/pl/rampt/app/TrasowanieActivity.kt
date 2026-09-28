package pl.rampt.app

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.graphics.BitmapFactory
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.EditText
import android.widget.TableRow
import android.widget.TextView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.AdapterView
import androidx.appcompat.app.AppCompatActivity
import pl.rampt.app.databinding.ActivityTrasowanieBinding

class TrasowanieActivity : AppCompatActivity() {

    private lateinit var b: ActivityTrasowanieBinding
    private var sledz = false
    private var trybGl = 0   // 0=Bazowanie 1=Trasowanie 2=Pomiar pozycji
    private var bazaTryb = TrasowanieCanvasView.Tryb.KRAWEDZ_A
    private var licznikPomiar = 0
    private val fotoFile by lazy { File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "tras_foto.jpg") }
    private val zrobZdjecie = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) wczytajFoto()
    }
    private fun wczytajFoto() {
        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
        val bmp = BitmapFactory.decodeFile(fotoFile.absolutePath, opts) ?: return
        b.canvas.ustawZdjecie(bmp)
    }

    private val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
    private val beepHandler = Handler(Looper.getMainLooper())
    private var aktualnyDist = Float.MAX_VALUE
    private var celAktywny = false
    private var trasowanieTab = false
    private val zasiegMm = 50f                    // od tej odległości zaczyna pikać
    private val beepRunnable = object : Runnable {
        override fun run() {
            if (trasowanieTab && celAktywny && aktualnyDist <= zasiegMm) {
                toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 35)
                val interval = (60f + aktualnyDist * 26f).toLong().coerceIn(60L, 1400L)
                beepHandler.postDelayed(this, interval)
            } else {
                beepHandler.postDelayed(this, 250)   // czuwanie
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityTrasowanieBinding.inflate(layoutInflater)
        setContentView(b.root)

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        b.canvas.onReadout = { x, y, r, kat, enc, info ->
            b.tvX.text = x; b.tvY.text = y
            b.tvR.text = r; b.tvKat.text = kat
            b.tvEnc.text = enc; b.tvInfo.text = info
        }

        // tryby bazowania (przyciski z podświetleniem)
        val bazaBtns = listOf(b.btnBXlinia, b.btnBXokrag, b.btnBYlinia, b.btnBYpunkt)
        b.btnBXlinia.setOnClickListener { ustawBaza(TrasowanieCanvasView.Tryb.KRAWEDZ_A, b.btnBXlinia, bazaBtns) }
        b.btnBXokrag.setOnClickListener { ustawBaza(TrasowanieCanvasView.Tryb.OKRAG, b.btnBXokrag, bazaBtns) }
        b.btnBYlinia.setOnClickListener { ustawBaza(TrasowanieCanvasView.Tryb.KRAWEDZ_B, b.btnBYlinia, bazaBtns) }
        b.btnBYpunkt.setOnClickListener { ustawBaza(TrasowanieCanvasView.Tryb.KIERUNEK, b.btnBYpunkt, bazaBtns) }
        podswietl(b.btnBXlinia, bazaBtns)

        // rodzaje punktów trasowania (przyciski -> okna z parametrami)
        val trasBtns = listOf(b.btnT1, b.btnT2, b.btnT3, b.btnT4)
        b.btnT1.setOnClickListener { dialogTras(1, b.btnT1, trasBtns) }
        b.btnT2.setOnClickListener { dialogTras(2, b.btnT2, trasBtns) }
        b.btnT3.setOnClickListener { dialogTras(3, b.btnT3, trasBtns) }
        b.btnT4.setOnClickListener { dialogTras(4, b.btnT4, trasBtns) }

        // przełącznik trybu głównego (Bazowanie / Trasowanie / Pomiar pozycji)
        b.btnTrybGl.setOnClickListener { ustawTrybGl((trybGl + 1) % 3) }

        b.btnUndo.setOnClickListener { b.canvas.cofnij() }
        b.btnClear.setOnClickListener { b.canvas.wyczysc() }
        b.btnPotwierdz.setOnClickListener { potwierdzPunkt() }
        b.btnSledz.setOnClickListener {
            sledz = !sledz; b.canvas.ustawSledzenie(sledz)
            b.btnSledz.alpha = if (sledz) 1f else 0.5f
            b.btnSledz.text = if (sledz) "Śledź: wł" else "Śledź: wył"
        }
        b.btnSledz.alpha = 0.5f
        b.btnZapiszPoz.setOnClickListener { zapiszPozycje() }
        b.btnFotoTras.setOnClickListener {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", fotoFile)
            try { zrobZdjecie.launch(uri) } catch (e: Exception) { }
        }
        b.btnZdjEdit.setOnClickListener {
            if (!b.canvas.maZdjecie()) return@setOnClickListener
            b.canvas.toggleEdycjaZdjecia()
            b.kadrBox.visibility = View.GONE
            b.tvZdjPomoc.visibility = View.VISIBLE
        }
        b.btnZdjUsun.setOnClickListener {
            b.canvas.usunZdjecie()
            b.kadrBox.visibility = View.GONE
            b.tvZdjPomoc.visibility = View.GONE
        }
        b.btnZdjObrot.setOnClickListener { b.canvas.obrocZdjecie() }
        b.btnZdjLustro.setOnClickListener { b.canvas.lustroZdjecie() }
        b.btnKadr.setOnClickListener {
            if (!b.canvas.maZdjecie()) return@setOnClickListener
            b.canvas.toggleEdycjaKadru()
            val wl = b.canvas.czyEdycjaKadru()
            b.kadrBox.visibility = if (wl) View.VISIBLE else View.GONE
            b.tvZdjPomoc.visibility = if (wl) View.VISIBLE else View.GONE
            b.btnKadr.alpha = if (wl) 1f else 0.6f
        }
        b.btnKadrCofnij.setOnClickListener { b.canvas.cofnijPunktKadru() }
        b.btnKadrWyczysc.setOnClickListener { b.canvas.wyczyscKadr() }
        b.btnClearPoz.setOnClickListener { while (b.tabelaPomiar.childCount > 1) b.tabelaPomiar.removeViewAt(1); licznikPomiar = 0 }

        b.canvas.onNaprowadzanie = { dist, idx ->
            aktualnyDist = dist
            celAktywny = idx >= 0
            b.tvNaprow.text = if (idx >= 0 && dist < Float.MAX_VALUE)
                "do celu #${idx + 1}:  %.1f mm".format(java.util.Locale.US, dist)
            else "do celu: –"
        }
        val domyslnyKolorXY = b.tvX.currentTextColor
        b.canvas.onKolorOsi = { kx, ky ->
            b.tvX.setTextColor(kx ?: domyslnyKolorXY)
            b.tvY.setTextColor(ky ?: domyslnyKolorXY)
        }
        b.btnDystansXY.setOnClickListener {
            val wl = !b.canvas.pokazPozostalosc
            b.canvas.pokazPozostalosc = wl
            b.btnDystansXY.text = if (wl) "X/Y: do celu" else "X/Y: pozycja"
            b.btnDystansXY.alpha = if (wl) 1f else 0.6f
        }
        b.btnDystansXY.alpha = 0.6f

        b.btnNowyToggle.setOnClickListener {
            val vis = b.nowyBox.visibility == View.VISIBLE
            b.nowyBox.visibility = if (vis) View.GONE else View.VISIBLE
            b.btnNowyToggle.text = if (vis) "// nowy układ  [pokaż]" else "// nowy układ  [ukryj]"
        }
        b.btnEncToggle.setOnClickListener {
            val vis = b.tvEnc.visibility == View.VISIBLE
            b.tvEnc.visibility = if (vis) View.GONE else View.VISIBLE
            b.btnEncToggle.text = if (vis) "// układ enkodera  [pokaż]" else "// układ enkodera  [ukryj]"
        }

        ustawTrybGl(0)
    }

    override fun onResume() {
        super.onResume()
        beepHandler.post(beepRunnable)
    }

    override fun onPause() {
        super.onPause()
        beepHandler.removeCallbacks(beepRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        beepHandler.removeCallbacks(beepRunnable)
        toneGen.release()
    }
    private fun ustawBaza(tryb: TrasowanieCanvasView.Tryb, btn: android.widget.Button, grupa: List<android.widget.Button>) {
        bazaTryb = tryb
        if (trybGl == 0) b.canvas.tryb = tryb
        podswietl(btn, grupa)
    }
    private fun podswietl(aktywny: android.widget.Button, grupa: List<android.widget.Button>) {
        for (x in grupa) { x.alpha = if (x === aktywny) 1f else 0.45f }
    }
    private fun ustawTrybGl(pos: Int) {
        trybGl = pos
        trasowanieTab = (pos == 1)
        b.bazowaniePanel.visibility = if (pos == 0) View.VISIBLE else View.GONE
        b.trasowaniePanel.visibility = if (pos == 1) View.VISIBLE else View.GONE
        b.pomiarPanel.visibility = if (pos == 2) View.VISIBLE else View.GONE
        b.btnTrybGl.text = "Tryb: " + when (pos) { 0 -> "Bazowanie"; 1 -> "Trasowanie"; else -> "Pomiar pozycji" } + "  (dotknij, aby zmienić)"
        b.canvas.tryb = if (pos == 0) bazaTryb else TrasowanieCanvasView.Tryb.TRASOWANIE
    }
    private fun zapiszPozycje() {
        val poz = b.canvas.biezacaPozycja() ?: return
        licznikPomiar++
        val row = TableRow(this)
        fun cell(t: String) = TextView(this).apply {
            text = t; typeface = android.graphics.Typeface.MONOSPACE; setPadding(8, 4, 8, 4)
        }
        row.addView(cell("$licznikPomiar"))
        row.addView(cell("%.2f".format(java.util.Locale.US, poz.first)))
        row.addView(cell("%.2f".format(java.util.Locale.US, poz.second)))
        b.tabelaPomiar.addView(row)
        toneGen.startTone(ToneGenerator.TONE_PROP_ACK, 120)
    }
    private fun potwierdzPunkt() {
        val idx = b.canvas.potwierdzNajblizszy()
        if (idx < 0) return
        toneGen.startTone(ToneGenerator.TONE_PROP_ACK, 150)   // potwierdzenie
        val rowIndex = idx + 1
        if (rowIndex in 1 until b.tabelaCele.childCount) {
            (b.tabelaCele.getChildAt(rowIndex) as? TableRow)?.let { row ->
                for (k in 0 until row.childCount) {
                    (row.getChildAt(k) as? TextView)?.apply {
                        setTextColor(0xFF2E7D32.toInt())
                        paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                    }
                }
            }
        }
    }



    private val prefsT by lazy { getSharedPreferences("tras_param", MODE_PRIVATE) }

    private fun zastosujCele(cele: List<Pair<Float, Float>>, btn: android.widget.Button, grupa: List<android.widget.Button>) {
        b.canvas.ustawCele(cele)
        wypelnijTabeleCeli(cele)
        podswietl(btn, grupa)
    }

    private fun dialogParam(tytul: String, pola: List<Triple<String, String, String>>, onOk: (Map<String, Float?>) -> Unit) {
        val ets = LinkedHashMap<String, EditText>()
        val cont = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 16, 40, 8) }
        for ((label, key, def) in pola) {
            cont.addView(android.widget.TextView(this).apply { text = label; textSize = 12f })
            val et = EditText(this).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                setSingleLine(); setText(prefsT.getString(key, def))
            }
            ets[key] = et; cont.addView(et)
        }
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle(tytul)
            .setView(android.widget.ScrollView(this).apply { addView(cont) })
            .setPositiveButton("Wyznacz") { _, _ ->
                val ed = prefsT.edit(); val vals = HashMap<String, Float?>()
                for ((k, et) in ets) { val t = et.text.toString(); ed.putString(k, t); vals[k] = t.replace(',', '.').toFloatOrNull() }
                ed.apply(); onOk(vals)
            }
            .setNegativeButton("Anuluj", null).show()
    }

    private fun dialogTras(typ: Int, btn: android.widget.Button, grupa: List<android.widget.Button>) {
        when (typ) {
            1 -> dialogParam("Jeden punkt (środek)", listOf(
                Triple("X [mm]", "t1x", "0"), Triple("Y [mm]", "t1y", "0"))) { v ->
                zastosujCele(listOf((v["t1x"] ?: 0f) to (v["t1y"] ?: 0f)), btn, grupa)
            }
            2 -> dialogParam("Punkty chaotyczne", listOf(
                Triple("liczba", "t2n", "5"), Triple("zakres ± [mm]", "t2z", "100"))) { v ->
                val n = (v["t2n"] ?: 5f).toInt().coerceIn(1, 200); val z = v["t2z"] ?: 100f
                val out = (0 until n).map { (Math.random().toFloat() * 2 - 1) * z to (Math.random().toFloat() * 2 - 1) * z }
                zastosujCele(out, btn, grupa)
            }
            3 -> dialogParam("Punkty na okręgu", listOf(
                Triple("średnica podziałowa [mm]", "t3d", "100"), Triple("ilość", "t3n", "6"),
                Triple("kąt startowy [°]", "t3a0", "0"), Triple("rozmieszczenie [°] (opc)", "t3r", ""),
                Triple("kąt końcowy [°] (opc)", "t3a1", ""))) { v ->
                val dd = v["t3d"] ?: return@dialogParam; val n = (v["t3n"] ?: 0f).toInt(); if (n < 1) return@dialogParam
                val r = dd / 2f; val a0 = v["t3a0"] ?: 0f; val rozm = v["t3r"]; val a1 = v["t3a1"]
                val step = when { rozm != null -> rozm; a1 != null && n > 1 -> (a1 - a0) / (n - 1); else -> 360f / n }
                val out = (0 until n).map { i -> val a = Math.toRadians((a0 + i * step).toDouble()); (r * Math.cos(a).toFloat()) to (r * Math.sin(a).toFloat()) }
                zastosujCele(out, btn, grupa)
            }
            4 -> dialogParam("Siatka równomierna", listOf(
                Triple("kolumny", "t4c", "3"), Triple("wiersze", "t4w", "2"),
                Triple("odstęp X [mm]", "t4dx", "20"), Triple("odstęp Y [mm]", "t4dy", "20"),
                Triple("X początkowy", "t4x0", "0"), Triple("Y początkowy", "t4y0", "0"),
                Triple("obrót [°]", "t4g", "0"))) { v ->
                val c = (v["t4c"] ?: 0f).toInt(); val rw = (v["t4w"] ?: 0f).toInt()
                val dx = v["t4dx"] ?: return@dialogParam; val dy = v["t4dy"] ?: return@dialogParam
                if (c < 1 || rw < 1) return@dialogParam
                val x0 = v["t4x0"] ?: 0f; val y0 = v["t4y0"] ?: 0f
                val g = Math.toRadians((v["t4g"] ?: 0f).toDouble()); val cg = Math.cos(g).toFloat(); val sg = Math.sin(g).toFloat()
                val out = ArrayList<Pair<Float, Float>>()
                for (j in 0 until rw) for (i in 0 until c) { val lx = i * dx; val ly = j * dy; out.add((x0 + lx * cg - ly * sg) to (y0 + lx * sg + ly * cg)) }
                zastosujCele(out, btn, grupa)
            }
        }
    }

    private fun wypelnijTabeleCeli(cele: List<Pair<Float, Float>>) {
        while (b.tabelaCele.childCount > 1) b.tabelaCele.removeViewAt(1)
        val us = java.util.Locale.US
        cele.forEachIndexed { i, (x, y) ->
            val row = TableRow(this)
            fun cell(t: String) = TextView(this).apply {
                text = t; typeface = android.graphics.Typeface.MONOSPACE; setPadding(8, 4, 8, 4)
            }
            row.addView(cell("${i + 1}"))
            row.addView(cell("%.2f".format(us, x)))
            row.addView(cell("%.2f".format(us, y)))
            b.tabelaCele.addView(row)
        }
    }

    private fun spinnerAdapter(sp: Spinner, items: List<String>) {
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
    }

    private fun wybor(akcja: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = akcja(position)
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }
}