package ro.memento

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.*

class MainActivity : ComponentActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        N.channel(this); N.daily(this)
        if (Build.VERSION.SDK_INT >= 33)
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
        val save = registerForActivityResult(ActivityResultContracts.CreateDocument("text/calendar")) { u ->
            u?.let { lifecycleScope.launch(Dispatchers.IO) {
                contentResolver.openOutputStream(it)?.use { o -> o.write(ics(DB.get(this@MainActivity).dao().list()).toByteArray()) }
            } }
        }
        val backup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { u ->
            u?.let { lifecycleScope.launch(Dispatchers.IO) {
                val d = DB.get(this@MainActivity).dao()
                contentResolver.openOutputStream(it)?.use { o -> o.write(Backup.toJson(d.list(), d.listR()).toByteArray()) }
            } }
        }
        val restore = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
            u?.let { lifecycleScope.launch(Dispatchers.IO) {
                val ok = runCatching {
                    val txt = contentResolver.openInputStream(it)!!.bufferedReader().readText()
                    Backup.restore(this@MainActivity, txt)
                }.isSuccess
                launch(Dispatchers.Main) { Toast.makeText(this@MainActivity, if (ok) "Date restaurate" else "Fișier invalid", Toast.LENGTH_LONG).show() }
            } }
        }
        setContent { MaterialTheme { App({ save.launch("memento.ics") }, { backup.launch("memento-backup.json") }, { restore.launch(arrayOf("*/*")) }) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(onExport: () -> Unit, onBackup: () -> Unit, onRestore: () -> Unit) {
    val c = LocalContext.current; val d = remember { DB.get(c).dao() }; val sc = rememberCoroutineScope()
    val all by d.all().collectAsState(emptyList())
    var tab by remember { mutableStateOf<String?>(null) }
    var add by remember { mutableStateOf(false) }; var sel by remember { mutableStateOf<Item?>(null) }
    var menu by remember { mutableStateOf(false) }; var confirm by remember { mutableStateOf(false) }
    val today = LocalDate.now().toEpochDay()
    val shown = all.filter { if (tab == "DONE") it.done else !it.done && (tab == null || it.cat == tab) }
        .sortedBy { if (it.cat == Cat.BIRTH.name) nextBirthday(it.due, today) else it.due }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Memento") }, actions = { Box {
            TextButton({ menu = true }) { Text("⋮") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("Exportă calendar (.ics)") }, onClick = { menu = false; onExport() })
                DropdownMenuItem(text = { Text("Salvează copie de siguranță") }, onClick = { menu = false; onBackup() })
                DropdownMenuItem(text = { Text("Restaurează din copie") }, onClick = { menu = false; confirm = true })
            } } }) },
        floatingActionButton = { FloatingActionButton({ add = true }) { Text("+") } }
    ) { p ->
        Column(Modifier.padding(p)) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(tab == null, { tab = null }, { Text("Toate") })
                Cat.values().forEach { k -> FilterChip(tab == k.name, { tab = k.name }, { Text(k.label) }) }
                FilterChip(tab == "DONE", { tab = "DONE" }, { Text("Efectuate / Plătite") })
            }
            LazyColumn {
                items(shown) { i ->
                    val bd = i.cat == Cat.BIRTH.name; val nb = if (bd) nextBirthday(i.due, today) else i.due; val left = nb - today
                    ListItem(
                        headlineContent = { Text(i.title) },
                        supportingContent = { Text(listOfNotNull(i.grp.ifBlank { null }, if (bd) "împlinește ${ageAt(i.due, nb)} ani, ${fmt(nb)}" else fmt(if (i.done) i.doneAt else i.due)).joinToString(" · ")) },
                        trailingContent = { if (!i.done) Text(if (left < 0) "întârziat ${-left} z" else "$left z", color = if (left <= 7) Color.Red else Color.Unspecified) },
                        modifier = Modifier.clickable { sel = i })
                    HorizontalDivider()
                }
            }
        }
    }

    if (add) AddDlg({ add = false }) { n ->
        add = false
        sc.launch {
            val id = d.add(n)
            if (n.cat == Cat.GENERAL.name) N.schedule(c, n.copy(id = id),
                LocalDateTime.of(LocalDate.ofEpochDay(n.due), LocalTime.of(n.minute / 60, n.minute % 60)).ms())
        }
    }

    if (confirm) AlertDialog({ confirm = false }, title = { Text("Restaurare") },
        text = { Text("Toate datele curente vor fi înlocuite cu cele din copia de siguranță.") },
        confirmButton = { TextButton({ confirm = false; onRestore() }) { Text("Continuă") } },
        dismissButton = { TextButton({ confirm = false }) { Text("Anulează") } })

    sel?.let { i ->
        val isIdx = i.cat == Cat.INDEX.name
        var v by remember { mutableStateOf("") }
        val hist by d.readings(i.id).collectAsState(emptyList())
        AlertDialog({ sel = null }, title = { Text(i.title) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                if (i.note.isNotBlank()) Text(i.note)
                if (isIdx) {
                    OutlinedTextField(v, { v = it }, label = { Text("Index transmis") })
                    Text("Istoric:"); hist.forEach { Text("${fmt(it.day)}: ${it.value}") }
                }
            } },
            confirmButton = { if (!i.done && i.cat != Cat.BIRTH.name) TextButton({ sc.launch { Repo.complete(d, i, if (isIdx) v else null) }; sel = null },
                enabled = !isIdx || v.isNotBlank()) { Text(if (isIdx) "Transmis" else "Gata / Plătit") } },
            dismissButton = { TextButton({ sc.launch { d.del(i) }; sel = null }) { Text("Șterge") } })
    }
}

