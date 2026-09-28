package pl.rampt.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

class TrasowanieCanvasView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, def: Int = 0
) : View(context, attrs, def) {

    enum class Tryb { KRAWEDZ_A, KRAWEDZ_B, OKRAG, KIERUNEK, TRASOWANIE }

    var onReadout: ((x: String, y: String, r: String, kat: String, enc: String, info: String) -> Unit)? = null

    private val kA = ArrayList<Punkt>()
    private val kB = ArrayList<Punkt>()
    private val kOkrag = ArrayList<Punkt>()
    private var kierunek: Punkt? = null
    private var uklad: Uklad? = null
    private var okrag: Bazowanie.Okrag? = null
    private var biezacy: Punkt? = null
    private var cele: List<Pair<Float, Float>> = emptyList()
    private var done = BooleanArray(0)
    private var najblizszyIdx = -1
    private var najblizszyDist = Float.MAX_VALUE
    var onNaprowadzanie: ((dist: Float, idx: Int) -> Unit)? = null
    /** Kolor osi X/Y (czerwony->żółty->zielony wg zbliżania się do celu); null = brak (przywróć domyślny). */
    var onKolorOsi: ((kolorX: Int?, kolorY: Int?) -> Unit)? = null

    var tryb = Tryb.KRAWEDZ_A

        set(v) { field = v; raportuj() }
    /** Gdy true, wskazania X/Y (w nowym układzie) pokazują odległość pozostałą do wybranego/najbliższego celu. */
    var pokazPozostalosc = false
        set(v) { field = v; raportuj() }
    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d
    private var zdjecie: Bitmap? = null
    private var zdjCx = 0f; private var zdjCy = 0f      // środek zdjęcia w mm (maszyna, y w górę)
    private var zdjRotRad = 0f                          // obrót zdjęcia [rad]
    private var zdjSkala = 1f                           // mm na piksel bitmapy
    private var lustro = false
    private var edycjaZdjecia = false
    private var zdjLastX = 0f; private var zdjLastY = 0f
    private var gestDist0 = 0f; private var gestAngle0 = 0f
    private var gestFocalX0 = 0f; private var gestFocalY0 = 0f
    private val zdjMatrix = Matrix()
    private val pZdj = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = 190; isFilterBitmap = true }
    private val pZdjSrodek = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF7043.toInt(); style = Paint.Style.FILL }

    /**
     * Punkt kadru: [src] to miejsce zdjęcia oznaczone przy dodaniu punktu (stałe, w px bitmapy),
     * [dst] to jego bieżące (przeciągnięte) położenie - też w px bitmapy. Obraz jest rysowany
     * trójkątami (wachlarz od punktu 0) mapowanymi src->dst, więc przeciągnięcie punktu
     * ROZCIĄGA/ZNIEKSZTAŁCA fragment zdjęcia wokół niego, a nie tylko przesuwa kadr.
     */
    private data class KadrPunkt(val src: Punkt, var dst: Punkt)
    // wielokąt punktów kadru W UKŁADZIE LOKALNYM ZDJĘCIA (px bitmapy) - dzięki temu kadr
    // przesuwa się/obraca/skaluje razem ze zdjęciem. Przy edycji: stuknięcie = dodaj punkt, przeciągnięcie = rozciągnij.
    private val kadrPunkty = ArrayList<KadrPunkt>()
    private var kadrDrag = -1
    private var edycjaKadru = false
    private val pKadr = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2E7D32.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(1.8f) }
    private val pKadrUchwyt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2E7D32.toInt(); style = Paint.Style.FILL }

    fun ustawZdjecie(bmp: Bitmap?) {
        zdjecie = bmp
        kadrPunkty.clear()
        if (bmp != null) {
            val wmm = 120f
            zdjSkala = wmm / bmp.width
            zdjCx = 0f; zdjCy = 0f; zdjRotRad = 0f; lustro = false
            edycjaZdjecia = true; edycjaKadru = false
        } else edycjaZdjecia = false
        invalidate()
    }
    fun usunZdjecie() { zdjecie = null; edycjaZdjecia = false; edycjaKadru = false; kadrPunkty.clear(); invalidate() }
    fun toggleEdycjaZdjecia() { if (zdjecie != null) { edycjaZdjecia = !edycjaZdjecia; if (edycjaZdjecia) edycjaKadru = false; invalidate() } }
    fun maZdjecie() = zdjecie != null
    fun toggleEdycjaKadru() {
        if (zdjecie == null) return
        edycjaKadru = !edycjaKadru
        if (edycjaKadru) edycjaZdjecia = false
        invalidate()
    }
    fun czyEdycjaKadru() = edycjaKadru
    fun wyczyscKadr() { kadrPunkty.clear(); invalidate() }
    fun cofnijPunktKadru() { if (kadrPunkty.isNotEmpty()) { kadrPunkty.removeAt(kadrPunkty.size - 1); invalidate() } }
    fun obrocZdjecie() {
        if (zdjecie == null) return
        zdjRotRad += (Math.PI / 2f).toFloat()
        invalidate(); raportuj()
    }
    fun lustroZdjecie() { if (zdjecie != null) { lustro = !lustro; invalidate() } }
    // "Baza" = widok ustawiony ręcznie (pinch/przeciąganie/śledzenie/dopasowanie). Faktycznie
    // rysowany widok (spanMm/centrMx/centrMy) to "baza" doraźnie dobliżona do celu podczas
    // trasowania - patrz zastosujZbliżenieDoCelu() wywoływane na początku onDraw().
    private var spanBaza = 320f
    private var centrBazaX = 0f; private var centrBazaY = 0f
    private var spanMm = 320f
    private var centrMx = 0f; private var centrMy = 0f     // środek widoku w mm (po zbliżeniu do celu)
    private var ppm = 1f
    private var cx = 0f
    private var cy = 0f
    private var lastFocalX = 0f; private var lastFocalY = 0f
    private val skalaDet = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(det: ScaleGestureDetector): Boolean {
            val ww = width.toFloat(); val hh = height.toFloat()
            val ppmOld = min(ww, hh) / spanBaza
            val fmx = centrBazaX + (det.focusX - ww / 2f) / ppmOld     // punkt maszynowy pod palcami (przed)
            val fmy = centrBazaY - (det.focusY - hh / 2f) / ppmOld
            spanBaza = (spanBaza / det.scaleFactor).coerceIn(20f, 8000f)
            val ppmNew = min(ww, hh) / spanBaza
            centrBazaX = fmx - (det.focusX - ww / 2f) / ppmNew          // utrzymaj punkt pod palcami
            centrBazaY = fmy + (det.focusY - hh / 2f) / ppmNew
            invalidate(); return true
        }
    })
    private val ZOOM_CEL_ZASIEG_MM = 80f     // mm - poniżej tej odległości widok zaczyna się dobliżać do celu
    private val ZOOM_CEL_MIN_SPAN_MM = 25f   // mm - najmniejszy rozstaw widoku tuż przy celu

    private val pGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x14000000; strokeWidth = dp(0.7f) }
    private val pAxis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55000000; strokeWidth = dp(1f) }
    private val pA = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1565C0.toInt(); style = Paint.Style.FILL }
    private val pB = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0F6E56.toInt(); style = Paint.Style.FILL }
    private val pO = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8E24AA.toInt(); style = Paint.Style.FILL }
    private val pKier = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEF6C00.toInt(); style = Paint.Style.FILL }
    private val pLineA = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1565C0.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(2.2f) }
    private val pLineB = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0F6E56.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(2.2f) }
    private val pOkrag = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8E24AA.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(2f) }
    private val pRadius = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000; style = Paint.Style.STROKE; strokeWidth = dp(1.2f) }
    private val pCur = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE53935.toInt(); style = Paint.Style.FILL }
    private val pOrigin = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF102A43.toInt(); style = Paint.Style.FILL }
    private val pCenter = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF444441.toInt(); style = Paint.Style.FILL }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF666666.toInt(); textSize = dp(11f) }
    private val pAxisLbl = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(12f) }
    private val pCel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD81B60.toInt(); style = Paint.Style.FILL }
    private val pCelTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFAD1457.toInt(); textSize = dp(10f) }
    private val pCelDone = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2E7D32.toInt(); style = Paint.Style.FILL }
    private val pCelActive = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD81B60.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(2f) }

    fun cofnij() {
        when (tryb) {
            Tryb.KRAWEDZ_B -> if (kB.isNotEmpty()) kB.removeAt(kB.size - 1)
            Tryb.OKRAG -> if (kOkrag.isNotEmpty()) kOkrag.removeAt(kOkrag.size - 1)
            Tryb.KIERUNEK -> kierunek = null
            else -> if (kA.isNotEmpty()) kA.removeAt(kA.size - 1)
        }
        przelicz(); invalidate()
    }

    fun wyczysc() {
        kA.clear(); kB.clear(); kOkrag.clear(); kierunek = null
        uklad = null; okrag = null; biezacy = null
        przelicz(); invalidate()
    }
    var sledzenie = false
    fun ustawSledzenie(on: Boolean) { sledzenie = on; if (on) biezacy?.let { centrBazaX = it.xm; centrBazaY = it.ym }; invalidate() }
    /** Do wpięcia pozycji z trasownicy (BT): ustawia bieżący punkt i, jeśli włączone, centruje widok. */
    fun aktualizujPozycje(xm: Float, ym: Float) {
        biezacy = Punkt(xm, ym)
        if (sledzenie) { centrBazaX = xm; centrBazaY = ym }
        raportuj(); invalidate()
    }
    fun ustawCele(c: List<Pair<Float, Float>>) { cele = c; done = BooleanArray(c.size); najblizszyIdx = -1; dopasujWidok(); invalidate() }
    /** Dopasowuje środek i skalę widoku do celów (+ środek obrotu), z marginesem. */
    fun dopasujWidok() {
        if (cele.isEmpty()) { spanBaza = 320f; centrBazaX = 0f; centrBazaY = 0f; invalidate(); return }
        var minx = 0f; var maxx = 0f; var miny = 0f; var maxy = 0f      // uwzględnij środek obrotu (0,0)
        for (cc in cele) {
            val (mx, my) = celMachine(cc.first, cc.second)
            if (mx < minx) minx = mx; if (mx > maxx) maxx = mx
            if (my < miny) miny = my; if (my > maxy) maxy = my
        }
        centrBazaX = (minx + maxx) / 2f; centrBazaY = (miny + maxy) / 2f
        val rozmiar = kotlin.math.max(maxx - minx, maxy - miny)
        spanBaza = (rozmiar * 1.3f).coerceAtLeast(60f)
        invalidate()
    }
    fun biezacaPozycja(): Pair<Float, Float>? {
        val p = biezacy ?: return null
        val u = uklad
        return if (u != null) u.przelicz(p.xm, p.ym) else Pair(p.xm, p.ym)
    }

    fun potwierdzNajblizszy(): Int {
        val i = najblizszyIdx
        if (i in done.indices) { done[i] = true; najblizszyIdx = -1; invalidate(); raportuj() }
        return i
    }

    /**
     * Dobliża widok (zmniejsza spanMm i przesuwa środek w stronę celu) w miarę zbliżania się
     * do najbliższego/wybranego punktu trasowania - ułatwia precyzyjne trafienie w punkt.
     * Bazowy widok (spanBaza/centrBazaX/Y), ustawiany ręcznie (pinch/przeciąganie/śledzenie),
     * pozostaje nienaruszony - po oddaleniu się od celu widok wraca do niego płynnie.
     */
    private fun zastosujZblizenieDoCelu() {
        val i = najblizszyIdx
        if (tryb == Tryb.TRASOWANIE && i in cele.indices && najblizszyDist < ZOOM_CEL_ZASIEG_MM) {
            val (tmx, tmy) = celMachine(cele[i].first, cele[i].second)
            val frac = 1f - (najblizszyDist / ZOOM_CEL_ZASIEG_MM).coerceIn(0f, 1f)
            val spanCel = ZOOM_CEL_MIN_SPAN_MM.coerceAtMost(spanBaza)
            spanMm = spanBaza - (spanBaza - spanCel) * frac
            centrMx = centrBazaX + (tmx - centrBazaX) * frac
            centrMy = centrBazaY + (tmy - centrBazaY) * frac
        } else {
            spanMm = spanBaza
            centrMx = centrBazaX; centrMy = centrBazaY
        }
    }

    /** Kolor osi: czerwony (daleko) -> żółty -> zielony (blisko celu), wg odchyłki na danej osi. */
    private fun kolorOsi(odchylka: Float): Int {
        val zasieg = 30f       // mm - od tej odchyłki kolor jest w pełni czerwony
        val frac = (kotlin.math.abs(odchylka) / zasieg).coerceIn(0f, 1f)
        val hue = 120f * (1f - frac)   // 0=czerwony 60=żółty 120=zielony
        return Color.HSVToColor(floatArrayOf(hue, 0.85f, 0.85f))
    }

    private fun celMachine(x: Float, y: Float): Pair<Float, Float> {
        val u = uklad ?: return Pair(x, y)
        return Pair(u.ox + x * u.ux + y * u.nx, u.oy + x * u.uy + y * u.ny)
    }

    private fun przelicz() {
        okrag = if (kOkrag.size >= 3) Bazowanie.dopasujOkrag(kOkrag) else null
        val o = okrag; val kier = kierunek
        uklad = when {
            o != null && kier != null -> Bazowanie.ukladOkrag(o, kier)
            kA.size >= 2 && kB.size >= 2 -> Bazowanie.zbazuj(kA, kB)
            kA.size >= 2 -> Bazowanie.ukladKrawedzA(kA)
            else -> null
        }
        raportuj()
    }

    private fun mToS(xm: Float, ym: Float) = Pair(cx + (xm - centrMx) * ppm, cy - (ym - centrMy) * ppm)
    private fun sToM(sx: Float, sy: Float) = Punkt(centrMx + (sx - cx) / ppm, centrMy - (sy - cy) / ppm)

    /** Macierz: piksele bitmapy zdjęcia -> piksele ekranu (uwzględnia lustro/skalę/obrót/pozycję zdjęcia i widok canvasu). */
    private fun budujMacierzZdjecia(bmp: Bitmap): Matrix {
        val m = Matrix()
        m.postTranslate(-bmp.width / 2f, -bmp.height / 2f)
        if (lustro) m.postScale(-1f, 1f)
        m.postScale(zdjSkala, zdjSkala)
        m.postRotate(Math.toDegrees(zdjRotRad.toDouble()).toFloat())
        m.postTranslate(zdjCx, zdjCy)
        m.postTranslate(-centrMx, -centrMy)
        m.postScale(ppm, -ppm)
        m.postTranslate(cx, cy)
        return m
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bmpEdycja = zdjecie
        if (edycjaZdjecia && bmpEdycja != null) {
            parent?.requestDisallowInterceptTouchEvent(true)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount >= 2) {
                        kadrDrag = -1
                        val x0 = event.getX(0); val y0 = event.getY(0)
                        val x1 = event.getX(1); val y1 = event.getY(1)
                        gestDist0 = hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()).toFloat()
                        gestAngle0 = atan2((y1 - y0).toDouble(), (x1 - x0).toDouble()).toFloat()
                        gestFocalX0 = (x0 + x1) / 2f; gestFocalY0 = (y0 + y1) / 2f
                    } else {
                        zdjLastX = event.x; zdjLastY = event.y
                        // złap istniejący punkt kadru pod palcem - działa też poza dedykowanym trybem "Kadr"
                        kadrDrag = -1
                        if (kadrPunkty.isNotEmpty()) {
                            val m = budujMacierzZdjecia(bmpEdycja)
                            var bestD = dp(20f)
                            for (i in kadrPunkty.indices) {
                                val a = floatArrayOf(kadrPunkty[i].dst.xm, kadrPunkty[i].dst.ym)
                                m.mapPoints(a)
                                val dd = hypot((event.x - a[0]).toDouble(), (event.y - a[1]).toDouble()).toFloat()
                                if (dd < bestD) { bestD = dd; kadrDrag = i }
                            }
                        }
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount >= 2) {
                        val x0 = event.getX(0); val y0 = event.getY(0)
                        val x1 = event.getX(1); val y1 = event.getY(1)
                        val dist = hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()).toFloat()
                        val angle = atan2((y1 - y0).toDouble(), (x1 - x0).toDouble()).toFloat()
                        val fx = (x0 + x1) / 2f; val fy = (y0 + y1) / 2f
                        if (gestDist0 > 1f) zdjSkala = (zdjSkala * (dist / gestDist0)).coerceIn(0.001f, 50f)
                        zdjRotRad -= (angle - gestAngle0)
                        if (ppm > 0f) { zdjCx += (fx - gestFocalX0) / ppm; zdjCy -= (fy - gestFocalY0) / ppm }
                        gestDist0 = dist; gestAngle0 = angle; gestFocalX0 = fx; gestFocalY0 = fy
                    } else if (kadrDrag in kadrPunkty.indices) {
                        // przeciągamy złapany punkt kadru -> deformujemy zdjęcie (nawet po kadrze, poza trybem "Kadr")
                        val m = budujMacierzZdjecia(bmpEdycja)
                        val inv = Matrix()
                        if (m.invert(inv)) {
                            val a = floatArrayOf(event.x, event.y)
                            inv.mapPoints(a)
                            kadrPunkty[kadrDrag].dst = Punkt(a[0], a[1])
                        }
                    } else if (ppm > 0f) {
                        zdjCx += (event.x - zdjLastX) / ppm
                        zdjCy -= (event.y - zdjLastY) / ppm
                        zdjLastX = event.x; zdjLastY = event.y
                    }
                    invalidate()
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    // event.x/y (bez indeksu) to zawsze wskaźnik 0 - jeśli TO on jest podnoszony,
                    // trzeba zapamiętać pozycję TEGO, który ZOSTAJE, inaczej kolejny ACTION_MOVE
                    // (już z 1 palcem) policzy skok równy odległości między palcami.
                    val podnoszony = event.actionIndex
                    for (i in 0 until event.pointerCount) {
                        if (i != podnoszony) { zdjLastX = event.getX(i); zdjLastY = event.getY(i); break }
                    }
                }
                MotionEvent.ACTION_UP -> kadrDrag = -1
            }
            return true
        }
        val bmpKadr = zdjecie
        if (edycjaKadru && bmpKadr != null && event.pointerCount == 1) {
            parent?.requestDisallowInterceptTouchEvent(true)
            val m = budujMacierzZdjecia(bmpKadr)
            val inv = Matrix()
            val maInv = m.invert(inv)
            fun doLokalnych(sx: Float, sy: Float): Punkt {
                val a = floatArrayOf(sx, sy)
                if (maInv) inv.mapPoints(a)
                return Punkt(a[0], a[1])
            }
            fun doEkranu(p: Punkt): Pair<Float, Float> {
                val a = floatArrayOf(p.xm, p.ym)
                m.mapPoints(a)
                return a[0] to a[1]
            }
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    kadrDrag = -1; var bestD = dp(20f)
                    for (i in kadrPunkty.indices) {
                        val (sx, sy) = doEkranu(kadrPunkty[i].dst)
                        val dd = hypot((event.x - sx).toDouble(), (event.y - sy).toDouble()).toFloat()
                        if (dd < bestD) { bestD = dd; kadrDrag = i }
                    }
                    if (kadrDrag < 0) {
                        val p = doLokalnych(event.x, event.y)
                        kadrPunkty.add(KadrPunkt(p, p)); invalidate()
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (kadrDrag in kadrPunkty.indices) { kadrPunkty[kadrDrag].dst = doLokalnych(event.x, event.y); invalidate() }
                }
                MotionEvent.ACTION_UP -> kadrDrag = -1
            }
            return true
        }
        skalaDet.onTouchEvent(event)
        if (skalaDet.isInProgress || event.pointerCount > 1) {
            parent?.requestDisallowInterceptTouchEvent(true)
            if (event.pointerCount >= 2) {
                val fx = (event.getX(0) + event.getX(1)) / 2f
                val fy = (event.getY(0) + event.getY(1)) / 2f
                if (event.actionMasked == MotionEvent.ACTION_MOVE && !skalaDet.isInProgress && ppm > 0f) {
                    centrBazaX -= (fx - lastFocalX) / ppm; centrBazaY += (fy - lastFocalY) / ppm; invalidate()
                }
                lastFocalX = fx; lastFocalY = fy
            }
            return true
        }
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                biezacy = sToM(event.x, event.y)
                if (sledzenie) biezacy?.let { centrBazaX = it.xm; centrBazaY = it.ym }
                raportuj(); invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val p = sToM(event.x, event.y)
                biezacy = p
                when (tryb) {
                    Tryb.KRAWEDZ_A -> { kA.add(p); przelicz() }
                    Tryb.KRAWEDZ_B -> { kB.add(p); przelicz() }
                    Tryb.OKRAG -> { kOkrag.add(p); przelicz() }
                    Tryb.KIERUNEK -> { kierunek = p; przelicz() }
                    Tryb.TRASOWANIE -> {}
                }
                invalidate()
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        zastosujZblizenieDoCelu()
        ppm = min(w, h) / spanMm
        cx = w / 2f; cy = h / 2f

        zdjecie?.let { bmp ->
            zdjMatrix.set(budujMacierzZdjecia(bmp))
            val ptsDst = kadrPunkty.map { p ->
                val a = floatArrayOf(p.dst.xm, p.dst.ym)
                zdjMatrix.mapPoints(a)
                a[0] to a[1]
            }

            if (ptsDst.size >= 3) {
                // wachlarz trójkątów (0, i, i+1): każdy mapuje SRC->DST osobno, dając miejscowe
                // rozciągnięcie/zniekształcenie obrazu wokół przeciągniętych punktów kadru.
                val warpMatrix = Matrix()
                for (i in 1 until kadrPunkty.size - 1) {
                    val src = floatArrayOf(
                        kadrPunkty[0].src.xm, kadrPunkty[0].src.ym,
                        kadrPunkty[i].src.xm, kadrPunkty[i].src.ym,
                        kadrPunkty[i + 1].src.xm, kadrPunkty[i + 1].src.ym
                    )
                    val dst = floatArrayOf(
                        kadrPunkty[0].dst.xm, kadrPunkty[0].dst.ym,
                        kadrPunkty[i].dst.xm, kadrPunkty[i].dst.ym,
                        kadrPunkty[i + 1].dst.xm, kadrPunkty[i + 1].dst.ym
                    )
                    if (!warpMatrix.setPolyToPoly(src, 0, dst, 0, 3)) continue
                    val triMatrix = Matrix(zdjMatrix)
                    triMatrix.preConcat(warpMatrix)

                    val (ax, ay) = ptsDst[0]; val (bx, by) = ptsDst[i]; val (cx3, cy3) = ptsDst[i + 1]
                    val path = Path().apply { moveTo(ax, ay); lineTo(bx, by); lineTo(cx3, cy3); close() }
                    canvas.save()
                    canvas.clipPath(path)
                    canvas.drawBitmap(bmp, triMatrix, pZdj)
                    canvas.restore()
                }
            } else {
                canvas.drawBitmap(bmp, zdjMatrix, pZdj)
            }

            if (edycjaZdjecia) {
                val (sx, sy) = mToS(zdjCx, zdjCy)
                canvas.drawCircle(sx, sy, dp(6f), pZdjSrodek)
            }
            if (edycjaKadru || kadrPunkty.isNotEmpty()) {
                for (i in ptsDst.indices) {
                    val (ax, ay) = ptsDst[i]
                    if (ptsDst.size >= 2) {
                        val (bx, by) = ptsDst[(i + 1) % ptsDst.size]
                        if (i < ptsDst.size - 1 || ptsDst.size >= 3) canvas.drawLine(ax, ay, bx, by, pKadr)
                    }
                    if (edycjaKadru || edycjaZdjecia) canvas.drawCircle(ax, ay, dp(6f), pKadrUchwyt)
                }
            }
        }

        val (ox0, oy0) = mToS(0f, 0f)                 // środek obrotu na ekranie
        val stepPx = 50f * ppm
        if (stepPx > 6f) {
            var gx = ox0 % stepPx; if (gx < 0) gx += stepPx
            while (gx < w) { canvas.drawLine(gx, 0f, gx, h, pGrid); gx += stepPx }
            var gy = oy0 % stepPx; if (gy < 0) gy += stepPx
            while (gy < h) { canvas.drawLine(0f, gy, w, gy, pGrid); gy += stepPx }
        }
        canvas.drawLine(0f, oy0, w, oy0, pAxis)
        canvas.drawLine(ox0, 0f, ox0, h, pAxis)
        canvas.drawCircle(ox0, oy0, dp(5f), pCenter)
        canvas.drawText("oś obrotu (0,0)", ox0 + dp(8f), oy0 - dp(6f), pText)

        // punkty i pomoce bazowania - ukryte w trybie trasowania, żeby nie mylić ich
        // z punktami pomiaru i punktami do wytrasowania
        val pokazBazowanie = tryb != Tryb.TRASOWANIE

        if (pokazBazowanie) {
            okrag?.let { o ->
                val (sx, sy) = mToS(o.cx, o.cy)
                canvas.drawCircle(sx, sy, o.r * ppm, pOkrag)
                canvas.drawCircle(sx, sy, dp(4f), pO)
            }
        }

        uklad?.let { u ->
            val len = 260f
            val a1 = mToS(u.ox - u.ux * len, u.oy - u.uy * len)
            val a2 = mToS(u.ox + u.ux * len, u.oy + u.uy * len)
            canvas.drawLine(a1.first, a1.second, a2.first, a2.second, pLineA)
            val b1 = mToS(u.ox - u.nx * len, u.oy - u.ny * len)
            val b2 = mToS(u.ox + u.nx * len, u.oy + u.ny * len)
            canvas.drawLine(b1.first, b1.second, b2.first, b2.second, pLineB)
            val ax = mToS(u.ox + u.ux * 60f, u.oy + u.uy * 60f)
            pAxisLbl.color = 0xFF1565C0.toInt(); canvas.drawText("+x", ax.first, ax.second, pAxisLbl)
            val by = mToS(u.ox + u.nx * 60f, u.oy + u.ny * 60f)
            pAxisLbl.color = 0xFF0F6E56.toInt(); canvas.drawText("+y", by.first, by.second, pAxisLbl)
            val (ox, oy) = mToS(u.ox, u.oy)
            canvas.drawCircle(ox, oy, dp(5f), pOrigin)
        }

        if (pokazBazowanie) {
            for (p in kA) { val (sx, sy) = mToS(p.xm, p.ym); canvas.drawCircle(sx, sy, dp(4f), pA) }
            for (p in kB) { val (sx, sy) = mToS(p.xm, p.ym); canvas.drawCircle(sx, sy, dp(4f), pB) }
            for (p in kOkrag) { val (sx, sy) = mToS(p.xm, p.ym); canvas.drawCircle(sx, sy, dp(4f), pO) }
            kierunek?.let { val (sx, sy) = mToS(it.xm, it.ym); canvas.drawCircle(sx, sy, dp(5f), pKier) }
        }

        for ((i, c) in cele.withIndex()) {
            val (mx, my) = celMachine(c.first, c.second)
            val (sx, sy) = mToS(mx, my)
            val paint = if (i < done.size && done[i]) pCelDone else pCel
            val aktywny = i == najblizszyIdx
            canvas.drawCircle(sx, sy, dp(4f), paint)
            if (aktywny) canvas.drawCircle(sx, sy, dp(8f), pCelActive)
            canvas.drawText("${i + 1}", sx + dp(5f), sy - dp(5f), pCelTxt)
        }

        biezacy?.let { p ->
            val (sx, sy) = mToS(p.xm, p.ym)
            canvas.drawLine(cx, cy, sx, sy, pRadius)
            canvas.drawCircle(sx, sy, dp(5.5f), pCur)
        }
    }

    private fun raportuj() {
        val us = Locale.US
        val p = biezacy

        val stage = when {
            okrag != null && kierunek != null -> "okrąg + kierunek"
            kA.size >= 2 && kB.size >= 2 -> "detal (A+B)"
            kA.size >= 2 -> "krawędź A (wstępny)"
            else -> "środek obrotu"
        }
        val info = "układ: $stage   |   A:${kA.size} B:${kB.size} O:${kOkrag.size}${if (kierunek != null) "+kier" else ""}"

        if (p == null) {
            onReadout?.invoke("X: –", "Y: –", "R: –", "kąt: –", "r = –   θ = –", info)
            najblizszyIdx = -1
            najblizszyDist = Float.MAX_VALUE
            onNaprowadzanie?.invoke(Float.MAX_VALUE, -1)
            onKolorOsi?.invoke(null, null)
            return
        }

        val re = hypot(p.xm, p.ym)
        var te = Math.toDegrees(atan2(p.ym, p.xm).toDouble()).toFloat()
        if (te < 0) te += 360f
        val enc = "r = %.1f mm   θ = %.2f°".format(us, re, te)

        val u = uklad
        val (x, y) = if (u != null) u.przelicz(p.xm, p.ym) else Pair(p.xm, p.ym)
        val rn = hypot(x, y)
        var tn = Math.toDegrees(atan2(y, x).toDouble()).toFloat()
        if (tn < 0) tn += 360f

        var best = -1; var bestD = Float.MAX_VALUE; var bestDx = 0f; var bestDy = 0f
        if (tryb == Tryb.TRASOWANIE && cele.isNotEmpty()) {
            for (i in cele.indices) {
                if (i < done.size && done[i]) continue
                val dx = cele[i].first - x; val dy = cele[i].second - y
                val dd = hypot(dx, dy)
                if (dd < bestD) { bestD = dd; best = i; bestDx = dx; bestDy = dy }
            }
        }
        najblizszyIdx = best
        najblizszyDist = if (best >= 0) bestD else Float.MAX_VALUE
        onNaprowadzanie?.invoke(najblizszyDist, best)
        onKolorOsi?.invoke(
            if (best >= 0) kolorOsi(bestDx) else null,
            if (best >= 0) kolorOsi(bestDy) else null
        )

        val doCelu = pokazPozostalosc && best >= 0
        val xStr = if (doCelu) "ΔX: %.2f".format(us, bestDx) else "X: %.2f".format(us, x)
        val yStr = if (doCelu) "ΔY: %.2f".format(us, bestDy) else "Y: %.2f".format(us, y)

        onReadout?.invoke(
            xStr, yStr,
            "R: %.2f".format(us, rn),
            "kąt: %.2f°".format(us, tn),
            enc, info
        )
    }
}