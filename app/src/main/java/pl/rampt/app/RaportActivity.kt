package pl.rampt.app

import android.content.Intent
import android.net.Uri
import android.text.SpannableStringBuilder
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.Environment
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import pl.rampt.app.databinding.ActivityRaportBinding
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RaportActivity : AppCompatActivity() {

    private lateinit var b: ActivityRaportBinding
    private val fotoFile by lazy { File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "raport_foto.jpg") }

    private val robZdjecie = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) przetworzFoto() else toast("Nie zrobiono zdjęcia")
    }
    private val wybierzZGalerii = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) try {
            contentResolver.openInputStream(uri)?.use { ins ->
                FileOutputStream(fotoFile).use { out -> ins.copyTo(out) }
            }
            przetworzFoto()
        } catch (e: Exception) { toast("Nie udało się wczytać zdjęcia: ${e.message}") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityRaportBinding.inflate(layoutInflater)
        setContentView(b.root)

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val us = Locale.US
        val data = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date())
        b.tvMeta.text = "Wygenerowano: $data"

        val p = DaneWalca.punkty
        val w = DaneWalca.wynik
        if (p.isEmpty() || w == null) {
            b.tvWyniki.text = "Brak danych pomiaru."
            b.polar.wyczysc()
        } else {
            b.polar.ustawDaneWyp(DaneWalca.pelne, DaneWalca.wypelnienie, w)
            rysujWykresBicia(b.chartBicie, p, w)
            var zebrane = 0; var wyp = 0
            for (dg in 0 until 360) if (!DaneWalca.pelne[dg].isNaN()) { zebrane++; if (DaneWalca.wypelnienie[dg]) wyp++ }
            fun mm(v: Float) = "%.3f mm".format(us, v)
            fun deg(v: Float) = "${"%.0f".format(us, v)}\u00B0"
            fun row(label: String, value: String) = "%-13s%s".format(us, label, value)
            val sb = SpannableStringBuilder()
            fun linia(s: CharSequence) { sb.append(s); sb.append("\n") }
            linia(row("R sredni:", mm(w.promienSredni)))
            linia(row("R max:", "${mm(w.promienMax)}  @ ${deg(w.katMax)}"))
            linia(row("R min:", "${mm(w.promienMin)}  @ ${deg(w.katMin)}"))
            linia(wierszZOcena("$SYM_BICIE Bicie:", w.bicie, DaneWalca.tolerancjaBicia))
            linia(wierszZOcena("$SYM_WALCOWOSC Walcowosc:", w.okraglosc, DaneWalca.tolerancjaWalcowosc))
            linia(wierszOwalnosciNorma(w))
            linia(row("Mimosrod:", "${mm(w.ekscentrycznosc)}  @ ${deg(w.kierunekPrzesuniecia)}"))
            linia(row("Owalizacja:", "${mm(w.owalizacja)}  (os ${deg(w.osOwalu)})"))
            linia(row("Punktow:", "$zebrane/360  (wypelnione: $wyp)"))
            if (DanePrzelamania.pomiary.isNotEmpty()) linia(wierszPrzelamania())
            b.tvWyniki.text = sb
        }

        if (fotoFile.exists()) { b.imgFoto.setImageBitmap(BitmapFactory.decodeFile(fotoFile.absolutePath)) }

        b.btnFoto.setOnClickListener {
            try {
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", fotoFile)
                robZdjecie.launch(uri)
            } catch (e: Exception) { toast("Brak aplikacji aparatu: ${e.message}") }
        }
        b.btnGaleria.setOnClickListener { wybierzZGalerii.launch("image/*") }
        b.btnPdf.setOnClickListener { eksportPdf() }
    }

    /** Zmniejsza, obraca wg EXIF i wypala datę+godzinę na zdjęciu. */
    private fun przetworzFoto() {
        try {
            val gran = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(fotoFile.absolutePath, gran)
            var s = 1
            while (gran.outWidth / s > 1400) s *= 2
            var bmp = BitmapFactory.decodeFile(fotoFile.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = s }) ?: return

            val rot = when (ExifInterface(fotoFile.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (rot != 0f) {
                val m = Matrix(); m.postRotate(rot)
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }

            val out = bmp.copy(Bitmap.Config.ARGB_8888, true)
            val c = Canvas(out)
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
            val h = out.height * 0.05f
            val pad = h * 0.35f
            val tło = Paint().apply { color = 0xB0000000.toInt() }
            val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = h * 0.8f }
            val tw = txt.measureText(ts)
            c.drawRect(0f, out.height - h, tw + 2 * pad, out.height.toFloat(), tło)
            c.drawText(ts, pad, out.height - pad, txt)

            FileOutputStream(fotoFile).use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            b.imgFoto.setImageBitmap(out)
            b.tvFotoData.text = "Zdjęcie: $ts"
        } catch (e: Exception) { toast("Błąd zdjęcia: ${e.message}") }
    }

    /** Rysuje kartę „papier" do PDF i udostępnia. */
    private fun eksportPdf() {
        val view = b.paper
        if (view.width == 0 || view.height == 0) { toast("Poczekaj, aż raport się wyświetli"); return }
        try {
            val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp); canvas.drawColor(Color.WHITE); view.draw(canvas)

            val pdf = PdfDocument()
            val info = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, 1).create()
            val page = pdf.startPage(info)
            page.canvas.drawBitmap(bmp, 0f, 0f, null)
            pdf.finishPage(page)

            val plik = File(cacheDir, "raport_zks.pdf")
            FileOutputStream(plik).use { pdf.writeTo(it) }
            pdf.close(); bmp.recycle()

            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", plik)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "Udostępnij raport PDF"))
        } catch (e: Exception) { toast("Błąd PDF: ${e.message}") }
    }

    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show()
}