package com.gohil.bookkeeper.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gohil.bookkeeper.core.model.ReviewReason
import com.gohil.bookkeeper.data.Account
import com.gohil.bookkeeper.data.AccountKind
import com.gohil.bookkeeper.data.WorkbookStore
import com.gohil.bookkeeper.work.ProcessJob

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Gohil Bookkeeper") })
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            when (state.stage) {
                MainViewModel.Stage.Idle -> SetupScreen(vm, state)
                MainViewModel.Stage.Processing -> ProgressScreen(state)
                MainViewModel.Stage.Reviewing -> ReviewScreen(vm, state)
                MainViewModel.Stage.Writing -> ProgressScreen(state, "Writing to your workbooks…")
                MainViewModel.Stage.Done -> DoneScreen(vm, state)
            }
        }
    }

    state.error?.let { message ->
        AlertDialog(
            onDismissRequest = vm::dismissError,
            confirmButton = { TextButton(onClick = vm::dismissError) { Text("OK") } },
            title = { Text("Something went wrong") },
            text = { Text(message) },
        )
    }
}

@Composable
private fun SetupScreen(vm: MainViewModel, state: MainViewModel.UiState) {
    val purchasePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(vm::setPurchaseWorkbook) }

    val salesPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(vm::setSalesWorkbook) }

    val backupPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let(vm::setBackupFolder) }

    var docKind by remember { mutableStateOf(ProcessJob.Kind.PURCHASE_INVOICE) }
    var docAccount by remember { mutableStateOf<Account?>(null) }
    val docPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> if (uris.isNotEmpty()) vm.addDocuments(uris, docKind, docAccount) }

    fun pick(kind: ProcessJob.Kind, mime: Array<String>, account: Account? = null) {
        docKind = kind
        docAccount = account
        docPicker.launch(mime)
    }

    var showAddAccount by remember { mutableStateOf(false) }
    if (showAddAccount) {
        AddAccountDialog(
            onDismiss = { showAddAccount = false },
            onAdd = { label, kind, password ->
                vm.addAccount(label, kind, password)
                showAddAccount = false
            },
        )
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard("1 · Your workbooks") {
                FilePickRow(
                    label = "Purchase Register",
                    value = state.purchaseWorkbookName,
                    onPick = { purchasePicker.launch(arrayOf(WorkbookStore.MIME_XLSX)) },
                )
                OutlinedTextField(
                    value = state.purchaseTab,
                    onValueChange = vm::setPurchaseTab,
                    label = { Text("Month tab (e.g. Jun)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                FilePickRow(
                    label = "Sales Register",
                    value = state.salesWorkbookName,
                    onPick = { salesPicker.launch(arrayOf(WorkbookStore.MIME_XLSX)) },
                )
                OutlinedTextField(
                    value = state.salesTab,
                    onValueChange = vm::setSalesTab,
                    label = { Text("Month tab (e.g. June)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "The two workbooks name their tabs differently, so both are asked for.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionCard("2 · Your accounts") {
                Text(
                    "Add each bank account and credit card once, with its statement " +
                        "password. Passwords are remembered for next month.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.accounts.isEmpty()) {
                    Text(
                        "No accounts yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = { showAddAccount = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Add a bank account or card")
                }
            }
        }

        items(state.accounts, key = { it.id }) { account ->
            AccountCard(
                account = account,
                hasPassword = account.id in state.accountsWithPassword,
                statementCount = state.documents.count { it.accountId == account.id },
                onAddStatement = {
                    pick(
                        kind = if (account.kind == AccountKind.BANK) {
                            ProcessJob.Kind.BANK_STATEMENT
                        } else {
                            ProcessJob.Kind.CREDIT_CARD_STATEMENT
                        },
                        mime = arrayOf("application/pdf"),
                        account = account,
                    )
                },
                onSetPassword = { vm.setAccountPassword(account.id, it) },
                onRemove = { vm.removeAccount(account.id) },
            )
        }

        item {
            SectionCard("3 · Invoices") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    FilledTonalButton(
                        onClick = { pick(ProcessJob.Kind.PURCHASE_INVOICE, arrayOf("application/pdf", "image/*")) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Purchase bills") }
                    FilledTonalButton(
                        onClick = { pick(ProcessJob.Kind.SALES_INVOICE, arrayOf("application/pdf", "image/*")) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Sale invoices") }
                }
                Text(
                    "PDFs or photos. Add as many as you like.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(state.documents) { doc ->
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(doc.displayName, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            doc.accountLabel?.let { "${doc.kind.label()} · $it" } ?: doc.kind.label(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { vm.removeDocument(doc) }) {
                        Icon(Icons.Default.Close, contentDescription = "Remove")
                    }
                }
            }
        }

        item {
            SectionCard("4 · Backups") {
                FilePickRow(
                    label = "Backup folder",
                    value = if (state.backupFolder != null) "Selected" else "",
                    onPick = { backupPicker.launch(null) },
                )
                Text(
                    "A timestamped copy of each workbook is saved here before it is changed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            Button(
                onClick = vm::run,
                enabled = state.readyToRun,
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            ) { Text("Process ${state.documents.size} document(s)") }
        }
    }
}

@Composable
private fun AccountCard(
    account: Account,
    hasPassword: Boolean,
    statementCount: Int,
    onAddStatement: () -> Unit,
    onSetPassword: (String) -> Unit,
    onRemove: () -> Unit,
) {
    var editingPassword by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(account.label, fontWeight = FontWeight.SemiBold)
                    Text(
                        buildString {
                            append(account.kind.label)
                            append(if (hasPassword) " · password saved" else " · no password yet")
                            if (statementCount > 0) append(" · $statementCount statement(s) added")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (hasPassword) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Default.Close, contentDescription = "Remove account")
                }
            }

            if (editingPassword || !hasPassword) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Statement password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    onClick = {
                        onSetPassword(password)
                        password = ""
                        editingPassword = false
                    },
                    enabled = password.isNotBlank(),
                ) { Text("Save password") }
            } else {
                TextButton(onClick = { editingPassword = true }) { Text("Change password") }
            }

            FilledTonalButton(onClick = onAddStatement, modifier = Modifier.fillMaxWidth()) {
                Text("Add statement for ${account.label}")
            }
        }
    }
}

@Composable
private fun AddAccountDialog(
    onDismiss: () -> Unit,
    onAdd: (String, AccountKind, String) -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(AccountKind.BANK) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add account") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Name (e.g. HDFC Savings, Axis Card)") },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AccountKind.entries.forEach { option ->
                        FilterChip(
                            selected = kind == option,
                            onClick = { kind = option },
                            label = { Text(option.label) },
                        )
                    }
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Statement password (optional)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Text(
                    "Stored in the Android Keystore, encrypted by the OS. This app has no " +
                        "internet permission, so nothing can leave the phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(label, kind, password) }, enabled = label.isNotBlank()) {
                Text("Add")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ProgressScreen(state: MainViewModel.UiState, message: String = "Reading your documents…") {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(message, style = MaterialTheme.typography.titleMedium)
        state.progress?.let { p ->
            Spacer(Modifier.height(8.dp))
            Text(p.currentFile, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { if (p.total == 0) 0f else p.done.toFloat() / p.total },
                modifier = Modifier.fillMaxWidth(0.7f),
            )
            Text("${p.done} of ${p.total}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ReviewScreen(vm: MainViewModel, state: MainViewModel.UiState) {
    val result = state.result ?: return

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard("Ready to write") {
                Text("${result.purchaseRows.size} row(s) → Purchase Register")
                Text("${result.salesRows.size} row(s) → Sales Register")
                if (result.review.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${result.review.size} item(s) need your decision below. " +
                            "Nothing here is written unless you tick it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        itemsIndexed(result.review) { index, item ->
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(item.sourceFile, fontWeight = FontWeight.SemiBold)
                    Text(
                        item.reason.friendly(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(item.detail, style = MaterialTheme.typography.bodySmall)

                    if (item.rawText.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "What was read:",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(
                            item.rawText.take(600),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }

                    if (item.suggested != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = index in state.approved,
                                onCheckedChange = { vm.toggleApproval(index) },
                            )
                            Text("Add this row anyway")
                        }
                    } else {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "No row is suggested for this one — it needs checking against the " +
                                "original document.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = vm::reset, modifier = Modifier.weight(1f)) { Text("Start over") }
                Button(onClick = vm::write, modifier = Modifier.weight(1f)) { Text("Write to workbooks") }
            }
        }
    }
}

@Composable
private fun DoneScreen(vm: MainViewModel, state: MainViewModel.UiState) {
    Column(
        Modifier.fillMaxSize().padding(top = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Done", style = MaterialTheme.typography.headlineSmall)
        Text(state.writeSummary.orEmpty(), style = MaterialTheme.typography.bodyMedium)
        Button(onClick = vm::reset) { Text("Process another batch") }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun FilePickRow(label: String, value: String, onPick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                value.ifBlank { "Not selected" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = onPick) { Text("Choose") }
    }
}

private fun ProcessJob.Kind.label(): String = when (this) {
    ProcessJob.Kind.PURCHASE_INVOICE -> "Purchase bill"
    ProcessJob.Kind.SALES_INVOICE -> "Sale invoice"
    ProcessJob.Kind.BANK_STATEMENT -> "Bank statement"
    ProcessJob.Kind.CREDIT_CARD_STATEMENT -> "Credit card bill"
}

private fun ReviewReason.friendly(): String = when (this) {
    ReviewReason.LOW_CONFIDENCE -> "Could not read everything"
    ReviewReason.PARSE_FAILED -> "Could not understand this line"
    ReviewReason.EXTRACTION_FAILED -> "Could not open this file"
    ReviewReason.TIMED_OUT -> "Took too long"
    ReviewReason.PASSWORD_REQUIRED -> "Password needed"
    ReviewReason.AMBIGUOUS_CATEGORY -> "Personal or business?"
    ReviewReason.UNMATCHED_CREDIT -> "Unexplained money received"
    ReviewReason.DUPLICATE_SUSPECTED -> "Possible duplicate"
}
