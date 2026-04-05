package com.shellbox

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.shellbox.service.ShellBoxService
import com.shellbox.shell.ProotBootstrap
import com.shellbox.shell.RishExecutor
import com.shellbox.terminal.RishTerminalSession
import com.shellbox.terminal.ShellDiscovery
import com.shellbox.terminal.backend.TerminalSession
import com.shellbox.terminal.view.TerminalView
import com.shellbox.terminal.view.TerminalViewClient

private const val TAG = "ShellBox"

class MainActivity : AppCompatActivity(), TerminalViewClient {

    private lateinit var terminalView: TerminalView
    private lateinit var tabBar: LinearLayout
    private var currentTextSize = 14

    data class Tab(val id: Int, val shellId: String, val title: String, val session: TerminalSession)

    private val tabs = mutableListOf<Tab>()
    private var activeTabId = -1
    private var tabCounter = 0

    private val sessionCallback = object : TerminalSession.SessionChangedCallback {
        override fun onTextChanged(session: TerminalSession) { terminalView.onScreenUpdated() }
        override fun onTitleChanged(session: TerminalSession) { runOnUiThread { updateTabBar() } }
        override fun onSessionFinished(session: TerminalSession) { removeTabBySession(session) }
        override fun onClipboardText(session: TerminalSession, text: String) {
            val clip = getSystemService(android.content.ClipboardManager::class.java)
            clip.setPrimaryClip(android.content.ClipData.newPlainText("ShellBox", text))
        }
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        terminalView = findViewById(R.id.terminalView)
        tabBar = findViewById(R.id.tabBar)

        terminalView.setTerminalViewClient(this)
        terminalView.setTextSize(currentTextSize)
        terminalView.setTypeface(Typeface.MONOSPACE)

        setupExtraKeys()
        requestPermissions()

        val intent = Intent(this, ShellBoxService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)

        java.io.File(filesDir, "home").mkdirs()
        java.io.File(filesDir, "tmp").mkdirs()
        ProotBootstrap.ensurePatched(this) {}

        openShell("sh")
        findViewById<Button>(R.id.btnNewTab).setOnClickListener { showShellPicker() }
    }

    private fun openShell(shellId: String) {
        val shells = ShellDiscovery.getShells(this)
        val shell = shells.firstOrNull { it.id == shellId } ?: shells.first()

        val session: TerminalSession = if (shell.id == "rish" && shell.available) {
            // Use RishTerminalSession for elevated Shizuku shell
            val dexPath = shell.env.firstOrNull { it.startsWith("RISH_DEX=") }?.substringAfter("=") ?: ""
            val envMap = shell.env.associate { val parts = it.split("=", limit = 2); parts[0] to parts.getOrElse(1) { "" } }
            RishTerminalSession(dexPath, envMap, sessionCallback)
        } else {
            TerminalSession(shell.command, "/", shell.args, shell.env, sessionCallback)
        }

        val tabId = tabCounter++
        tabs.add(Tab(tabId, shell.id, shell.name, session))
        switchToTab(tabId)
        updateTabBar()
    }

    private fun switchToTab(tabId: Int) {
        val tab = tabs.firstOrNull { it.id == tabId } ?: return
        activeTabId = tabId
        terminalView.attachSession(tab.session)
        if (tab.session.emulator != null) terminalView.onScreenUpdated()
        updateTabBar()
    }

    private fun removeTabBySession(session: TerminalSession) {
        runOnUiThread {
            val tab = tabs.firstOrNull { it.session == session } ?: return@runOnUiThread
            tabs.remove(tab)
            if (tabs.isEmpty()) openShell("sh")
            else if (activeTabId == tab.id) switchToTab(tabs.last().id)
            updateTabBar()
        }
    }

