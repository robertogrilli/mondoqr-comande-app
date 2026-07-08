package it.mondoqr.comande

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.InputType
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast
import com.dantsu.escposprinter.EscPosPrinter
import com.dantsu.escposprinter.connection.DeviceConnection
import com.dantsu.escposprinter.connection.bluetooth.BluetoothPrintersConnections
import com.dantsu.escposprinter.connection.tcp.TcpConnection
import com.dantsu.escposprinter.connection.usb.UsbConnection
import com.dantsu.escposprinter.connection.usb.UsbPrintersConnections
import java.io.File
import java.io.FileOutputStream

/**
 * App "Comande MondoQR".
 * - WebView a schermo intero dell'admin MondoQR (schermo sempre acceso).
 * - v0.7: MAI più schermata bianca muta — ogni fallimento (rete, server, SSL,
 *   WebView troppo vecchia, crash del renderer) mostra una schermata di diagnosi
 *   con la causa vera e i tasti per risolvere (Riprova / Aggiorna WebView / Browser).
 *   Lo slug del locale è configurabile (dialog al primo avvio, salvato in prefs).
 * - Ponte JS->nativo (stampa ESC/POS diretta, zero tap):
 *     window.AndroidPrint.printTcp(markup, ip, porta, larghezza)  -> stampa su termica di RETE
 *     window.AndroidPrint.printBt(markup, larghezza)              -> stampa su termica BLUETOOTH accoppiata
 *     window.AndroidPrint.printUsb(markup, larghezza)             -> stampa su termica USB collegata
 *     window.AndroidPrint.saveTestToGallery(testo)                -> test senza stampante (salva in Galleria)
 *     window.AndroidPrint.appVersion()                            -> versione app (per il tasto "aggiorna")
 *     window.AndroidPrint.changeVenue()                           -> dialog per cambiare locale (slug)
 */
class MainActivity : Activity() {

