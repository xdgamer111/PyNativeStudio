package com.xdgamer.pynativestudio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class MainActivity : AppCompatActivity() {
    private lateinit var editor: CodeEditorView
    private lateinit var tabsView: LinearLayout
    private lateinit var console: TextView
    private lateinit var consoleScroll: ScrollView
    private lateinit var inputLayout: TextInputLayout
    private lateinit var consoleInput: TextInputEditText
    private lateinit var timeView: TextView
    private lateinit var suggestionButton: MaterialButton

    private val autosaveHandler = Handler(Looper.getMainLooper())
    private lateinit var workspaceStore: WorkspaceStore
    private var currentSuggestion: AutoCompleteEngine.Suggestion? = null
    private var hasRestoredWorkspace = false

    private val autosaveRunnable = Runnable {
        saveWorkspace(showStatus = true)
    }

    private val tabs = mutableListOf<EditorTab>()
    private var activeTabIndex = 0
    private var suppressEditorChanges = false
    private var receiverRegistered = false

    private val preferences by lazy {
        getSharedPreferences("settings", MODE_PRIVATE)
    }

    private val openFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(::loadUri)
        }

    private val saveFileLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/x-python")) { uri ->
            uri?.let(::saveToUri)
        }

    private val importZipLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(::importProject)
        }

    private val exportZipLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            uri?.let(::exportProject)
        }

    private val runnerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getStringExtra(PythonRunnerService.EXTRA_EVENT)) {
                PythonRunnerService.EVENT_STDOUT,
                PythonRunnerService.EVENT_STDERR -> appendConsole(
                    intent.getStringExtra(PythonRunnerService.EXTRA_TEXT).orEmpty()
                )

                PythonRunnerService.EVENT_INPUT_REQUEST -> {
                    inputLayout.visibility = View.VISIBLE
                    consoleInput.requestFocus()
                }

                PythonRunnerService.EVENT_FINISHED -> {
                    val elapsed = intent.getDoubleExtra(
                        PythonRunnerService.EXTRA_ELAPSED,
                        0.0
                    )
                    val state = intent.getStringExtra(
                        PythonRunnerService.EXTRA_TEXT
                    ).orEmpty()

                    timeView.text = "$state • %.3f s".format(elapsed)
                    inputLayout.visibility = View.GONE
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        workspaceStore = WorkspaceStore(this)
        bindViews()
        configureButtons()
        configureEditor()
        configureConsoleInput()
        configureToolbar()

        hasRestoredWorkspace = restoreWorkspace()
        if (!hasRestoredWorkspace) {
            newTab("main.py", EXAMPLES.first().second)
        }
        applySettings()
        preferences.edit().putBoolean("clean_shutdown", false).apply()
    }

    override fun onStart() {
        super.onStart()

        ContextCompat.registerReceiver(
            this,
            runnerReceiver,
            IntentFilter(PythonRunnerService.ACTION_EVENT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }

    override fun onPause() {
        saveWorkspace(showStatus = false)
        super.onPause()
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(runnerReceiver)
            receiverRegistered = false
        }

        saveWorkspace(showStatus = false)
        super.onStop()
    }

    override fun onDestroy() {
        autosaveHandler.removeCallbacks(autosaveRunnable)
        saveWorkspace(showStatus = false)
        preferences.edit().putBoolean("clean_shutdown", true).apply()
        super.onDestroy()
    }

    private fun bindViews() {
        editor = findViewById(R.id.editor)
        tabsView = findViewById(R.id.tabs)
        console = findViewById(R.id.console)
        consoleScroll = findViewById(R.id.consoleScroll)
        inputLayout = findViewById(R.id.inputLayout)
        consoleInput = findViewById(R.id.consoleInput)
        timeView = findViewById(R.id.executionTime)
        suggestionButton = findViewById(R.id.suggestionButton)
    }

    private fun configureButtons() {
        findViewById<View>(R.id.clearConsole).setOnClickListener {
            console.text = ""
            timeView.text = getString(R.string.ready)
        }

        findViewById<View>(R.id.quickRun).setOnClickListener {
            runCode()
        }

        findViewById<View>(R.id.quickStop).setOnClickListener {
            stopCode()
        }

        findViewById<View>(R.id.quickSave).setOnClickListener {
            saveCurrentTab()
        }

        findViewById<View>(R.id.quickNew).setOnClickListener {
            createUntitledTab()
        }

        suggestionButton.setOnClickListener {
            currentSuggestion?.let { suggestion ->
                editor.acceptSuggestion(suggestion)
                updateSuggestion()
            }
        }
    }

    private fun configureEditor() {
        editor.setOnContentChanged { text ->
            if (suppressEditorChanges || tabs.isEmpty()) {
                return@setOnContentChanged
            }

            tabs[activeTabIndex].text = text
            tabs[activeTabIndex].dirty = true
            tabs[activeTabIndex].cursorPosition = editor.cursorPosition()
            tabs[activeTabIndex].scrollY = editor.scrollPositionY()
            renderTabs()
            updateSuggestion()
            scheduleAutosave()
        }
    }

    private fun configureConsoleInput() {
        consoleInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submitInput()
                true
            } else {
                false
            }
        }
    }

    private fun configureToolbar() {
        findViewById<MaterialToolbar>(R.id.toolbar).setOnMenuItemClickListener {
            handleMenu(it.itemId)
            true
        }
    }

    private fun handleMenu(id: Int) {
        when (id) {
            R.id.action_run -> runCode()
            R.id.action_stop -> stopCode()
            R.id.action_new -> createUntitledTab()
            R.id.action_open -> openFileLauncher.launch(
                arrayOf(
                    "text/x-python",
                    "text/plain",
                    "application/octet-stream"
                )
            )

            R.id.action_save -> saveCurrentTab()
            R.id.action_save_as -> saveFileLauncher.launch(currentTab().title)
            R.id.action_rename -> promptRename()
            R.id.action_delete -> deleteCurrent()
            R.id.action_find -> findReplace()
            R.id.action_undo -> editor.undo()
            R.id.action_redo -> editor.redo()
            R.id.action_examples -> chooseExample()
            R.id.action_import_project -> importZipLauncher.launch(
                arrayOf("application/zip", "application/octet-stream")
            )

            R.id.action_export_project -> exportZipLauncher.launch(
                "python-project.zip"
            )

            R.id.action_settings -> settingsDialog()
        }
    }

    private fun createUntitledTab() {
        newTab("untitled${tabs.size + 1}.py", "")
    }

    private fun currentTab(): EditorTab = tabs[activeTabIndex]

    private fun newTab(
        title: String,
        content: String,
        uri: Uri? = null
    ) {
        tabs += EditorTab(
            title = title,
            text = content,
            uri = uri,
            dirty = false
        )
        switchTab(tabs.lastIndex)
    }

    private fun switchTab(index: Int) {
        if (tabs.isEmpty()) {
            return
        }

        if (activeTabIndex in tabs.indices) {
            tabs[activeTabIndex].cursorPosition = editor.cursorPosition()
            tabs[activeTabIndex].scrollY = editor.scrollPositionY()
        }

        activeTabIndex = index.coerceIn(tabs.indices)
        suppressEditorChanges = true
        editor.setText(currentTab().text)
        editor.restorePosition(
            currentTab().cursorPosition,
            currentTab().scrollY
        )
        suppressEditorChanges = false
        renderTabs()
        updateSuggestion()
    }

    private fun renderTabs() {
        tabsView.removeAllViews()

        tabs.forEachIndexed { index, tab ->
            val button = MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = buildString {
                    if (index == activeTabIndex) {
                        append("● ")
                    }
                    append(tab.title)
                    if (tab.dirty) {
                        append(" *")
                    }
                }
                isAllCaps = false
                cornerRadius = 28

                setOnClickListener {
                    switchTab(index)
                }

                setOnLongClickListener {
                    if (tabs.size > 1) {
                        tabs.removeAt(index)
                        switchTab(activeTabIndex.coerceAtMost(tabs.lastIndex))
                    }
                    true
                }
            }

            tabsView.addView(button)
        }
    }

    private fun runCode() {
        if (tabs.isEmpty()) {
            return
        }

        saveWorkspace(showStatus = false)
        saveCurrentUriSilently()
        console.text = ""
        timeView.text = getString(R.string.running)

        val intent = Intent(this, PythonRunnerService::class.java)
            .setAction(PythonRunnerService.ACTION_RUN)
            .putExtra(PythonRunnerService.EXTRA_CODE, editor.text())
            .putExtra(PythonRunnerService.EXTRA_NAME, currentTab().title)

        startService(intent)
    }

    private fun stopCode() {
        startService(
            Intent(this, PythonRunnerService::class.java)
                .setAction(PythonRunnerService.ACTION_STOP)
        )
    }

    private fun submitInput() {
        val value = consoleInput.text?.toString().orEmpty()

        appendConsole("$value\n")
        consoleInput.setText("")
        inputLayout.visibility = View.GONE

        startService(
            Intent(this, PythonRunnerService::class.java)
                .setAction(PythonRunnerService.ACTION_INPUT)
                .putExtra(PythonRunnerService.EXTRA_INPUT, value)
        )
    }

    private fun appendConsole(text: String) {
        console.append(text)
        consoleScroll.post {
            consoleScroll.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    private fun loadUri(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Some document providers do not allow persistent permissions.
        }

        val text = contentResolver
            .openInputStream(uri)
            ?.bufferedReader()
            ?.use { reader -> reader.readText() }
            ?: return

        val name = DocumentFile
            .fromSingleUri(this, uri)
            ?.name
            ?: "opened.py"

        newTab(name, text, uri)
        rememberRecent(uri)
    }

    private fun saveCurrentTab() {
        if (tabs.isEmpty()) {
            return
        }

        val uri = currentTab().uri
        if (uri != null) {
            saveToUri(uri)
        } else {
            saveFileLauncher.launch(currentTab().title)
        }
    }

    private fun saveToUri(uri: Uri) {
        val outputStream = contentResolver.openOutputStream(uri, "wt")
        if (outputStream == null) {
            Toast.makeText(this, "Unable to save file", Toast.LENGTH_SHORT).show()
            return
        }

        outputStream.bufferedWriter().use { writer ->
            writer.write(editor.text())
        }

        currentTab().uri = uri
        currentTab().title = DocumentFile
            .fromSingleUri(this, uri)
            ?.name
            ?: currentTab().title
        currentTab().dirty = false

        rememberRecent(uri)
        renderTabs()
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
    }

    private fun rememberRecent(uri: Uri) {
        val recent = preferences
            .getStringSet("recent", emptySet())
            .orEmpty()
            .toMutableList()

        recent.remove(uri.toString())
        recent += uri.toString()

        preferences.edit()
            .putStringSet("recent", recent.takeLast(12).toSet())
            .apply()
    }

    private fun promptRename() {
        val field = EditText(this).apply {
            setText(currentTab().title)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Rename tab")
            .setView(field)
            .setPositiveButton("Rename") { _, _ ->
                currentTab().title = field.text
                    .toString()
                    .ifBlank { "untitled.py" }
                renderTabs()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteCurrent() {
        val tab = currentTab()

        MaterialAlertDialogBuilder(this)
            .setTitle("Delete ${tab.title}?")
            .setMessage(
                "This also deletes the document when Android grants " +
                    "delete permission."
            )
            .setPositiveButton("Delete") { _, _ ->
                tab.uri?.let { uri ->
                    DocumentFile.fromSingleUri(this, uri)?.delete()
                }

                tabs.removeAt(activeTabIndex)
                if (tabs.isEmpty()) {
                    newTab("main.py", "")
                } else {
                    switchTab(activeTabIndex.coerceAtMost(tabs.lastIndex))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun findReplace() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 8, 32, 0)
        }
        val findField = EditText(this).apply {
            hint = "Find"
        }
        val replaceField = EditText(this).apply {
            hint = "Replace with"
        }

        container.addView(findField)
        container.addView(replaceField)

        MaterialAlertDialogBuilder(this)
            .setTitle("Find and replace")
            .setView(container)
            .setPositiveButton("Replace all") { _, _ ->
                val needle = findField.text.toString()
                if (needle.isNotEmpty()) {
                    editor.setText(
                        editor.text().replace(
                            oldValue = needle,
                            newValue = replaceField.text.toString()
                        )
                    )
                }
            }
            .setNeutralButton("Find next") { _, _ ->
                val needle = findField.text.toString()
                if (needle.isEmpty()) {
                    return@setNeutralButton
                }

                val start = editor.editText.selectionEnd.coerceAtLeast(0)
                val position = editor.text().indexOf(
                    string = needle,
                    startIndex = start,
                    ignoreCase = true
                )

                if (position >= 0) {
                    editor.editText.requestFocus()
                    editor.editText.setSelection(
                        position,
                        position + needle.length
                    )
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun chooseExample() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Example programs")
            .setItems(EXAMPLES.map { example -> example.first }.toTypedArray()) { _, selected ->
                val example = EXAMPLES[selected]
                val fileName = example.first
                    .lowercase()
                    .replace(" ", "_") + ".py"
                newTab(fileName, example.second)
            }
            .show()
    }

    private fun settingsDialog() {
        val sizes = arrayOf("12", "14", "16", "18", "20", "22")
        val currentSize = preferences.getInt("font", 16).toString()
        val selectedIndex = sizes.indexOf(currentSize).coerceAtLeast(0)
        val lightThemeEnabled = preferences.getBoolean("light", false)

        MaterialAlertDialogBuilder(this)
            .setTitle("Editor font size")
            .setSingleChoiceItems(sizes, selectedIndex) { dialog, which ->
                preferences.edit()
                    .putInt("font", sizes[which].toInt())
                    .apply()
                applySettings()
                dialog.dismiss()
            }
            .setNeutralButton(
                if (lightThemeEnabled) {
                    "Use dark theme"
                } else {
                    "Use light theme"
                }
            ) { _, _ ->
                preferences.edit()
                    .putBoolean("light", !lightThemeEnabled)
                    .apply()
                applySettings()
            }
            .show()
    }

    private fun applySettings() {
        editor.setFontSize(
            preferences.getInt("font", 16).toFloat()
        )
        editor.applyTheme(
            preferences.getBoolean("light", false)
        )
    }

    private fun exportProject(uri: Uri) {
        val output = contentResolver.openOutputStream(uri)
        if (output == null) {
            Toast.makeText(this, "Unable to export project", Toast.LENGTH_SHORT).show()
            return
        }

        output.use { rawOutput ->
            ZipOutputStream(BufferedOutputStream(rawOutput)).use { zip ->
                tabs.forEach { tab ->
                    val safeName = tab.title.replace("/", "_")
                    zip.putNextEntry(ZipEntry(safeName))
                    zip.write(tab.text.toByteArray())
                    zip.closeEntry()
                }
            }
        }

        Toast.makeText(this, "Project exported", Toast.LENGTH_SHORT).show()
    }

    private fun importProject(uri: Uri) {
        val input = contentResolver.openInputStream(uri) ?: return

        input.use { rawInput ->
            ZipInputStream(BufferedInputStream(rawInput)).use { zip ->
                var entry = zip.nextEntry

                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith(".py", true)) {
                        val buffer = ByteArrayOutputStream()
                        val chunk = ByteArray(8192)
                        var bytesRead = zip.read(chunk)

                        while (bytesRead > 0) {
                            buffer.write(chunk, 0, bytesRead)
                            bytesRead = zip.read(chunk)
                        }

                        newTab(
                            entry.name.substringAfterLast('/'),
                            buffer.toString(Charsets.UTF_8.name())
                        )
                    }

                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
    }

    private fun scheduleAutosave() {
        autosaveHandler.removeCallbacks(autosaveRunnable)
        autosaveHandler.postDelayed(autosaveRunnable, AUTOSAVE_DELAY_MS)
    }

    private fun saveWorkspace(showStatus: Boolean) {
        if (tabs.isEmpty() || !::workspaceStore.isInitialized) return

        if (activeTabIndex in tabs.indices) {
            tabs[activeTabIndex].text = editor.text()
            tabs[activeTabIndex].cursorPosition = editor.cursorPosition()
            tabs[activeTabIndex].scrollY = editor.scrollPositionY()
        }

        workspaceStore.save(
            tabs = tabs,
            activeTabIndex = activeTabIndex,
            consoleText = console.text.toString()
        )
        saveCurrentUriSilently()

        if (showStatus && timeView.text.toString() != getString(R.string.running)) {
            timeView.text = "Auto-saved"
        }
    }

    private fun saveCurrentUriSilently() {
        if (tabs.isEmpty()) return
        val tab = currentTab()
        val uri = tab.uri ?: return

        runCatching {
            contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { writer ->
                writer.write(tab.text)
            } ?: return@runCatching
            tab.dirty = false
        }
    }

    private fun restoreWorkspace(): Boolean {
        val snapshot = workspaceStore.load() ?: return false
        if (snapshot.tabs.isEmpty()) return false

        tabs.clear()
        tabs.addAll(snapshot.tabs)
        console.text = snapshot.consoleText
        activeTabIndex = snapshot.activeTabIndex.coerceIn(tabs.indices)
        switchTab(activeTabIndex)

        val wasClean = preferences.getBoolean("clean_shutdown", true)
        timeView.text = if (wasClean) "Workspace restored" else "Recovered after shutdown"

        if (!wasClean) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Work recovered")
                .setMessage("PyNative Studio restored your tabs and unsaved code from the last auto-save.")
                .setPositiveButton("Continue", null)
                .show()
        }
        return true
    }

    private fun updateSuggestion() {
        if (tabs.isEmpty()) {
            hideSuggestion()
            return
        }

        currentSuggestion = AutoCompleteEngine.suggest(
            source = editor.text(),
            cursor = editor.cursorPosition()
        )

        val suggestion = currentSuggestion
        if (suggestion == null) {
            hideSuggestion()
        } else {
            suggestionButton.text = "Tap to insert: ${suggestion.label}"
            suggestionButton.visibility = View.VISIBLE
        }
    }

    private fun hideSuggestion() {
        currentSuggestion = null
        suggestionButton.visibility = View.GONE
    }

    companion object {
        private const val AUTOSAVE_DELAY_MS = 2_000L

        val EXAMPLES = listOf(
            "Hello world" to
                "print(\"Hello from real CPython on Android!\")\n",
            "Interactive input" to
                "name = input(\"What is your name? \" )\n" +
                "print(f\"Hello, {name}!\")\n",
            "Fibonacci" to
                "def fibonacci(n):\n" +
                "    a, b = 0, 1\n" +
                "    for _ in range(n):\n" +
                "        yield a\n" +
                "        a, b = b, a + b\n\n" +
                "print(list(fibonacci(15)))\n",
            "Error traceback" to
                "def divide(a, b):\n" +
                "    return a / b\n\n" +
                "print(divide(10, 0))\n"
        )
    }
}
