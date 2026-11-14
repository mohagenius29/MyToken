package com.mytoken.app.ui

import android.Manifest
import android.app.Application
import android.content.Intent
import android.provider.Settings
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mytoken.app.data.AppDb
import com.mytoken.app.data.Meter
import com.mytoken.app.data.Prefs
import com.mytoken.app.data.Purchase
import com.mytoken.app.data.Reading
import com.mytoken.app.parser.Fields
import com.mytoken.app.sms.ReviewItem
import com.mytoken.app.data.display
import com.mytoken.app.export.Exporter
import com.mytoken.app.logic.Insight
import com.mytoken.app.logic.Insights
import com.mytoken.app.parser.KplcParser
import com.mytoken.app.sms.SmsScanner
import com.mytoken.app.widget.DaysWidget
import com.mytoken.app.work.ReminderWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainVm(app: Application) : AndroidViewModel(app) {
    private val ctx: Application = app
    private val dao = AppDb.get(app).dao()

    val meters = dao.meters().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val purchases = dao.purchases().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val readings = dao.readings().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val review = MutableStateFlow<List<ReviewItem>?>(null)
    val selected = MutableStateFlow(Prefs.selectedMeter(app))
    val threshold = MutableStateFlow(Prefs.threshold(app))
    val message = MutableStateFlow<String?>(null)

    fun select(number: String) {
        selected.value = number
        Prefs.setSelectedMeter(ctx, number)
        DaysWidget.refresh(ctx)
    }

    fun setThreshold(v: Int) {
        val n = v.coerceIn(1, 14)
        threshold.value = n
        Prefs.setThreshold(ctx, n)
    }

    fun scan() {
        viewModelScope.launch {
            val r = try {
                SmsScanner.scan(ctx)
            } catch (e: SecurityException) {
                -1
            } catch (e: Exception) {
                -2
            }
            message.value = when {
                r == -1 -> "Allow SMS permission to scan your messages"
                r == -2 -> "Scan failed"
                r == 0 -> "No new KPLC tokens found"
                else -> "Found $r new token(s)"
            }
            DaysWidget.refresh(ctx)
        }
    }

    fun importText(text: String) {
        viewModelScope.launch {
            var n = 0
            for (chunk in text.split(Regex("\\n\\s*\\n"))) {
                val p = KplcParser.parse(chunk, null) ?: continue
                if (SmsScanner.save(dao, p)) n++
            }
            message.value = if (n > 0) "Imported $n token(s)" else "No KPLC token found in that text"
            DaysWidget.refresh(ctx)
        }
    }

    fun setTracked(number: String, v: Boolean) {
        viewModelScope.launch {
            val m = dao.metersNow().firstOrNull { it.number == number } ?: return@launch
            dao.updateMeter(m.copy(tracked = v))
            DaysWidget.refresh(ctx)
        }
    }

    fun rename(number: String, label: String) {
        viewModelScope.launch {
            val m = dao.metersNow().firstOrNull { it.number == number } ?: return@launch
            dao.updateMeter(m.copy(label = label.trim()))
            DaysWidget.refresh(ctx)
        }
    }

    fun saveBalance(number: String, units: Double) {
        viewModelScope.launch {
            dao.insertReading(Reading(meterNumber = number, units = units, atMillis = System.currentTimeMillis()))
            message.value = "Balance saved"
            DaysWidget.refresh(ctx)
        }
    }

    fun undoLastReading(number: String) {
        viewModelScope.launch {
            val r = dao.readingsNow().filter { it.meterNumber == number }.maxByOrNull { it.atMillis } ?: return@launch
            dao.deleteReading(r)
            DaysWidget.refresh(ctx)
        }
    }

    fun loadReview() {
        viewModelScope.launch {
            review.value = try {
                SmsScanner.review(ctx)
            } catch (e: SecurityException) {
                message.value = "Allow SMS permission first (tap Scan)"
                null
            } catch (e: Exception) {
                message.value = "Could not read messages"
                null
            }
        }
    }

    fun addManual(meter: String, token: String, units: Double, amount: Double) {
        viewModelScope.launch {
            val ex = dao.metersNow().firstOrNull { it.number == meter }
            if (ex == null) dao.insertMeter(Meter(number = meter, tracked = true))
            else if (!ex.tracked) dao.updateMeter(ex.copy(tracked = true))
            val id = dao.insertPurchase(
                Purchase(
                    meterNumber = meter, token = token, units = units, amount = amount,
                    dateMillis = System.currentTimeMillis(), manual = true
                )
            )
            message.value = if (id == -1L) "That token is already saved" else "Token added"
            select(meter)
        }
    }

    fun toggleUsed(p: Purchase) {
        viewModelScope.launch { dao.updatePurchase(p.copy(used = !p.used)) }
    }

    fun delete(p: Purchase) {
        viewModelScope.launch { dao.deletePurchase(p) }
    }

    fun export(pdf: Boolean, onReady: (File, String) -> Unit) {
        viewModelScope.launch {
            val ms = dao.metersNow()
            val ps = dao.purchasesNow()
            val file = withContext(Dispatchers.IO) {
                if (pdf) Exporter.pdf(ctx, ms, ps) else Exporter.csv(ctx, ms, ps)
            }
            onReady(file, if (pdf) "application/pdf" else "text/csv")
        }
    }

    fun backupTo(uri: Uri) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val json = Exporter.backupJson(dao.metersNow(), dao.purchasesNow(), dao.readingsNow())
                    ctx.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                    true
                } catch (e: Exception) {
                    false
                }
            }
            message.value = if (ok) "Backup saved" else "Backup failed"
        }
    }

    fun restoreFrom(uri: Uri) {
        viewModelScope.launch {
            val n = withContext(Dispatchers.IO) {
                try {
                    val json = ctx.contentResolver.openInputStream(uri)?.use { String(it.readBytes()) } ?: return@withContext -1
                    Exporter.restore(json, dao)
                } catch (e: Exception) {
                    -1
                }
            }
            message.value = if (n >= 0) "Restored $n token(s)" else "That file is not a valid MyToken backup"
            DaysWidget.refresh(ctx)
        }
    }
}

