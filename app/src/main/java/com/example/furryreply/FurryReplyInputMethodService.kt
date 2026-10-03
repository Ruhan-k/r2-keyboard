package com.example.furryreply

import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.InterruptedIOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FurryReplyInputMethodService : InputMethodService() {
    private val databaseExecutor = Executors.newSingleThreadExecutor()
    private val database by lazy { FurryReplyDatabase.get(this) }
    private val preferences by lazy { getSharedPreferences("keyboard_preferences", MODE_PRIVATE) }

    private var activeCall: Call<ReplyResponse>? = null
    private var activeClassifyCall: Call<ConversationStateClassificationResponse>? = null
    private var isLoadingContext = false
    private var generationToken = 0
    private var copiedMessageText = ""
    private var manualContextText = ""
    private var selectedClientId: Long? = null
    private var suggestionClientId: Long? = null
    private var currentSuggestions = emptyList<String>()
    private var selectedFeedbackId: Long? = null
    private var selectedReplyText = ""
    private var selectedIntent = "AUTO"
    private var selectedModifier = "NONE"
    private var currentUserMeaning: String? = null
    private var clients = emptyList<Client>()
    private var recentClients = emptyList<Client>()
    private var hostPackageName = ""
    private var clientLoadToken = 0
    private var localEditor: EditText? = null
    private var selectionStart = -1
    private var selectionEnd = -1
    @Volatile private var destroyed = false
    private val memoryCalls = java.util.concurrent.CopyOnWriteArraySet<Call<*>>()
    private val extractingClients = mutableSetOf<Long>()
    private val summarizingClients = mutableSetOf<Long>()
    private lateinit var keyboard: View
    private lateinit var copiedMessage: TextView
    private lateinit var messageSource: TextView
    private lateinit var manualContext: TextView
    private lateinit var clientPickerButton: Button
    private lateinit var clientStatus: TextView
    private lateinit var commissionStatus: TextView
    private lateinit var generateButton: Button
    private lateinit var statusText: TextView
    private lateinit var suggestionButtons: List<Button>
    private lateinit var qwerty: QwertyKeyboardView
    private lateinit var normalPanel: View
    private lateinit var auxPanel: LinearLayout

    override fun onCreateInputView(): View {
        cancelGeneration()
        copiedMessageText = ""
        keyboard = layoutInflater.inflate(R.layout.keyboard_view, null)
        keyboard.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(0, dp(8), 0, dp(4) + insets.systemWindowInsetBottom)
            insets
        }
        keyboard.requestApplyInsets()
        window.window?.let { imeWindow ->
            imeWindow.navigationBarColor = Color.parseColor("#10141F")
            imeWindow.decorView.systemUiVisibility = imeWindow.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        }
        normalPanel = keyboard.findViewById(R.id.normal_panel)
        auxPanel = keyboard.findViewById(R.id.aux_panel)
        qwerty = keyboard.findViewById(R.id.qwerty_keyboard)
        qwerty.onText = ::typeText
        qwerty.onBackspace = ::backspace
        qwerty.onEnter = ::enter
        qwerty.onSwitchKeyboard = { getSystemService(InputMethodManager::class.java).showInputMethodPicker() }
        qwerty.setEditor(currentInputEditorInfo)
        val topScroll = keyboard.findViewById<ScrollView>(R.id.top_panel_scroll)
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            topScroll.layoutParams.height = dp(108)
        }
        copiedMessage = keyboard.findViewById(R.id.copied_message)
        messageSource = keyboard.findViewById(R.id.message_source)
        manualContext = keyboard.findViewById(R.id.manual_context)
        clientPickerButton = keyboard.findViewById(R.id.client_picker_button)
        clientStatus = keyboard.findViewById(R.id.client_status)
        commissionStatus = keyboard.findViewById(R.id.commission_status)
        generateButton = keyboard.findViewById(R.id.generate_replies_button)
        statusText = keyboard.findViewById(R.id.generation_status)
        statusText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                statusText.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        suggestionButtons = listOf(R.id.suggestion_one_button, R.id.suggestion_two_button, R.id.suggestion_three_button)
            .map { keyboard.findViewById<Button>(it) }

        hostPackageName = currentInputEditorInfo?.packageName.orEmpty()
        clientPickerButton.setOnClickListener { showClientPicker() }
        keyboard.findViewById<Button>(R.id.new_conversation_button).setOnClickListener {
            clearForClientChange();
        updateGenerateButton(); showClientPicker()
        }
        keyboard.findViewById<Button>(R.id.more_button).setOnClickListener { showMoreActions() }
        keyboard.findViewById<Button>(R.id.add_chat_context_button).setOnClickListener { showAddChatContextDialog() }
        keyboard.findViewById<Button>(R.id.use_clipboard_button).setOnClickListener { useClipboard() }
        keyboard.findViewById<Button>(R.id.use_current_context_button).setOnClickListener { useCurrentContext() }
        
        val intents = listOf("AUTO", "CHILL", "FUNNY", "CUTE", "DRY", "FLIRTY", "ARTIST")
        val intentButtons = intents.map { intent ->
            keyboard.findViewById<Button>(resources.getIdentifier("intent_${intent.lowercase()}", "id", packageName))
        }
        selectedIntent = preferences.getString("selected_intent", "AUTO") ?: "AUTO"
        
        fun updateIntentUI() {
            intentButtons.forEachIndexed { i, btn ->
                if (intents[i] == selectedIntent) {
                    btn.setBackgroundResource(R.drawable.fr_primary)
                    btn.setTextColor(Color.WHITE)
                } else {
                    btn.setBackgroundResource(R.drawable.fr_control)
                    btn.setTextColor(Color.parseColor("#F2F4FC"))
                }
            }
        }
        
        intentButtons.forEachIndexed { i, btn ->
            btn.setOnClickListener {
                selectedIntent = intents[i]
                preferences.edit().putString("selected_intent", selectedIntent).apply()
                updateIntentUI()
            }
        }
        updateIntentUI()

        val adjustButton = keyboard.findViewById<Button>(R.id.adjust_modifier_button)
        selectedModifier = preferences.getString("selected_modifier", "NONE") ?: "NONE"
        
        fun updateModifierUI() {
            if (selectedModifier == "NONE") {
                adjustButton.text = "Adjust"
                adjustButton.setTextColor(Color.parseColor("#BBAEFF"))
                adjustButton.setBackgroundResource(R.drawable.fr_control)
            } else {
                adjustButton.text = selectedModifier.replace("_", " ")
                adjustButton.setTextColor(Color.WHITE)
                adjustButton.setBackgroundResource(R.drawable.fr_primary)
            }
        }

        adjustButton.setOnClickListener {
            val popup = android.widget.PopupMenu(this, adjustButton)
            val modifiers = listOf("NONE", "MORE_PLAYFUL", "MORE_CHAOTIC", "MORE_DIRECT", "MORE_FLIRTY", "LESS_FLIRTY", "SHORTER", "LONGER", "SOFTER", "BOLDER")
            modifiers.forEachIndexed { index, mod ->
                popup.menu.add(0, index, index, mod.replace("_", " "))
            }
            popup.setOnMenuItemClickListener { item ->
                selectedModifier = modifiers[item.itemId]
                preferences.edit().putString("selected_modifier", selectedModifier).apply()
                updateModifierUI()
                true
            }
            popup.show()
        }
        val whatIMeanIndicator = keyboard.findViewById<View>(R.id.what_i_mean_indicator)
        val whatIMeanText = keyboard.findViewById<TextView>(R.id.what_i_mean_text)
        val whatIMeanClear = keyboard.findViewById<Button>(R.id.what_i_mean_clear)
        val whatIMeanButton = keyboard.findViewById<Button>(R.id.what_i_mean_button)

        fun updateWhatIMeanUI() {
            if (currentUserMeaning.isNullOrBlank()) {
                whatIMeanIndicator.visibility = View.GONE
            } else {
                whatIMeanIndicator.visibility = View.VISIBLE
                whatIMeanText.text = "Intent note: ${currentUserMeaning}"
            }
        }

        whatIMeanButton.setOnClickListener {
            val body = beginPanel("What I Mean")
            body.addView(panelText("What do you want to say?", true))
            val input = localInput("e.g. tell them im free now", currentUserMeaning ?: "", multiline = true)
            body.addView(input)
            body.addView(panelButton("Use") {
                currentUserMeaning = input.text.toString().trim().ifEmpty { null }
                updateWhatIMeanUI()
                closePanel()
            })
            body.addView(panelButton("Cancel") { closePanel() })
        }

        whatIMeanClear.setOnClickListener {
            currentUserMeaning = null
            updateWhatIMeanUI()
        }
        updateWhatIMeanUI()

        suggestionButtons.forEachIndexed { index, button ->
            button.setOnClickListener {
                val reply = button.text.toString()
                suggestionClientId?.let { clientId ->
                    if (reply.isNotBlank() && clientId == selectedClientId && currentInputConnection?.commitText(reply, 1) == true) {
                        
                        saveReplyFeedback(clientId, reply, index + 1)
                        statusText.text = "Reply inserted · keep typing to edit"
                    }
                }
            }
        }
        generateButton.setOnClickListener {
            if (activeCall != null || isLoadingContext) {
                ++generationToken
                cancelGeneration()
        updateGenerateButton()
                statusText.setText(R.string.generation_cancelled)
            } else generateReplies()
        }
        loadClients()
        updateContextCaptureButton()
        updateGenerateButton()
        return keyboard
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        hostPackageName = info?.packageName.orEmpty()
        selectionStart = info?.initialSelStart ?: -1
        selectionEnd = info?.initialSelEnd ?: -1
        selectedClientId = null
        clientLoadToken++
        clearForClientChange()
        if (::keyboard.isInitialized) {
            closePanel()
            qwerty.setEditor(info)
            updateContextCaptureButton()
            loadClients()
        }
    }

    override fun onEvaluateFullscreenMode() = false

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        selectionStart = newSelStart
        selectionEnd = newSelEnd
    }

    private fun typeText(text: String) {
        val editor = localEditor
        if (editor == null) currentInputConnection?.commitText(text, 1)
        else {
            val start = editor.selectionStart.coerceAtLeast(0)
            val end = editor.selectionEnd.coerceAtLeast(0)
            editor.text.replace(minOf(start, end), maxOf(start, end), text)
        }
    }

    private fun backspace() {
        val editor = localEditor
        if (editor != null) {
            val start = minOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0)
            val end = maxOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0)
            if (start != end) editor.text.delete(start, end)
            else if (start > 0) editor.text.delete(Character.offsetByCodePoints(editor.text, start, -1), start)
        } else {
            val connection = currentInputConnection ?: return
            if (selectionStart >= 0 && selectionStart != selectionEnd) connection.commitText("", 1)
            else if (!connection.deleteSurroundingTextInCodePoints(1, 0)) sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }
    }

    private fun enter() {
        if (localEditor != null) { if (localEditor?.maxLines != 1) typeText("\n"); return }
        val info = currentInputEditorInfo
        if (info?.actionLabel != null && info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION == 0) {
            currentInputConnection?.performEditorAction(info.actionId)
        } else if (!sendDefaultEditorAction(true)) currentInputConnection?.commitText("\n", 1)
    }

    private fun loadClients(selectClientId: Long? = null) {
        val host = hostPackageName
        val token = ++clientLoadToken
        databaseExecutor.execute {
            val loaded = database.clientDao().getAll()
            val last = host.takeIf(String::isNotBlank)?.let { database.appClientUsageDao().getLastClientId(it) }
            val recent = host.takeIf(String::isNotBlank)?.let { database.appClientUsageDao().getRecentClients(it, 8) }.orEmpty()
            keyboard.post {
                if (destroyed || token != clientLoadToken || host != hostPackageName) return@post
                clients = loaded
                recentClients = recent
                val client = loaded.firstOrNull { it.id == (selectClientId ?: last) }
                if (client != null) setActiveClient(client, rememberForHost = false)
                else {
                    selectedClientId = null
                    clearForClientChange()
                    clientPickerButton.text = "Choose client  ▾"
                    clientStatus.text = "No client selected"
        updateGenerateButton()
                }
            }
        }
    }

    private fun setActiveClient(client: Client, rememberForHost: Boolean) {
        ++clientLoadToken
        if (selectedClientId != client.id) {
            selectedClientId = client.id
            preferences.edit().putLong("selected_client_id", client.id).apply()
            clearForClientChange()
        }
        clientPickerButton.text = "${client.displayName}  ▾"
        clientPickerButton.contentDescription = "Active client: ${clientLabel(client)}. Choose another client"
        clientStatus.text = "${hostAppName()} » ${client.username.ifBlank { client.platform }.ifBlank { "Client memory active" }}"
        
        commissionStatus.visibility = View.GONE
        databaseExecutor.execute {
            val comm = database.commissionProfileDao().getForClient(client.id)
            if (comm != null && comm.status !in listOf("NONE", "CANCELLED", "COMPLETED")) {
                val emoji = when (comm.status) {
                    "INTERESTED", "QUOTE_SENT", "WAITING" -> "💰"
                    "ACCEPTED", "PAID", "IN_PROGRESS", "REVISION" -> "🎨"
                    else -> "📝"
                }
                val text = "$emoji ${comm.status}"
                keyboard.post {
                    if (selectedClientId == client.id) {
                        commissionStatus.text = text
                        commissionStatus.visibility = View.VISIBLE
                    }
                }
            } else if (comm != null && comm.status == "COMPLETED") {
                keyboard.post {
                    if (selectedClientId == client.id) {
                        commissionStatus.text = "✅ COMPLETED"
                        commissionStatus.visibility = View.VISIBLE
                    }
                }
            }
        }
        
        updateGenerateButton()
        val host = hostPackageName
        if (rememberForHost && host.isNotBlank()) {
            recentClients = (listOf(client) + recentClients.filter { it.id != client.id }).take(8)
            databaseExecutor.execute {
                if (database.clientDao().getById(client.id) != null)
                    database.appClientUsageDao().upsert(AppClientUsage(host, client.id, System.currentTimeMillis()))
            }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun panelText(value: String, small: Boolean = false) = TextView(this).apply {
        text = value
        textSize = if (small) 12f else 16f
        setTextColor(Color.parseColor(if (small) "#A3ADC2" else "#F2F4FC"))
        setPadding(dp(4), dp(8), dp(4), dp(6))
    }

    private fun panelButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 14f
        isFocusable = false
        minHeight = dp(44); minimumWidth = 0
        setTextColor(Color.parseColor("#F2F4FC"))
        background = getDrawable(R.drawable.fr_control)
        setPadding(dp(12), dp(4), dp(12), dp(4))
        layoutParams = LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(4) }
        setOnClickListener { action() }
    }

    private fun beginPanel(title: String): LinearLayout {
        localEditor = null
        normalPanel.visibility = View.GONE
        auxPanel.removeAllViews()
        auxPanel.visibility = View.VISIBLE
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(panelText(title), LinearLayout.LayoutParams(0, dp(44), 1f))
        header.addView(panelButton("Done") { closePanel() }, LinearLayout.LayoutParams(dp(64), dp(44)))
        auxPanel.addView(header)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(body); isFillViewport = true }
        auxPanel.addView(scroll, LinearLayout.LayoutParams(-1, dp(176)))
        qwerty.setEditor(null)
        keyboard.findViewById<ScrollView>(R.id.top_panel_scroll).scrollTo(0, 0)
        return body
    }

    private fun closePanel() {
        localEditor?.clearFocus()
        localEditor = null
        if (!::auxPanel.isInitialized) return
        auxPanel.visibility = View.GONE
        auxPanel.removeAllViews()
        normalPanel.visibility = View.VISIBLE
        qwerty.setEditor(currentInputEditorInfo)
    }

    private fun localInput(hintText: String, value: String = "", multiline: Boolean = false) = EditText(this).apply {
        hint = hintText; setText(value); textSize = 15f
        setTextColor(Color.parseColor("#F2F4FC")); setHintTextColor(Color.parseColor("#A3ADC2"))
        background = getDrawable(R.drawable.fr_surface)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        showSoftInputOnFocus = false
        inputType = InputType.TYPE_CLASS_TEXT or if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
        setSingleLine(!multiline)
        minLines = if (multiline) 2 else 1
        maxLines = if (multiline) 5 else 1
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
        setOnFocusChangeListener { _, focused -> if (focused) localEditor = this }
        setOnClickListener { localEditor = this }
    }

    private fun showClientPicker() {
        val body = beginPanel("Choose client")
        val search = localInput("Search name, username, platform")
        body.addView(search)
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(results)
        fun updateResults(query: String) {
            results.removeAllViews()
            val matching = clients.filter { client -> listOf(client.displayName, client.username, client.platform)
                .any { it.contains(query.trim(), ignoreCase = true) } }
            val used = mutableSetOf<Long>()
            fun addClient(client: Client) {
                if (!used.add(client.id)) return
                results.addView(panelButton(clientLabel(client)) { setActiveClient(client, true); closePanel() })
            }
            selectedClientId?.let { id -> matching.firstOrNull { it.id == id } }?.let {
                results.addView(panelText("CURRENT", true)); addClient(it)
            }
            val recent = recentClients.filter { recent -> matching.any { it.id == recent.id } && recent.id !in used }
            if (recent.isNotEmpty()) results.addView(panelText("RECENT IN ${hostAppName().uppercase()}", true))
            recent.forEach(::addClient)
            if (matching.any { it.id !in used }) results.addView(panelText("ALL CLIENTS", true))
            matching.forEach(::addClient)
            if (matching.isEmpty()) results.addView(panelText("No matching clients", true))
            results.addView(panelButton("+ New Client") { showCreateClientDialog() })
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updateResults(s?.toString().orEmpty())
            override fun afterTextChanged(s: Editable?) = Unit
        })
        updateResults("")
        search.requestFocus()
        localEditor = search
    }

    private fun showMoreActions() {
        val body = beginPanel("Reply tools")
        body.addView(panelButton(if (manualContextText.isBlank()) "Add context" else "Edit context") { showManualContextDialog() })
        body.addView(panelButton("Clear captured message") { clearForClientChange();
        updateGenerateButton(); closePanel() })
        body.addView(panelButton("Save edited reply for style") { showSaveEditedReplyDialog() }.apply { isEnabled = selectedFeedbackId != null })
        body.addView(panelButton("Clients & learned style") {
            closePanel()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })
        body.addView(panelButton("Switch keyboard") { closePanel(); getSystemService(InputMethodManager::class.java).showInputMethodPicker() })
    }

    private fun hostAppName() = when (hostPackageName) {
        "com.example.furryreply" -> "R2 Keyboard"
        "com.instagram.android" -> "Instagram"
        "com.discord" -> "Discord"
        "com.whatsapp" -> "WhatsApp"
        "org.telegram.messenger" -> "Telegram"
        else -> hostPackageName.ifBlank { getString(R.string.current_app) }
    }

    private fun showCreateClientDialog() {
        val host = hostPackageName
        val body = beginPanel("New client")
        val name = localInput("Display name")
        val platform = localInput("Platform (optional)", if (host.isNotBlank()) hostAppName() else "")
        val username = localInput("Username (optional)")
        body.addView(name); body.addView(platform); body.addView(username)
        val save = panelButton("Create client") {}
        body.addView(save)
        save.setOnClickListener {
            val displayName = name.text.toString().trim()
            if (displayName.isBlank()) { name.error = getString(R.string.client_name_required); return@setOnClickListener }
            val platformText = platform.text.toString().trim()
            val usernameText = username.text.toString().trim()
            save.isEnabled = false
            databaseExecutor.execute {
                val client = Client(displayName = displayName, platform = platformText, username = usernameText, createdAt = System.currentTimeMillis())
                val id = database.clientDao().insert(client)
                if (host.isNotBlank()) database.appClientUsageDao().upsert(AppClientUsage(host, id, System.currentTimeMillis()))
                keyboard.post {
                    if (destroyed || host != hostPackageName) return@post
                    clients = clients + client.copy(id = id)
                    setActiveClient(client.copy(id = id), true)
                    closePanel()
                }
            }
        }
        name.requestFocus(); localEditor = name
    }

    private fun showAddChatContextDialog() {
        val clientId = selectedClientId ?: run { clientStatus.setText(R.string.no_client_selected); return }
        val body = beginPanel("Add Chat Context")
        val input = localInput("Paste recent chat here...", "", multiline = true)
        body.addView(input)
        
        var mode = "MIXED"
        val modes = listOf("INCOMING", "OUTGOING", "MIXED")
        val modeLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val modeButtons = modes.map { m ->
            Button(this).apply {
                text = m
                layoutParams = LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(0, 0, 0, 0)
                textSize = 10f
                setOnClickListener {
                    mode = m
                    val parentGroup = parent as android.view.ViewGroup
                    for (i in 0 until parentGroup.childCount) {
                        val child = parentGroup.getChildAt(i)
                        if (child is Button) {
                            child.setBackgroundResource(if (child.text == mode) R.drawable.fr_primary else R.drawable.fr_control)
                            child.setTextColor(if (child.text == mode) Color.WHITE else Color.parseColor("#F2F4FC"))
                        }
                    }
                }
            }
        }
        modeButtons.forEach {
            it.setBackgroundResource(if (it.text == mode) R.drawable.fr_primary else R.drawable.fr_control)
            it.setTextColor(if (it.text == mode) Color.WHITE else Color.parseColor("#F2F4FC"))
            modeLayout.addView(it)
        }
        body.addView(modeLayout)
        
        body.addView(panelButton("Preview Import") {
            val textValue = input.text.toString().trim()
            if (textValue.isEmpty()) return@panelButton
            previewContextImport(clientId, textValue, mode)
        })
        body.addView(panelButton("Cancel") { closePanel() })
    }

    private fun previewContextImport(clientId: Long, rawText: String, mode: String) {
        val body = beginPanel("Preview Import")
        val lines = rawText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val parsedMessages = mutableListOf<Pair<String, String>>()
        var hasAmbiguous = false
        
        if (mode == "INCOMING" || mode == "OUTGOING") {
            for (line in lines) parsedMessages.add(mode to line)
        } else {
            var currentSender = ""
            var currentContent = ""
            for (line in lines) {
                val l = line.lowercase()
                if (l.startsWith("them:") || l.startsWith("client:")) {
                    if (currentSender.isNotEmpty()) parsedMessages.add(currentSender to currentContent.trim())
                    currentSender = "INCOMING"
                    currentContent = line.substringAfter(":").trim()
                } else if (l.startsWith("me:")) {
                    if (currentSender.isNotEmpty()) parsedMessages.add(currentSender to currentContent.trim())
                    currentSender = "OUTGOING"
                    currentContent = line.substringAfter(":").trim()
                } else {
                    if (currentSender.isEmpty()) {
                        hasAmbiguous = true
                        currentSender = "INCOMING"
                    }
                    currentContent += "\n" + line
                }
            }
            if (currentSender.isNotEmpty()) {
                parsedMessages.add(currentSender to currentContent.trim())
            }
        }
        
        if (hasAmbiguous) {
            body.addView(panelText("Warning: some lines didn't have 'me:' or 'them:' prefix and might be parsed incorrectly.", true))
        }
        
        val previewContainer = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(LinearLayout(this@FurryReplyInputMethodService).apply {
                orientation = LinearLayout.VERTICAL
                parsedMessages.take(10).forEach { (sender, content) ->
                    val preview = TextView(this@FurryReplyInputMethodService).apply {
                        this.text = "${if (sender == "INCOMING") "Incoming" else "Outgoing"}:\n\"$content\""
                        setTextColor(Color.WHITE)
                        setPadding(0, 8, 0, 8)
                    }
                    addView(preview)
                }
                if (parsedMessages.size > 10) {
                    addView(TextView(this@FurryReplyInputMethodService).apply {
                        this.text = "... and ${parsedMessages.size - 10} more"
                        setTextColor(Color.GRAY)
                    })
                }
            })
        }
        body.addView(previewContainer)
        
        body.addView(panelButton("Save ${parsedMessages.size} messages") {
            closePanel()
            databaseExecutor.execute {
                val recentExisting = database.messageDao().getRecentForClient(clientId, 20).reversed()
                var newlyAddedCount = 0
                val now = System.currentTimeMillis()
                for (parsed in parsedMessages) {
                    val isDuplicate = recentExisting.any { it.senderType == parsed.first && it.content == parsed.second }
                    if (!isDuplicate) {
                        database.messageDao().insert(Message(
                            clientId = clientId,
                            senderType = parsed.first,
                            content = parsed.second,
                            timestamp = now + newlyAddedCount
                        ))
                        newlyAddedCount++
                    }
                }
                if (newlyAddedCount > 0) {
                    extractMemoriesWhenReady(clientId)
                }
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    statusText.text = "Imported $newlyAddedCount messages (skipped duplicates)"
        updateGenerateButton()
                }
            }
        })
        body.addView(panelButton("Cancel") { closePanel() })
    }

    private fun useClipboard() {
        val clientId = selectedClientId ?: run { clientStatus.setText(R.string.no_client_selected); return }
        clearForClientChange()
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString().orEmpty() else ""
        setIncomingMessage(clientId, text, R.string.source_clipboard)
        updateGenerateButton()
    }

    private fun useCurrentContext() {
        val clientId = selectedClientId ?: run { clientStatus.setText(R.string.no_client_selected); return }
        if (isSecureInputField()) {
            statusText.setText(R.string.secure_context_unavailable)
            updateContextCaptureButton()
            return
        }
        val connection = currentInputConnection
        val selected = connection?.getSelectedText(0)?.toString().orEmpty()
        if (selected.isNotBlank()) {
            clearForClientChange()
            setIncomingMessage(clientId, selected, R.string.source_selected_text)
        updateGenerateButton()
            return
        }
        val beforeCursor = connection?.getTextBeforeCursor(10000, 0)?.toString().orEmpty()
        if (beforeCursor.isNotBlank()) {
            clearForClientChange()
            setIncomingMessage(clientId, beforeCursor, R.string.source_current_field)
        updateGenerateButton()
            return
        }
        useClipboard()
    }

    private fun setIncomingMessage(clientId: Long, text: String, sourceRes: Int) {
        copiedMessageText = text.trim().take(10000)
        messageSource.setText(sourceRes)
        copiedMessage.text = if (copiedMessageText.isEmpty()) getString(R.string.no_copied_message) else copiedMessageText
        if (copiedMessageText.isNotEmpty()) saveMessage(clientId, SenderType.INCOMING, copiedMessageText)
        keyboard.findViewById<View>(R.id.context_preview).visibility = View.VISIBLE
    }

    private fun updateContextCaptureButton() {
        if (!::keyboard.isInitialized) return
        val button = keyboard.findViewById<Button>(R.id.use_current_context_button)
        val secure = isSecureInputField()
        button.isEnabled = !secure
        button.text = if (secure) "Secure field" else "Context"
        button.contentDescription = if (secure) "Context capture disabled in secure field" else getString(R.string.use_current_context)
    }

    private fun isSecureInputField(): Boolean {
        val inputType = currentInputEditorInfo?.inputType ?: return true
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    private fun showManualContextDialog() {
        val body = beginPanel("Extra context")
        body.addView(panelText("Explain the backstory in any language.", true))
        val input = localInput(getString(R.string.manual_context_hint), manualContextText, multiline = true)
        body.addView(input)
        body.addView(panelButton("Save context") {
            val value = input.text.toString().trim()
            if (value.length > 10000) input.error = getString(R.string.context_too_long)
            else {
                manualContextText = value
                manualContext.text = value
                manualContext.visibility = if (value.isEmpty()) View.GONE else View.VISIBLE
                generationToken++; cancelGeneration(); resetReplies()
                statusText.text = "";
        updateGenerateButton(); closePanel()
            }
        })
        input.requestFocus(); localEditor = input
    }

    private fun generateReplies() {
        val clientId = selectedClientId ?: return
        val message = copiedMessageText
        if (message.isBlank() || activeCall != null || isLoadingContext) return
        resetReplies()
        if (message.length > 10000) { statusText.setText(R.string.message_too_long); return }
        isLoadingContext = true
        val requestToken = ++generationToken
        statusText.setText(R.string.loading_client_context)
        updateGenerateButton()
        databaseExecutor.execute {
            try {
                val client = database.clientDao().getById(clientId)
                val recent = database.messageDao().getRecentForClient(clientId, 14).asReversed()
                val memories = database.clientMemoryDao().getActiveForClient(clientId)
                val now = System.currentTimeMillis()
                val validMemories = memories.filter { m ->
                    if (m.category == "TEMPORARY" && (now - m.createdAt) > 86400000L) { // 24 hours
                        database.clientMemoryDao().update(m.copy(isActive = false, updatedAt = now))
                        false
                    } else {
                        true
                    }
                }.sortedByDescending { it.importance }.take(12)
                val summary = database.conversationSummaryDao().getForClient(clientId)
                val ownerStyle = database.styleProfileDao().get()?.summary.orEmpty()
                val commissionEntity = database.commissionProfileDao().getForClient(clientId)
                val commission = commissionEntity?.let {
                    CommissionProfileDto(
                        status = it.status,
                        commissionType = it.commissionType,
                        quotedPrice = it.quotedPrice,
                        currency = it.currency,
                        clientBudget = it.clientBudget,
                        subjectDescription = it.subjectDescription,
                        referenceNotes = it.referenceNotes,
                        deadline = it.deadline,
                        paymentStatus = it.paymentStatus,
                        revisionStatus = it.revisionStatus,
                        notes = it.notes
                    )
                }
                val conversationStateSettings = database.conversationStateFor(clientId)
                val recentMessages = recent.map { ConversationMessage(it.senderType, it.content, it.timestamp) }
                val memoryContext = validMemories.map { MemoryContext(it.key, it.value, it.importance, it.category) }
                keyboard.post {
                    if (requestToken != generationToken || clientId != selectedClientId) return@post
                    isLoadingContext = false
                    beginReplyGeneration(
                        requestToken = requestToken,
                        clientId = clientId,
                        client = client,
                        message = message,
                        recentMessages = recentMessages,
                        memories = memoryContext,
                        summary = summary?.content.orEmpty(),
                        ownerStyle = ownerStyle,
                        manualContext = manualContextText,
                        conversationStateSettings = conversationStateSettings,
                        commission = commission,
                    )
                }
            } catch (_: Exception) {
                keyboard.post {
                    if (requestToken == generationToken) {
                        isLoadingContext = false; statusText.setText(R.string.generation_error);
        updateGenerateButton()
                    }
                }
            }
        }
    }

    private fun beginReplyGeneration(
        requestToken: Int,
        clientId: Long,
        client: Client?,
        message: String,
        recentMessages: List<ConversationMessage>,
        memories: List<MemoryContext>,
        summary: String,
        ownerStyle: String,
        manualContext: String,
        conversationStateSettings: ClientConversationState, commission: CommissionProfileDto?,
    ) {
        if (conversationStateSettings.automaticMode) {
            statusText.setText(R.string.analyzing_conversation_state)
        updateGenerateButton()
            val classifyCall = ReplyClient.api.classifyConversationState(
                ConversationStateClassificationRequest(
                    message = message,
                    recentMessages = recentMessages,
                    memories = memories,
                    conversationSummary = summary,
                    clientDisplayName = client?.displayName.orEmpty(),
                    clientPlatform = client?.platform.orEmpty(),
                    clientUsername = client?.username.orEmpty(),
                )
            )
            classifyCall.timeout().timeout(20, TimeUnit.SECONDS)
            activeClassifyCall = classifyCall
            classifyCall.enqueue(object : Callback<ConversationStateClassificationResponse> {
                override fun onResponse(
                    call: Call<ConversationStateClassificationResponse>,
                    response: Response<ConversationStateClassificationResponse>,
                ) {
                    if (activeClassifyCall !== call || requestToken != generationToken || clientId != selectedClientId) return
                    activeClassifyCall = null
                    val detected = if (response.isSuccessful) {
                        val body = response.body()
                        ConversationState.fromName(body?.state).name to body?.confidence?.toFloat()
                    } else {
                        response.errorBody()?.close()
                        conversationStateSettings.detectedState to conversationStateSettings.confidence
                    }
                    databaseExecutor.execute {
                        val updated = conversationStateSettings.copy(
                            detectedState = detected.first,
                            confidence = detected.second,
                        )
                        database.saveConversationState(updated)
                        keyboard.post {
                            if (requestToken != generationToken || clientId != selectedClientId) return@post
                            startNetworkGeneration(
                                clientId, message, recentMessages, memories, summary, ownerStyle, manualContext,
                                updated.effectiveState().name, commission,
                            )
                        }
                    }
                }

                override fun onFailure(call: Call<ConversationStateClassificationResponse>, error: Throwable) {
                    if (activeClassifyCall !== call || requestToken != generationToken) return
                    activeClassifyCall = null
                    startNetworkGeneration(
                        clientId, message, recentMessages, memories, summary, ownerStyle, manualContext,
                        conversationStateSettings.effectiveState().name, commission,
                    )
                }
            })
        } else {
            startNetworkGeneration(
                clientId, message, recentMessages, memories, summary, ownerStyle, manualContext,
                conversationStateSettings.effectiveState().name, commission,
            )
        }
    }

    private fun startNetworkGeneration(
        clientId: Long,
        message: String,
        recent: List<ConversationMessage>,
        memories: List<MemoryContext>,
        summary: String,
        ownerStyle: String,
        manualContext: String,
        conversationState: String, commission: CommissionProfileDto?,
    ) {
        databaseExecutor.execute {
            var autoPreference: String? = null
            if (selectedIntent == "AUTO") {
                val resetTime = preferences.getLong("auto_analytics_reset_time", 0L)
                val allFeedbacks = database.replyFeedbackDao().getAllAfter(resetTime)
                val stateFeedbacks = allFeedbacks.filter { it.conversationState == conversationState && !it.usedWhatIMean }
                if (stateFeedbacks.isNotEmpty()) {
                    val total = stateFeedbacks.size
                    val intents = stateFeedbacks.groupBy { it.replyIntent }.mapValues { it.value.size }
                    val sortedIntents = intents.entries.sortedByDescending { it.value }.take(3)
                    val prefs = sortedIntents.joinToString("\n") { "${it.key} chosen ${it.value * 100 / total}%" }
                    val modifiers = stateFeedbacks.groupBy { it.replyModifier }.mapValues { it.value.size }.entries.sortedByDescending { it.value }.firstOrNull()
                    val avgLength = stateFeedbacks.map { it.selectedReplyLength }.average().toInt()
                    autoPreference = "$prefs\nCommon modifier: ${modifiers?.key ?: "NONE"}\nAverage selected reply length: $avgLength chars"
                }
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (clientId != selectedClientId) return@post
                val call = ReplyClient.api.generateReplies(
                    ReplyRequest(message, recent, memories, summary, ownerStyle, manualContext, conversationState, selectedIntent, selectedModifier, currentUserMeaning, autoPreference)
                )
                call.timeout().timeout(25, TimeUnit.SECONDS)
                activeCall = call; statusText.setText(R.string.generating_replies);
        updateGenerateButton()
                call.enqueue(object : Callback<ReplyResponse> {
            override fun onResponse(call: Call<ReplyResponse>, response: Response<ReplyResponse>) {
                if (activeCall !== call) return
                activeCall = null;
        updateGenerateButton()
                if (!response.isSuccessful) {
                    response.errorBody()?.close()
                    statusText.setText(when (response.code()) {
                        422 -> R.string.message_too_long; 503 -> R.string.backend_not_configured
                        429 -> R.string.rate_limit_error; 504 -> R.string.timeout_error; else -> R.string.generation_error })
                    return
                }
                val replies = response.body()?.replies
                if (replies == null || replies.size != 3 || replies.any { it.isNullOrBlank() }) { statusText.setText(R.string.invalid_replies); return }
                suggestionClientId = clientId
                currentSuggestions = replies.filterNotNull()
                suggestionButtons.forEachIndexed { index, button -> button.text = replies[index]; button.visibility = View.VISIBLE }
                keyboard.findViewById<View>(R.id.suggestions_empty).visibility = View.GONE
                keyboard.findViewById<View>(R.id.suggestion_scroll).visibility = View.VISIBLE
                keyboard.findViewById<android.widget.HorizontalScrollView>(R.id.suggestion_scroll).scrollTo(0, 0)
                suggestionButtons.forEach { button -> button.setOnLongClickListener {
                    val body = beginPanel("Full suggestion")
                    body.addView(panelText(button.text.toString()))
                    body.addView(panelButton("Insert reply") { closePanel(); button.performClick() })
                    true
                } }
                statusText.text = ""
                // Show the requested replies before starting optional LLM memory maintenance.
                // Context capture itself still only saves messages locally.
                databaseExecutor.execute {
                    if (!destroyed && database.clientDao().getById(clientId) != null) {
                        extractMemoriesWhenReady(clientId)
                    }
                }
            }
            override fun onFailure(call: Call<ReplyResponse>, error: Throwable) {
                if (activeCall !== call) return
                activeCall = null
        updateGenerateButton()
                statusText.setText(if (error is InterruptedIOException) R.string.timeout_error else R.string.connection_error)
            }
        })
            }
        }
    }

    private fun saveMessage(clientId: Long, senderType: String, content: String) = databaseExecutor.execute {
        database.messageDao().insert(Message(clientId = clientId, senderType = senderType, content = content, timestamp = System.currentTimeMillis()))
    }

    private fun saveReplyFeedback(clientId: Long, suggestion: String, selectedIndex: Int) {
        val incoming = copiedMessageText
        val token = generationToken
        val currentIntent = selectedIntent
        val currentModifier = selectedModifier
        val usedMeaning = !currentUserMeaning.isNullOrBlank()
        if (incoming.isBlank()) return
        databaseExecutor.execute {
            val stateRecord = database.conversationStateFor(clientId)
            val conversationState = stateRecord.effectiveState().name
            val emojiCount = suggestion.count { !it.isLetterOrDigit() && !it.isWhitespace() && ",.?!'\"".indexOf(it) < 0 }
            val feedbackId = database.replyFeedbackDao().insert(ReplyFeedback(
                clientId = clientId, incomingMessage = incoming, generatedSuggestion = suggestion,
                finalSentMessage = suggestion, selectedSuggestionIndex = selectedIndex,
                conversationState = conversationState,
                replyIntent = currentIntent,
                replyModifier = currentModifier,
                usedWhatIMean = usedMeaning,
                selectedReplyLength = suggestion.length,
                selectedReplyEmojiCount = emojiCount,
                timestamp = System.currentTimeMillis()
            ))
            refreshStyleProfile()
            keyboard.post {
                if (!destroyed && token == generationToken && clientId == selectedClientId) {
                    selectedFeedbackId = feedbackId
                    selectedReplyText = suggestion
                }
            }
        }
    }

    private fun showSaveEditedReplyDialog() {
        val feedbackId = selectedFeedbackId ?: return
        val body = beginPanel("Learn from edited reply")
        body.addView(panelText("Save your final wording here for style learning.", true))
        val input = localInput("Final wording", selectedReplyText, multiline = true)
        body.addView(input)
        val token = generationToken
        val save = panelButton("Save wording") {}
        body.addView(save)
        save.setOnClickListener {
            val finalReply = input.text.toString().trim()
            if (finalReply.isBlank()) { input.error = getString(R.string.reply_required); return@setOnClickListener }
            save.isEnabled = false
            databaseExecutor.execute {
                val feedback = database.replyFeedbackDao().getById(feedbackId)
                if (feedback != null) {
                    database.replyFeedbackDao().update(feedback.copy(finalSentMessage = finalReply))
                    refreshStyleProfile()
                }
                keyboard.post {
                    if (destroyed || token != generationToken) return@post
                    selectedReplyText = finalReply
                    closePanel(); statusText.setText(R.string.reply_saved_for_style)
                }
            }
        }
        input.requestFocus(); localEditor = input
    }

    private fun refreshStyleProfile() {
        val feedback = database.replyFeedbackDao().getRecent(100)
        database.styleProfileDao().insert(StyleProfile(
            learnedReplyCount = feedback.size, summary = StyleLearning.summarize(feedback), updatedAt = System.currentTimeMillis()
        ))
    }

    private fun extractMemoriesWhenReady(clientId: Long) {
        if (!extractingClients.add(clientId)) return
        val messages = database.messageDao().getUnprocessedForClient(clientId, 20)
        if (messages.size < 4) {
            extractingClients.remove(clientId)
            summarizeConversationWhenReady(clientId)
            return
        }
        val existingMemories = database.clientMemoryDao().getActiveForClient(clientId)
        val call = ReplyClient.api.extractMemories(MemoryExtractionRequest(
            messages.map { ConversationMessage(it.senderType, it.content, it.timestamp) },
            existingMemories.map { MemoryContext(it.key, it.value, it.importance, it.category) }
        ))
        if (destroyed) return
        memoryCalls.add(call)
        call.enqueue(object : Callback<MemoryExtractionResponse> {
            override fun onResponse(call: Call<MemoryExtractionResponse>, response: Response<MemoryExtractionResponse>) {
                memoryCalls.remove(call)
                if (destroyed) return
                val body = if (response.isSuccessful) response.body() else null
                response.errorBody()?.close()
                databaseExecutor.execute {
                    extractingClients.remove(clientId)
                    if (database.clientDao().getById(clientId) == null) return@execute
                    if (body != null) {
                        val now = System.currentTimeMillis()
                        body.addedMemories?.forEach { m ->
                            val key = m.key.trim().lowercase()
                            val value = m.value.trim()
                            if (key.isNotEmpty() && value.isNotEmpty()) {
                                database.clientMemoryDao().insert(ClientMemory(
                                    clientId = clientId, key = key, value = value,
                                    importance = m.importance.coerceIn(1, 10), category = m.category, confidence = m.confidence,
                                    isActive = true, createdAt = now, updatedAt = now
                                ))
                            }
                        }
                        body.updatedMemories?.forEach { m ->
                            database.clientMemoryDao().deleteByKey(clientId, m.originalKey.trim().lowercase())
                            val key = m.key.trim().lowercase()
                            val value = m.value.trim()
                            if (key.isNotEmpty() && value.isNotEmpty()) {
                                database.clientMemoryDao().insert(ClientMemory(
                                    clientId = clientId, key = key, value = value,
                                    importance = m.importance.coerceIn(1, 10), category = m.category, confidence = m.confidence,
                                    isActive = true, createdAt = now, updatedAt = now
                                ))
                            }
                        }
                        body.deletedMemoryKeys?.forEach { key ->
                            database.clientMemoryDao().deleteByKey(clientId, key.trim().lowercase())
                        }
                        
                        body.commissionUpdate?.let { update ->
                            val existing = database.commissionProfileDao().getForClient(clientId) ?: CommissionProfile(
                                clientId = clientId, status = "NONE", commissionType = null, quotedPrice = null,
                                currency = null, clientBudget = null, subjectDescription = null, referenceNotes = null,
                                deadline = null, paymentStatus = "NOT_DISCLOSED", revisionStatus = "NONE",
                                createdAt = now, updatedAt = now, notes = null
                            )
                            val merged = existing.copy(
                                status = update.status ?: existing.status,
                                commissionType = update.commissionType ?: existing.commissionType,
                                quotedPrice = update.quotedPrice ?: existing.quotedPrice,
                                currency = update.currency ?: existing.currency,
                                clientBudget = update.clientBudget ?: existing.clientBudget,
                                subjectDescription = update.subjectDescription ?: existing.subjectDescription,
                                referenceNotes = update.referenceNotes ?: existing.referenceNotes,
                                deadline = update.deadline ?: existing.deadline,
                                paymentStatus = update.paymentStatus ?: existing.paymentStatus,
                                revisionStatus = update.revisionStatus ?: existing.revisionStatus,
                                updatedAt = now
                            )
                            database.commissionProfileDao().upsert(merged)
                        }
                        
                        database.messageDao().markMemoryProcessed(messages.map { it.id })
                    }
                    summarizeConversationWhenReady(clientId)
                }
            }

            override fun onFailure(call: Call<MemoryExtractionResponse>, error: Throwable) {
                memoryCalls.remove(call)
                if (destroyed) return
                databaseExecutor.execute { extractingClients.remove(clientId) }
            }
        })
    }

    private fun summarizeConversationWhenReady(clientId: Long) {
        if (!summarizingClients.add(clientId)) return
        val messageDao = database.messageDao()
        if (messageDao.countForClient(clientId) <= 40) {
            summarizingClients.remove(clientId)
            return
        }
        val recent = messageDao.getRecentForClient(clientId, 20)
        val firstRecentId = recent.lastOrNull()?.id
        if (firstRecentId == null || messageDao.countUnprocessedBefore(clientId, firstRecentId) != 0) {
            summarizingClients.remove(clientId)
            return
        }
        val previous = database.conversationSummaryDao().getForClient(clientId)
        val olderMessages = messageDao.getBetween(clientId, previous?.lastSummarizedMessageId ?: 0, firstRecentId)
        if (olderMessages.isEmpty()) {
            summarizingClients.remove(clientId)
            return
        }
        val call = ReplyClient.api.summarizeConversation(ConversationSummaryRequest(
            previous?.content.orEmpty(), olderMessages.map { ConversationMessage(it.senderType, it.content, it.timestamp) }
        ))
        if (destroyed) return
        memoryCalls.add(call)
        call.enqueue(object : Callback<ConversationSummaryResponse> {
            override fun onResponse(call: Call<ConversationSummaryResponse>, response: Response<ConversationSummaryResponse>) {
                memoryCalls.remove(call)
                if (destroyed) return
                val summary = if (response.isSuccessful) response.body()?.summary?.trim() else null
                response.errorBody()?.close()
                databaseExecutor.execute {
                    summarizingClients.remove(clientId)
                    if (database.clientDao().getById(clientId) == null) return@execute
                    if (!summary.isNullOrBlank()) {
                        database.runInTransaction {
                        database.conversationSummaryDao().insert(ConversationSummary(
                            id = previous?.id ?: 0, clientId = clientId, content = summary.take(4000),
                            lastSummarizedMessageId = olderMessages.last().id, updatedAt = System.currentTimeMillis()
                        ))
                        messageDao.deleteBefore(clientId, firstRecentId)
                        }
                    }
                }
            }
            override fun onFailure(call: Call<ConversationSummaryResponse>, error: Throwable) {
                memoryCalls.remove(call)
                if (destroyed) return
                databaseExecutor.execute { summarizingClients.remove(clientId) }
            }
        })
    }

    private fun clearForClientChange() {
        generationToken++; cancelGeneration(); copiedMessageText = ""; manualContextText = ""; suggestionClientId = null; currentSuggestions = emptyList(); selectedFeedbackId = null; selectedReplyText = ""
        currentUserMeaning = null
        if (::keyboard.isInitialized) {
            keyboard.findViewById<View>(R.id.what_i_mean_indicator)?.visibility = View.GONE
        }
        if (::copiedMessage.isInitialized) copiedMessage.text = ""
        if (::messageSource.isInitialized) messageSource.setText(R.string.message_source)
        if (::manualContext.isInitialized) manualContext.text = ""
        if (::manualContext.isInitialized) manualContext.visibility = View.GONE
        if (::statusText.isInitialized) statusText.text = ""
        if (::keyboard.isInitialized) keyboard.findViewById<View>(R.id.context_preview).visibility = View.GONE
        if (::suggestionButtons.isInitialized) resetReplies()
    }
    private fun resetReplies() {
        suggestionClientId = null; currentSuggestions = emptyList()
        suggestionButtons.forEach { it.text = ""; it.visibility = View.GONE }
        keyboard.findViewById<View>(R.id.suggestions_empty).visibility = View.VISIBLE
        keyboard.findViewById<View>(R.id.suggestion_scroll).visibility = View.GONE
    }
    private fun
        updateGenerateButton() {
        if (::generateButton.isInitialized) {
            val busy = activeCall != null || activeClassifyCall != null || isLoadingContext
            generateButton.isEnabled = busy || (selectedClientId != null && copiedMessageText.isNotBlank())
            generateButton.text = if (busy) getString(R.string.cancel) else "Generate"
            generateButton.contentDescription = getString(if (busy) R.string.cancel_generation else R.string.generate_replies)
        }
    }
    private fun cancelGeneration() {
        activeCall?.cancel()
        activeClassifyCall?.cancel()
        activeCall = null
        activeClassifyCall = null
        isLoadingContext = false
    }
    override fun onFinishInputView(finishingInput: Boolean) {
        ++clientLoadToken; clearForClientChange(); closePanel(); super.onFinishInputView(finishingInput)
    }
    override fun onDestroy() {
        destroyed = true; cancelGeneration(); memoryCalls.forEach { it.cancel() }; memoryCalls.clear()
        databaseExecutor.shutdown(); super.onDestroy()
    }
    private fun clientLabel(client: Client) = buildString {
        append(client.displayName); if (client.platform.isNotBlank()) append(" · ").append(client.platform)
        if (client.username.isNotBlank()) append(" @").append(client.username)
    }
}

object SenderType { const val INCOMING = "INCOMING"; const val OUTGOING = "OUTGOING" }


