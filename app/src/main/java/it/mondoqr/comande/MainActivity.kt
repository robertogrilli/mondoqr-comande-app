package it.mondoqr.comande

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
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
 * - Ponte JS->nativo (stampa ESC/POS diretta, zero tap):
 *     window.AndroidPrint.printTcp(markup, ip, porta, larghezza)  -> stampa su termica di RETE
 *     window.AndroidPrint.printBt(markup, larghezza)              -> stampa su termica BLUETOOTH accoppiata
 *     window.AndroidPrint.printUsb(markup, larghezza)             -> stampa su termica USB collegata
 *     window.AndroidPrint.saveTestToGallery(testo)                -> test senza stampante (salva in Galleria)
 *     window.AndroidPrint.appVersion()                            -> versione app (per il tasto "aggiorna")
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private val startUrl = "https://template.mondoqr.it/gestione?c=template"

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

    @SuppressLint("SetJavaScriptEnabled")
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

    override fun onDestroy() {
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) { }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
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
