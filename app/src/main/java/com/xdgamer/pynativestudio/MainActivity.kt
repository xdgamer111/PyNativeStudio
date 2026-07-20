package com.xdgamer.pynativestudio

import android.content.*
import android.net.Uri
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class MainActivity : AppCompatActivity() {
    private lateinit var editor: CodeEditorView
    private lateinit var tabsView: LinearLayout
    private lateinit var console: TextView
    private lateinit var consoleScroll: ScrollView
    private lateinit var inputLayout: com.google.android.material.textfield.TextInputLayout
    private lateinit var consoleInput: TextInputEditText
    private lateinit var timeView: TextView
    private val tabs = mutableListOf<EditorTab>()
    private var active = 0
    private var suppressChanges = false
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::loadUri) }
    private val saveFile = registerForActivityResult(ActivityResultContracts.CreateDocument("text/x-python")) { uri -> uri?.let { saveToUri(it) } }
    private val importZip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::importProject) }
    private val exportZip = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let(::exportProject) }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getStringExtra(PythonRunnerService.EXTRA_EVENT)) {
                PythonRunnerService.EVENT_STDOUT -> appendConsole(intent.getStringExtra(PythonRunnerService.EXTRA_TEXT).orEmpty())
                PythonRunnerService.EVENT_STDERR -> appendConsole(intent.getStringExtra(PythonRunnerService.EXTRA_TEXT).orEmpty())
                PythonRunnerService.EVENT_INPUT_REQUEST -> { inputLayout.visibility = android.view.View.VISIBLE; consoleInput.requestFocus() }
                PythonRunnerService.EVENT_FINISHED -> {
                    val elapsed = intent.getDoubleExtra(PythonRunnerService.EXTRA_ELAPSED, 0.0)
                    timeView.text = "${intent.getStringExtra(PythonRunnerService.EXTRA_TEXT)} • %.3f s".format(elapsed)
                    inputLayout.visibility = android.view.View.GONE
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setContentView(R.layout.activity_main)
        editor = findViewById(R.id.editor); tabsView = findViewById(R.id.tabs); console = findViewById(R.id.console)
        consoleScroll = findViewById(R.id.consoleScroll); inputLayout = findViewById(R.id.inputLayout)
        consoleInput = findViewById(R.id.consoleInput); timeView = findViewById(R.id.executionTime)
        findViewById<android.view.View>(R.id.clearConsole).setOnClickListener { console.text = "" }
        editor.setOnContentChanged { if (!suppressChanges && tabs.isNotEmpty()) { tabs[active].text = it; tabs[active].dirty = true; renderTabs() } }
        consoleInput.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE) { submitInput(); true } else false
        }
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).setOnMenuItemClickListener { handleMenu(it.itemId); true }
        newTab("main.py", EXAMPLES.first().second)
        applySettings()
    }

    override fun onStart() { super.onStart(); ContextCompat.registerReceiver(this, receiver, IntentFilter(PythonRunnerService.ACTION_EVENT), ContextCompat.RECEIVER_NOT_EXPORTED) }
    override fun onStop() { unregisterReceiver(receiver); super.onStop() }

    private fun handleMenu(id: Int) {
        when (id) {
            R.id.action_run -> runCode(); R.id.action_stop -> startService(Intent(this, PythonRunnerService::class.java).setAction(PythonRunnerService.ACTION_STOP))
            R.id.action_new -> newTab("untitled${tabs.size + 1}.py", "")
            R.id.action_open -> openFile.launch(arrayOf("text/x-python", "text/plain", "application/octet-stream"))
            R.id.action_save -> if (tabs[active].uri != null) saveToUri(tabs[active].uri!!) else saveFile.launch(tabs[active].title)
            R.id.action_save_as -> saveFile.launch(tabs[active].title)
            R.id.action_rename -> promptRename(); R.id.action_delete -> deleteCurrent(); R.id.action_find -> findReplace()
            R.id.action_undo -> editor.undo(); R.id.action_redo -> editor.redo(); R.id.action_examples -> chooseExample()
            R.id.action_import_project -> importZip.launch(arrayOf("application/zip", "application/octet-stream"))
            R.id.action_export_project -> exportZip.launch("python-project.zip")
            R.id.action_settings -> settingsDialog()
        }
    }

    private fun newTab(title: String, content: String, uri: Uri? = null) {
        tabs += EditorTab(title, content, uri, false); switchTab(tabs.lastIndex)
    }
    private fun switchTab(index: Int) {
        if (tabs.isEmpty()) return
        active = index.coerceIn(tabs.indices); suppressChanges = true; editor.setText(tabs[active].text); suppressChanges = false; renderTabs()
    }
    private fun renderTabs() {
        tabsView.removeAllViews()
        tabs.forEachIndexed { i, tab ->
            Button(this).apply {
                text = (if (i == active) "● " else "") + tab.title + if (tab.dirty) " *" else ""
                isAllCaps = false; setOnClickListener { switchTab(i) }
                setOnLongClickListener { if (tabs.size > 1) { tabs.removeAt(i); switchTab(active.coerceAtMost(tabs.lastIndex)) }; true }
                tabsView.addView(this)
            }
        }
    }
    private fun runCode() {
        console.text = ""; timeView.text = "Running…"
        startService(Intent(this, PythonRunnerService::class.java).setAction(PythonRunnerService.ACTION_RUN)
            .putExtra(PythonRunnerService.EXTRA_CODE, editor.text()).putExtra(PythonRunnerService.EXTRA_NAME, tabs[active].title))
    }
    private fun submitInput() {
        val value = consoleInput.text?.toString().orEmpty(); appendConsole(value + "\n"); consoleInput.setText(""); inputLayout.visibility = android.view.View.GONE
        startService(Intent(this, PythonRunnerService::class.java).setAction(PythonRunnerService.ACTION_INPUT).putExtra(PythonRunnerService.EXTRA_INPUT, value))
    }
    private fun appendConsole(text: String) { console.append(text); consoleScroll.post { consoleScroll.fullScroll(ScrollView.FOCUS_DOWN) } }

    private fun loadUri(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: Exception) {}
        val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return
        val name = DocumentFile.fromSingleUri(this, uri)?.name ?: "opened.py"
        newTab(name, text, uri); rememberRecent(uri)
    }
    private fun saveToUri(uri: Uri) {
        contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(editor.text()) }
        tabs[active].uri = uri; tabs[active].title = DocumentFile.fromSingleUri(this, uri)?.name ?: tabs[active].title; tabs[active].dirty = false
        rememberRecent(uri); renderTabs(); Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
    }
    private fun rememberRecent(uri: Uri) {
        val recent = prefs.getStringSet("recent", emptySet())!!.toMutableSet(); recent += uri.toString(); prefs.edit().putStringSet("recent", recent.toList().takeLast(12).toSet()).apply()
    }
    private fun promptRename() {
        val field = EditText(this).apply { setText(tabs[active].title) }
        MaterialAlertDialogBuilder(this).setTitle("Rename tab").setView(field).setPositiveButton("Rename") { _, _ -> tabs[active].title = field.text.toString().ifBlank { "untitled.py" }; renderTabs() }.setNegativeButton("Cancel", null).show()
    }
    private fun deleteCurrent() {
        val tab = tabs[active]
        MaterialAlertDialogBuilder(this).setTitle("Delete ${tab.title}?").setMessage("This also deletes the document when Android grants delete permission.")
            .setPositiveButton("Delete") { _, _ -> tab.uri?.let { DocumentFile.fromSingleUri(this, it)?.delete() }; tabs.removeAt(active); if (tabs.isEmpty()) newTab("main.py", "") else switchTab(active.coerceAtMost(tabs.lastIndex)) }
            .setNegativeButton("Cancel", null).show()
    }
    private fun findReplace() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 8, 32, 0) }
        val find = EditText(this).apply { hint = "Find" }; val replace = EditText(this).apply { hint = "Replace with" }; box.addView(find); box.addView(replace)
        MaterialAlertDialogBuilder(this).setTitle("Find and replace").setView(box).setPositiveButton("Replace all") { _, _ ->
            val needle = find.text.toString(); if (needle.isNotEmpty()) editor.setText(editor.text().replace(needle, replace.text.toString()))
        }.setNeutralButton("Find next") { _, _ ->
            val start = editor.editText.selectionEnd.coerceAtLeast(0); val at = editor.text().indexOf(find.text.toString(), start, true)
            if (at >= 0) { editor.editText.requestFocus(); editor.editText.setSelection(at, at + find.length()) }
        }.setNegativeButton("Cancel", null).show()
    }
    private fun chooseExample() {
        MaterialAlertDialogBuilder(this).setTitle("Example programs").setItems(EXAMPLES.map { it.first }.toTypedArray()) { _, which -> newTab(EXAMPLES[which].first.lowercase().replace(" ", "_") + ".py", EXAMPLES[which].second) }.show()
    }
    private fun settingsDialog() {
        val sizes = arrayOf("12", "14", "16", "18", "20", "22")
        MaterialAlertDialogBuilder(this).setTitle("Editor font size").setSingleChoiceItems(sizes, sizes.indexOf(prefs.getInt("font", 16).toString())) { dialog, which -> prefs.edit().putInt("font", sizes[which].toInt()).apply(); applySettings(); dialog.dismiss() }
            .setNeutralButton(if (prefs.getBoolean("light", false)) "Use dark theme" else "Use light theme") { _, _ -> prefs.edit().putBoolean("light", !prefs.getBoolean("light", false)).apply(); applySettings() }.show()
    }
    private fun applySettings() { editor.setFontSize(prefs.getInt("font", 16).toFloat()); editor.applyTheme(prefs.getBoolean("light", false)) }

    private fun exportProject(uri: Uri) {
        contentResolver.openOutputStream(uri)?.use { raw -> ZipOutputStream(BufferedOutputStream(raw)).use { zip -> tabs.forEach { tab -> zip.putNextEntry(ZipEntry(tab.title.replace("/", "_"))); zip.write(tab.text.toByteArray()); zip.closeEntry() } } }
        Toast.makeText(this, "Project exported", Toast.LENGTH_SHORT).show()
    }
    private fun importProject(uri: Uri) {
        contentResolver.openInputStream(uri)?.use { raw -> ZipInputStream(BufferedInputStream(raw)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".py", true)) {
                    val buffer = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    var read = zip.read(chunk)
                    while (read > 0) { buffer.write(chunk, 0, read); read = zip.read(chunk) }
                    newTab(entry.name.substringAfterLast('/'), buffer.toString(Charsets.UTF_8.name()))
                }
                zip.closeEntry(); entry = zip.nextEntry
            }
        } }
    }

    companion object {
        val EXAMPLES = listOf(
            "Hello world" to "print(\"Hello from real CPython on Android!\")\n",
            "Interactive input" to "name = input(\"What is your name? \" )\nprint(f\"Hello, {name}!\")\n",
            "Fibonacci" to "def fibonacci(n):\n    a, b = 0, 1\n    for _ in range(n):\n        yield a\n        a, b = b, a + b\n\nprint(list(fibonacci(15)))\n",
            "Error traceback" to "def divide(a, b):\n    return a / b\n\nprint(divide(10, 0))\n"
        )
    }
}
