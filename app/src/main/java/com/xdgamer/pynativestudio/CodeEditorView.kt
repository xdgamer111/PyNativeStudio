package com.xdgamer.pynativestudio

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.util.ArrayDeque
import java.util.regex.Pattern

/** Lightweight native editor with line numbers and Python-aware editing behavior. */
class CodeEditorView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    private val lineNumbers = TextView(context)
    val editText = EditText(context)
    private val undo = ArrayDeque<String>()
    private val redo = ArrayDeque<String>()
    private var internalChange = false
    private var lastText = ""
    private var onChanged: ((String) -> Unit)? = null

    private val keywords = Pattern.compile("\\b(False|None|True|and|as|assert|async|await|break|case|class|continue|def|del|elif|else|except|finally|for|from|global|if|import|in|is|lambda|match|nonlocal|not|or|pass|raise|return|try|while|with|yield)\\b")
    private val builtins = Pattern.compile("\\b(abs|all|any|bin|bool|bytearray|bytes|callable|chr|classmethod|compile|complex|dict|dir|divmod|enumerate|eval|exec|filter|float|format|frozenset|getattr|globals|hasattr|hash|help|hex|id|input|int|isinstance|issubclass|iter|len|list|locals|map|max|memoryview|min|next|object|oct|open|ord|pow|print|property|range|repr|reversed|round|set|setattr|slice|sorted|staticmethod|str|sum|super|tuple|type|vars|zip|__import__)\\b")
    private val numbers = Pattern.compile("(?<![\\w.])(?:0[xX][0-9a-fA-F]+|0[bB][01]+|0[oO][0-7]+|\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)")
    private val declarations = Pattern.compile("(?m)(?<=\\bdef\\s)[A-Za-z_]\\w*|(?<=\\bclass\\s)[A-Za-z_]\\w*")
    private val operators = Pattern.compile("[+\\-*/%@&|^~<>]=?|==|!=|:=|//|\\*\\*")
    private val strings = Pattern.compile("(?s)(?:[rubfRUBF]{0,2})(\"\"\".*?\"\"\"|'''.*?'''|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')")
    private val comments = Pattern.compile("(?m)#.*$")

    init {
        orientation = HORIZONTAL
        lineNumbers.apply {
            gravity = Gravity.TOP or Gravity.END
            setPadding(10, 14, 10, 14)
            typeface = Typeface.MONOSPACE
            textSize = 14f
            alpha = .62f
            text = "1"
        }
        addView(lineNumbers, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        val scroller = HorizontalScrollView(context).apply { isFillViewport = true }
        editText.apply {
            gravity = Gravity.TOP or Gravity.START
            setPadding(12, 10, 24, 24)
            typeface = Typeface.MONOSPACE
            textSize = 15f
            setHorizontallyScrolling(true)
            isVerticalScrollBarEnabled = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        scroller.addView(editText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(scroller, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                if (!internalChange && s != null) lastText = s.toString()
            }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(e: Editable?) {
                if (internalChange || e == null) return
                if (lastText != e.toString()) {
                    undo.addLast(lastText)
                    while (undo.size > 100) undo.removeFirst()
                    redo.clear()
                }
                handlePairsAndIndent(e)
                updateLineNumbers(e)
                highlight(e)
                onChanged?.invoke(e.toString())
            }
        })
        editText.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_TAB && event.action == KeyEvent.ACTION_DOWN) {
                val p = editText.selectionStart.coerceAtLeast(0)
                editText.text.insert(p, "    ")
                true
            } else false
        }
        applyTheme(false)
    }

    fun setOnContentChanged(listener: (String) -> Unit) { onChanged = listener }
    fun setText(value: String) {
        internalChange = true
        editText.setText(value)
        editText.setSelection(value.length)
        undo.clear(); redo.clear(); lastText = value
        updateLineNumbers(editText.text); highlight(editText.text)
        internalChange = false
    }
    fun text(): String = editText.text.toString()
    fun setFontSize(sp: Float) { editText.textSize = sp; lineNumbers.textSize = sp - 1 }
    fun applyTheme(light: Boolean) {
        setBackgroundColor(if (light) Color.rgb(250,250,252) else Color.rgb(17,19,24))
        editText.setTextColor(if (light) Color.rgb(28,27,31) else Color.rgb(230,225,229))
        editText.setHintTextColor(if (light) Color.GRAY else Color.LTGRAY)
        lineNumbers.setTextColor(if (light) Color.DKGRAY else Color.LTGRAY)
        highlight(editText.text)
    }
    fun undo() { if (undo.isNotEmpty()) replaceFromHistory(undo.removeLast(), redo) }
    fun redo() { if (redo.isNotEmpty()) replaceFromHistory(redo.removeLast(), undo) }
    private fun replaceFromHistory(value: String, opposite: ArrayDeque<String>) {
        opposite.addLast(text())
        internalChange = true; editText.setText(value); editText.setSelection(value.length); internalChange = false
        updateLineNumbers(editText.text); highlight(editText.text); onChanged?.invoke(value)
    }

    private fun updateLineNumbers(e: Editable) {
        val count = e.count { it == '\n' } + 1
        lineNumbers.text = (1..count).joinToString("\n")
    }
    private fun handlePairsAndIndent(e: Editable) {
        val pos = editText.selectionStart
        if (pos <= 0 || pos > e.length) return
        val c = e[pos - 1]
        val close = when (c) { '(' -> ')'; '[' -> ']'; '{' -> '}'; '"' -> '"'; '\'' -> '\''; else -> null }
        if (close != null && (pos == e.length || e[pos] != close)) {
            internalChange = true; e.insert(pos, close.toString()); editText.setSelection(pos); internalChange = false
        } else if (c == '\n') {
            val before = e.substring(0, pos - 1)
            val line = before.substringAfterLast('\n')
            val indent = line.takeWhile { it == ' ' || it == '\t' } + if (line.trimEnd().endsWith(':')) "    " else ""
            if (indent.isNotEmpty()) { internalChange = true; e.insert(pos, indent); editText.setSelection(pos + indent.length); internalChange = false }
        }
    }

    private fun highlight(e: Editable) {
        val light = (editText.currentTextColor and 0xFFFFFF) < 0x808080
        e.getSpans(0, e.length, ForegroundColorSpan::class.java).forEach(e::removeSpan)
        fun span(pattern: Pattern, darkColor: Int, lightColor: Int) {
            val m = pattern.matcher(e)
            while (m.find()) e.setSpan(ForegroundColorSpan(if (light) lightColor else darkColor), m.start(), m.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        span(keywords, Color.rgb(198,120,221), Color.rgb(120,40,150))
        span(builtins, Color.rgb(86,182,194), Color.rgb(0,105,125))
        span(numbers, Color.rgb(181,206,168), Color.rgb(20,110,40))
        span(declarations, Color.rgb(220,220,170), Color.rgb(120,90,0))
        span(operators, Color.rgb(212,212,212), Color.rgb(70,70,70))
        span(strings, Color.rgb(206,145,120), Color.rgb(165,65,30))
        span(comments, Color.rgb(106,153,85), Color.rgb(40,125,45))
    }
}