    private var web: WebView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("comande", Context.MODE_PRIVATE) }

    // Diagnosi schermo-bianco: errori console raccolti + tentativi di verifica mount React.
    private val consoleErrors = ArrayDeque<String>()
    private var mountAttempts = 0
    private var diagVisible = false
    private val diagBase = "https://diag.mondoqr.local/"

    private fun slug(): String = prefs.getString("slug", null) ?: "template"
    private fun startUrl(): String { val s = slug(); return "https://$s.mondoqr.it/gestione?c=$s" }

    private val actionUsbPermission = "it.mondoqr.comande.USB_PERMISSION"
    private var pendingUsbMarkup: String? = null
    private var pendingUsbWidth: Int = 80

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (actionUsbPermission == intent.action) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                val usbManager = getSystemService(Context.USB_SERVICE) as? UsbManager
                @Suppress("DEPRECATION")
                val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                val markup = pendingUsbMarkup
                pendingUsbMarkup = null
                if (granted && usbManager != null && device != null && markup != null) {
                    doPrint(UsbConnection(usbManager, device), markup, pendingUsbWidth)
                } else {
                    runOnUiThread { toast("USB: permesso negato o stampante assente") }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, IntentFilter(actionUsbPermission), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbReceiver, IntentFilter(actionUsbPermission))
        }

        // Servizio in primo piano: tiene viva la stampa con app in background / schermo spento.
        val svc = Intent(this, PrintKeepAliveService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2001)
        }

        val w = buildWebView()
        web = w
        setContentView(w)
        if (prefs.contains("slug")) w.loadUrl(startUrl()) else askSlug(first = true)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(): WebView {
        val w = WebView(this)
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
        }
        w.webViewClient = object : WebViewClient() {

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (url != null && !url.startsWith(diagBase)) {
                    handler.removeCallbacksAndMessages(null)
                    mountAttempts = 0
                    diagVisible = false
                    consoleErrors.clear()
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (url != null && url.contains("/gestione") && !url.startsWith(diagBase) && !diagVisible) {
                    handler.postDelayed({ checkMounted() }, 7000)
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    showDiag(
                        "Pagina non raggiungibile",
                        "${error.description} (codice ${error.errorCode}). Controlla che il dispositivo sia connesso a Internet (Wi-Fi o dati), poi tocca Riprova."
                    )
                }
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (request.isForMainFrame) {
                    showDiag(
                        "Errore dal server",
                        "Il server ha risposto ${errorResponse.statusCode}. Di solito è temporaneo: aspetta qualche secondo e tocca Riprova."
                    )
                }
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(view: WebView?, sslHandler: SslErrorHandler, error: SslError) {
                sslHandler.cancel()
                showDiag(
                    "Connessione non sicura (SSL)",
                    "Il certificato del sito non risulta valido su questo dispositivo (errore ${error.primaryError}). Causa tipica: DATA o ORA sbagliate — controlla Impostazioni → Data e ora (metti automatiche), poi tocca Riprova."
                )
            }

            // Il motore della WebView è crashato: senza questo handler l'app resta su uno schermo bianco morto.
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                try { (view.parent as? ViewGroup)?.removeView(view) } catch (_: Exception) { }
                try { view.destroy() } catch (_: Exception) { }
                handler.removeCallbacksAndMessages(null)
                val fresh = buildWebView()
                web = fresh
                setContentView(fresh)
                fresh.loadUrl(startUrl())
                toast("Il motore web si è riavviato")
                return true
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                if (msg.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    val src = msg.sourceId()?.substringAfterLast('/') ?: ""
                    consoleErrors.addLast("$src:${msg.lineNumber()} ${msg.message()}".take(300))
                    while (consoleErrors.size > 12) consoleErrors.removeFirst()
                }
                return super.onConsoleMessage(msg)
            }
        }
        // Download (es. link "Aggiorna l'app" -> APK): apri nel browser di sistema.
        w.setDownloadListener { url, _, _, _, _ ->
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { }
        }
        w.addJavascriptInterface(PrintBridge(), "AndroidPrint")
        w.addJavascriptInterface(DiagBridge(), "AndroidDiag")
        return w
    }

    // ── Watchdog schermo-bianco: la pagina è arrivata (HTTP ok) ma React non è mai partito? ──
    private fun checkMounted() {
        val w = web ?: return
        if (diagVisible) return
        w.evaluateJavascript(
            "(function(){try{var r=document.getElementById('root');if(r&&r.children.length>0)return 'ok';return 'empty:'+document.readyState}catch(e){return 'err:'+e}})()"
        ) { res ->
            if (res != null && res.contains("ok")) return@evaluateJavascript
            mountAttempts++
            if (mountAttempts < 3) {
                handler.postDelayed({ checkMounted() }, 4000)
            } else {
                showDiag(
                    "Il gestionale non si è avviato (schermata bianca)",
                    "La pagina è stata scaricata ma il codice non è partito. Quasi sempre la causa è la WebView di sistema troppo vecchia: tocca «Aggiorna WebView» qui sotto (si apre il Play Store), aggiorna, poi torna qui e tocca Riprova."
                )
            }
        }
    }

    // ── Schermata di diagnosi: HTML locale semplicissimo (renderizza anche su motori vecchi) ──
    private fun showDiag(title: String, advice: String) {
        diagVisible = true
        handler.removeCallbacksAndMessages(null)
        val html = buildDiagHtml(title, advice)
        runOnUiThread { web?.loadDataWithBaseURL(diagBase, html, "text/html", "utf-8", null) }
    }

    private fun wvPackageName(): String =
        (if (Build.VERSION.SDK_INT >= 26) WebView.getCurrentWebViewPackage()?.packageName else null)
            ?: "com.google.android.webview"

    private fun buildDiagHtml(title: String, advice: String): String {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val wvPkg = if (Build.VERSION.SDK_INT >= 26) WebView.getCurrentWebViewPackage() else null
        val wvVer = wvPkg?.versionName ?: "sconosciuta"
        val wvMajor = wvVer.split(".").firstOrNull()?.toIntOrNull() ?: 0
        val appVer = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (_: Exception) { "?" }
        val oldWvBox = if (wvMajor in 1..110) {
            "<div class='warn'>⚠️ WebView versione <b>${esc(wvVer)}</b>: troppo vecchia per il gestionale (serve almeno la 111). <b>Aggiornala dal Play Store</b> col tasto qui sotto.</div>"
        } else ""
        val errs = if (consoleErrors.isEmpty()) "" else
            "<p class='k'>Errori tecnici (per l'assistenza):</p><pre>" + consoleErrors.joinToString("\n") { esc(it) } + "</pre>"
        return """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
body{margin:0;background:#111;color:#eee;font-family:sans-serif;padding:20px}
h1{font-size:22px;margin:8px 0 12px}
p{font-size:16px;line-height:1.5;color:#ccc}
.warn{background:#3a2a00;border:1px solid #b58a00;color:#ffd76a;border-radius:10px;padding:12px;margin:14px 0;font-size:15px;line-height:1.5}
button{display:block;width:100%;box-sizing:border-box;margin:10px 0;padding:16px;font-size:17px;font-weight:bold;border:0;border-radius:12px;background:#fff;color:#111}
button.sec{background:#2a2a2a;color:#eee;border:1px solid #444}
.k{color:#888;font-size:13px;margin-top:22px}
pre{background:#000;color:#f88;font-size:11px;padding:10px;border-radius:8px;white-space:pre-wrap;word-break:break-all}
.info{color:#666;font-size:12px;margin-top:16px}
</style></head><body>
<h1>${esc(title)}</h1>
<p>${esc(advice)}</p>
$oldWvBox
<button onclick="AndroidDiag.retry()">🔄 Riprova</button>
<button class="sec" onclick="AndroidDiag.updateWebView()">⬆️ Aggiorna WebView (Play Store)</button>
<button class="sec" onclick="AndroidDiag.openBrowser()">🌐 Apri nel browser</button>
<button class="sec" onclick="AndroidDiag.changeVenue()">🏖 Cambia locale</button>
$errs
<p class="info">App v${esc(appVer)} · Android ${esc(Build.VERSION.RELEASE ?: "?")} · WebView ${esc(wvVer)}<br>Locale: ${esc(slug())} · ${esc(startUrl())}</p>
</body></html>"""
    }

    inner class DiagBridge {
        @JavascriptInterface
        fun retry() {
            runOnUiThread { diagVisible = false; web?.loadUrl(startUrl()) }
        }

        @JavascriptInterface
        fun updateWebView() {
            runOnUiThread {
                val pkg = wvPackageName()
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")))
                } catch (_: Exception) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")))
                    } catch (_: Exception) {
                        toast("Play Store non disponibile su questo dispositivo")
                    }
                }
            }
        }

        @JavascriptInterface
        fun openBrowser() {
            runOnUiThread {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(startUrl()))) } catch (_: Exception) { toast("Nessun browser trovato") }
            }
        }

        @JavascriptInterface
        fun changeVenue() {
            runOnUiThread { askSlug(first = false) }
        }
    }

    /** Dialog per scegliere il locale: lo slug è il nome nell'indirizzo <slug>.mondoqr.it. */
    private fun askSlug(first: Boolean) {
        val input = EditText(this).apply {
            hint = "es. template"
            setText(slug())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        AlertDialog.Builder(this)
            .setTitle("Locale MondoQR")
            .setMessage("Scrivi il nome del locale come appare nel suo indirizzo (<nome>.mondoqr.it):")
            .setView(input)
            .setCancelable(!first)
            .setPositiveButton("Apri") { _, _ ->
                val s = input.text.toString().trim().lowercase().replace(Regex("[^a-z0-9-]"), "")
                if (s.isNotEmpty()) prefs.edit().putString("slug", s).apply()
                diagVisible = false
                web?.loadUrl(startUrl())
            }
            .show()
    }

    override fun onDestroy() {
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) { }
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val w = web
        if (w != null && w.canGoBack()) w.goBack() else super.onBackPressed()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dims(width: Int): Pair<Float, Int> = if (width == 58) Pair(48f, 32) else Pair(72f, 48)

    /** Stampa comune: crea la stampante sulla connessione data (su thread) e stampa+taglia. */
    private fun doPrint(connection: DeviceConnection, markup: String, width: Int) {
        Thread {
            try {
                val (mm, ch) = dims(width)
                val printer = EscPosPrinter(connection, 203, mm, ch)
                printer.printFormattedTextAndCut(markup)
                printer.disconnectPrinter()
                runOnUiThread { toast("Comanda stampata") }
            } catch (e: Exception) {
                runOnUiThread { toast("Stampa fallita: ${e.message}") }
            }
        }.start()
    }

    inner class PrintBridge {

        /** RETE: termica su IP:porta. */
        @JavascriptInterface
        fun printTcp(markup: String, host: String, port: Int, width: Int) {
            doPrint(TcpConnection(host, port, 15), markup, width)
        }

        /** BLUETOOTH: prima termica accoppiata (accoppiala nelle impostazioni Bluetooth di Android). */
        @JavascriptInterface
        fun printBt(markup: String, width: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                runOnUiThread {
                    toast("Concedi il permesso Bluetooth e riprova")
                    requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1001)
                }
                return
            }
            Thread {
                try {
                    val conn = BluetoothPrintersConnections.selectFirstPaired()
                    if (conn == null) { runOnUiThread { toast("Nessuna termica Bluetooth accoppiata") }; return@Thread }
                    val (mm, ch) = dims(width)
                    val printer = EscPosPrinter(conn, 203, mm, ch)
                    printer.printFormattedTextAndCut(markup)
                    printer.disconnectPrinter()
                    runOnUiThread { toast("Comanda stampata") }
                } catch (e: Exception) {
                    runOnUiThread { toast("Stampa BT fallita: ${e.message}") }
                }
            }.start()
        }

        /** USB: prima termica collegata (chiede il permesso USB, poi stampa). */
        @JavascriptInterface
        fun printUsb(markup: String, width: Int) {
            runOnUiThread {
                try {
                    val usbManager = getSystemService(Context.USB_SERVICE) as? UsbManager
                    val usbConn = UsbPrintersConnections.selectFirstConnected(this@MainActivity)
                    if (usbManager == null || usbConn == null) { toast("Nessuna termica USB collegata"); return@runOnUiThread }
                    pendingUsbMarkup = markup
                    pendingUsbWidth = width
                    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                    val pi = PendingIntent.getBroadcast(
                        this@MainActivity, 0,
                        Intent(actionUsbPermission).setPackage(packageName), flags
                    )
                    usbManager.requestPermission(usbConn.device, pi)
                } catch (e: Exception) {
                    toast("USB errore: ${e.message}")
                }
            }
        }

        /** TEST senza stampante: rende la comanda (stesso markup della stampa) come immagine, la salva in Galleria e la apre. */
        @JavascriptInterface
        fun saveTestToGallery(markup: String) {
            Thread {
                try {
                    val uri = saveBitmapToGallery(renderMarkupBitmap(markup))
                    runOnUiThread {
                        toast("Comanda di prova salvata in Galleria")
                        if (uri != null) {
                            try {
                                startActivity(Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, "image/*")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                })
                            } catch (_: Exception) { }
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread { toast("Salvataggio fallito: ${e.message}") }
                }
            }.start()
        }

        @JavascriptInterface
        fun appVersion(): String = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Exception) { "" }

        /** Apre le impostazioni Bluetooth di Android (per accoppiare la termica in un tap). */
        @JavascriptInterface
        fun openBluetoothSettings() {
            runOnUiThread {
                try { startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) } catch (_: Exception) { }
            }
        }

        /** Cambia locale (slug) — richiamabile anche dall'admin. */
        @JavascriptInterface
        fun changeVenue() {
            runOnUiThread { askSlug(first = false) }
        }

        @JavascriptInterface
        fun available(): Boolean = true
    }

    // ── Rendering markup DantSu -> bitmap (anteprima fedele: allineamento, grassetto, dimensione, colonna prezzi) ──
    private data class MLine(val left: String, val right: String, val align: Char, val scale: Float, val bold: Boolean)

    private fun parseMarkup(markup: String): List<MLine> {
        val alignRe = Regex("^\\[(L|C|R)\\]")
        val tagRe = Regex("<[^>]*>")
        val colRe = Regex("\\[(L|C|R)\\]")
        return markup.split("\n").map { raw ->
            var s = raw
            var align = 'L'
            alignRe.find(s)?.let { align = it.groupValues[1][0]; s = s.substring(it.value.length) }
            val scale = when {
                s.contains("size='big-2'") -> 2.6f
                s.contains("size='big'") -> 1.9f
                else -> 1f
            }
            val bold = s.contains("<b>")
            var left = s
            var right = ""
            val ri = s.indexOf("[R]")
            if (ri >= 0) { left = s.substring(0, ri); right = s.substring(ri + 3) }
            fun strip(t: String) = t.replace(tagRe, "").replace(colRe, "").trim()
            MLine(strip(left), strip(right), align, scale, bold)
        }
    }

    private fun renderMarkupBitmap(markup: String): Bitmap {
        val width = 576 // 80mm @ 203dpi
        val base = 26f
        val pad = 20f
        val lines = parseMarkup(markup)
        val paint = Paint().apply { color = Color.BLACK; typeface = Typeface.MONOSPACE; isAntiAlias = true }
        var h = pad * 2
        for (ln in lines) h += base * ln.scale * 1.35f
        val bmp = Bitmap.createBitmap(width, h.toInt().coerceAtLeast(120), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        var y = pad
        for (ln in lines) {
            paint.textSize = base * ln.scale
            paint.typeface = if (ln.bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
            y += paint.textSize
            val tw = paint.measureText(ln.left)
            val x = when (ln.align) {
                'C' -> (width - tw) / 2f
                'R' -> width - pad - tw
                else -> pad
            }
            canvas.drawText(ln.left, x, y, paint)
            if (ln.right.isNotEmpty()) {
                val rw = paint.measureText(ln.right)
                canvas.drawText(ln.right, width - pad - rw, y, paint)
            }
            y += paint.textSize * 0.35f
        }
        return bmp
    }

    private fun saveBitmapToGallery(bmp: Bitmap): Uri? {
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
            return uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "ComandeMondoQR")
            dir.mkdirs()
            FileOutputStream(File(dir, name)).use { os -> bmp.compress(Bitmap.CompressFormat.PNG, 100, os) }
            return null
        }
    }
}