class MainActivity : ComponentActivity() {
    private val vm: MainVm by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ReminderWorker.schedule(this)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF1B5E20))) {
                Surface(modifier = Modifier.fillMaxSize()) { App(vm) }
            }
        }
    }
}

private fun fmtDate(ms: Long): String = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(ms))
private fun money(d: Double): String = "KES %,.2f".format(d)
private fun prettyToken(t: String): String = t.chunked(4).joinToString("-")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: MainVm) {
    val ctx = LocalContext.current
    val meters by vm.meters.collectAsState()
    val purchases by vm.purchases.collectAsState()
    val readings by vm.readings.collectAsState()
    val review by vm.review.collectAsState()
    val selected by vm.selected.collectAsState()
    val message by vm.message.collectAsState()
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snack.showSnackbar(it)
            vm.message.value = null
        }
    }

    var showHelp by remember { mutableStateOf(false) }
    var showPaste by remember { mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.READ_SMS] == true) vm.scan() else showHelp = true
    }
    val askAndScan: () -> Unit = {
        val perms = mutableListOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        permLauncher.launch(perms.toTypedArray())
    }

    var showAdd by remember { mutableStateOf(false) }
    var prefill by remember { mutableStateOf<Fields?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    val tracked = meters.filter { it.tracked }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = { TopAppBar(title = { Text("MyToken", fontWeight = FontWeight.Bold) }) },
        bottomBar = {
            if (tracked.isNotEmpty()) {
                NavigationBar {
                    listOf("Home", "Charts", "Meters", "More").forEachIndexed { i, label ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Text(listOf("⚡", "📊", "🔢", "⚙")[i]) },
                            label = { Text(label) }
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (tracked.isNotEmpty() && tab == 0) {
                ExtendedFloatingActionButton(onClick = { showAdd = true }) { Text("+ Add token") }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad)) {
            val rv = review
            if (rv != null) {
                ReviewScreen(vm, rv) { f -> prefill = f; showAdd = true }
            } else if (tracked.isEmpty()) {
                Setup(meters, purchases, askAndScan, { showAdd = true }, { showPaste = true }, vm::setTracked)
            } else when (tab) {
                0 -> Home(vm, tracked, selected, purchases, readings)
                1 -> Charts(vm, tracked, selected, purchases)
                2 -> MetersTab(vm, meters, purchases, readings, askAndScan)
                else -> MoreTab(vm, askAndScan, { showPaste = true }, { vm.loadReview() })
            }
        }
    }

    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text("SMS permission is blocked") },
            text = {
                Text(
                    "Android blocks SMS access for apps installed outside the Play Store. To allow it:\n\n" +
                        "1. Tap Open app settings.\n" +
                        "2. Tap the three-dot menu (top right) and choose Allow restricted settings. Confirm with your PIN or fingerprint.\n" +
                        "3. Go to Permissions, then SMS, then Allow.\n" +
                        "4. Come back here and tap Scan again.\n\n" +
                        "No luck? Use Paste a token message instead."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showHelp = false
                    ctx.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", ctx.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }) { Text("Open app settings") }
            },
            dismissButton = { TextButton(onClick = { showHelp = false }) { Text("Close") } }
        )
    }

    if (showPaste) {
        TextDialog(
            title = "Paste token message(s)",
            label = "Paste your KPLC SMS here",
            initial = "",
            numeric = false,
            onDismiss = { showPaste = false },
            onDone = { t ->
                vm.importText(t)
                showPaste = false
            }
        )
    }

    if (showAdd) {
        val pf = prefill
        ManualAddDialog(
            defaultMeter = pf?.meter ?: selected ?: tracked.firstOrNull()?.number ?: "",
            initialToken = pf?.token ?: "",
            initialUnits = pf?.units?.toString() ?: "",
            initialAmount = pf?.amount?.toString() ?: "",
            onDismiss = { showAdd = false; prefill = null },
            onSave = { m, t, u, a ->
                vm.addManual(m, t, u, a)
                showAdd = false
                prefill = null
            }
        )
    }
}

