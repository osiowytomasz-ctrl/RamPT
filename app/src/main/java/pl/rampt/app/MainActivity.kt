package pl.rampt.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import pl.rampt.app.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var adapter: BluetoothAdapter? = null
    private val prefs by lazy { getSharedPreferences("cylindryczne_ustawienia", MODE_PRIVATE) }

    private val zebrane = StringBuilder("kat;X;Y;L\n")

    private val pomiarWalca = LinkedHashMap<Int, Float>()
    private var nagrywanie = false

    private val simHandler = Handler(Looper.getMainLooper())
    private var simActive = false
    private var simDeg = 0
    private var simSpeed = 10
    private var simR0 = 100f
    private var simE = 0.15f;  private var simFe = 0f
    private var simOv = 0.06f; private var simFo = 0f
    private var simTri = 0.02f

    private val naPomiar: (Pomiar) -> Unit = { m -> pokazPomiar(m) }
    private val naStatus: (String, Boolean) -> Unit = { st, ok ->
        if (st.isNotEmpty()) { b.tvStatus.text = st; log(st) }
        ustawIkoneBt(ok)
    }

    private val reqPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) pickDeviceAndConnect() else toast("Brak uprawnień Bluetooth")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        adapter = getSystemService(BluetoothManager::class.java)?.adapter

        // treść nie może chować się pod paskiem nawigacji systemu (edge-to-edge od Androida 15+)
        ViewCompat.setOnApplyWindowInsetsListener(b.scrollRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, bars.bottom)
            insets
        }

        b.btnConn.setOnClickListener { if (Bt.polaczone) Bt.rozlacz() else ensurePermsThenConnect() }
        ustawIkoneBt(Bt.polaczone)

        b.btnSave.setOnClickListener { zapiszCsv() }

        wczytajUstawienia()
        ustawSpinnery()

        DaneWalca.promienZadany?.let { b.etPromienZadany.setText(it.toString()) }
        DaneWalca.tolerancjaBicia?.let { b.etTolBicie.setText(it.toString()) }
        DaneWalca.tolerancjaWalcowosc?.let { b.etTolWalcowosc.setText(it.toString()) }
        DaneWalca.grubosc?.let { b.etGrubosc.setText(it.toString()) }
        DanePrzelamania.liczbaSpoin?.let { b.etLiczbaSpoin.setText(it.toString()) }
        DanePrzelamania.tolerancjaMm?.let { b.etTolPrzelamania.setText(it.toString()) }

        b.etPromienZadany.addTextChangedListener(tolWatcher { DaneWalca.promienZadany = it; zapiszFloatUst("promienZadany", it); autoWypelnijTolerancje() })
        b.etTolBicie.addTextChangedListener(tolWatcher { DaneWalca.tolerancjaBicia = it; zapiszFloatUst("tolBicie", it) })
        b.etTolWalcowosc.addTextChangedListener(tolWatcher { DaneWalca.tolerancjaWalcowosc = it; zapiszFloatUst("tolWalcowosc", it) })
        b.etGrubosc.addTextChangedListener(tolWatcher { DaneWalca.grubosc = it; zapiszFloatUst("grubosc", it); autoWypelnijTolerancje() })
        b.etTolPrzelamania.addTextChangedListener(tolWatcher { DanePrzelamania.tolerancjaMm = it; zapiszFloatUst("tolPrzelamania", it) })
        b.etLiczbaSpoin.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val v = s?.toString()?.trim()?.toIntOrNull()
                DanePrzelamania.liczbaSpoin = v
                prefs.edit().apply { if (v != null) putInt("liczbaSpoin", v) else remove("liczbaSpoin") }.apply()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        b.btnWyslijR.setOnClickListener {
            val r = b.etPromienZadany.text.toString().trim()
            if (r.isNotEmpty()) { Bt.wyslij("m$r"); log("→ m$r  (promień)") }
        }
        autoWypelnijTolerancje()

        b.btnStartPomiar.setOnClickListener {
            potwierdzZastapienieDanych {
                pomiarWalca.clear()
                nagrywanie = true
                b.tvPomiarInfo.visibility = View.VISIBLE
                b.tvPomiarInfo.text = "Pomiar walcowości: nagrywanie… 0/360°"
                log("— start pomiaru walcowości —")
                prefs.edit().remove("pomiarWalcaProbki").putBoolean("nagrywanieAktywne", true).apply()
            }
        }
        b.btnStopPomiar.setOnClickListener { zakonczPomiar() }
        b.btnResetPomiar.setOnClickListener {
            potwierdzZastapienieDanych {
                pomiarWalca.clear(); nagrywanie = false
                b.polarLive.wyczysc()
                b.tvPomiarInfo.visibility = View.VISIBLE
                b.tvPomiarInfo.text = "Pomiar walcowości: reset"
                DanePrzelamania.pomiary = emptyList()
                b.tvPrzelamanieInfo.text = "Przełamanie: brak symulacji"
                DaneWalca.punkty = emptyList(); DaneWalca.wynik = null
                prefs.edit().remove("pomiarPunkty").remove("pomiarWalcaProbki")
                    .putBoolean("nagrywanieAktywne", false).apply()
            }
        }
        b.btnTabela.setOnClickListener { startActivity(Intent(this, TabelaActivity::class.java)) }
        b.btnRaportWalca.setOnClickListener {
            if (DaneWalca.punkty.isEmpty() || DaneWalca.wynik == null) toast("Brak zakończonego pomiaru — najpierw wykonaj pomiar walcowości")
            else startActivity(Intent(this, WalcowoscActivity::class.java))
        }

        wczytajOstatniPomiar()
        wczytajPostepNagrywania()

        b.btnSymToggle.setOnClickListener {
            val vis = b.simBox.visibility == View.VISIBLE
            b.simBox.visibility = if (vis) View.GONE else View.VISIBLE
            b.btnSymToggle.text = if (vis) "▸ Symulator" else "▾ Symulator"
        }
        b.btnSymLive.setOnClickListener {
            if (simActive) stopSymulacji()
            else potwierdzZastapienieDanych { startSymulacji() }
        }
        b.seekSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                simSpeed = 10 + progress
                b.tvSpeed.text = "$simSpeed °/s"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        b.btnLogToggle.setOnClickListener {
            val vis = b.logScroll.visibility == View.VISIBLE
            b.logScroll.visibility = if (vis) View.GONE else View.VISIBLE
            b.btnLogToggle.text = if (vis) "▸ Log" else "▾ Log"
        }
    }

    override fun onResume() {
        super.onResume()
        Bt.dodajPomiar(naPomiar); Bt.dodajStatus(naStatus)
    }

    override fun onPause() {
        super.onPause()
        Bt.usunPomiar(naPomiar); Bt.usunStatus(naStatus)
    }

    private fun ustawIkoneBt(ok: Boolean) {
        b.btnConn.setColorFilter(if (ok) 0xFF1565C0.toInt() else 0xFF9E9E9E.toInt())
    }

    // ---------- uprawnienia + wybór urządzenia ----------

    private fun neededPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        else
            arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)

    private fun ensurePermsThenConnect() {
        val a = adapter ?: run { toast("To urządzenie nie ma Bluetooth"); return }
        if (!a.isEnabled) { toast("Włącz Bluetooth w ustawieniach"); return }
        val missing = neededPerms().any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing) reqPerms.launch(neededPerms()) else pickDeviceAndConnect()
    }

    @SuppressLint("MissingPermission")
    private fun pickDeviceAndConnect() {
        val a = adapter ?: return
        val bonded = try { a.bondedDevices.toList() } catch (e: SecurityException) { emptyList() }
        if (bonded.isEmpty()) {
            toast("Najpierw sparuj urządzenie (np. \"Trasowanica Oska\") w ustawieniach BT")
            return
        }
        val names = bonded.map { "${it.name}\n${it.address}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Wybierz urządzenie")
            .setItems(names) { _, i -> connectTo(bonded[i]) }
            .show()
    }

    private fun connectTo(device: BluetoothDevice) {
        val a = adapter ?: return
        Bt.polaczZ(a, device)
    }

    // ---------- odbiór i prezentacja danych ----------

    private fun pokazPomiar(m: Pomiar) {
        b.tvKat.text = "Kąt:  %.2f°".format(Locale.US, m.kat)
        b.tvX.text   = "X:  %.3f".format(Locale.US, m.x)
        b.tvY.text   = "Y:  %.3f".format(Locale.US, m.y)
        b.tvL.text   = if (m.l != null) "L:  %.3f mm".format(Locale.US, m.l) else "L:  brak"

        if (nagrywanie) rejestrujProbke(m)
        fun fmt(v: Float) = "%.3f".format(Locale.US, v).replace('.', ',')
        val line = "${fmt(m.kat)};${fmt(m.x)};${fmt(m.y)};${if (m.l != null) fmt(m.l) else "brak"}"
        zebrane.append(line).append('\n')
        log(line)
    }

    // ---------- pomiar walcowości (jedna próbka na 1°, first-wins) ----------

    private fun rejestrujProbke(m: Pomiar) {
        val r = m.l ?: return
        var deg = m.kat % 360f
        if (deg < 0) deg += 360f
        val bin = deg.toInt().coerceIn(0, 359)
        if (!pomiarWalca.containsKey(bin)) {          // first-wins: pierwszy odczyt w danym stopniu
            pomiarWalca[bin] = r
            b.tvPomiarInfo.visibility = View.VISIBLE
            b.tvPomiarInfo.text = "Pomiar walcowości: nagrywanie… ${pomiarWalca.size}/360°"
            odswiezPolarLive()
            zapiszPostepNagrywania()
        }
    }

    /** Zapisuje bieżący (niedokończony) pomiar walcowości do pamięci podręcznej, żeby przypadkowe wyjście z aplikacji
     *  w trakcie obracania płaszcza nie wymuszało powtórzenia całego obrotu. */
    private fun zapiszPostepNagrywania() {
        val punkty = pomiarWalca.toSortedMap().map { PomiarWalca(it.key.toFloat(), it.value) }
        prefs.edit()
            .putString("pomiarWalcaProbki", serializujPunkty(punkty))
            .putBoolean("nagrywanieAktywne", nagrywanie)
            .apply()
    }

    private fun odswiezPolarLive() {
        if (pomiarWalca.size < 3) return
        val punkty = pomiarWalca.toSortedMap().map { PomiarWalca(it.key.toFloat(), it.value) }
        b.polarLive.ustawDane(punkty, AnalizaWalca.analizuj(punkty))
    }

    private fun zakonczPomiar() {
        nagrywanie = false
        if (pomiarWalca.size < 10) {
            toast("Za mało punktów (${pomiarWalca.size}). Obróć płaszcz o pełny obrót.")
            b.tvPomiarInfo.text = "Pomiar walcowości: przerwano (${pomiarWalca.size} pkt)"
            zapiszPostepNagrywania()   // punkty zebrane do tej pory zostają w cache — nic nie ginie
            return
        }
        val punkty = pomiarWalca.toSortedMap().map { PomiarWalca(it.key.toFloat(), it.value) }
        val (pelne, wyp) = AnalizaWalca.wypelnijSasiadem(pomiarWalca)
        DaneWalca.punkty = punkty
        DaneWalca.wynik = AnalizaWalca.analizuj(punkty)
        DaneWalca.pelne = pelne
        DaneWalca.wypelnienie = wyp
        prefs.edit().putString("pomiarPunkty", serializujPunkty(punkty))
            .remove("pomiarWalcaProbki").putBoolean("nagrywanieAktywne", false).apply()
        b.tvPomiarInfo.text = "Pomiar walcowości: ${punkty.size} pkt – gotowe"
        startActivity(Intent(this, WalcowoscActivity::class.java))
    }

    private fun serializujPunkty(p: List<PomiarWalca>): String = p.joinToString(";") { "${it.katDeg}:${it.promien}" }

    private fun deserializujPunkty(s: String): List<PomiarWalca> =
        if (s.isBlank()) emptyList() else s.split(";").mapNotNull { seg ->
            val cz = seg.split(":")
            if (cz.size != 2) null else {
                val k = cz[0].toFloatOrNull(); val v = cz[1].toFloatOrNull()
                if (k != null && v != null) PomiarWalca(k, v) else null
            }
        }

    /** Jeśli w module są już jakiekolwiek dane (pomiar walcowości w toku/zakończony albo przełamanie spoin),
     *  pyta o potwierdzenie przed ich nadpisaniem; w przeciwnym razie wykonuje akcję od razu. */
    private fun potwierdzZastapienieDanych(akcja: () -> Unit) {
        val saDane = pomiarWalca.isNotEmpty() || DaneWalca.punkty.isNotEmpty() || DanePrzelamania.pomiary.isNotEmpty()
        if (!saDane) { akcja(); return }
        AlertDialog.Builder(this)
            .setTitle("Zastąpić bieżące dane?")
            .setMessage("W module są już dane pomiarowe (walcowość i/lub przełamanie spoin). Ta operacja je nadpisze.")
            .setPositiveButton("Zastąp") { _, _ -> akcja() }
            .setNegativeButton("Anuluj", null)
            .show()
    }

    /** Przywraca ostatni zakończony pomiar walcowości: albo z żyjącego w pamięci procesu obiektu DaneWalca
     *  (np. po powrocie z menu, gdy sam ekran modułu został odtworzony od nowa), albo — po zimnym starcie
     *  aplikacji, gdy DaneWalca jest puste — z trwałego zapisu w SharedPreferences. */
    private fun wczytajOstatniPomiar() {
        if (DaneWalca.punkty.isNotEmpty()) {
            val mapa = DaneWalca.punkty.associate { it.katDeg.toInt() to it.promien }
            pomiarWalca.putAll(mapa)
            b.tvPomiarInfo.visibility = View.VISIBLE
            b.tvPomiarInfo.text = "Pomiar walcowości: ${DaneWalca.punkty.size} pkt – gotowe"
            b.polarLive.ustawDane(DaneWalca.punkty, DaneWalca.wynik ?: AnalizaWalca.analizuj(DaneWalca.punkty))
            return
        }
        val zapisane = deserializujPunkty(prefs.getString("pomiarPunkty", "") ?: "")
        if (zapisane.isEmpty()) return
        DaneWalca.punkty = zapisane
        DaneWalca.wynik = AnalizaWalca.analizuj(zapisane)
        val mapa = zapisane.associate { it.katDeg.toInt() to it.promien }
        val (pelne, wyp) = AnalizaWalca.wypelnijSasiadem(mapa)
        DaneWalca.pelne = pelne; DaneWalca.wypelnienie = wyp
        pomiarWalca.putAll(mapa)
        b.tvPomiarInfo.visibility = View.VISIBLE
        b.tvPomiarInfo.text = "Pomiar walcowości: ${zapisane.size} pkt – przywrócono ostatni pomiar"
        b.polarLive.ustawDane(zapisane, DaneWalca.wynik!!)
    }

    /** Przywraca niedokończone nagrywanie pomiaru walcowości (np. po przypadkowym wyjściu/zabiciu procesu w trakcie obrotu). */
    private fun wczytajPostepNagrywania() {
        if (pomiarWalca.isNotEmpty()) return
        val zapisane = deserializujPunkty(prefs.getString("pomiarWalcaProbki", "") ?: "")
        if (zapisane.isEmpty()) return
        pomiarWalca.putAll(zapisane.associate { it.katDeg.toInt() to it.promien })
        nagrywanie = prefs.getBoolean("nagrywanieAktywne", false)
        b.tvPomiarInfo.visibility = View.VISIBLE
        b.tvPomiarInfo.text = if (nagrywanie)
            "Pomiar walcowości: przywrócono nagrywanie… ${pomiarWalca.size}/360°"
        else
            "Pomiar walcowości: przywrócono przerwany pomiar (${pomiarWalca.size} pkt)"
        odswiezPolarLive()
    }

    // ---------- trwałość ustawień (SharedPreferences) ----------

    private fun prefsFloat(key: String): Float? = prefs.getFloat(key, Float.NaN).let { if (it.isNaN()) null else it }

    private fun zapiszFloatUst(key: String, v: Float?) {
        prefs.edit().apply { if (v != null) putFloat(key, v) else remove(key) }.apply()
    }

    private fun wczytajUstawienia() {
        if (DaneWalca.promienZadany == null) DaneWalca.promienZadany = prefsFloat("promienZadany")
        if (DaneWalca.tolerancjaBicia == null) DaneWalca.tolerancjaBicia = prefsFloat("tolBicie")
        if (DaneWalca.tolerancjaWalcowosc == null) DaneWalca.tolerancjaWalcowosc = prefsFloat("tolWalcowosc")
        if (DaneWalca.grubosc == null) DaneWalca.grubosc = prefsFloat("grubosc")
        DaneWalca.typKonstrukcji = TypKonstrukcji.values()
            .getOrElse(prefs.getInt("typKonstrukcji", DaneWalca.typKonstrukcji.ordinal)) { DaneWalca.typKonstrukcji }
        if (DanePrzelamania.liczbaSpoin == null)
            prefs.getInt("liczbaSpoin", -1).takeIf { it >= 0 }?.let { DanePrzelamania.liczbaSpoin = it }
        if (DanePrzelamania.tolerancjaMm == null) DanePrzelamania.tolerancjaMm = prefsFloat("tolPrzelamania")
        DanePrzelamania.wariant = WariantPrzelamania.values()
            .getOrElse(prefs.getInt("wariant", DanePrzelamania.wariant.ordinal)) { DanePrzelamania.wariant }
        DanePrzelamania.obciazenie = Obciazenie.values()
            .getOrElse(prefs.getInt("obciazenie", DanePrzelamania.obciazenie.ordinal)) { DanePrzelamania.obciazenie }
    }

    private fun tolWatcher(zapisz: (Float?) -> Unit) = object : TextWatcher {
        override fun afterTextChanged(s: Editable?) {
            zapisz(s?.toString()?.trim()?.replace(',', '.')?.toFloatOrNull())
        }
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    }

    // ---------- wybór normy (spinnery) ----------

    private fun ustawSpinnery() {
        val typy = listOf(
            "zbiornik ciśnieniowy (%, PN-EN 13445-4)" to TypKonstrukcji.CISNIENIOWY,
            "zbiornik magazynowy (mm wg D, API 650)" to TypKonstrukcji.MAGAZYNOWY
        )
        b.spinTypKonstrukcji.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, typy.map { it.first })
        b.spinTypKonstrukcji.setSelection(typy.indexOfFirst { it.second == DaneWalca.typKonstrukcji })
        b.spinTypKonstrukcji.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                DaneWalca.typKonstrukcji = typy[position].second
                prefs.edit().putInt("typKonstrukcji", DaneWalca.typKonstrukcji.ordinal).apply()
                autoWypelnijTolerancje()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val warianty = listOf(
            Triple("EN13445-4:2014+, obciążenie cykliczne", WariantPrzelamania.A_2014, Obciazenie.CYKLICZNE),
            Triple("EN13445-4:2014+, obciążenie statyczne", WariantPrzelamania.A_2014, Obciazenie.STATYCZNE),
            Triple("EN13445-4:2009, obciążenie cykliczne", WariantPrzelamania.B_2009, Obciazenie.CYKLICZNE),
            Triple("EN13445-4:2009, obciążenie statyczne", WariantPrzelamania.B_2009, Obciazenie.STATYCZNE)
        )
        b.spinWariantPrzelamania.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, warianty.map { it.first })
        b.spinWariantPrzelamania.setSelection(warianty.indexOfFirst {
            it.second == DanePrzelamania.wariant && it.third == DanePrzelamania.obciazenie
        })
        b.spinWariantPrzelamania.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                DanePrzelamania.wariant = warianty[position].second
                DanePrzelamania.obciazenie = warianty[position].third
                prefs.edit()
                    .putInt("wariant", DanePrzelamania.wariant.ordinal)
                    .putInt("obciazenie", DanePrzelamania.obciazenie.ordinal)
                    .apply()
                autoWypelnijTolerancje()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /** Gdy znane są R i grubość, proponuje tolerancję bicia/walcowości/przełamania wg wybranej normy - tylko do pustych pól. */
    private fun autoWypelnijTolerancje() {
        val r = DaneWalca.promienZadany ?: return
        val e = DaneWalca.grubosc ?: return

        val (tolMm, _) = progOwalnosciMm(e, r, DaneWalca.typKonstrukcji)
        val txt = "%.3f".format(Locale.US, tolMm)
        if (b.etTolBicie.text.isNullOrBlank()) { b.etTolBicie.setText(txt) }
        if (b.etTolWalcowosc.text.isNullOrBlank()) { b.etTolWalcowosc.setText(txt) }

        val tolPrzelamaniaMm = progPrzelamaniaMm(e, r, DanePrzelamania.wariant, DanePrzelamania.obciazenie)
        if (b.etTolPrzelamania.text.isNullOrBlank()) {
            b.etTolPrzelamania.setText("%.3f".format(Locale.US, tolPrzelamaniaMm))
        }
    }

    // ---------- symulator na żywo (bez Nucleo) ----------

    private val simRunnable = object : Runnable {
        override fun run() {
            if (!simActive) return
            krokSymulacji()
            simHandler.postDelayed(this, (1000L / simSpeed).coerceAtLeast(1L))
        }
    }

    /** Referencyjna amplituda [mm] do skalowania symulacji: tolerancja bicia, w braku - próg z normy, w braku - wartość domyślna. */
    private fun progDemoMm(): Float {
        DaneWalca.tolerancjaBicia?.let { return it }
        val r = DaneWalca.promienZadany; val e = DaneWalca.grubosc
        if (r != null && e != null) return progOwalnosciMm(e, r, DaneWalca.typKonstrukcji).first
        return 0.15f
    }

    /** Symuluje pomiar przełamania na spoinach wzdłużnych: N punktów co ~250mm na spoinę, celowo losując OK/PRZEKROCZONO. */
    private fun symulujPrzelamanie() {
        val rnd = java.util.Random()
        val liczbaSpoin = (DanePrzelamania.liczbaSpoin ?: 1).coerceAtLeast(1)
        val tol = DanePrzelamania.tolerancjaMm ?: run {
            val r = DaneWalca.promienZadany; val e = DaneWalca.grubosc
            if (r != null && e != null) progPrzelamaniaMm(e, r, DanePrzelamania.wariant, DanePrzelamania.obciazenie) else 2f
        }
        val punktowNaSpoine = 8
        val pomiary = ArrayList<PomiarPrzelamania>()
        val ktoraSpoinaPrzekracza = if (rnd.nextBoolean()) rnd.nextInt(liczbaSpoin) else -1
        for (s in 0 until liczbaSpoin) {
            val przekroczyc = s == ktoraSpoinaPrzekracza
            for (j in 0 until punktowNaSpoine) {
                val faktor = if (przekroczyc) 1.2f + rnd.nextFloat() * 0.6f else 0.1f + rnd.nextFloat() * 0.6f
                val p = tol * faktor
                val roznica = (rnd.nextFloat() - 0.5f) * 0.2f * tol
                pomiary.add(PomiarPrzelamania(s + 1, j * 250f, p + roznica, p - roznica))
            }
        }
        DanePrzelamania.pomiary = pomiary
        val pmax = pMax(pomiary) ?: 0f
        val ok = pmax <= tol
        b.tvPrzelamanieInfo.text = "Przełamanie: Pmax=%.3f mm  (tol %.3f mm)  %s"
            .format(Locale.US, pmax, tol, if (ok) "OK" else "PRZEKROCZONO")
        log("— symulacja przełamania: $liczbaSpoin spoin, Pmax=${"%.3f".format(Locale.US, pmax)}mm, " +
            (if (ok) "OK" else "PRZEKROCZONO") + " —")
    }

    private fun startSymulacji() {
        val rnd = java.util.Random()
        simR0 = DaneWalca.promienZadany ?: 100f

        // celowo: losowo albo w tolerancji, albo z przekroczeniem - żeby zademonstrować obie oceny OK/PRZEKROCZONO
        val przekroczyc = rnd.nextBoolean()
        val prog = progDemoMm()
        val faktor = if (przekroczyc) 1.3f + rnd.nextFloat() * 0.7f else 0.15f + rnd.nextFloat() * 0.5f
        simE = prog * faktor / 2f      // bicie (max-min) ≈ 2*simE dla czystego mimośrodu
        simFe = rnd.nextFloat() * 360f
        simOv = 0.02f + rnd.nextFloat() * 0.13f; simFo = rnd.nextFloat() * 180f
        simTri = rnd.nextFloat() * 0.04f
        simDeg = 0
        pomiarWalca.clear()
        nagrywanie = true
        simActive = true
        b.btnSymLive.text = "Symulacja na żywo: STOP"
        log("— symulacja: R=${"%.1f".format(Locale.US, simR0)}mm, " +
            (if (przekroczyc) "celowo POZA tolerancją" else "w tolerancji") + " —")
        symulujPrzelamanie()
        simHandler.post(simRunnable)
    }

    private fun stopSymulacji(pelnyObrot: Boolean = false) {
        simActive = false
        simHandler.removeCallbacks(simRunnable)
        b.btnSymLive.text = "Start symulacji"
        if (pelnyObrot) zakonczPomiar()
    }

    private fun krokSymulacji() {
        val th = simDeg.toFloat()
        val a  = Math.toRadians(th.toDouble())
        val ae = Math.toRadians((th - simFe).toDouble())
        val ao = Math.toRadians((2f * (th - simFo)).toDouble())
        val at = Math.toRadians((3f * th).toDouble())
        var r = simR0
        r += (simE  * Math.cos(ae)).toFloat()
        r += (simOv * Math.cos(ao)).toFloat()
        r += (simTri * Math.cos(at)).toFloat()
        r += (Math.random().toFloat() - 0.5f) * 0.016f

        val m = Pomiar(th, (r * Math.cos(a)).toFloat(), (r * Math.sin(a)).toFloat(), r)
        val us = Locale.US
        b.tvKat.text = "Kąt:  %.2f°".format(us, m.kat)
        b.tvX.text   = "X:  %.3f".format(us, m.x)
        b.tvY.text   = "Y:  %.3f".format(us, m.y)
        b.tvL.text   = "L:  %.3f mm".format(us, m.l)
        rejestrujProbke(m)

        simDeg++
        if (simDeg >= 360) stopSymulacji(pelnyObrot = true)
    }

    // ---------- zapis CSV ----------

    private fun zapiszCsv() {
        try {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            val f = File(dir, "rampt_$ts.csv")
            f.writeText(zebrane.toString())
            toast("Zapisano: ${f.absolutePath}")
            log("Zapisano CSV: ${f.name}")
        } catch (e: Exception) {
            toast("Błąd zapisu: ${e.message}")
        }
    }

    private fun log(s: String) {
        b.tvLog.append(s + "\n")
        b.logScroll.post { b.logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        super.onDestroy()
        simActive = false
        simHandler.removeCallbacks(simRunnable)
        // wspólne połączenie Bt zostaje aktywne dla pozostałych modułów
    }
}