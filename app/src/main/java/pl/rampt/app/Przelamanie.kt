package pl.rampt.app

import java.util.Locale

/** Wariant tabeli progów przełamania wg PN-EN 13445-4 (rozdz. 3 dokumentu źródłowego). */
enum class WariantPrzelamania { A_2014, B_2009 }

/** Rodzaj obciążenia — steruje progiem przełamania i (w wariancie A) owalności. */
enum class Obciazenie { CYKLICZNE, STATYCZNE }

/** Typ konstrukcji — steruje wyborem tabeli progu owalności (rozdz. 2 dokumentu źródłowego). */
enum class TypKonstrukcji { CISNIENIOWY, MAGAZYNOWY }

/** Jeden punkt pomiaru przełamania na spoinie wzdłużnej: odczyty szczelinomierza po obu stronach szablonu. */
data class PomiarPrzelamania(val spoinaNr: Int, val pozycjaMm: Float, val p1: Float, val p2: Float) {
    val p: Float get() = 0.25f * (p1 + p2)
}

/** Współdzielone ustawienia/dane kontroli przełamania spoiny wzdłużnej. */
object DanePrzelamania {
    var liczbaSpoin: Int? = null
    var tolerancjaMm: Float? = null
    var wariant: WariantPrzelamania = WariantPrzelamania.A_2014
    var obciazenie: Obciazenie = Obciazenie.STATYCZNE
    var pomiary: List<PomiarPrzelamania> = emptyList()
}

/** Próg dopuszczalnego przełamania [mm] wg wybranego wariantu normy (PN-EN 13445-4, rozdz. 3). */
fun progPrzelamaniaMm(gruboscMm: Float, promienNominalnyMm: Float, wariant: WariantPrzelamania, obciazenie: Obciazenie): Float {
    return when (wariant) {
        WariantPrzelamania.A_2014 -> {
            val eD = gruboscMm / (2f * promienNominalnyMm)
            if (obciazenie == Obciazenie.CYKLICZNE) minOf(gruboscMm / 3f, 10f)
            else if (eD <= 0.025f) minOf(gruboscMm / 3f, 10f)
            else minOf(gruboscMm / 6f, 15f)
        }
        WariantPrzelamania.B_2009 -> {
            val cykl = obciazenie == Obciazenie.CYKLICZNE
            when {
                gruboscMm <= 3f -> if (cykl) 1.5f else 2.0f
                gruboscMm <= 6f -> if (cykl) 2.0f else 2.5f
                gruboscMm <= 10f -> if (cykl) 2.5f else 3.0f
                gruboscMm <= 20f -> if (cykl) 3.0f else 3.5f
                else -> if (cykl) 3.5f else 4.0f   // >30mm poza tabelą źródła - przybliżenie najwyższym progiem
            }
        }
    }
}

/** Maksymalna wartość P = 0,25*(P1+P2) spośród wszystkich zebranych punktów (norma: szuka się miejsca maks. przełamania). */
fun pMax(pomiary: List<PomiarPrzelamania>): Float? = pomiary.maxOfOrNull { it.p }

/** Wiersz wyniku przełamania z oceną tolerancji (jeśli dane są kompletne). */
fun wierszPrzelamania(): CharSequence {
    val us = Locale.US
    val pmax = pMax(DanePrzelamania.pomiary)
    if (pmax == null) return "%-13s%s".format(us, "Przelamanie:", "brak pomiaru")
    return wierszZOcena("Przelamanie:", pmax, DanePrzelamania.tolerancjaMm)
}