@Composable
fun Setup(
    meters: List<Meter>,
    purchases: List<Purchase>,
    onScan: () -> Unit,
    onAdd: () -> Unit,
    onPaste: () -> Unit,
    onTrack: (String, Boolean) -> Unit
) {
    Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (meters.isEmpty()) {
            Text("Welcome to MyToken", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("MyToken finds your KPLC token messages, saves every token and tells you when you are about to run out of units.")
            Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) { Text("Scan my messages") }
            OutlinedButton(onClick = onPaste, modifier = Modifier.fillMaxWidth()) { Text("Paste a token message") }
            OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("Add a token manually") }
            Text(
                "Scanning needs permission to read your SMS. Everything stays on your phone.",
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            Text("Choose your meter", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("These meter numbers were found in your messages. Tick the ones you want to track.")
            meters.forEach { m ->
                val count = purchases.count { it.meterNumber == m.number }
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = m.tracked, onCheckedChange = { onTrack(m.number, it) })
                        Column {
                            Text(m.number, fontWeight = FontWeight.Bold)
                            Text("$count token message(s)", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("Add a token manually") }
        }
    }
}

@Composable
fun Home(vm: MainVm, tracked: List<Meter>, selectedNum: String?, purchases: List<Purchase>, readings: List<Reading>) {
    val m = tracked.firstOrNull { it.number == selectedNum } ?: tracked.first()
    val mine = purchases.filter { it.meterNumber == m.number }
    val myReadings = readings.filter { it.meterNumber == m.number }
    val ins = remember(m, mine, myReadings) { Insights.compute(m, mine, myReadings) }
    val effs = remember(mine) { Insights.effective(mine).filter { !it.duplicate }.sortedByDescending { it.p.dateMillis } }
    var balDialog by remember { mutableStateOf(false) }
    val clip = LocalClipboardManager.current

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (tracked.size > 1) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tracked.forEach { mt ->
                        FilterChip(
                            selected = mt.number == m.number,
                            onClick = { vm.select(mt.number) },
                            label = { Text(mt.display()) }
                        )
                    }
                }
            }
        }
        item { InsightCard(ins, m, myReadings.lastOrNull(), { balDialog = true }, { vm.undoLastReading(m.number) }) }
        item { Text("Purchases", style = MaterialTheme.typography.titleMedium) }
        if (effs.isEmpty()) item { Text("No tokens for this meter yet.") }
        items(effs, key = { it.p.id }) { e ->
            val p = e.p
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(fmtDate(p.dateMillis), fontWeight = FontWeight.Bold)
                        Text(money(p.amount))
                    }
                    if (p.kind == "PAYMENT") {
                        Text("Payment message - no token message found", style = MaterialTheme.typography.bodySmall)
                        Text("About %.1f units (estimated)".format(e.units))
                    } else {
                        val per = if (e.units > 0 && p.amount > 0) "  -  KES %.2f/unit".format(p.amount / e.units) else ""
                        val est = if (e.estimated) " (estimated)" else ""
                        Text("%.2f units".format(e.units) + est + per, style = MaterialTheme.typography.bodyMedium)
                        Text(prettyToken(p.token), fontFamily = FontFamily.Monospace, fontSize = 15.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (p.kind != "PAYMENT") {
                            TextButton(onClick = {
                                clip.setText(AnnotatedString(p.token))
                                vm.message.value = "Token copied"
                            }) { Text("Copy") }
                            Checkbox(checked = p.used, onCheckedChange = { vm.toggleUsed(p) })
                            Text("Used")
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { vm.delete(p) }) { Text("Delete") }
                    }
                }
            }
        }
    }

    if (balDialog) {
        TextDialog(
            title = "Current meter balance",
            label = "Units now showing on your meter",
            initial = "",
            numeric = true,
            onDismiss = { balDialog = false },
            onDone = { s ->
                s.toDoubleOrNull()?.let { vm.saveBalance(m.number, it) }
                balDialog = false
            }
        )
    }
}

