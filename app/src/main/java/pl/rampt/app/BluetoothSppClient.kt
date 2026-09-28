package pl.rampt.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.Looper
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Klient Bluetooth klasycznego (SPP / RFCOMM).
 * ESP32 z BluetoothSerial wystawia standardowy profil SPP,
 * dlatego łączymy się po UUID 00001101-0000-1000-8000-00805F9B34FB.
 *
 * Odbiór działa w osobnym wątku; linie (zakończone \n) trafiają do onLine
 * już na wątku głównym UI.
 */
class BluetoothSppClient(
    private val adapter: BluetoothAdapter,
    private val onLine: (String) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onConnected: (Boolean) -> Unit
) {
    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private val main = Handler(Looper.getMainLooper())
    private var socket: BluetoothSocket? = null
    private var outStream: OutputStream? = null
    @Volatile private var running = false

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        // Wykrywanie zakłóca połączenie – wyłączamy je przed connect().
        try { adapter.cancelDiscovery() } catch (_: SecurityException) {}
        Thread {
            try {
                post { onStatus("Łączenie z ${device.name}...") }
                val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket = s
                s.connect()                       // blokujące, dlatego w wątku
                outStream = s.outputStream
                running = true
                post { onStatus("Połączono z ${device.name}"); onConnected(true) }
                readLoop(s.inputStream)
            } catch (e: Exception) {
                post { onStatus("Błąd połączenia: ${e.message}"); onConnected(false) }
                closeQuietly()
            }
        }.start()
    }

    private fun readLoop(input: InputStream) {
        val buf = ByteArray(1024)
        val line = StringBuilder()
        try {
            while (running) {
                val n = input.read(buf)
                if (n < 0) break
                android.util.Log.d("BT_RAW", "odebrano $n bajtów")
                for (i in 0 until n) {
                    val c = buf[i].toInt().toChar()
                    when (c) {
                        '\n' -> {
                            val l = line.toString().trim()
                            line.setLength(0)
                            android.util.Log.d("BT_RAW", "linia=[$l]")
                            if (l.isNotEmpty()) post { onLine(l) }
                        }
                        '\r' -> { /* pomijamy */ }
                        else -> line.append(c)
                    }
                }
            }
        } catch (e: Exception) {
            if (running) post { onStatus("Utracono połączenie: ${e.message}") }
        } finally {
            running = false
            post { onConnected(false) }
            closeQuietly()
        }
    }

    /** Wysyła tekst do Nucleo, dopisując CR+LF (firmware kończy komendę na \r lub \n). */
    fun send(text: String) {
        val out = outStream ?: run {
            post { onStatus("Nie połączono – nie wysłano") }
            return
        }
        Thread {
            try {
                out.write((text + "\r\n").toByteArray())
                out.flush()
            } catch (e: Exception) {
                post { onStatus("Błąd wysyłania: ${e.message}") }
            }
        }.start()
    }

    fun disconnect() {
        running = false
        closeQuietly()
        post { onStatus("Rozłączono"); onConnected(false) }
    }

    private fun closeQuietly() {
        try { outStream?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        outStream = null
        socket = null
    }

    private fun post(block: () -> Unit) { main.post(block) }
}