    private fun updateTabBar() {
        tabBar.removeAllViews()
        tabs.forEach { tab ->
            val btn = Button(this).apply {
                text = "${tab.title}${if (tabs.size > 1) " ✕" else ""}"
                textSize = 11f; setTextColor(if (tab.id == activeTabId) Color.WHITE else Color.GRAY)
                setBackgroundColor(if (tab.id == activeTabId) 0xFF333333.toInt() else Color.TRANSPARENT)
                setPadding(16, 0, 16, 0); minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT)
                setOnClickListener { switchToTab(tab.id) }
                setOnLongClickListener { if (tabs.size > 1) { tab.session.finishIfRunning(); removeTabBySession(tab.session) }; true }
            }
            tabBar.addView(btn)
        }
    }

    private fun showShellPicker() {
        val shells = ShellDiscovery.getShells(this)
        val names = shells.map { it.name }.toTypedArray()
        AlertDialog.Builder(this).setTitle("New Terminal").setItems(names) { _, i ->
            val shell = shells[i]
            when {
                shell.id == "ubuntu" && shell.needsSetup -> setupUbuntu()
                shell.id == "rish" && shell.needsSetup -> {
                    try {
                        rikka.shizuku.Shizuku.requestPermission(0)
                        Toast.makeText(this, "Grant Shizuku permission, then try again", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Toast.makeText(this, "Shizuku error: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
                shell.available -> openShell(shell.id)
            }
        }.show()
    }

    private fun setupUbuntu() {
        Toast.makeText(this, "Setting up Ubuntu...", Toast.LENGTH_LONG).show()
        Thread {
            val ok = ProotBootstrap.setup(this) { msg -> Log.d(TAG, "Setup: $msg") }
            runOnUiThread {
                if (ok) { Toast.makeText(this, "Ubuntu ready!", Toast.LENGTH_SHORT).show(); openShell("ubuntu") }
                else Toast.makeText(this, "Ubuntu setup failed", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    // ── Sticky key bar (ported from AIOPE) ──────────────────────────────────

    enum class StickyState { IDLE, ARMED, LOCKED }

    data class StickyKey(
        val label: String, val isModifier: Boolean,
        val instantSeq: String = "",
        var state: StickyState = StickyState.IDLE,
        var view: android.widget.TextView? = null
    )

    private val stickyKeys = listOf(
        StickyKey("ESC", false, instantSeq = "\u001b"),
        StickyKey("TAB", false, instantSeq = "\t"),
        StickyKey("CTRL", true),
        StickyKey("ALT", true),
        StickyKey("↑", false, instantSeq = "\u001b[A"),
        StickyKey("↓", false, instantSeq = "\u001b[B"),
        StickyKey("←", false, instantSeq = "\u001b[D"),
        StickyKey("→", false, instantSeq = "\u001b[C"),
        StickyKey("HOME", false, instantSeq = "\u001b[H"),
        StickyKey("END", false, instantSeq = "\u001b[F"),
        StickyKey("DEL", false, instantSeq = "\u001b[3~")
    )

    private val colIdle = Color.parseColor("#0F1729")
    private val colArmed = Color.parseColor("#1A3A1A")
    private val colLocked = Color.parseColor("#3A1A1A")

    private fun refreshStickyView(key: StickyKey) {
        key.view?.setBackgroundColor(when (key.state) {
            StickyState.IDLE -> colIdle; StickyState.ARMED -> colArmed; StickyState.LOCKED -> colLocked
        })
        key.view?.setTextColor(when (key.state) {
            StickyState.IDLE -> Color.parseColor("#F8FAFC")
            StickyState.ARMED -> Color.parseColor("#88FF88")
            StickyState.LOCKED -> Color.parseColor("#FF8888")
        })
    }

    private fun setupExtraKeys() {
        val keysLayout = findViewById<LinearLayout>(R.id.extraKeys)
        keysLayout.removeAllViews()
        val dp = resources.displayMetrics.density

        stickyKeys.forEach { key ->
            val tv = android.widget.TextView(this).apply {
                text = key.label; textSize = 12f
                setTextColor(Color.parseColor("#F8FAFC"))
                typeface = Typeface.MONOSPACE
                gravity = android.view.Gravity.CENTER
                setBackgroundColor(colIdle)
                layoutParams = LinearLayout.LayoutParams(0, (40 * dp).toInt(), 1f).apply {
                    marginEnd = (1 * dp).toInt()
                }
                if (key.isModifier) {
                    setOnClickListener {
                        key.state = when (key.state) {
                            StickyState.IDLE -> StickyState.ARMED
                            StickyState.ARMED -> StickyState.LOCKED
                            StickyState.LOCKED -> StickyState.IDLE
                        }
                        refreshStickyView(key)
                    }
                } else {
                    setOnClickListener {
                        setBackgroundColor(Color.parseColor("#1E293B"))
                        postDelayed({ setBackgroundColor(colIdle) }, 120)
                        sendKey(key.instantSeq)
                    }
                }
            }
            key.view = tv
            keysLayout.addView(tv)
        }
    }

    private fun sendKey(data: String) {
        val s = tabs.firstOrNull { it.id == activeTabId }?.session ?: return
        val ctrl = stickyKeys.first { it.label == "CTRL" }
        val alt = stickyKeys.first { it.label == "ALT" }
        if (ctrl.state != StickyState.IDLE && data.length == 1) {
            val code = data[0].lowercaseChar().code - 'a'.code + 1
            if (code in 1..26) s.write(byteArrayOf(code.toByte()), 0, 1)
            else s.write(data)
            if (ctrl.state == StickyState.ARMED) { ctrl.state = StickyState.IDLE; refreshStickyView(ctrl) }
            if (alt.state == StickyState.ARMED) { alt.state = StickyState.IDLE; refreshStickyView(alt) }
        } else {
            s.write(data)
        }
    }

    // ── TerminalViewClient ────────────────────────────────────────────────────

    override fun onScale(scale: Float): Float {
        val dp = resources.displayMetrics.scaledDensity
        if (currentTextSize == 0) currentTextSize = (14 * dp).toInt()
        currentTextSize = (currentTextSize * scale).coerceIn(6 * dp, 36 * dp).toInt()
        terminalView.setTextSize(currentTextSize)
        return 1f
    }
    override fun onSingleTapUp(e: MotionEvent) { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(terminalView, 0) }
    override fun shouldBackButtonBeMappedToEscape() = false
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession) = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent) = false
    override fun onLongPress(event: MotionEvent) = false
    override fun readControlKey() = false
    override fun readAltKey() = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession) = false

    // ── Permissions ───────────────────────────────────────────────────────────

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager())
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }
}
