package com.txfast.app

import android.Manifest
import android.app.AlertDialog
import android.app.DownloadManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
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
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
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
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class MainActivity : ComponentActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var homeBox: LinearLayout
    private lateinit var sessionBox: LinearLayout
    private lateinit var resultBox: LinearLayout
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
    private val tempFiles: MutableList<File> = Collections.synchronizedList(ArrayList())

    @Volatile private var sessionId = 0
    private val progress = AtomicLong(0)
    @Volatile private var total = 0L
    @Volatile private var finished = false
    private var isSender = false
    private var lastBytes = 0L
    private var lastTime = 0L
    private var smooth = 0.0

    private class AppItem(
        val label: String,
        val pkg: String,
        val info: ApplicationInfo,
        val icon: Drawable,
        val size: Long
    )

    private val installAction get() = "$packageName.INSTALL_RESULT"

    private val installReceiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(c: Context, i: Intent) {
            when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val act = if (Build.VERSION.SDK_INT >= 33)
                        i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    act?.let {
                        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(it)
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> toast("✅ تم التثبيت")
                else -> toast("❌ فشل التثبيت: " + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
            }
        }
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res -> permCallback?.invoke(res.values.all { it }) }

    private val pickFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) startSend(uris.map { srcFromUri(it) }) }

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
            if (total > 0 && !finished) {
                bar.progress = (done * 1000 / total).toInt().coerceIn(0, 1000)
                if (done > 0) {
                    speedTv.text = "%s / %s   •   %.1f MB/s".format(fmt(minOf(done, total)), fmt(total), smooth)
                }
                if (isSender && done >= total) {
                    finished = true
                    bar.progress = 1000
                    qrView.visibility = View.GONE
                    statusTv.text = "✅ تم الإرسال"
                }
            }
            ui.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            cacheDir.listFiles()?.filter { it.name.endsWith(".apks") }?.forEach { it.delete() }
        } catch (_: Exception) {}
        ContextCompat.registerReceiver(
            this, installReceiver, IntentFilter(installAction), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        buildUi()
    }

    override fun onDestroy() {
        stopSession()
        try { unregisterReceiver(installReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun btn(text: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 18f
        setTextColor(Color.WHITE)
        setBackgroundColor(color)
        isAllCaps = false
        setPadding(dp(16), dp(16), dp(16), dp(16))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
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

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(40), dp(24), dp(24))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        homeBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        homeBox.addView(tv(30f, true).apply { text = "⚡ TX"; setTextColor(0xFF2563EB.toInt()) })
        homeBox.addView(tv(14f).apply { text = "نقل ملفات وتطبيقات فائق السرعة"; alpha = 0.7f })

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
        row.addView(circle("📤", "إرسال", 0xFF2563EB.toInt()) { chooseSend() })
        homeBox.addView(row)

        directSwitch = Switch(this).apply {
            text = "⚡ اتصال مباشر 5GHz (أسرع سرعة — بدون راوتر)"
            textSize = 15f
            isChecked = true
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(56) }
        }
        homeBox.addView(directSwitch)
        homeBox.addView(tv(13f).apply {
            text = "إرسال: اختر ملفات أو تطبيقات فيظهر QR، والجهاز التاني يضغط «استلام» ويمسحه.\nلو أطفأت الاتصال المباشر: الهاتفين لازم على نفس الواي فاي."
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
        resultBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sessionBox.addView(statusTv)
        sessionBox.addView(qrView)
        sessionBox.addView(bar)
        sessionBox.addView(speedTv)
        sessionBox.addView(resultBox)
        sessionBox.addView(btn("إيقاف / رجوع", 0xFFDC2626.toInt()) { stopSession() })

        root.addView(homeBox)
        root.addView(sessionBox)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun fmt(n: Long): String =
        if (n >= 1L shl 30) "%.2f GB".format(n / 1073741824.0)
        else "%.1f MB".format(n / 1048576.0)

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun runUi(b: () -> Unit) = ui.post(b)

    private fun openFiles() {
        try {
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            toast("الملفات المستلمة في: Download/TX")
        }
    }

    // ------------------------------------------------------------ sources

    private fun srcFromUri(u: Uri): Src {
        var name = u.lastPathSegment ?: "file"
        var size = -1L
        contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        if (size < 0) {
            size = try {
                contentResolver.openFileDescriptor(u, "r")?.use { it.statSize } ?: 0L
            } catch (_: Exception) { 0L }
        }
        val cr = contentResolver
        return Src(name, size) { cr.openFileDescriptor(u, "r") }
    }

    private fun chooseSend() {
        val items = arrayOf("📄  ملفات", "🖼  صور وفيديو", "📦  تطبيقات")
        AlertDialog.Builder(this).setTitle("إرسال").setItems(items) { _, i ->
            when (i) {
                0 -> pickFiles.launch(arrayOf("*/*"))
                1 -> pickFiles.launch(arrayOf("image/*", "video/*"))
                else -> showAppPicker()
            }
        }.show()
    }

    private fun apkSize(a: ApplicationInfo): Long {
        var n = File(a.sourceDir).length()
        a.splitSourceDirs?.forEach { n += File(it).length() }
        return n
    }

    private fun showAppPicker() {
        val dlg = AlertDialog.Builder(this).setMessage("جاري تحميل التطبيقات...").show()
        Thread {
            val pm = packageManager
            val apps = pm.getInstalledApplications(0)
                .filter {
                    (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                        (it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                }
                .filter { it.packageName != packageName }
                .map {
                    AppItem(pm.getApplicationLabel(it).toString(), it.packageName, it, pm.getApplicationIcon(it), apkSize(it))
                }
                .sortedBy { it.label.lowercase() }
            runUi { dlg.dismiss(); showAppDialog(apps) }
        }.start()
    }

    private fun showAppDialog(apps: List<AppItem>) {
        val picked = HashSet<String>()
        val act = this
        val lv = ListView(this)
        val adapter = object : BaseAdapter() {
            override fun getCount() = apps.size
            override fun getItem(position: Int) = apps[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val a = apps[position]
                val row = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutDirection = View.LAYOUT_DIRECTION_RTL
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                }
                val cb = CheckBox(act).apply {
                    isChecked = a.pkg in picked
                    isClickable = false
                    isFocusable = false
                }
                val ic = ImageView(act).apply {
                    setImageDrawable(a.icon)
                    layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                }
                val tx = TextView(act).apply {
                    text = a.label + "\n" + fmt(a.size)
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                    setPadding(dp(12), 0, dp(12), 0)
                }
                row.addView(cb)
                row.addView(ic)
                row.addView(tx)
                return row
            }
        }
        lv.adapter = adapter
        lv.setOnItemClickListener { _, _, p, _ ->
            val k = apps[p].pkg
            if (!picked.remove(k)) picked.add(k)
            adapter.notifyDataSetChanged()
        }
        AlertDialog.Builder(this)
            .setTitle("اختر التطبيقات")
            .setView(lv)
            .setPositiveButton("إرسال") { _, _ -> prepareApps(apps.filter { it.pkg in picked }) }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun prepareApps(sel: List<AppItem>) {
        if (sel.isEmpty()) {
            toast("اختر تطبيق واحد على الأقل")
            return
        }
        val dlg = AlertDialog.Builder(this).setMessage("جاري تجهيز التطبيقات...").setCancelable(false).show()
        Thread {
            try {
                val srcs = sel.map { buildAppSrc(it) }
                runUi { dlg.dismiss(); startSend(srcs) }
            } catch (e: Exception) {
                runUi { dlg.dismiss(); toast("فشل التجهيز: " + e.message) }
            }
        }.start()
    }

    /** تطبيق عادي: APK واحد. تطبيق Split: نجمع كل الأجزاء في ملف .apks (بدون ضغط للسرعة). */
    private fun buildAppSrc(a: AppItem): Src {
        val safe = a.label.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { a.pkg }
        val splits = a.info.splitSourceDirs
        if (splits == null || splits.isEmpty()) {
            val f = File(a.info.sourceDir)
            return Src("$safe.apk", f.length()) {
                ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
            }
        }
        val out = File(cacheDir, "$safe.apks")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(out), 1 shl 20)).use { z ->
            z.setLevel(Deflater.NO_COMPRESSION)
            (listOf(a.info.sourceDir) + splits).forEach { path ->
                val f = File(path)
                z.putNextEntry(ZipEntry(f.name))
                FileInputStream(f).use { it.copyTo(z, 1 shl 20) }
                z.closeEntry()
            }
        }
        tempFiles.add(out)
        return Src("$safe.apks", out.length()) {
            ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }

    // ------------------------------------------------------------ install

    private fun installFile(name: String, uri: Uri) {
        toast("جاري التثبيت...")
        Thread {
            try {
                val pi = packageManager.packageInstaller
                val id = pi.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
                pi.openSession(id).use { s ->
                    val isZip = name.endsWith(".apks", true) || name.endsWith(".xapk", true) || name.endsWith(".zip", true)
                    if (isZip) {
                        var n = 0
                        ZipInputStream(BufferedInputStream(contentResolver.openInputStream(uri)!!)).use { z ->
                            var e = z.nextEntry
                            while (e != null) {
                                if (e.name.endsWith(".apk", true)) {
                                    s.openWrite("p${n++}.apk", 0, -1).use { o ->
                                        z.copyTo(o, 1 shl 20)
                                        s.fsync(o)
                                    }
                                }
                                e = z.nextEntry
                            }
                        }
                    } else {
                        contentResolver.openInputStream(uri)!!.use { ins ->
                            s.openWrite("base.apk", 0, -1).use { o ->
                                ins.copyTo(o, 1 shl 20)
                                s.fsync(o)
                            }
                        }
                    }
                    val pend = PendingIntent.getBroadcast(
                        this, id, Intent(installAction).setPackage(packageName),
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    s.commit(pend.intentSender)
                }
            } catch (e: Exception) {
                runUi { toast("فشل التثبيت: " + e.message) }
            }
        }.start()
    }

    private fun showResults(files: List<Pair<String, Uri>>) {
        resultBox.removeAllViews()
        files.filter { it.first.endsWith(".apk", true) || it.first.endsWith(".apks", true) || it.first.endsWith(".xapk", true) }
            .forEach { (name, uri) ->
                resultBox.addView(btn("📦 تثبيت: $name", 0xFF16A34A.toInt()) { installFile(name, uri) })
            }
        resultBox.addView(btn("📁 فتح الملفات", 0xFF2563EB.toInt()) { openFiles() })
    }

    // ------------------------------------------------------------ session

    private fun beginSession(status: String, sending: Boolean): Int {
        stopSession(silent = true)
        sessionId++
        isSender = sending
        progress.set(0); total = 0; finished = false
        lastBytes = 0; smooth = 0.0; lastTime = System.nanoTime()
        homeBox.visibility = View.GONE
        sessionBox.visibility = View.VISIBLE
        qrView.visibility = View.GONE
        resultBox.removeAllViews()
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
            synchronized(tempFiles) { tempFiles.forEach { try { it.delete() } catch (_: Exception) {} }; tempFiles.clear() }
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

    private fun startSend(srcs: List<Src>) {
        val direct = directSwitch.isChecked
        beginSession(if (direct) "جاري تشغيل الهوتسبوت 5GHz..." else "جاري التحضير...", true)
        val my = sessionId
        if (!direct) {
            serve(srcs, null, emptyList(), my)
            return
        }
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
        else Manifest.permission.ACCESS_FINE_LOCATION
        val go = {
            val before = try { Net.localIps() } catch (_: Exception) { emptyList() }
            startHotspot({ hs -> serve(srcs, hs, before, my) }, { m -> if (my == sessionId) fail(m) })
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

        fun launch(withConfig: Boolean) {
            val cb = object : WifiManager.LocalOnlyHotspotCallback() {
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
                    if (withConfig) launch(false)
                    else onFail("فشل تشغيل الهوتسبوت (كود $reason). شغّل الموقع (GPS) وأطفئ الهوتسبوت العادي وجرّب تاني، أو أطفئ «اتصال مباشر».")
                }
            }
            try {
                if (withConfig) {
                    // أندرويد 13+: نطلب نطاق 5GHz صراحة لأسرع سرعة
                    val cfg = SoftApConfiguration.Builder()
                        .setSsid("TX-" + newToken().substring(0, 6))
                        .setPassphrase(newToken(), SoftApConfiguration.SECURITY_TYPE_WPA2_PSK)
                        .setBand(SoftApConfiguration.BAND_5GHZ)
                        .build()
                    wm.startLocalOnlyHotspot(cfg, ContextCompat.getMainExecutor(this), cb)
                } else {
                    wm.startLocalOnlyHotspot(cb, ui)
                }
            } catch (e: Exception) {
                if (withConfig) launch(false) else onFail("فشل تشغيل الهوتسبوت: " + e.message)
            }
        }
        launch(Build.VERSION.SDK_INT >= 33)
    }

    private fun newToken(): String {
        val b = ByteArray(8)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    private fun serve(srcs: List<Src>, hs: Hotspot?, before: List<String>, my: Int) {
        Thread {
            try {
                val token = newToken()
                val s = Sender(srcs, token)
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
                    statusTv.text = "خلّي الجهاز التاني يمسح الكود 👇\n(${srcs.size} ملف • ${fmt(total)})"
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
        beginSession("جاري الاتصال...", false)
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
                        showResults(r.saved)
                    }
                }
            } catch (e: Exception) {
                runUi { if (my == sessionId) fail(e.message ?: "فشل النقل") }
            }
        }.start()
    }
}
