package com.liquid.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.telephony.SmsManager
import android.text.InputType
import android.util.Size
import android.view.*
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private val prefs by lazy { getSharedPreferences("s", MODE_PRIVATE) }
    private var pin: String
        get() = prefs.getString("pin", "1234")!!
        set(v) { prefs.edit().putString("pin", v).apply() }
    private var wp: Int
        get() = prefs.getInt("wp", 0)
        set(v) { prefs.edit().putInt("wp", v).apply() }
    private var unlocked = false
    private var web: WebView? = null
    private var pending: (() -> Unit)? = null
    private var always = false
    private var torchOn = false
    private fun cs(vararg s: String) = IntArray(s.size) { Color.parseColor(s[it]) }
    private val walls = listOf(cs("#FF6EC4", "#7F5CFF", "#4FACFE"), cs("#0F2027", "#203A43", "#2C5364"), cs("#F7971E", "#FF3B30", "#8E2DE2"), cs("#000000", "#1C1C1E"))
    private val CHAT = "https://chat-orbit-36.lovable.app"
    private val BROWSER = "https://custom-privacy-brows-0lyo.bolt.host"
    private val PH = ContactsContract.CommonDataKinds.Phone
    private val galP get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    @Suppress("DEPRECATION")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.decorView.systemUiVisibility = 1024 or 512 or 256
        root = FrameLayout(this)
        root.setOnApplyWindowInsetsListener { v, i -> v.setPadding(0, i.systemWindowInsetTop, 0, i.systemWindowInsetBottom); i }
        setContentView(root)
        applyWp()
        registerReceiver(object : BroadcastReceiver() { override fun onReceive(c: Context?, i: Intent?) { lock() } }, IntentFilter(Intent.ACTION_SCREEN_OFF))
        lock()
    }

    override fun onNewIntent(i: Intent?) { super.onNewIntent(i); if (unlocked) home() else lock() }
    override fun onBackPressed() { val w = web; if (w != null && w.canGoBack()) w.goBack() else if (unlocked) home() }

    // ---------- helpers ----------
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
    private fun applyWp() { root.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, walls[wp]) }
    private fun glass(r: Int = 20, a: Int = 60) = GradientDrawable().apply { setColor(Color.argb(a, 255, 255, 255)); cornerRadius = dp(r).toFloat(); setStroke(dp(1), Color.argb(110, 255, 255, 255)) }
    private fun bounce(v: View) {
        v.setOnTouchListener { x, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> x.animate().scaleX(.88f).scaleY(.88f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> x.animate().scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(3f)).setDuration(350).start()
            }
            false
        }
    }
    private fun tv(t: String, size: Float = 16f) = TextView(this).apply { text = t; textSize = size; setTextColor(Color.WHITE) }
    private fun btn(t: String, f: () -> Unit) = tv(t, 17f).apply { gravity = Gravity.CENTER; setPadding(dp(16), dp(14), dp(16), dp(14)); background = glass(); setOnClickListener { f() }; bounce(this) }
    private fun circ(t: String, size: Int, f: () -> Unit) = tv(t, 28f).apply { gravity = Gravity.CENTER; background = glass(size / 2); setOnClickListener { f() }; bounce(this) }
    private fun edit(h: String) = EditText(this).apply { hint = h; setSingleLine(true); setTextColor(Color.WHITE); setHintTextColor(Color.argb(150, 255, 255, 255)); background = glass(14); setPadding(dp(14), dp(12), dp(14), dp(12)) }
    private fun col() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
    private fun lp(m: Int = 6) = LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(m), dp(m), dp(m), dp(m)) }
    private fun card(t: String) = tv(t, 14f).apply { background = glass(14); setPadding(dp(14), dp(10), dp(14), dp(10)) }
    private fun show(v: View) { root.removeAllViews(); web = null; root.addView(v, FrameLayout.LayoutParams(-1, -1)) }
    private fun screen(title: String, body: View): View {
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val h = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        h.addView(btn("‹ Home") { home() }); h.addView(tv(title, 20f).apply { setPadding(dp(16), 0, 0, 0) })
        c.addView(h)
        c.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        return c
    }

    private fun need(perms: List<String>, retry: Boolean = false, then: () -> Unit) {
        val miss = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (miss.isEmpty()) then() else { pending = then; always = retry; requestPermissions(miss.toTypedArray(), 1) }
    }
    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        val ok = r.all { it == PackageManager.PERMISSION_GRANTED }
        val f = pending; pending = null
        if (!ok) toast("Permission denied. You can change it in Settings.")
        if (f != null && (ok || always)) f()
    }
    private fun call(n: String) = need(listOf(Manifest.permission.CALL_PHONE)) { startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(n)))) }

    // ---------- lock ----------
    private fun lock() {
        unlocked = false
        val v = FrameLayout(this)
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        c.addView(TextClock(this).apply { format12Hour = "EEEE, MMMM d"; format24Hour = "EEEE, MMMM d"; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(0, dp(70), 0, 0) })
        c.addView(TextClock(this).apply { format12Hour = "h:mm"; format24Hour = "H:mm"; textSize = 88f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); gravity = Gravity.CENTER })
        c.addView(tv("Tap to unlock", 14f).apply { gravity = Gravity.CENTER; setPadding(0, dp(40), 0, 0) })
        v.addView(c, FrameLayout.LayoutParams(-1, -1))
        v.addView(circ("🔦", 56) { torch() }, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(44), 0, 0, dp(44)) })
        v.addView(circ("📷", 56) { capture() }, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(44), dp(44)) })
        v.setOnClickListener { pinPad() }
        show(v)
    }
    private fun torch() {
        try { val m = getSystemService(CameraManager::class.java); torchOn = !torchOn; m.setTorchMode(m.cameraIdList[0], torchOn) } catch (e: Exception) { toast("Flashlight unavailable") }
    }
    private fun pinPad() {
        var e = ""
        val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val d = tv("○ ○ ○ ○", 28f).apply { gravity = Gravity.CENTER; setPadding(0, dp(20), 0, dp(20)) }
        fun upd() { d.text = (0..3).joinToString(" ") { if (it < e.length) "●" else "○" } }
        fun press(k: String) {
            if (k == "⌫") e = e.dropLast(1) else if (e.length < 4) e += k
            upd()
            if (e.length == 4) {
                if (e == pin) { unlocked = true; home() }
                else { d.animate().translationX(24f).setDuration(60).withEndAction { d.animate().translationX(0f).setInterpolator(OvershootInterpolator(4f)).setDuration(300).start() }.start(); e = ""; upd() }
            }
        }
        v.addView(tv("Enter Passcode", 20f).apply { gravity = Gravity.CENTER }); v.addView(d)
        val g = GridLayout(this).apply { columnCount = 3 }
        for (k in listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "⌫")) {
            val lpg = GridLayout.LayoutParams().apply { width = dp(76); height = dp(76); setMargins(dp(8), dp(8), dp(8), dp(8)) }
            g.addView(if (k == "") Space(this) else circ(k, 76) { press(k) }, lpg)
        }
        v.addView(g); v.addView(btn("Cancel") { lock() }, lp(20))
        show(v)
    }

    // ---------- home ----------
    private fun tile(label: String, icon: View, f: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(4), dp(10), dp(4), dp(10))
        addView(icon, LinearLayout.LayoutParams(dp(58), dp(58)))
        addView(tv(label, 11f).apply { gravity = Gravity.CENTER; maxLines = 1 })
        setOnClickListener { f() }; bounce(this)
    }
    private fun emo(e: String) = tv(e, 28f).apply { gravity = Gravity.CENTER; background = glass(15, 70) }
    private fun home() {
        unlocked = true
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(TextClock(this).apply { format12Hour = "h:mm  •  EEE, MMM d"; format24Hour = "H:mm  •  EEE, MMM d"; textSize = 22f; setTextColor(Color.WHITE); setPadding(dp(20), dp(12), 0, dp(4)) })
        val grid = GridLayout(this).apply { columnCount = 4 }
        val w = resources.displayMetrics.widthPixels / 4
        fun add(v: View) = grid.addView(v, GridLayout.LayoutParams().apply { width = w })
        val built = listOf<Triple<String, String, () -> Unit>>(
            Triple("Phone", "📞") { phone() }, Triple("Messages", "💬") { messages() }, Triple("Contacts", "👤") { contacts() },
            Triple("Camera", "📷") { capture() }, Triple("Photos", "🌸") { gallery() }, Triple("Calendar", "📅") { calendar() },
            Triple("Browser", "🧭") { web(BROWSER, false, "Browser") }, Triple("Chat", "🟢") { web(CHAT, false, "Chat") }, Triple("Settings", "⚙️") { settings() })
        built.forEach { add(tile(it.first, emo(it.second), it.third)) }
        val pm = packageManager
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
            .forEach { ri ->
                val pkg = ri.activityInfo.packageName
                add(tile(ri.loadLabel(pm).toString(), ImageView(this).apply { setImageDrawable(ri.loadIcon(pm)) }) { pm.getLaunchIntentForPackage(pkg)?.let { startActivity(it) } })
            }
        c.addView(ScrollView(this).apply { addView(grid) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val dock = LinearLayout(this).apply { background = glass(30); setPadding(dp(6), dp(6), dp(6), dp(6)) }
        val dl = { LinearLayout.LayoutParams(0, -2, 1f) }
        dock.addView(tile("Phone", emo("📞")) { phone() }, dl()); dock.addView(tile("Messages", emo("💬")) { messages() }, dl())
        dock.addView(tile("Browser", emo("🧭")) { web(BROWSER, false, "Browser") }, dl()); dock.addView(tile("Camera", emo("📷")) { capture() }, dl())
        c.addView(dock, lp(14))
        show(c)
    }

    // ---------- phone / contacts / sms ----------
    private fun phone() {
        var n = ""
        val d = tv("", 34f).apply { gravity = Gravity.CENTER; setPadding(0, dp(24), 0, dp(24)) }
        val b = col(); b.addView(d)
        val g = GridLayout(this).apply { columnCount = 3 }
        for (k in listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#"))
            g.addView(circ(k, 76) { n += k; d.text = n }, GridLayout.LayoutParams().apply { width = dp(76); height = dp(76); setMargins(dp(8), dp(8), dp(8), dp(8)) })
        b.addView(g); b.gravity = Gravity.CENTER_HORIZONTAL
        b.addView(btn("📞 Call") { if (n.isNotEmpty()) call(n) }, lp()); b.addView(btn("⌫ Delete") { n = n.dropLast(1); d.text = n }, lp())
        show(screen("Phone", b))
    }
    private fun contacts() = need(listOf(Manifest.permission.READ_CONTACTS)) {
        val b = col()
        contentResolver.query(PH.CONTENT_URI, arrayOf(PH.DISPLAY_NAME, PH.NUMBER), null, null, "${PH.DISPLAY_NAME} COLLATE NOCASE ASC")?.use { c ->
            var i = 0
            while (c.moveToNext() && i++ < 400) { val no = c.getString(1); b.addView(btn("${c.getString(0)}\n$no") { call(no) }, lp(4)) }
        }
        show(screen("Contacts", b))
    }
    private fun messages() = need(listOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS)) {
        val b = col()
        val to = edit("To (phone number)").apply { inputType = InputType.TYPE_CLASS_PHONE }
        val body = edit("Message")
        b.addView(to, lp()); b.addView(body, lp())
        b.addView(btn("Send SMS") {
            val t = to.text.toString(); val m = body.text.toString()
            if (t.isEmpty() || m.isEmpty()) toast("Enter a number and a message") else {
                val s = getSystemService(SmsManager::class.java); s.sendMultipartTextMessage(t, null, s.divideMessage(m), null, null); body.setText(""); toast("Sent")
            }
        }, lp())
        contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY), null, null, "date DESC")?.use { c ->
            var i = 0
            while (c.moveToNext() && i++ < 40) b.addView(card("${c.getString(0)}\n${c.getString(1)}"), lp(4))
        }
        show(screen("Messages", b))
    }

    // ---------- camera / gallery / calendar ----------
    private fun capture() = need(listOf(Manifest.permission.CAMERA)) {
        val cv = ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME, "IMG_${System.currentTimeMillis()}.jpg"); put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg"); put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera") }
        val u = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
        try { startActivity(Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, u).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) } catch (e: Exception) { toast("No camera app found") }
    }
    private fun gallery() = need(listOf(galP)) {
        val g = GridLayout(this).apply { columnCount = 3 }
        val w = resources.displayMetrics.widthPixels / 3
        val base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        contentResolver.query(base, arrayOf(MediaStore.Images.Media._ID), null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
            var i = 0
            while (c.moveToNext() && i++ < 60) {
                val u = ContentUris.withAppendedId(base, c.getLong(0))
                val iv = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    try { setImageBitmap(contentResolver.loadThumbnail(u, Size(300, 300), null)) } catch (e: Exception) {}
                    setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                }
                g.addView(iv, GridLayout.LayoutParams().apply { width = w - 2; height = w - 2; setMargins(1, 1, 1, 1) })
            }
        }
        val b = col(); b.addView(btn("📷 Take photo") { capture() }, lp()); b.addView(g)
        show(screen("Photos", b))
    }
    private fun calendar() = need(listOf(Manifest.permission.READ_CALENDAR)) {
        val b = col()
        b.addView(btn("Open Calendar app") { startActivity(Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI.buildUpon().appendPath("time").build())) }, lp())
        val E = CalendarContract.Events
        contentResolver.query(E.CONTENT_URI, arrayOf(E.TITLE, E.DTSTART), "${E.DTSTART}>=?", arrayOf(System.currentTimeMillis().toString()), "${E.DTSTART} ASC")?.use { c ->
            var i = 0
            while (c.moveToNext() && i++ < 30) b.addView(card("${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(c.getLong(1)))}\n${c.getString(0)}"), lp(4))
        }
        show(screen("Calendar", b))
    }

    // ---------- browser (Chromium-based Android System WebView) ----------
    @SuppressLint("SetJavaScriptEnabled")
    private fun web(start: String?, bar: Boolean, title: String = "Chat") {
        val w = WebView(this).apply {
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = WebViewClient()
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onPermissionRequest(r: android.webkit.PermissionRequest) {
                    val need = r.resources.map { if (it == android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE) Manifest.permission.CAMERA else Manifest.permission.RECORD_AUDIO }
                    need(need) { runOnUiThread { r.grant(r.resources) } }
                }
            }
        }
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val e = edit("Search or enter website").apply { imeOptions = EditorInfo.IME_ACTION_GO }
        fun go(t: String) {
            var u = t.trim(); if (u.isEmpty()) return
            if (!u.startsWith("http")) u = if (u.contains('.') && !u.contains(' ')) "https://$u" else "https://duckduckgo.com/?q=" + Uri.encode(u) + "&ia=chat"
            w.loadUrl(u)
        }
        e.setOnEditorActionListener { _, _, _ -> go(e.text.toString()); true }
        val h = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(8)) }
        h.addView(btn("‹") { home() })
        if (bar) h.addView(e, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(8), 0, 0, 0) }) else h.addView(tv(title, 20f).apply { setPadding(dp(16), 0, 0, 0) })
        c.addView(h); c.addView(w, LinearLayout.LayoutParams(-1, 0, 1f))
        show(c); web = w
        if (start != null) w.loadUrl(start) else w.loadUrl("https://duckduckgo.com/?ia=chat")
    }

    // ---------- settings ----------
    private fun settings() {
        val b = col()
        b.addView(tv("Wallpaper", 18f), lp())
        val r = LinearLayout(this)
        walls.forEachIndexed { i, colors ->
            r.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply { cornerRadius = dp(14).toFloat(); setStroke(dp(2), Color.WHITE) }; setOnClickListener { wp = i; applyWp() } },
                LinearLayout.LayoutParams(dp(56), dp(90)).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
        }
        b.addView(r)
        b.addView(tv("Passcode", 18f), lp())
        val old = edit("Current passcode").apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD }
        val nw = edit("New 4-digit passcode").apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD }
        b.addView(old, lp()); b.addView(nw, lp())
        b.addView(btn("Update passcode") {
            if (old.text.toString() != pin) toast("Wrong current passcode")
            else if (!Regex("\\d{4}").matches(nw.text)) toast("Use exactly 4 digits")
            else { pin = nw.text.toString(); old.setText(""); nw.setText(""); toast("Passcode changed") }
        }, lp())
        b.addView(tv("Permissions", 18f), lp())
        val groups = listOf("Phone" to listOf(Manifest.permission.CALL_PHONE), "Contacts" to listOf(Manifest.permission.READ_CONTACTS),
            "SMS" to listOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS), "Camera" to listOf(Manifest.permission.CAMERA),
            "Photos" to listOf(galP), "Calendar" to listOf(Manifest.permission.READ_CALENDAR))
        groups.forEach { (name, perms) ->
            val ok = perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
            b.addView(btn("$name  —  " + if (ok) "Allowed ✓" else "Tap to allow") { if (!ok) need(perms, true) { settings() } }, lp())
        }
        b.addView(btn("Allow everything") { need(groups.flatMap { it.second }, true) { settings() } }, lp())
        b.addView(tv("System", 18f), lp())
        b.addView(btn("Set as default home app") { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }, lp())
        b.addView(btn("App permission settings") { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }, lp())
        b.addView(btn("Android system settings") { startActivity(Intent(Settings.ACTION_SETTINGS)) }, lp())
        b.addView(btn("Lock now") { lock() }, lp())
        show(screen("Settings", b))
    }
}