@Composable
fun InsightCard(ins: Insight, m: Meter, last: Reading?, onBalance: () -> Unit, onUndo: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(m.display(), style = MaterialTheme.typography.labelLarge)
            if (ins.daysLeft != null) {
                Text("About %.1f days left".format(ins.daysLeft), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            } else {
                Text("Not enough data yet", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Tap Update my balance and type the units now showing on your meter. Do it again in a few days and I will learn how fast you use units.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            ins.remainingUnits?.let { Text("Estimated units left: %.1f".format(it)) }
            ins.dailyUnits?.let { Text("Average use: %.1f units/day".format(it)) }
            ins.avgPrice?.let { Text("Average price: KES %.2f per unit".format(it)) }
            Text("Total bought: %.1f units for %s".format(ins.totalUnits, money(ins.totalSpend)))
            Text("Forecast based on: ${ins.basis}", style = MaterialTheme.typography.bodySmall)
            if (ins.estimatedCount > 0) {
                Text(
                    "${ins.estimatedCount} purchase(s) have estimated units (payment seen, token message not read).",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            last?.let { Text("Last balance: %.1f units on %s".format(it.units, fmtDate(it.atMillis)), style = MaterialTheme.typography.bodySmall) }
            Row {
                TextButton(onClick = onBalance) { Text("Update my balance") }
                if (last != null) TextButton(onClick = onUndo) { Text("Undo last") }
            }
        }
    }
}

@Composable
fun Charts(vm: MainVm, tracked: List<Meter>, selectedNum: String?, purchases: List<Purchase>) {
    val m = tracked.firstOrNull { it.number == selectedNum } ?: tracked.first()
    val mine = purchases.filter { it.meterNumber == m.number }
    val effs = Insights.effective(mine).filter { !it.duplicate }
    val keyFmt = SimpleDateFormat("yyyyMM", Locale.US)
    val labelFmt = SimpleDateFormat("MMM yy", Locale.getDefault())
    val groups = effs.groupBy { keyFmt.format(Date(it.p.dateMillis)) }.toSortedMap().entries.toList().takeLast(6)

    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (tracked.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tracked.forEach { mt ->
                    FilterChip(selected = mt.number == m.number, onClick = { vm.select(mt.number) }, label = { Text(mt.display()) })
                }
            }
        }
        if (groups.isEmpty()) {
            Text("No purchases yet. Charts will appear after your first token.")
        } else {
            BarBlock("Monthly spend (KES)", groups.map { g -> labelFmt.format(Date(g.value.first().p.dateMillis)) to g.value.sumOf { e -> e.p.amount } }, Color(0xFF2E7D32))
            BarBlock("Monthly units bought", groups.map { g -> labelFmt.format(Date(g.value.first().p.dateMillis)) to g.value.sumOf { e -> e.units } }, Color(0xFFF9A825))
        }
    }
}

@Composable
fun BarBlock(title: String, data: List<Pair<String, Double>>, color: Color) {
    val max = (data.maxOfOrNull { it.second } ?: 1.0).coerceAtLeast(1.0)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            data.forEach { (label, v) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, modifier = Modifier.width(64.dp), style = MaterialTheme.typography.bodySmall)
                    Box(
                        Modifier
                            .weight(1f)
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth((v / max).toFloat().coerceIn(0.03f, 1f))
                                .height(20.dp)
                                .background(color)
                        )
                    }
                    Text("%,.0f".format(v), modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun MetersTab(vm: MainVm, meters: List<Meter>, purchases: List<Purchase>, readings: List<Reading>, onScan: () -> Unit) {
    var renaming by remember { mutableStateOf<Meter?>(null) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(
                "Tick a meter to track it. Give each one a name such as Home, Shop or a tenant's name.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        items(meters, key = { it.number }) { m ->
            val mine = purchases.filter { it.meterNumber == m.number }
            val ins = Insights.compute(m, mine, readings.filter { it.meterNumber == m.number })
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = m.tracked, onCheckedChange = { vm.setTracked(m.number, it) })
                    Column(Modifier.weight(1f)) {
                        Text(m.display(), fontWeight = FontWeight.Bold)
                        if (m.label.isNotBlank()) Text(m.number, style = MaterialTheme.typography.bodySmall)
                        Text(
                            "${mine.size} record(s) - %.1f units - %s".format(ins.totalUnits, money(ins.totalSpend)),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    TextButton(onClick = { renaming = m }) { Text("Rename") }
                }
            }
        }
        item { OutlinedButton(onClick = onScan, modifier = Modifier.fillMaxWidth()) { Text("Rescan messages") } }
    }
    renaming?.let { m ->
        TextDialog(
            title = "Name this meter",
            label = "Name (e.g. Home, Shop, Tenant A)",
            initial = m.label,
            numeric = false,
            onDismiss = { renaming = null },
            onDone = { s ->
                vm.rename(m.number, s)
                renaming = null
            }
        )
    }
}

@Composable
fun ReviewScreen(vm: MainVm, list: List<ReviewItem>, onAdd: (Fields) -> Unit) {
    val clip = LocalClipboardManager.current
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Messages I looked at", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.review.value = null }) { Text("Close") }
            }
            Text(
                "Green: understood and saved. Red: looks like KPLC but was not recognised. For red ones tap Add manually (fields are pre-filled) or Copy the text and send it to the developer.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (list.isEmpty()) item { Text("No KPLC-looking messages found in your inbox.") }
        items(list) { r ->
            val ok = r.kind != null
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (ok) Color(0xFFE8F5E9) else Color(0xFFFFEBEE))) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${r.address} - ${fmtDate(r.date)}", fontWeight = FontWeight.Bold)
                    Text(
                        when (r.kind) {
                            "TOKEN" -> "Understood as a token message"
                            "PAYMENT" -> "Understood as a payment to KPLC"
                            else -> "Not recognised"
                        },
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(r.body, style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = {
                            clip.setText(AnnotatedString(r.body))
                            vm.message.value = "Message copied"
                        }) { Text("Copy") }
                        if (!ok) TextButton(onClick = { onAdd(r.fields) }) { Text("Add manually") }
                    }
                }
            }
        }
    }
}

