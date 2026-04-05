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
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.shellbox.service.ShellBoxService
import com.shellbox.shell.ProotBootstrap
import com.shellbox.shell.RishExecutor
import com.shellbox.terminal.ShellDiscovery
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

private const val TAG = "ShellBox"

class MainActivity : AppCompatActivity(), TerminalViewClient, TerminalSessionClient {

    private lateinit var terminalView: TerminalView
    private lateinit var tabBar: LinearLayout
    private var currentTextSize = 14

    data class Tab(val id: Int, val shellId: String, val title: String, val session: TerminalSession)

    private val tabs = mutableListOf<Tab>()
    private var activeTabId = -1
    private var tabCounter = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        terminalView = findViewById(R.id.terminalView)
        tabBar = findViewById(R.id.tabBar)

        terminalView.setTerminalViewClient(this)
        terminalView.setTextSize(14)
        terminalView.setTypeface(Typeface.MONOSPACE)

        setupExtraKeys()
        requestPermissions()

        val intent = Intent(this, ShellBoxService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)

        // Ensure home/tmp dirs exist
        java.io.File(filesDir, "home").mkdirs()
        java.io.File(filesDir, "tmp").mkdirs()

        ProotBootstrap.ensurePatched(this) {}
        openShell("sh")

        findViewById<Button>(R.id.btnNewTab).setOnClickListener { showShellPicker() }
    }

    private fun openShell(shellId: String) {
        val shells = ShellDiscovery.getShells(this)
        val shell = shells.firstOrNull { it.id == shellId } ?: shells.first()

        if (shell.id == "rish") {
            openRishShell(); return
        }

        val session = TerminalSession(shell.command, "/", shell.args, shell.env, 4000, this)
        val tabId = tabCounter++
        tabs.add(Tab(tabId, shell.id, shell.name, session))
        switchToTab(tabId)
        updateTabBar()
    }

    private fun openRishShell() {
        // For Shizuku, we use a regular TerminalSession with /system/bin/sh
        // but the session runs at app level. To get true elevation, we'd need
        // to override TerminalSession's process creation (like AIOPE's RishTerminalSession).
        // For now, use the regular sh session and exec commands via Shizuku's newProcess.
        // TODO: Port AIOPE's RishTerminalSession for true elevated PTY
        Toast.makeText(this, "Shizuku shell: use 'id' to verify elevation", Toast.LENGTH_SHORT).show()

        try {
            // Create a script that uses Shizuku for elevation
            val session = TerminalSession(
                "/system/bin/sh", "/data/local/tmp",
                arrayOf(), arrayOf("TERM=xterm-256color", "HOME=/data/local/tmp"),
                4000, this
            )
            val tabId = tabCounter++
            tabs.add(Tab(tabId, "rish", "Shizuku (ADB)", session))
            switchToTab(tabId)
            updateTabBar()
        } catch (e: Exception) {
            Toast.makeText(this, "Shizuku error: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e(TAG, "Shizuku shell failed", e)
        }
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

    // ── Extra keys ────────────────────────────────────────────────────────────

    private fun setupExtraKeys() {
        val keysLayout = findViewById<LinearLayout>(R.id.extraKeys)
        val keys = listOf("ESC" to "\u001b", "TAB" to "\t", "CTRL" to null,
            "↑" to "\u001b[A", "↓" to "\u001b[B", "←" to "\u001b[D", "→" to "\u001b[C",
            "|" to "|", "/" to "/", "-" to "-", "~" to "~")
        var ctrlActive = false
        keys.forEach { (label, value) ->
            val btn = Button(this).apply {
                text = label; textSize = 14f; setTextColor(Color.WHITE)
                setBackgroundColor(0xFF2D2D2D.toInt()); setPadding(24, 12, 24, 12)
                minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(3, 4, 3, 4) }
            }
            if (label == "CTRL") {
                btn.setOnClickListener { ctrlActive = !ctrlActive; btn.setBackgroundColor(if (ctrlActive) 0xFF7C4DFF.toInt() else 0xFF2D2D2D.toInt()) }
            } else {
                btn.setOnClickListener {
                    val s = tabs.firstOrNull { it.id == activeTabId }?.session ?: return@setOnClickListener
                    if (ctrlActive && value != null && value.length == 1) {
                        val code = value[0].code - 96; if (code in 1..26) s.write(byteArrayOf(code.toByte()), 0, 1)
                        ctrlActive = false; keysLayout.getChildAt(2)?.setBackgroundColor(0xFF2D2D2D.toInt())
                    } else if (value != null) s.write(value)
                }
            }
            keysLayout.addView(btn)
        }
    }

    // ── TerminalSessionClient ─────────────────────────────────────────────────

    override fun onTextChanged(session: TerminalSession) { terminalView.onScreenUpdated() }
    override fun onTitleChanged(session: TerminalSession) { runOnUiThread { updateTabBar() } }
    override fun onSessionFinished(session: TerminalSession) { removeTabBySession(session) }
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val clip = getSystemService(android.content.ClipboardManager::class.java)
        clip.setPrimaryClip(android.content.ClipData.newPlainText("ShellBox", text))
    }
    override fun onPasteTextFromClipboard(session: TerminalSession) {
        val clip = getSystemService(android.content.ClipboardManager::class.java)
        clip.primaryClip?.getItemAt(0)?.text?.let { session.write(it.toString()) }
    }
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle(): Int = 0

    // ── TerminalViewClient ────────────────────────────────────────────────────

    override fun onScale(scale: Float): Float { currentTextSize = (currentTextSize * scale).coerceIn(8f, 32f).toInt(); terminalView.setTextSize(currentTextSize); return scale }
    override fun onSingleTapUp(e: MotionEvent) { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(terminalView, 0) }
    override fun shouldBackButtonBeMappedToEscape() = false
    override fun shouldEnforceCharBasedInput() = true
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession) = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent) = false
    override fun onLongPress(event: MotionEvent) = false
    override fun readControlKey() = false
    override fun readAltKey() = false
    override fun readShiftKey() = false
    override fun readFnKey() = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession) = false
    override fun onEmulatorSet() {}

    // ── Logging (required by both interfaces) ─────────────────────────────────

    override fun logError(tag: String, msg: String) { Log.e(tag, msg) }
    override fun logWarn(tag: String, msg: String) { Log.w(tag, msg) }
    override fun logInfo(tag: String, msg: String) { Log.i(tag, msg) }
    override fun logDebug(tag: String, msg: String) { Log.d(tag, msg) }
    override fun logVerbose(tag: String, msg: String) { Log.v(tag, msg) }
    override fun logStackTraceWithMessage(tag: String, msg: String, e: Exception) { Log.e(tag, msg, e) }
    override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "Stack trace", e) }

    // ── Permissions ───────────────────────────────────────────────────────────

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager())
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }
}
