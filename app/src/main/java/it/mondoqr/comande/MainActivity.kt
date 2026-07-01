package it.mondoqr.comande

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.dantsu.escposprinter.EscPosPrinter
import com.dantsu.escposprinter.connection.tcp.TcpConnection
import java.io.File
import java.io.FileOutputStream

/**
 * App "Comande MondoQR".
 * - WebView a schermo intero dell'admin MondoQR (schermo sempre acceso).
 * - Ponte JS->nativo:
 *     window.AndroidPrint.printTcp(markup, ip, porta, larghezza)  -> stampa ESC/POS diretta (zero tap)
 *     window.AndroidPrint.saveTestToGallery(testo)                -> salva la comanda di prova in Galleria (test senza stampante)
 *     window.AndroidPrint.appVersion()                            -> versione app (per il bottone "aggiorna")
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private val startUrl = "https://template.mondoqr.it/gestione?c=template"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
        }
        web.webViewClient = WebViewClient()
        web.webChromeClient = WebChromeClient()
        // Download (es. link "Aggiorna l'app" -> APK): apri nel browser di sistema.
        web.setDownloadListener { url, _, _, _, _ ->
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { }
        }
        web.addJavascriptInterface(PrintBridge(), "AndroidPrint")
        setContentView(web)
        web.loadUrl(startUrl)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    inner class PrintBridge {

        /** Stampa la comanda (markup DantSu) sulla termica di rete host:port. width = 58 o 80 (mm). */
        @JavascriptInterface
        fun printTcp(markup: String, host: String, port: Int, width: Int) {
            Thread {
                try {
                    val widthMM: Float
                    val chars: Int
                    if (width == 58) { widthMM = 48f; chars = 32 } else { widthMM = 72f; chars = 48 }
                    val printer = EscPosPrinter(TcpConnection(host, port, 15), 203, widthMM, chars)
                    printer.printFormattedTextAndCut(markup)
                    printer.disconnectPrinter()
                    runOnUiThread { toast("Comanda stampata") }
                } catch (e: Exception) {
                    runOnUiThread { toast("Stampa fallita: ${e.message}") }
                }
            }.start()
        }

        /** TEST senza stampante: rende la comanda (testo semplice) come immagine e la salva in Galleria. */
        @JavascriptInterface
        fun saveTestToGallery(text: String) {
            Thread {
                try {
                    saveBitmapToGallery(renderTextBitmap(text))
                    runOnUiThread { toast("Comanda di prova salvata in Galleria") }
                } catch (e: Exception) {
                    runOnUiThread { toast("Salvataggio fallito: ${e.message}") }
                }
            }.start()
        }

        /** Versione dell'app installata (per il confronto "aggiorna" lato web). */
        @JavascriptInterface
        fun appVersion(): String = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Exception) { "" }

        @JavascriptInterface
        fun available(): Boolean = true
    }

    // ── Rendering comanda -> bitmap (per il test in Galleria) ──
    private fun renderTextBitmap(text: String): Bitmap {
        val width = 576 // 80mm @ 203dpi
        val paint = Paint().apply {
            color = Color.BLACK
            textSize = 26f
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
        }
        val lines = text.split("\n")
        val lineHeight = paint.textSize * 1.35f
        val pad = 20f
        val height = (pad * 2 + lineHeight * lines.size).toInt().coerceAtLeast(120)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        var y = pad + paint.textSize
        for (line in lines) {
            canvas.drawText(line, pad, y, paint)
            y += lineHeight
        }
        return bmp
    }

    private fun saveBitmapToGallery(bmp: Bitmap) {
        val name = "comanda_prova_" + System.currentTimeMillis() + ".png"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ComandeMondoQR")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw RuntimeException("MediaStore null")
            contentResolver.openOutputStream(uri)?.use { os -> bmp.compress(Bitmap.CompressFormat.PNG, 100, os) }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "ComandeMondoQR")
            dir.mkdirs()
            FileOutputStream(File(dir, name)).use { os -> bmp.compress(Bitmap.CompressFormat.PNG, 100, os) }
        }
    }
}
