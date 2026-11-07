package com.txfast.app

import android.Manifest
import android.app.DownloadManager
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong

class MainActivity : ComponentActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var homeBox: LinearLayout
    private lateinit var sessionBox: LinearLayout
    private lateinit var directSwitch: Switch
    private lateinit var statusTv: TextView
    private lateinit var speedTv: TextView
    private lateinit var bar: ProgressBar
    private lateinit var qrView: ImageView

    private var sender: Sender? = null
    private var receiver: Receiver? = null
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var permCallback: ((Boolean) -> Unit)? = null

    @Volatile private var sessionId = 0
    private val progress = AtomicLong(0)
    @Volatile private var total = 0L
    @Volatile private var finished = false
    private var lastBytes = 0L
    private var lastTime = 0L
    private var smooth = 0.0
    private var startTime = 0L

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res -> permCallback?.invoke(res.values.all { it }) }

    private val pickFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) startSend(uris) }

    private val scan = registerForActivityResult(ScanContract()) { r ->
        r.contents?.let { startReceive(it) }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (homeBox.visibility == View.VISIBLE) return
            val now = System.nanoTime()
            val done = progress.get()
            val dt = (now - lastTime) / 1e9
            if (dt > 0) {
                val inst = (done - lastBytes) / 1048576.0 / dt
                smooth = if (smooth == 0.0) inst else smooth * 0.6 + inst * 0.4
                lastBytes = done
                lastTime = now
            }
            if (total > 0) {
                bar.progress = (done * 1000 / total).toInt().coerceIn(0, 1000)
                if (done > 0) {
                    speedTv.text = "%s / %s   •   %.1f MB/s".format(fmt(done), fmt(total), smooth)
                }
            }
            ui.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onDestroy() {
        stopSession()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun btn(text: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 20f
        setTextColor(Color.WHITE)
        setBackgroundColor(color)
        isAllCaps = false
        setPadding(dp(16), dp(22), dp(16), dp(22))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }
        setOnClickListener { onClick() }
    }

    private fun tv(size: Float, bold: Boolean = false) = TextView(this).apply {
        textSize = size
        gravity = Gravity.CENTER
        if (bold) setTypeface(typeface, Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
    }

    private fun circle(icon: String, label: String, color: Int, onClick: () -> Unit): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setOnClickListener { onClick() }
        }
        val c = TextView(this).apply {
            text = icon
            textSize = 30f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
            layoutParams = LinearLayout.LayoutParams(dp(78), dp(78))
        }
        val l = TextView(this).apply {
            text = label
            textSize = 16f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) }
        }
        box.addView(c)
        box.addView(l)
        return box
    }

    private fun openFiles() {
        try {
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            toast("الملفات المستلمة في: Download/TX")
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(40), dp(24), dp(24))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        homeBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        homeBox.addView(tv(30f, true).apply { text = "⚡ TX"; setTextColor(0xFF2563EB.toInt()) })
        homeBox.addView(tv(14f).apply { text = "نقل ملفات فائق السرعة بين الهواتف"; alpha = 0.7f })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 3f
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(48) }
        }
        row.addView(circle("📁", "ملفات", 0xFF2563EB.toInt()) { openFiles() })
        row.addView(circle("📥", "استلام", 0xFF2563EB.toInt()) {
            scan.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setBeepEnabled(false)
                    .setOrientationLocked(false)
                    .setPrompt("وجّه الكاميرا على كود الجهاز المُرسل")
            )
        })
        row.addView(circle("📤", "إرسال", 0xFF2563EB.toInt()) { pickFiles.launch(arrayOf("*/*")) })
        homeBox.addView(row)

        directSwitch = Switch(this).apply {
            text = "اتصال مباشر (هوتسبوت خاص) — أسرع، بدون راوتر"
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(56) }
        }
        homeBox.addView(directSwitch)
        homeBox.addView(tv(13f).apply {
            text = "إرسال: اختر الملفات فيظهر كود QR، والجهاز التاني يضغط «استلام» ويمسحه.\nبدون الاتصال المباشر: الهاتفين على نفس الواي فاي أو هوتسبوت أحدهما."
            alpha = 0.7f
        })

        sessionBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        statusTv = tv(18f, true)
        qrView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(260), dp(260)).apply {
                gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(16)
            }
            visibility = View.GONE
        }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            layoutParams = LinearLayout.LayoutParams(-1, dp(12)).apply { topMargin = dp(24) }
        }
        speedTv = tv(16f)
        sessionBox.addView(statusTv)
        sessionBox.addView(qrView)
        sessionBox.addView(bar)
        sessionBox.addView(speedTv)
        sessionBox.addView(btn("إيقاف / رجوع", 0xFFDC2626.toInt()) { stopSession() })

        root.addView(homeBox)
        root.addView(sessionBox)
        setContentView(root)
    }

    private fun fmt(n: Long): String =
        if (n >= 1L shl 30) "%.2f GB".format(n / 1073741824.0)
        else "%.1f MB".format(n / 1048576.0)

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun runUi(b: () -> Unit) = ui.post(b)

    // ------------------------------------------------------------ session

    private fun beginSession(status: String): Int {
        stopSession(silent = true)
        sessionId++
        progress.set(0); total = 0; finished = false
        lastBytes = 0; smooth = 0.0; lastTime = System.nanoTime(); startTime = lastTime
        homeBox.visibility = View.GONE
        sessionBox.visibility = View.VISIBLE
        qrView.visibility = View.GONE
        bar.progress = 0
        speedTv.text = ""
        statusTv.text = status
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "tx").also { it.acquire() }
        } catch (_: Exception) {}
        ui.removeCallbacks(ticker)
        ui.postDelayed(ticker, 250)
        return sessionId
    }

    private fun stopSession(silent: Boolean = false) {
        sessionId++
        ui.removeCallbacks(ticker)
        try { sender?.stop() } catch (_: Exception) {}
        try { receiver?.cancel() } catch (_: Exception) {}
        sender = null; receiver = null
        try { reservation?.close() } catch (_: Exception) {}
        reservation = null
        val cm = getSystemService(ConnectivityManager::class.java)
        netCallback?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        netCallback = null
        try { cm.bindProcessToNetwork(null) } catch (_: Exception) {}
        try { wifiLock?.release() } catch (_: Exception) {}
        wifiLock = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!silent) {
            sessionBox.visibility = View.GONE
            homeBox.visibility = View.VISIBLE
        }
    }

    private fun fail(msg: String) {
        stopSession(silent = true)
        statusTv.text = "❌ $msg"
        qrView.visibility = View.GONE
    }

    // --------------------------------------------------------------- send

    private class Hotspot(val ssid: String, val pass: String, val sec: Int)

    private fun startSend(uris: List<Uri>) {
        val direct = directSwitch.isChecked
        beginSession(if (direct) "جاري تشغيل الهوتسبوت الخاص..." else "جاري التحضير...")
        val my = sessionId
        if (!direct) {
            serve(uris, null, emptyList(), my)
            return
        }
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
        else Manifest.permission.ACCESS_FINE_LOCATION
        val go = {
            val before = try { Net.localIps() } catch (_: Exception) { emptyList() }
            startHotspot({ hs -> serve(uris, hs, before, my) }, { m -> if (my == sessionId) fail(m) })
        }
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) go()
        else {
            permCallback = { ok -> if (ok) go() else fail("لازم تسمح بالإذن لتشغيل الاتصال المباشر") }
            permLauncher.launch(arrayOf(perm))
        }
    }

    @Suppress("DEPRECATION")
    private fun startHotspot(onReady: (Hotspot) -> Unit, onFail: (String) -> Unit) {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        try {
            wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = r
                    var ssid: String? = null
                    var pass: String? = null
                    var sec = 2
                    try {
                        if (Build.VERSION.SDK_INT >= 30) {
                            val c = r.softApConfiguration
                            ssid = c.ssid
                            pass = c.passphrase
                            if (c.securityType == SoftApConfiguration.SECURITY_TYPE_WPA3_SAE) sec = 3
                        } else {
                            val c = r.wifiConfiguration
                            ssid = c?.SSID?.trim('"')
                            pass = c?.preSharedKey?.trim('"')
                        }
                    } catch (_: Exception) {}
                    if (ssid.isNullOrEmpty() || pass.isNullOrEmpty()) onFail("مش قادر أقرا بيانات الهوتسبوت")
                    else onReady(Hotspot(ssid, pass, sec))
                }

                override fun onFailed(reason: Int) {
                    onFail("فشل تشغيل الهوتسبوت (كود $reason). شغّل الموقع (GPS) وأطفئ الهوتسبوت العادي وجرّب تاني، أو أطفئ «اتصال مباشر».")
                }
            }, ui)
        } catch (e: Exception) {
            onFail("فشل تشغيل الهوتسبوت: ${e.message}")
        }
    }

    private fun newToken(): String {
        val b = ByteArray(8)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    private fun serve(uris: List<Uri>, hs: Hotspot?, before: List<String>, my: Int) {
        Thread {
            try {
                val token = newToken()
                val s = Sender(contentResolver, uris, token)
                val port = s.start { n -> progress.addAndGet(n) }
                var ip: String? = null
                for (i in 0 until 20) {
                    val now = Net.localIps()
                    ip = if (hs != null) now.firstOrNull { it !in before } else now.firstOrNull()
                    if (ip != null) break
                    Thread.sleep(250)
                }
                if (ip == null && hs != null) ip = Net.localIps().firstOrNull()
                if (ip == null) {
                    s.stop()
                    throw IllegalStateException("مفيش شبكة واي فاي. شغّل الواي فاي أو الهوتسبوت وجرّب تاني.")
                }
                val payload = listOf("TX1", ip, port.toString(), token, hs?.ssid ?: "", hs?.pass ?: "", (hs?.sec ?: 0).toString())
                    .joinToString("\n")
                val bmp = qr(payload)
                runUi {
                    if (my != sessionId) {
                        s.stop(); return@runUi
                    }
                    sender = s
                    total = s.totalSize
                    qrView.setImageBitmap(bmp)
                    qrView.visibility = View.VISIBLE
                    statusTv.text = "خلّي الجهاز التاني يمسح الكود 👇\n(${uris.size} ملف • ${fmt(total)})"
                }
            } catch (e: Exception) {
                runUi { if (my == sessionId) fail(e.message ?: "خطأ") }
            }
        }.start()
    }

    private fun qr(text: String): Bitmap {
        val size = 700
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val px = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) px[y * size + x] = if (m[x, y]) Color.BLACK else Color.WHITE
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    // ------------------------------------------------------------ receive

    private fun startReceive(payload: String) {
        val p = payload.split("\n")
        val port = p.getOrNull(2)?.toIntOrNull()
        if (p.size < 7 || p[0] != "TX1" || port == null) {
            toast("الكود ده مش من تطبيق TX")
            return
        }
        val ip = p[1]; val token = p[3]; val ssid = p[4]; val pass = p[5]
        val sec = p[6].toIntOrNull() ?: 2
        beginSession("جاري الاتصال...")
        val my = sessionId
        if (ssid.isEmpty()) {
            runReceive(ip, port, token, my)
            return
        }
        statusTv.text = "جاري الاتصال بالهوتسبوت المباشر... وافق على الطلب اللي هيظهر"
        val cm = getSystemService(ConnectivityManager::class.java)
        val spec = WifiNetworkSpecifier.Builder().setSsid(ssid).also {
            if (sec == 3) it.setWpa3Passphrase(pass) else it.setWpa2Passphrase(pass)
        }.build()
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (my != sessionId) return
                cm.bindProcessToNetwork(network)
                runReceive(ip, port, token, my)
            }

            override fun onUnavailable() {
                runUi { if (my == sessionId) fail("فشل الاتصال بالهوتسبوت") }
            }

            override fun onLost(network: Network) {
                runUi { if (my == sessionId && !finished) fail("انقطع الاتصال بالهوتسبوت") }
            }
        }
        netCallback = cb
        cm.requestNetwork(req, cb, ui, 60_000)
    }

    private fun runReceive(ip: String, port: Int, token: String, my: Int) {
        Thread {
            try {
                val r = Receiver(contentResolver, ip, port, token)
                runUi { if (my == sessionId) receiver = r else r.cancel() }
                val infos = r.list()
                val t0 = System.nanoTime()
                total = infos.sumOf { it.size }
                runUi { if (my == sessionId) statusTv.text = "⬇ بيتحمّل ${infos.size} ملف..." }
                r.download(infos) { n -> progress.addAndGet(n) }
                val secs = (System.nanoTime() - t0) / 1e9
                val avg = total / 1048576.0 / secs.coerceAtLeast(0.001)
                finished = true
                runUi {
                    if (my == sessionId) {
                        bar.progress = 1000
                        statusTv.text = "✅ تم!\nالملفات في: Download/TX"
                        speedTv.text = "%s في %.1f ثانية • متوسط %.1f MB/s".format(fmt(total), secs, avg)
                        ui.removeCallbacks(ticker)
                    }
                }
            } catch (e: Exception) {
                runUi { if (my == sessionId) fail(e.message ?: "فشل النقل") }
            }
        }.start()
    }
}