@Composable
fun AddDlg(onCancel: () -> Unit, onDone: (Item) -> Unit) {
    val c = LocalContext.current
    var cat by remember { mutableStateOf(Cat.AUTO) }
    var t by remember { mutableStateOf("") }; var g by remember { mutableStateOf("") }; var n by remember { mutableStateOf("") }
    var due by remember { mutableStateOf(LocalDate.now().plusDays(30).toEpochDay()) }
    var st by remember { mutableStateOf(LocalDate.now().toEpochDay()) }
    var min by remember { mutableStateOf(540) }
    var off by remember { mutableStateOf("30,15,1") }; var rep by remember { mutableStateOf("0") }
    fun pick(cur: Long, f: (Long) -> Unit) { val x = LocalDate.ofEpochDay(cur)
        DatePickerDialog(c, { _, y, m, dd -> f(LocalDate.of(y, m + 1, dd).toEpochDay()) }, x.year, x.monthValue - 1, x.dayOfMonth).show() }

    AlertDialog(onCancel, title = { Text("Memento nou") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Cat.values().forEach { k -> FilterChip(cat == k, {
                    cat = k; off = when (k) { Cat.BILL, Cat.PAY -> "3,1"; Cat.INDEX, Cat.GENERAL, Cat.BIRTH -> ""; else -> "30,15,1" }
                    rep = if (k == Cat.INDEX) "1" else "0"
                    if (k == Cat.BIRTH) due = LocalDate.now().minusYears(30).toEpochDay() }, { Text(k.label) }) }
            }
            OutlinedTextField(t, { t = it }, label = { Text(if (cat == Cat.BIRTH) "Nume (ex: Andrei)" else "Titlu (RCA, ITP, Pașaport, Apă, Rată eMAG...)") })
            if (cat.groupLabel.isNotBlank()) OutlinedTextField(g, { g = it }, label = { Text(cat.groupLabel) })
            OutlinedTextField(n, { n = it }, label = { Text("Descriere (opțional)") })
            TextButton({ pick(due) { due = it } }) { Text(if (cat == Cat.BIRTH) "Data nașterii: ${fmt(due)}" else "Termen: ${fmt(due)}") }
            if (cat == Cat.INDEX) TextButton({ pick(st) { st = it } }) { Text("Începe transmiterea din: ${fmt(st)}") }
            if (cat == Cat.GENERAL) TextButton({ TimePickerDialog(c, { _, h, m -> min = h * 60 + m }, min / 60, min % 60, true).show() }) {
                Text("Ora: %02d:%02d".format(min / 60, min % 60)) }
            if (cat != Cat.INDEX && cat != Cat.GENERAL && cat != Cat.BIRTH) OutlinedTextField(off, { off = it }, label = { Text("Notificări cu X zile înainte") })
            if (cat in listOf(Cat.BILL, Cat.PAY, Cat.INDEX)) OutlinedTextField(rep, { rep = it }, label = { Text("Se repetă la câte luni (0 = nu)") })
        } },
        confirmButton = { TextButton({ onDone(Item(cat = cat.name, title = t.trim(), grp = g.trim(), note = n.trim(), due = due,
            start = if (cat == Cat.INDEX) st else 0, minute = min, offsets = off, daily = cat == Cat.INDEX,
            repeatMonths = rep.toIntOrNull() ?: 0)) }, enabled = t.isNotBlank()) { Text("Salvează") } },
        dismissButton = { TextButton(onCancel) { Text("Anulează") } })
}
