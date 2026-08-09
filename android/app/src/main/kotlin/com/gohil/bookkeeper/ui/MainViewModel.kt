package com.gohil.bookkeeper.ui

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gohil.bookkeeper.core.model.ProcessResult
import com.gohil.bookkeeper.core.model.RegisterRow
import com.gohil.bookkeeper.core.model.RegisterType
import com.gohil.bookkeeper.core.model.ReviewItem
import com.gohil.bookkeeper.core.xlsx.XlsxAppender
import com.gohil.bookkeeper.data.Account
import com.gohil.bookkeeper.data.AccountKind
import com.gohil.bookkeeper.data.AccountStore
import com.gohil.bookkeeper.data.Prefs
import com.gohil.bookkeeper.data.Secret
import com.gohil.bookkeeper.data.WorkbookStore
import com.gohil.bookkeeper.pdf.DocumentExtractor
import com.gohil.bookkeeper.work.ProcessJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)
    private val accounts = AccountStore(app)
    private val store = WorkbookStore(app)
    private val extractor = DocumentExtractor(app)

    data class UiState(
        val purchaseWorkbook: Uri? = null,
        val salesWorkbook: Uri? = null,
        val backupFolder: Uri? = null,
        val purchaseWorkbookName: String = "",
        val salesWorkbookName: String = "",
        val purchaseTab: String = "",
        val salesTab: String = "",
        val documents: List<ProcessJob.Document> = emptyList(),
        val accounts: List<Account> = emptyList(),
        /** Account ids that have a password saved, so the UI can show which still need one. */
        val accountsWithPassword: Set<String> = emptySet(),
        val stage: Stage = Stage.Idle,
        val progress: ProcessJob.Progress? = null,
        val result: ProcessResult? = null,
        /** Review items the user has approved for writing. */
        val approved: Set<Int> = emptySet(),
        val writeSummary: String? = null,
        val error: String? = null,
    ) {
        val readyToRun: Boolean
            get() = documents.isNotEmpty() &&
                (purchaseWorkbook != null || salesWorkbook != null) &&
                (purchaseTab.isNotBlank() || salesTab.isNotBlank())
    }

    enum class Stage { Idle, Processing, Reviewing, Writing, Done }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        _state.update {
            it.copy(
                purchaseWorkbook = prefs.purchaseWorkbook,
                salesWorkbook = prefs.salesWorkbook,
                backupFolder = prefs.backupFolder,
                purchaseWorkbookName = prefs.purchaseWorkbook?.let(store::displayName).orEmpty(),
                salesWorkbookName = prefs.salesWorkbook?.let(store::displayName).orEmpty(),
                purchaseTab = prefs.purchaseTab,
                salesTab = prefs.salesTab,
            )
        }
        refreshAccounts()
    }

    private fun refreshAccounts() {
        val all = accounts.all()
        _state.update {
            it.copy(
                accounts = all,
                accountsWithPassword = all.filter(accounts::hasPassword).map { a -> a.id }.toSet(),
            )
        }
    }

    // ── setup ────────────────────────────────────────────────────────────────────

    fun setPurchaseWorkbook(uri: Uri) {
        prefs.persist(uri)
        prefs.purchaseWorkbook = uri
        _state.update { it.copy(purchaseWorkbook = uri, purchaseWorkbookName = store.displayName(uri)) }
    }

    fun setSalesWorkbook(uri: Uri) {
        prefs.persist(uri)
        prefs.salesWorkbook = uri
        _state.update { it.copy(salesWorkbook = uri, salesWorkbookName = store.displayName(uri)) }
    }

    fun setBackupFolder(uri: Uri) {
        prefs.persist(uri)
        prefs.backupFolder = uri
        _state.update { it.copy(backupFolder = uri) }
    }

    fun setPurchaseTab(value: String) {
        prefs.purchaseTab = value
        _state.update { it.copy(purchaseTab = value) }
    }

    fun setSalesTab(value: String) {
        prefs.salesTab = value
        _state.update { it.copy(salesTab = value) }
    }

    fun addDocuments(uris: List<Uri>, kind: ProcessJob.Kind, account: Account? = null) {
        val app = getApplication<Application>()
        val added = uris.map { uri ->
            ProcessJob.Document(
                uri = uri,
                displayName = DocumentFile.fromSingleUri(app, uri)?.name ?: "document",
                kind = kind,
                accountId = account?.id,
                accountLabel = account?.label,
            )
        }
        _state.update { it.copy(documents = it.documents + added) }
    }

    fun removeDocument(doc: ProcessJob.Document) {
        _state.update { it.copy(documents = it.documents - doc) }
    }

    // ── accounts ─────────────────────────────────────────────────────────────────

    fun addAccount(label: String, kind: AccountKind, password: String) {
        if (label.isBlank()) return
        accounts.add(label, kind, password)
        refreshAccounts()
    }

    fun setAccountPassword(id: String, password: String) {
        if (password.isBlank()) return
        accounts.setPassword(id, password)
        refreshAccounts()
    }

    fun removeAccount(id: String) {
        accounts.remove(id)
        // Drop any documents filed under it, so a run cannot reference a deleted account.
        _state.update { it.copy(documents = it.documents.filterNot { d -> d.accountId == id }) }
        refreshAccounts()
    }

    // ── run ──────────────────────────────────────────────────────────────────────

    fun run() {
        val current = _state.value
        if (!current.readyToRun) return

        _state.update { it.copy(stage = Stage.Processing, error = null, writeSummary = null) }

        viewModelScope.launchCatching {
            val job = ProcessJob(extractor, prefs.loadRules())
            val known = accounts.all()

            val result = withContext(Dispatchers.Default) {
                job.run(
                    documents = current.documents,
                    passwordsFor = { doc ->
                        val filedUnder = known.firstOrNull { it.id == doc.accountId }
                        accounts.passwordCandidates(filedUnder).map(::Secret)
                    },
                ) { progress ->
                    _state.update { it.copy(progress = progress) }
                }
            }

            _state.update {
                it.copy(stage = Stage.Reviewing, result = result, progress = null, approved = emptySet())
            }
        }
    }

    fun toggleApproval(index: Int) {
        _state.update {
            val approved = if (index in it.approved) it.approved - index else it.approved + index
            it.copy(approved = approved)
        }
    }

    /** Replaces a review item's suggested row after the user has corrected it. */
    fun updateReviewRow(index: Int, row: RegisterRow) {
        _state.update { state ->
            val result = state.result ?: return@update state
            val updated = result.review.toMutableList()
            updated[index] = updated[index].copy(suggested = row)
            state.copy(result = result.copy(review = updated))
        }
    }

    // ── write ────────────────────────────────────────────────────────────────────

    fun write() {
        val current = _state.value
        val result = current.result ?: return

        _state.update { it.copy(stage = Stage.Writing, error = null) }

        viewModelScope.launchCatching {
            // Approved review items join the automatic rows only now, at the last moment,
            // so nothing the user did not agree to can reach a workbook.
            val approvedRows = current.approved
                .mapNotNull { result.review.getOrNull(it) }
                .mapNotNull { item -> item.suggested?.let { item.target to it } }

            val purchaseRows = result.purchaseRows +
                approvedRows.filter { it.first == RegisterType.PURCHASE }.map { it.second }
            val salesRows = result.salesRows +
                approvedRows.filter { it.first == RegisterType.SALES }.map { it.second }

            val notes = mutableListOf<String>()

            // Rows with nowhere to go must be announced, not dropped. Processing a sale
            // invoice with only the Purchase Register configured used to end with a cheerful
            // summary that never mentioned the sales rows, so the run looked complete while
            // the invoice had gone nowhere.
            if (purchaseRows.isNotEmpty() &&
                (current.purchaseWorkbook == null || current.purchaseTab.isBlank())
            ) {
                notes += "⚠️ ${purchaseRows.size} purchase row(s) were NOT written — " +
                    "no Purchase Register file and month tab are set. Nothing was lost; " +
                    "set them and run again."
            }
            if (salesRows.isNotEmpty() &&
                (current.salesWorkbook == null || current.salesTab.isBlank())
            ) {
                notes += "⚠️ ${salesRows.size} sales row(s) were NOT written — " +
                    "no Sales Register file and month tab are set. Nothing was lost; " +
                    "set them and run again."
            }

            withContext(Dispatchers.IO) {
                current.purchaseWorkbook?.takeIf { purchaseRows.isNotEmpty() && current.purchaseTab.isNotBlank() }
                    ?.let { uri ->
                        val outcome = store.append(
                            uri = uri,
                            request = XlsxAppender.Request(
                                sheetName = current.purchaseTab,
                                rows = purchaseRows,
                                registerType = RegisterType.PURCHASE,
                                createSheetIfMissing = prefs.createMissingTabs,
                            ),
                            backupFolder = current.backupFolder,
                        )
                        notes += describe("Purchase", outcome)
                    }

                current.salesWorkbook?.takeIf { salesRows.isNotEmpty() && current.salesTab.isNotBlank() }
                    ?.let { uri ->
                        val outcome = store.append(
                            uri = uri,
                            request = XlsxAppender.Request(
                                sheetName = current.salesTab,
                                rows = salesRows,
                                registerType = RegisterType.SALES,
                                createSheetIfMissing = prefs.createMissingTabs,
                            ),
                            backupFolder = current.backupFolder,
                        )
                        notes += describe("Sales", outcome)
                    }
            }

            if (notes.isEmpty()) notes += "Nothing to write — no rows were approved."

            _state.update { it.copy(stage = Stage.Done, writeSummary = notes.joinToString("\n\n")) }
        }
    }

    private fun describe(label: String, outcome: WorkbookStore.WriteOutcome): String = buildString {
        val r = outcome.result
        append("$label Register — added ${r.rowsWritten} row(s) to '${r.sheetName}', ")
        append("SR NO ${r.firstSrNo} onwards.")
        if (r.addedColumns.isNotEmpty()) {
            append("\nAdded column(s): ${r.addedColumns.joinToString(", ")}.")
        }
        r.warnings.forEach { append("\n$it") }
        append(
            if (outcome.backupName != null) {
                "\nBackup saved as ${outcome.backupName}."
            } else {
                "\nNo backup was made — pick a backup folder in Settings to enable them."
            },
        )
    }

    fun reset() {
        _state.update {
            it.copy(
                documents = emptyList(),
                stage = Stage.Idle,
                result = null,
                approved = emptySet(),
                writeSummary = null,
                error = null,
            )
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    /**
     * Surfaces a failure to the user instead of crashing the app mid-run.
     *
     * A crash here would be especially unhelpful: it would leave the user unsure whether
     * their workbook had already been modified.
     */
    private fun CoroutineScope.launchCatching(block: suspend () -> Unit) {
        launch {
            try {
                block()
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        stage = if (it.result != null) Stage.Reviewing else Stage.Idle,
                        error = e.message ?: "Something went wrong.",
                        progress = null,
                    )
                }
            }
        }
    }
}
