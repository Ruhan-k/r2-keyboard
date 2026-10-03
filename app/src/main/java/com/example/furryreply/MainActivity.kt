package com.example.furryreply

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.google.gson.Gson
import java.io.InputStreamReader
import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val databaseExecutor = Executors.newSingleThreadExecutor()
    private val database by lazy { FurryReplyDatabase.get(this) }
    private val preferences by lazy { getSharedPreferences("keyboard_preferences", MODE_PRIVATE) }
    private lateinit var clientList: ListView
    private lateinit var selectedClientLabel: TextView
    private lateinit var messagesView: TextView
    private lateinit var memoriesView: ListView
    private lateinit var deleteButton: Button
    private lateinit var addMemoryButton: Button
    private lateinit var styleCountView: TextView
    private lateinit var styleSummaryView: TextView
    private lateinit var autoStatsView: TextView
    private lateinit var resetAutoButton: Button
    private lateinit var conversationStateValue: TextView
    private lateinit var conversationStateModeSwitch: Switch
    private lateinit var pickConversationStateButton: Button
    private var clients = emptyList<Client>()
    private var currentConversationState: ClientConversationState? = null
    private var memories = emptyList<ClientMemory>()
    private var selectedClient: Client? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        clientList = findViewById(R.id.client_list)
        selectedClientLabel = findViewById(R.id.main_selected_client)
        messagesView = findViewById(R.id.main_messages)
        memoriesView = findViewById(R.id.main_memories)
        deleteButton = findViewById(R.id.main_delete_client_button)
        addMemoryButton = findViewById(R.id.main_add_memory_button)
        styleCountView = findViewById(R.id.main_style_count)
        styleSummaryView = findViewById(R.id.main_style_summary)
        autoStatsView = findViewById(R.id.main_auto_stats)
        resetAutoButton = findViewById(R.id.main_reset_auto_button)
        conversationStateValue = findViewById(R.id.main_conversation_state_value)
        conversationStateModeSwitch = findViewById(R.id.main_conversation_state_mode_switch)
        pickConversationStateButton = findViewById(R.id.main_pick_conversation_state_button)
        findViewById<Button>(R.id.main_add_client_button).setOnClickListener { showCreateClientDialog() }
        deleteButton.setOnClickListener { deleteSelectedClient() }
        addMemoryButton.setOnClickListener { showAddMemoryDialog() }
        findViewById<Button>(R.id.main_edit_commission_button).setOnClickListener { showEditCommissionDialog() }
        findViewById<Button>(R.id.main_reset_style_button).setOnClickListener { resetLearnedStyle() }
        resetAutoButton.setOnClickListener { resetAutoLearning() }
        pickConversationStateButton.setOnClickListener { showConversationStatePicker() }
        clientList.setOnItemClickListener { _, _, position, _ -> selectClient(clients[position]) }
        memoriesView.setOnItemClickListener { _, _, position, _ -> memories.getOrNull(position)?.let(::showEditMemoryDialog) }
        
        findViewById<Button>(R.id.main_export_backup_button).setOnClickListener { exportBackup() }
        findViewById<Button>(R.id.main_import_backup_button).setOnClickListener { importBackup() }
        findViewById<Button>(R.id.main_export_client_button).setOnClickListener { exportClientData() }
        findViewById<Button>(R.id.main_delete_all_data_button).setOnClickListener { deleteAllData() }
        findViewById<Button>(R.id.main_clear_conversation_history_button).setOnClickListener { clearConversationHistory() }
        findViewById<Button>(R.id.main_clear_client_memories_button).setOnClickListener { clearClientMemories() }
        findViewById<Button>(R.id.main_reset_keyboard_prefs_button).setOnClickListener { resetKeyboardPreferences() }

        loadClients()
        loadStyleProfile()
        loadAutoStats()
    }

    override fun onResume() {
        super.onResume()
        loadClients(selectedClient?.id)
        loadStyleProfile()
        loadAutoStats()
    }

    private fun loadAutoStats() {
        databaseExecutor.execute {
            val resetTime = preferences.getLong("auto_analytics_reset_time", 0L)
            val allFeedbacks = database.replyFeedbackDao().getAllAfter(resetTime).filter { !it.usedWhatIMean }
            runOnUiThread {
                if (allFeedbacks.isEmpty()) {
                    autoStatsView.text = "No analytics available"
                } else {
                    val intents = allFeedbacks.groupBy { it.replyIntent }.mapValues { it.value.size }.entries.sortedByDescending { it.value }.take(3)
                    val stats = intents.joinToString("\n") { "${it.key}: ${it.value * 100 / allFeedbacks.size}%" }
                    val avgLen = allFeedbacks.map { it.selectedReplyLength }.average().toInt()
                    autoStatsView.text = "$stats\nTotal selections: ${allFeedbacks.size}\nAvg length: $avgLen"
                }
            }
        }
    }

    private fun resetAutoLearning() {
        AlertDialog.Builder(this).setTitle("Reset AUTO Learning")
            .setMessage("This will clear AUTO intent history. It will not delete client memory or creator profile.")
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton("Reset") { _, _ ->
                preferences.edit().putLong("auto_analytics_reset_time", System.currentTimeMillis()).apply()
                loadAutoStats()
            }.show()
    }

    private fun loadStyleProfile() {
        databaseExecutor.execute {
            val profile = database.styleProfileDao().get()
            runOnUiThread {
                styleCountView.text = if (profile == null) getString(R.string.no_learned_style)
                else getString(R.string.replies_learned) + ": " + profile.learnedReplyCount
                styleSummaryView.text = profile?.summary ?: getString(R.string.no_learned_style)
            }
        }
    }

    private fun resetLearnedStyle() {
        AlertDialog.Builder(this).setTitle(R.string.reset_learned_style)
            .setMessage(getString(R.string.reset_learned_style))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.reset_learned_style) { _, _ ->
                databaseExecutor.execute {
                    database.replyFeedbackDao().deleteAll()
                    database.styleProfileDao().deleteAll()
                    runOnUiThread { loadStyleProfile(); loadAutoStats() }
                }
            }.show()
    }

    private fun loadClients(preferredId: Long? = null) {
        databaseExecutor.execute {
            val loaded = database.clientDao().getAll()
            runOnUiThread {
                clients = loaded
                clientList.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, loaded.map(::clientLabel))
                val id = preferredId ?: selectedClient?.id ?: preferences.getLong("selected_client_id", -1L)
                val client = loaded.firstOrNull { it.id == id } ?: loaded.firstOrNull()
                if (client == null) {
                    selectedClient = null
                    selectedClientLabel.setText(R.string.no_client_selected)
                    messagesView.setText(R.string.no_messages)
                    memories = emptyList()
                    memoriesView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, listOf(getString(R.string.no_memories)))
                    deleteButton.isEnabled = false
                    addMemoryButton.isEnabled = false
                    bindConversationState(null)
                } else {
                    selectClient(client)
                }
            }
        }
    }

    private fun selectClient(client: Client) {
        selectedClient = client
        preferences.edit().putLong("selected_client_id", client.id).apply()
        selectedClientLabel.text = clientLabel(client)
        deleteButton.isEnabled = true
        addMemoryButton.isEnabled = true
        databaseExecutor.execute {
            val messages = database.messageDao().getAllForClient(client.id)
            val memories = database.clientMemoryDao().getAllForClient(client.id)
            val conversationState = database.conversationStateFor(client.id)
            runOnUiThread {
                if (selectedClient?.id != client.id) return@runOnUiThread
                bindConversationState(conversationState)
                messagesView.text = if (messages.isEmpty()) getString(R.string.no_messages) else messages.joinToString("\n\n") { message ->
                    "${if (message.senderType == SenderType.INCOMING) getString(R.string.incoming_sender) else getString(R.string.outgoing_sender)} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(message.timestamp))}\n${message.content}"
                }
                this.memories = memories
                memoriesView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1,
                    if (memories.isEmpty()) listOf(getString(R.string.no_memories)) else memories.map { memory ->
                        val status = if (memory.isActive) memory.category else "${memory.category} (expired)"
                        "${memory.key}\n${memory.value}\n$status"
                    })
            }
        }
    }

    private fun showCreateClientDialog() {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 0) }
        val name = EditText(this).apply { hint = getString(R.string.display_name_hint) }
        val platform = EditText(this).apply { hint = getString(R.string.platform_hint) }
        val username = EditText(this).apply { hint = getString(R.string.username_hint) }
        form.addView(name); form.addView(platform); form.addView(username)
        val dialog = AlertDialog.Builder(this).setTitle(R.string.create_client).setView(form)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.create_client, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val displayName = name.text.toString().trim()
                if (displayName.isEmpty()) { name.error = getString(R.string.client_name_required); return@setOnClickListener }
                databaseExecutor.execute {
                    val id = database.clientDao().insert(Client(
                        displayName = displayName, platform = platform.text.toString().trim(),
                        username = username.text.toString().trim(), createdAt = System.currentTimeMillis()))
                    runOnUiThread { dialog.dismiss(); loadClients(id) }
                }
            }
        }
        dialog.show()
    }

    private fun deleteSelectedClient() {
        val client = selectedClient ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_client)
            .setMessage(getString(R.string.delete_client_confirmation, client.displayName))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete_client) { _, _ ->
                databaseExecutor.execute {
                    // ON DELETE CASCADE handles messages, memories, summaries, feedback, states, commissions
                    database.clientDao().deleteById(client.id)
                    runOnUiThread {
                        if (selectedClient?.id == client.id) selectedClient = null
                        loadClients()
                    }
                }
            }
            .show()
    }

    private fun showAddMemoryDialog() {
        val client = selectedClient ?: return
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 0) }
        val key = EditText(this).apply { hint = getString(R.string.memory_key_hint) }
        val value = EditText(this).apply { hint = getString(R.string.memory_value_hint) }
        val importance = EditText(this).apply { hint = getString(R.string.memory_importance_hint); inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val category = EditText(this).apply { hint = "Category (STABLE, PREFERENCE, TEMPORARY, etc)" }
        val activeCheck = android.widget.CheckBox(this).apply { text = "Is Active"; isChecked = true }
        form.addView(key); form.addView(value); form.addView(importance); form.addView(category); form.addView(activeCheck)
        val dialog = AlertDialog.Builder(this).setTitle(R.string.add_memory).setView(form)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.add_memory, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val memoryKey = key.text.toString().trim()
                val memoryValue = value.text.toString().trim()
                val memoryCat = category.text.toString().trim().ifEmpty { "OTHER" }
                val isAct = activeCheck.isChecked
                if (memoryKey.isEmpty() || memoryValue.isEmpty()) {
                    key.error = getString(R.string.memory_key_value_required)
                    return@setOnClickListener
                }
                val level = importance.text.toString().toIntOrNull()?.coerceIn(1, 10) ?: 3
                databaseExecutor.execute {
                    val now = System.currentTimeMillis()
                    database.clientMemoryDao().insert(ClientMemory(
                        clientId = client.id, key = memoryKey, value = memoryValue,
                        importance = level, category = memoryCat, isActive = isAct, createdAt = now, updatedAt = now))
                    runOnUiThread { dialog.dismiss(); selectClient(client) }
                }
            }
        }
        dialog.show()
    }

    private fun bindConversationState(state: ClientConversationState?) {
        currentConversationState = state
        if (state == null) {
            conversationStateValue.setText(R.string.conversation_state_default)
            conversationStateModeSwitch.isEnabled = false
            conversationStateModeSwitch.isChecked = true
            pickConversationStateButton.isEnabled = false
            return
        }
        conversationStateModeSwitch.isEnabled = true
        conversationStateModeSwitch.setOnCheckedChangeListener(null)
        conversationStateModeSwitch.isChecked = !state.automaticMode
        conversationStateModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            val client = selectedClient ?: return@setOnCheckedChangeListener
            val current = currentConversationState ?: ClientConversationState(clientId = client.id)
            saveConversationState(client.id, current.copy(automaticMode = !isChecked))
        }
        conversationStateValue.text = state.bracketLabel()
        pickConversationStateButton.isEnabled = !state.automaticMode
    }

    private fun saveConversationState(clientId: Long, state: ClientConversationState) {
        databaseExecutor.execute {
            database.saveConversationState(state.copy(clientId = clientId))
            val saved = database.conversationStateFor(clientId)
            runOnUiThread {
                if (selectedClient?.id != clientId) return@runOnUiThread
                bindConversationState(saved)
            }
        }
    }

    private fun showConversationStatePicker() {
        val client = selectedClient ?: return
        val labels = ConversationState.entries.map { it.displayLabel() }.toTypedArray()
        val current = currentConversationState?.manualState ?: ConversationState.DEFAULT.name
        val selectedIndex = ConversationState.entries.indexOfFirst { it.name == current }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.conversation_state_picker_title)
            .setSingleChoiceItems(labels, selectedIndex) { dialog, which ->
                val picked = ConversationState.entries[which]
                val base = currentConversationState ?: ClientConversationState(clientId = client.id, automaticMode = false)
                saveConversationState(client.id, base.copy(automaticMode = false, manualState = picked.name))
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showEditMemoryDialog(memory: ClientMemory) {
        val client = selectedClient ?: return
        if (memory.clientId != client.id) return
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 0) }
        val key = EditText(this).apply { hint = getString(R.string.memory_key_hint); setText(memory.key) }
        val value = EditText(this).apply { hint = getString(R.string.memory_value_hint); setText(memory.value) }
        val importance = EditText(this).apply { hint = getString(R.string.memory_importance_hint); setText(memory.importance.toString()); inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val category = EditText(this).apply { hint = "Category (STABLE, PREFERENCE, TEMPORARY, etc)"; setText(memory.category) }
        val activeCheck = android.widget.CheckBox(this).apply { text = "Is Active"; isChecked = memory.isActive }
        form.addView(key); form.addView(value); form.addView(importance); form.addView(category); form.addView(activeCheck)
        val dialog = AlertDialog.Builder(this).setTitle(R.string.edit_memory).setView(form)
            .setNegativeButton(R.string.delete_memory, null).setNeutralButton(R.string.cancel, null)
            .setPositiveButton(R.string.edit_memory, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                databaseExecutor.execute {
                    database.clientMemoryDao().deleteById(memory.id)
                    runOnUiThread { dialog.dismiss(); selectClient(client) }
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val memoryKey = key.text.toString().trim()
                val memoryValue = value.text.toString().trim()
                val memoryCat = category.text.toString().trim().ifEmpty { "OTHER" }
                val isAct = activeCheck.isChecked
                if (memoryKey.isEmpty() || memoryValue.isEmpty()) { key.error = getString(R.string.memory_key_value_required); return@setOnClickListener }
                val level = importance.text.toString().toIntOrNull()?.coerceIn(1, 10) ?: 3
                databaseExecutor.execute {
                    database.clientMemoryDao().update(memory.copy(key = memoryKey, value = memoryValue, importance = level, category = memoryCat, isActive = isAct, updatedAt = System.currentTimeMillis()))
                    runOnUiThread { dialog.dismiss(); selectClient(client) }
                }
            }
        }
        dialog.show()
    }

    override fun onDestroy() {
        databaseExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun clientLabel(client: Client) = buildString {
        append(client.displayName)
        if (client.platform.isNotBlank()) append(" · ").append(client.platform)
        if (client.username.isNotBlank()) append(" @").append(client.username)
    }
    private fun showEditCommissionDialog() {
        val client = selectedClient ?: return
        databaseExecutor.execute {
            val comm = database.commissionProfileDao().getForClient(client.id) ?: CommissionProfile(
                clientId = client.id, status = "NONE", commissionType = null, quotedPrice = null,
                currency = null, clientBudget = null, subjectDescription = null, referenceNotes = null,
                deadline = null, paymentStatus = "NOT_DISCLOSED", revisionStatus = "NONE",
                createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(), notes = null
            )
            runOnUiThread {
                val view = android.widget.ScrollView(this).apply { setPadding(32, 32, 32, 32) }
                val layout = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
                view.addView(layout)

                val statusInput = android.widget.EditText(this).apply { hint = "Status (NONE, INTERESTED, QUOTE_SENT, WAITING, ACCEPTED, PAID, IN_PROGRESS, REVISION, COMPLETED, CANCELLED)"; setText(comm.status) }
                val typeInput = android.widget.EditText(this).apply { hint = "Commission Type"; setText(comm.commissionType) }
                val priceInput = android.widget.EditText(this).apply { hint = "Quoted Price"; setText(comm.quotedPrice) }
                val currencyInput = android.widget.EditText(this).apply { hint = "Currency"; setText(comm.currency) }
                val budgetInput = android.widget.EditText(this).apply { hint = "Client Budget"; setText(comm.clientBudget) }
                val descInput = android.widget.EditText(this).apply { hint = "Subject Description"; setText(comm.subjectDescription) }
                val notesInput = android.widget.EditText(this).apply { hint = "Notes"; setText(comm.notes) }
                
                layout.addView(statusInput); layout.addView(typeInput); layout.addView(priceInput)
                layout.addView(currencyInput); layout.addView(budgetInput); layout.addView(descInput); layout.addView(notesInput)

                val dialog = android.app.AlertDialog.Builder(this)
                    .setTitle("Edit Commission")
                    .setView(view)
                    .setPositiveButton("Save", null)
                    .setNegativeButton("Cancel", null)
                    .create()

                dialog.setOnShowListener {
                    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val newComm = comm.copy(
                            status = statusInput.text.toString().trim().ifEmpty { "NONE" },
                            commissionType = typeInput.text.toString().trim().ifEmpty { null },
                            quotedPrice = priceInput.text.toString().trim().ifEmpty { null },
                            currency = currencyInput.text.toString().trim().ifEmpty { null },
                            clientBudget = budgetInput.text.toString().trim().ifEmpty { null },
                            subjectDescription = descInput.text.toString().trim().ifEmpty { null },
                            notes = notesInput.text.toString().trim().ifEmpty { null },
                            updatedAt = System.currentTimeMillis()
                        )
                        databaseExecutor.execute {
                            database.commissionProfileDao().upsert(newComm)
                            runOnUiThread { dialog.dismiss() }
                        }
                    }
                }
                dialog.show()
            }
        }
    }
    private fun exportBackup() {
        AlertDialog.Builder(this)
            .setTitle("Unencrypted Backup Warning")
            .setMessage("The exported backup may contain private conversations. Please keep this file safe. This backup is UNENCRYPTED.")
            .setPositiveButton("Proceed") { _, _ ->
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                    putExtra(Intent.EXTRA_TITLE, "R2KeyboardBackup.json")
                }
                startActivityForResult(intent, 1001)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun importBackup() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, 1002)
    }
    
    private fun exportClientData() {
        val client = selectedClient ?: return
        AlertDialog.Builder(this)
            .setTitle("Unencrypted Client Export")
            .setMessage("The exported data is UNENCRYPTED.")
            .setPositiveButton("Proceed") { _, _ ->
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                    putExtra(Intent.EXTRA_TITLE, "R2Keyboard_Client_.json")
                }
                startActivityForResult(intent, 1003)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        
        when (requestCode) {
            1001 -> performExport(uri, null)
            1002 -> performImport(uri)
            1003 -> performExport(uri, selectedClient?.id)
        }
    }

    private fun performExport(uri: Uri, clientId: Long?) {
        databaseExecutor.execute {
            try {
                val dbData = if (clientId == null) {
                    FurryReplyData(
                        clients = database.clientDao().getAll(),
                        messages = database.messageDao().getAll(),
                        memories = database.clientMemoryDao().getAll(),
                        summaries = database.conversationSummaryDao().getAll(),
                        feedback = database.replyFeedbackDao().getRecent(100000),
                        styleProfiles = database.styleProfileDao().getAll(),
                        usage = database.appClientUsageDao().getAll(),
                        states = database.clientConversationStateDao().getAll(),
                        commissions = database.commissionProfileDao().getAll()
                    )
                } else {
                    FurryReplyData(
                        clients = listOfNotNull(database.clientDao().getById(clientId)),
                        messages = database.messageDao().getAllForClient(clientId),
                        memories = database.clientMemoryDao().getAllForClient(clientId),
                        summaries = listOfNotNull(database.conversationSummaryDao().getForClient(clientId)),
                        feedback = emptyList(),
                        styleProfiles = emptyList(),
                        usage = emptyList(),
                        states = listOfNotNull(database.clientConversationStateDao().getForClient(clientId)),
                        commissions = listOfNotNull(database.commissionProfileDao().getForClient(clientId))
                    )
                }
                
                val backup = BackupData(data = dbData)
                val json = Gson().toJson(backup)
                
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray())
                }
                runOnUiThread { Toast.makeText(this, "Export complete", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread { Toast.makeText(this, "Export failed", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun performImport(uri: Uri) {
        databaseExecutor.execute {
            try {
                val backup = contentResolver.openInputStream(uri)?.use { 
                    Gson().fromJson(InputStreamReader(it), BackupData::class.java) 
                }
                if (backup == null || backup.data == null) throw Exception("Invalid backup file")
                if (backup.backupVersion > 1) throw Exception("Unsupported backup version ${backup.backupVersion}. Please update the app.")
                
                val dbData = backup.data
                val counts = "${dbData.clients.size} clients, ${dbData.messages.size} messages, ${dbData.memories.size} memories"
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("Restore Backup")
                        .setMessage("Found: $counts\nChoose restore method:")
                        .setPositiveButton("Merge") { _, _ -> doRestore(dbData, false) }
                        .setNeutralButton("Replace") { _, _ -> doRestore(dbData, true) }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread { Toast.makeText(this, "Import failed", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun doRestore(data: FurryReplyData, replace: Boolean) {
        databaseExecutor.execute {
            try {
                database.runInTransaction {
                    if (replace) {
                        database.clientDao().deleteAll()
                        database.styleProfileDao().deleteAll()
                        database.replyFeedbackDao().deleteAll()
                    }
                    data.clients.forEach { database.clientDao().insert(it) }
                    data.messages.forEach { database.messageDao().insert(it) }
                    data.memories.forEach { database.clientMemoryDao().insert(it) }
                    data.summaries.forEach { database.conversationSummaryDao().insert(it) }
                    data.feedback.forEach { database.replyFeedbackDao().insert(it) }
                    data.styleProfiles.forEach { database.styleProfileDao().insert(it) }
                    data.usage.forEach { database.appClientUsageDao().upsert(it) }
                    data.states.forEach { database.clientConversationStateDao().upsert(it) }
                    data.commissions.forEach { database.commissionProfileDao().upsert(it) }
                }
                runOnUiThread { 
                    Toast.makeText(this, "Restore complete", Toast.LENGTH_SHORT).show()
                    loadClients()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread { Toast.makeText(this, "Restore error", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun deleteAllData() {
        AlertDialog.Builder(this)
            .setTitle("Delete All R2 Keyboard Data")
            .setMessage("This will irrevocably drop all clients, messages, and reset preferences. Are you sure?")
            .setPositiveButton("PROCEED") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("Final Confirmation")
                    .setMessage("Are you absolutely sure? This cannot be undone.")
                    .setPositiveButton("WIPE DATA") { _, _ ->
                        databaseExecutor.execute {
                            database.runInTransaction {
                                database.clientDao().deleteAll()
                                database.styleProfileDao().deleteAll()
                                database.replyFeedbackDao().deleteAll()
                            }
                            preferences.edit().clear().apply()
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "All data wiped", Toast.LENGTH_LONG).show()
                                loadClients()
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearConversationHistory() {
        AlertDialog.Builder(this)
            .setTitle("Clear Conversation History")
            .setMessage("Delete all messages for all clients?")
            .setPositiveButton("Clear") { _, _ ->
                databaseExecutor.execute {
                    database.messageDao().deleteAll()
                    runOnUiThread { Toast.makeText(this@MainActivity, "Messages cleared", Toast.LENGTH_SHORT).show() }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearClientMemories() {
        AlertDialog.Builder(this)
            .setTitle("Clear Client Memories")
            .setMessage("Delete all memories for all clients?")
            .setPositiveButton("Clear") { _, _ ->
                databaseExecutor.execute {
                    database.clientMemoryDao().deleteAll()
                    runOnUiThread { Toast.makeText(this@MainActivity, "Memories cleared", Toast.LENGTH_SHORT).show() }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun resetKeyboardPreferences() {
        AlertDialog.Builder(this)
            .setTitle("Reset Keyboard Preferences")
            .setMessage("Wipe all settings/preferences? (e.g. LLM keys, etc)")
            .setPositiveButton("Reset") { _, _ ->
                preferences.edit().clear().apply()
                Toast.makeText(this, "Preferences reset", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

}
