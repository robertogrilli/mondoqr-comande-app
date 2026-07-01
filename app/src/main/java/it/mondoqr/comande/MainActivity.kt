package it.mondoqr.comande

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.dantsu.escposprinter.EscPosPrinter
import com.dantsu.escposprinter.connection.tcp.TcpConnection

/**
 * App "Comande MondoQR".
 * - Mostra l'admin MondoQR a schermo intero (WebView), schermo sempre acceso.
 * - Espone un ponte JS→nativo: il web chiama window.AndroidPrint.printTcp(markup, ip, porta, larghezza)
 *   e la comanda esce DIRETTA sulla termica di rete (ESC/POS via DantSu) — niente Chrome, niente dialog, niente tap.
 */
class MainActivity : Activity() {

    private lateinit var web: WebView

    // Admin del chalet di test. In futuro configurabile.
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

        /** Il web usa questo per capire che gira DENTRO l'app nativa (e quindi stampare via ponte). */
        @JavascriptInterface
        fun available(): Boolean = true
    }
}
