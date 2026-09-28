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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import pl.rampt.app.databinding.ActivityMenuBinding
import java.util.Locale

/** Ekran startowy – wybór operacji + wspólne połączenie Bluetooth. */
class MenuActivity : AppCompatActivity() {

    private lateinit var b: ActivityMenuBinding
    private var adapter: BluetoothAdapter? = null

    private val naStatus: (String, Boolean) -> Unit = { st, ok ->
        b.tvBtStatus.text = when {
            ok -> "połączono: ${Bt.nazwa ?: ""}"
            st.isNotEmpty() -> st
            else -> "rozłączono"
        }
        b.btnBt.setColorFilter(if (ok) 0xFF1565C0.toInt() else 0xFF9E9E9E.toInt())
    }
    private val naPomiar: (Pomiar) -> Unit = { m ->
        b.tvBtLive.text = if (m.l != null) "L: %.2f".format(Locale.getDefault(), m.l)
        else "kąt: %.1f°".format(Locale.getDefault(), m.kat)
    }

    private val reqPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { wynik -> if (wynik.values.all { it }) wybierzUrzadzenie() else toast("Brak uprawnień Bluetooth") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMenuBinding.inflate(layoutInflater)
        setContentView(b.root)

        adapter = getSystemService(BluetoothManager::class.java)?.adapter

        b.btnBt.setOnClickListener { if (Bt.polaczone) Bt.rozlacz() else polaczBluetooth() }

        b.cardTrasowanie.setOnClickListener {
            startActivity(Intent(this, TrasowanieActivity::class.java))
        }

        b.cardCylinder.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        b.btnWyloguj.setOnClickListener {
            AllowlistAuth.wyloguj()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        Bt.dodajStatus(naStatus); Bt.dodajPomiar(naPomiar)
    }

    override fun onPause() {
        super.onPause()
        Bt.usunStatus(naStatus); Bt.usunPomiar(naPomiar)
    }

    private fun polaczBluetooth() {
        val a = adapter ?: run { toast("To urządzenie nie ma Bluetooth"); return }
        if (!a.isEnabled) { toast("Włącz Bluetooth w ustawieniach"); return }
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        else arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
        val brak = perms.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (brak) reqPerms.launch(perms) else wybierzUrzadzenie()
    }

    @SuppressLint("MissingPermission")
    private fun wybierzUrzadzenie() {
        val a = adapter ?: return
        val bonded = try { a.bondedDevices.toList() } catch (e: SecurityException) { emptyList() }
        if (bonded.isEmpty()) {
            toast("Najpierw sparuj urządzenie (np. \"Trasowanica Oska\") w ustawieniach BT"); return
        }
        val nazwy = bonded.map { d -> try { "${d.name}\n${d.address}" } catch (_: SecurityException) { d.address } }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Wybierz urządzenie")
            .setItems(nazwy) { _, i -> Bt.polaczZ(a, bonded[i]) }
            .setNegativeButton("Anuluj", null).show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}