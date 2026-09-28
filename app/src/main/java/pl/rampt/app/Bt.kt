package pl.rampt.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Wspólne połączenie Bluetooth (SPP) dla całej aplikacji.
 * Jedno połączenie, wielu słuchaczy. Odbierane linie parsuje na Pomiar (kąt;X;Y;L)
 * i rozsyła do zarejestrowanych modułów. Połączenie żyje między ekranami.
 */
object Bt {

    private var client: BluetoothSppClient? = null

    @Volatile var polaczone = false; private set
    @Volatile var ostatni: Pomiar? = null; private set
    @Volatile var ostatniaLinia: String? = null; private set
    var nazwa: String? = null; private set

    private val naPomiar = CopyOnWriteArrayList<(Pomiar) -> Unit>()
    private val naLinia = CopyOnWriteArrayList<(String) -> Unit>()
    private val naStatus = CopyOnWriteArrayList<(String, Boolean) -> Unit>()

    fun dodajPomiar(l: (Pomiar) -> Unit) { naPomiar.add(l) }
    fun usunPomiar(l: (Pomiar) -> Unit) { naPomiar.remove(l) }
    fun dodajLinia(l: (String) -> Unit) { naLinia.add(l) }
    fun usunLinia(l: (String) -> Unit) { naLinia.remove(l) }
    fun dodajStatus(l: (String, Boolean) -> Unit) { naStatus.add(l); l("", polaczone) }
    fun usunStatus(l: (String, Boolean) -> Unit) { naStatus.remove(l) }

    @SuppressLint("MissingPermission")
    fun polaczZ(adapter: BluetoothAdapter, device: BluetoothDevice) {
        rozlacz()
        nazwa = try { device.name } catch (_: SecurityException) { device.address }
        client = BluetoothSppClient(
            adapter = adapter,
            onLine = { line ->
                ostatniaLinia = line
                naLinia.forEach { it(line) }
                val p = Pomiar.parse(line)
                if (p != null) { ostatni = p; naPomiar.forEach { it(p) } }
            },
            onStatus = { s -> naStatus.forEach { it(s, polaczone) } },
            onConnected = { ok ->
                polaczone = ok
                naStatus.forEach { it(if (ok) "Połączono z ${nazwa ?: ""}" else "Rozłączono", ok) }
            }
        )
        client?.connect(device)
    }

    fun rozlacz() {
        client?.disconnect()
        client = null
        polaczone = false
    }

    fun wyslij(text: String) { client?.send(text) }
}