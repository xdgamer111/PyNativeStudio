package com.xdgamer.pynativestudio

import android.net.Uri

data class EditorTab(
    var title: String,
    var text: String = "",
    var uri: Uri? = null,
    var dirty: Boolean = false
)