@Composable
fun MoreTab(vm: MainVm, onScan: () -> Unit, onPaste: () -> Unit, onReview: () -> Unit) {
    val ctx = LocalContext.current
    val threshold by vm.threshold.collectAsState()
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { vm.backupTo(it) }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.restoreFrom(it) }
    }
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Low-balance reminder", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { vm.setThreshold(threshold - 1) }) { Text("-") }
            Text("  Remind me at $threshold day(s) left  ", modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { vm.setThreshold(threshold + 1) }) { Text("+") }
        }
        HorizontalDivider()
        Text("Export", style = MaterialTheme.typography.titleMedium)
        Button(onClick = { vm.export(false) { f, mime -> Exporter.share(ctx, f, mime) } }, modifier = Modifier.fillMaxWidth()) { Text("Share history as CSV") }
        Button(onClick = { vm.export(true) { f, mime -> Exporter.share(ctx, f, mime) } }, modifier = Modifier.fillMaxWidth()) { Text("Share history as PDF") }
        HorizontalDivider()
        Text("Backup", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { backupLauncher.launch("mytoken-backup.json") }, modifier = Modifier.fillMaxWidth()) { Text("Save a backup file") }
        OutlinedButton(onClick = { restoreLauncher.launch(arrayOf("application/json", "*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Restore from backup") }
        HorizontalDivider()
        OutlinedButton(onClick = onScan, modifier = Modifier.fillMaxWidth()) { Text("Rescan messages") }
        OutlinedButton(onClick = onPaste, modifier = Modifier.fillMaxWidth()) { Text("Paste a token message") }
        OutlinedButton(onClick = onReview, modifier = Modifier.fillMaxWidth()) { Text("Review scanned messages") }
        Text(
            "New KPLC token messages are saved automatically when they arrive. Home-screen widget: long-press your home screen and add the MyToken widget.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
fun TextDialog(
    title: String,
    label: String,
    initial: String,
    numeric: Boolean,
    onDismiss: () -> Unit,
    onDone: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text)
            )
        },
        confirmButton = { TextButton(onClick = { onDone(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun ManualAddDialog(
    defaultMeter: String,
    initialToken: String = "",
    initialUnits: String = "",
    initialAmount: String = "",
    onDismiss: () -> Unit,
    onSave: (String, String, Double, Double) -> Unit
) {
    var meter by remember { mutableStateOf(defaultMeter) }
    var token by remember { mutableStateOf(initialToken) }
    var units by remember { mutableStateOf(initialUnits) }
    var amount by remember { mutableStateOf(initialAmount) }
    var error by remember { mutableStateOf<String?>(null) }
    val digits = token.filter { it.isDigit() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a token") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(meter, { meter = it.filter { c -> c.isDigit() } }, label = { Text("Meter number") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(token, { token = it }, label = { Text("20-digit token") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(units, { units = it }, label = { Text("Units") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(amount, { amount = it }, label = { Text("Amount paid (KES)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val u = units.toDoubleOrNull()
                val a = amount.toDoubleOrNull()
                when {
                    meter.length < 6 -> error = "Enter the meter number"
                    digits.length != 20 -> error = "A token has 20 digits"
                    u == null || u <= 0 -> error = "Enter the units"
                    a == null || a < 0 -> error = "Enter the amount"
                    else -> onSave(meter, digits, u, a)
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
