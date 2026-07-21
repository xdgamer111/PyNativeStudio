package com.xdgamer.pynativestudio

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class WorkspaceStore(context: Context) {
    data class Snapshot(
        val tabs: List<EditorTab>,
        val activeTabIndex: Int,
        val consoleText: String,
        val savedAt: Long
    )

    private val file = File(context.filesDir, "workspace_recovery.json")

    fun exists(): Boolean = file.exists() && file.length() > 0

    fun save(
        tabs: List<EditorTab>,
        activeTabIndex: Int,
        consoleText: String
    ) {
        val tabArray = JSONArray()
        tabs.forEach { tab ->
            tabArray.put(
                JSONObject()
                    .put("title", tab.title)
                    .put("text", tab.text)
                    .put("uri", tab.uri?.toString() ?: JSONObject.NULL)
                    .put("dirty", tab.dirty)
                    .put("cursor", tab.cursorPosition)
                    .put("scrollY", tab.scrollY)
            )
        }

        val root = JSONObject()
            .put("version", 1)
            .put("savedAt", System.currentTimeMillis())
            .put("activeTab", activeTabIndex)
            .put("console", consoleText)
            .put("tabs", tabArray)

        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(root.toString())
        if (!temporary.renameTo(file)) {
            file.writeText(root.toString())
            temporary.delete()
        }
    }

    fun load(): Snapshot? = runCatching {
        val root = JSONObject(file.readText())
        val jsonTabs = root.getJSONArray("tabs")
        val restoredTabs = mutableListOf<EditorTab>()

        for (index in 0 until jsonTabs.length()) {
            val item = jsonTabs.getJSONObject(index)
            val uriValue = item.optString("uri").takeIf {
                it.isNotBlank() && it != "null"
            }
            restoredTabs += EditorTab(
                title = item.optString("title", "untitled.py"),
                text = item.optString("text", ""),
                uri = uriValue?.let(Uri::parse),
                dirty = item.optBoolean("dirty", true),
                cursorPosition = item.optInt("cursor", 0),
                scrollY = item.optInt("scrollY", 0)
            )
        }

        Snapshot(
            tabs = restoredTabs,
            activeTabIndex = root.optInt("activeTab", 0),
            consoleText = root.optString("console", ""),
            savedAt = root.optLong("savedAt", 0L)
        )
    }.getOrNull()
}
