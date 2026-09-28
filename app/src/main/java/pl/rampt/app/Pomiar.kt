package pl.rampt.app

/**
 * Jeden pomiar odebrany z Nucleo.
 * Firmware wysyła linię CSV w formacie:  kat;X;Y;L\r\n
 *  - separatorem pól jest ';'
 *  - separatorem dziesiętnym jest ',' (pod polski Excel)
 *  - pole L może mieć wartość "brak", gdy suwmiarka nie nadaje
 */
data class Pomiar(
    val kat: Float,
    val x: Float,
    val y: Float,
    val l: Float?          // null = "brak"
) {
    companion object {
        fun parse(line: String): Pomiar? {
            val p = line.split(';')
            if (p.size < 4) return null
            fun f(s: String) = s.trim().replace(',', '.').toFloatOrNull()
            val kat = f(p[0]) ?: return null
            val x   = f(p[1]) ?: return null
            val y   = f(p[2]) ?: return null
            val l   = if (p[3].trim().equals("brak", ignoreCase = true)) null else f(p[3])
            return Pomiar(kat, x, y, l)
        }
    }
}
