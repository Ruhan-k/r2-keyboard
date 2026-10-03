package com.example.furryreply

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout

/** A small, dependency-free keyboard. The service owns all text-field operations. */
class QwertyKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {
    var onText: ((String) -> Unit)? = null
    var onBackspace: (() -> Unit)? = null
    var onEnter: (() -> Unit)? = null
    var onSwitchKeyboard: (() -> Unit)? = null

    private enum class Page { LETTERS, SYMBOLS, MORE_SYMBOLS, NUMBERS }
    private enum class Shift { OFF, ONCE, LOCKED }

    private var page = Page.LETTERS
    private var shift = Shift.OFF
    private var lastShiftTap = 0L
    private var enterLabel = "↵"
    private var enterDescription = "New line"
    private var phoneInput = false
    private val repeatHandler = Handler(Looper.getMainLooper())
    private var repeatingDelete = false
    private val repeatDelete = object : Runnable {
        override fun run() {
            if (!repeatingDelete) return
            onBackspace?.invoke()
            repeatHandler.postDelayed(this, 65L)
        }
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#10141F"))
        setPadding(dp(3), dp(4), dp(3), dp(4))
        isFocusable = false
        buildKeys()
    }

    /** Uses metadata only. It never reads editor contents, including in password fields. */
    fun setEditor(info: EditorInfo?) {
        stopDeleteRepeat()
        shift = Shift.OFF
        lastShiftTap = 0L
        val inputClass = (info?.inputType ?: InputType.TYPE_CLASS_TEXT) and InputType.TYPE_MASK_CLASS
        phoneInput = inputClass == InputType.TYPE_CLASS_PHONE
        page = if (phoneInput || inputClass == InputType.TYPE_CLASS_NUMBER ||
            inputClass == InputType.TYPE_CLASS_DATETIME
        ) Page.NUMBERS else Page.LETTERS

        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val noEnterAction = ((info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        val label = if (noEnterAction) null else info?.actionLabel?.toString()?.takeIf { it.isNotBlank() }
        enterDescription = label ?: if (noEnterAction) "New line" else when (action) {
            EditorInfo.IME_ACTION_GO -> "Go"
            EditorInfo.IME_ACTION_SEARCH -> "Search"
            EditorInfo.IME_ACTION_SEND -> "Send"
            EditorInfo.IME_ACTION_NEXT -> "Next"
            EditorInfo.IME_ACTION_DONE -> "Done"
            EditorInfo.IME_ACTION_PREVIOUS -> "Previous"
            else -> "New line"
        }
        enterLabel = when (enterDescription) {
            "New line" -> "↵"
            "Previous" -> "Prev"
            else -> enterDescription
        }
        buildKeys()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        buildKeys()
    }

    override fun onDetachedFromWindow() {
        stopDeleteRepeat()
        super.onDetachedFromWindow()
    }

    private fun buildKeys() {
        stopDeleteRepeat()
        removeAllViews()
        when (page) {
            Page.LETTERS -> buildLetters()
            Page.SYMBOLS, Page.MORE_SYMBOLS -> buildSymbols()
            Page.NUMBERS -> buildNumbers()
        }
    }

    private fun buildLetters() {
        row { "qwertyuiop".forEach { characterKey(it.toString()) } }
        row {
            spacer(0.5f)
            "asdfghjkl".forEach { characterKey(it.toString()) }
            spacer(0.5f)
        }
        row {
            actionKey(
                if (shift == Shift.LOCKED) "⇪" else "⇧",
                when (shift) {
                    Shift.OFF -> "Shift. Double tap for caps lock"
                    Shift.ONCE -> "Shift on. Double tap for caps lock"
                    Shift.LOCKED -> "Caps lock on. Tap to turn off"
                },
                weight = 1.5f,
                accent = shift != Shift.OFF
            ) { toggleShift() }
            "zxcvbnm".forEach { characterKey(it.toString()) }
            deleteKey(1.5f)
        }
        bottomRow()
    }

    private fun buildSymbols() {
        row {
            (if (page == Page.SYMBOLS) "1234567890" else "~`|•√π÷×¶∆")
                .forEach { characterKey(it.toString()) }
        }
        row {
            (if (page == Page.SYMBOLS) "@#£_&-+()/" else "$€¥^°={}[]")
                .forEach { characterKey(it.toString()) }
        }
        row {
            actionKey(
                if (page == Page.SYMBOLS) "=\\<" else "?123",
                if (page == Page.SYMBOLS) "More symbols" else "Numbers and symbols",
                weight = 1.5f
            ) {
                page = if (page == Page.SYMBOLS) Page.MORE_SYMBOLS else Page.SYMBOLS
                buildKeys()
            }
            (if (page == Page.SYMBOLS) listOf("*", "\"", "'", ":", ";", "!", "?")
            else listOf("\\", "<", ">", "%", "©", "®", "™")).forEach { characterKey(it) }
            deleteKey(1.5f)
        }
        bottomRow()
    }

    private fun buildNumbers() {
        row {
            listOf("1", "2", "3").forEach { characterKey(it) }
            deleteKey()
        }
        row {
            listOf("4", "5", "6").forEach { characterKey(it) }
            characterKey(if (phoneInput) "*" else "-")
        }
        row {
            listOf("7", "8", "9").forEach { characterKey(it) }
            characterKey(if (phoneInput) "#" else ".")
        }
        row {
            actionKey("ABC", "Letters") { page = Page.LETTERS; buildKeys() }
            characterKey("0")
            actionKey("◎", "Switch keyboard") { onSwitchKeyboard?.invoke() }
            actionKey(enterLabel, enterDescription, accent = true) { onEnter?.invoke() }
        }
    }

    private fun bottomRow() {
        row {
            actionKey(if (page == Page.LETTERS) "?123" else "ABC", "Switch letters and symbols", 1.35f) {
                page = if (page == Page.LETTERS) Page.SYMBOLS else Page.LETTERS
                buildKeys()
            }
            actionKey("◎", "Switch keyboard", 0.95f) { onSwitchKeyboard?.invoke() }
            characterKey(",", 0.8f)
            actionKey("space", "Space", 4.35f, muted = true) { insertText(" ") }
            characterKey(".", 0.8f)
            actionKey(enterLabel, enterDescription, 1.75f, accent = true) { onEnter?.invoke() }
        }
    }

    private fun toggleShift() {
        val now = SystemClock.uptimeMillis()
        shift = when {
            shift == Shift.LOCKED -> Shift.OFF
            shift == Shift.ONCE && lastShiftTap != 0L && now - lastShiftTap <= 400L -> Shift.LOCKED
            shift == Shift.OFF -> Shift.ONCE
            else -> Shift.OFF
        }
        lastShiftTap = now
        buildKeys()
    }

    private fun insertText(value: String) {
        onText?.invoke(value)
        if (shift == Shift.ONCE && page == Page.LETTERS && value.any { it.isLetter() }) {
            shift = Shift.OFF
            lastShiftTap = 0L
            buildKeys()
        }
    }

    private fun row(addKeys: LinearLayout.() -> Unit) {
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(rowHeight()))
            addKeys()
        })
    }

    private fun LinearLayout.spacer(weight: Float) {
        addView(View(context), LayoutParams(0, 1, weight))
    }

    private fun LinearLayout.characterKey(value: String, weight: Float = 1f) {
        val text = if (page == Page.LETTERS && shift != Shift.OFF) value.uppercase() else value
        actionKey(text, text, weight, textSizeSp = 21f) { insertText(text) }
    }

    private fun LinearLayout.deleteKey(weight: Float = 1f) {
        val button = actionKey("⌫", "Backspace. Hold to keep deleting", weight, textSizeSp = 22f) {
            onBackspace?.invoke()
        }
        button.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    stopDeleteRepeat()
                    view.isPressed = true
                    // performClick also makes this custom repeat gesture accessible.
                    view.performClick()
                    repeatingDelete = true
                    repeatHandler.postDelayed(repeatDelete, 400L)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.x < 0 || event.x > view.width || event.y < 0 || event.y > view.height) {
                        view.isPressed = false
                        stopDeleteRepeat()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    stopDeleteRepeat()
                }
            }
            true
        }
    }

    private fun LinearLayout.actionKey(
        label: String,
        description: String,
        weight: Float = 1f,
        accent: Boolean = false,
        muted: Boolean = false,
        textSizeSp: Float = 14f,
        action: () -> Unit
    ): Button {
        return Button(context).apply {
            text = label
            contentDescription = description
            isAllCaps = false
            textSize = textSizeSp
            typeface = Typeface.create("sans-serif", if (accent) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(Color.parseColor(if (accent) "#171126" else if (muted) "#A3ADC2" else "#F2F4FC"))
            gravity = Gravity.CENTER
            includeFontPadding = false
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            isFocusable = false
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(1), 0, dp(1), 0)
            backgroundTintList = null
            background = keyBackground(accent)
            stateListAnimator = null
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, weight).apply {
                setMargins(dp(2), dp(3), dp(2), dp(3))
            }
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                action()
            }
            this@actionKey.addView(this)
        }
    }

    private fun keyBackground(accent: Boolean): RippleDrawable {
        val shape = GradientDrawable().apply {
            cornerRadius = dp(9).toFloat()
            setColor(Color.parseColor(if (accent) "#AFA0FF" else "#252C3C"))
            setStroke(dp(1), Color.parseColor(if (accent) "#C1B6FF" else "#30394D"))
        }
        return RippleDrawable(
            ColorStateList.valueOf(Color.parseColor(if (accent) "#D9D1FF" else "#586784")),
            shape,
            null
        )
    }

    private fun stopDeleteRepeat() {
        repeatingDelete = false
        repeatHandler.removeCallbacks(repeatDelete)
    }

    private fun rowHeight() = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 40 else 50
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
