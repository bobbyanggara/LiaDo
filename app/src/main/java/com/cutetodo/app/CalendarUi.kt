package com.cutetodo.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.*

private val idLocale = Locale("id", "ID")
private val monthFmt = SimpleDateFormat("MMMM yyyy", idLocale)
private val dayFmt = SimpleDateFormat("EEEE, d MMMM yyyy", idLocale)
private val shortDayFmt = SimpleDateFormat("EEE, d MMM yyyy", idLocale)

// Satu string berisi banyak format tanggal/jam supaya pencarian bisa memakai
// "oktober", "senin", "29/09", "2026-09-29", "14:30", dst.
private val searchDateFmt = SimpleDateFormat("EEEE d MMMM yyyy '|' dd/MM/yyyy '|' yyyy-MM-dd '|' HH:mm", idLocale)
private val weekdays = listOf("Sen", "Sel", "Rab", "Kam", "Jum", "Sab", "Min")

internal fun startOfDay(ms: Long): Long = Calendar.getInstance().apply {
    timeInMillis = ms
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

internal fun startOfMonth(ms: Long): Long = Calendar.getInstance().apply {
    timeInMillis = startOfDay(ms)
    set(Calendar.DAY_OF_MONTH, 1)
}.timeInMillis

private fun addMonths(monthStart: Long, n: Int): Long = Calendar.getInstance().apply {
    timeInMillis = monthStart
    add(Calendar.MONTH, n)
}.timeInMillis

/** Sel kalender untuk satu bulan (Senin di kolom pertama); null = sel kosong. */
private fun monthCells(monthStart: Long): List<Long?> {
    val c = Calendar.getInstance().apply { timeInMillis = monthStart }
    val offset = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7
    val n = c.getActualMaximum(Calendar.DAY_OF_MONTH)
    val out = ArrayList<Long?>()
    repeat(offset) { out.add(null) }
    for (d in 1..n) { c.set(Calendar.DAY_OF_MONTH, d); out.add(startOfDay(c.timeInMillis)) }
    while (out.size % 7 != 0) out.add(null)
    return out
}

/** Waktu default saat menambah tugas dari kalender: jam 09.00 di hari terpilih (atau jam bulat berikutnya bila hari ini sudah lewat). */
internal fun defaultDueFor(day: Long): Long {
    val now = System.currentTimeMillis()
    val c = Calendar.getInstance().apply {
        timeInMillis = day
        set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    if (c.timeInMillis <= now && isToday(day)) {
        c.timeInMillis = now
        c.add(Calendar.HOUR_OF_DAY, 1)
        c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
    }
    return c.timeInMillis
}

private fun matchesQuery(t: Task, q: String): Boolean {
    val subs = t.subs.lines().joinToString(" ") { it.drop(2) }
    if (listOf(t.title, t.note, subs).any { it.contains(q, ignoreCase = true) }) return true
    val d = t.dueAt ?: return false
    return searchDateFmt.format(Date(d)).contains(q, ignoreCase = true)
}

@Composable
fun CalendarScreen(
    tasks: List<Task>, th: CuteTheme,
    month: Long, selected: Long, query: String,
    onMonth: (Long) -> Unit, onSelect: (Long) -> Unit, onQuery: (String) -> Unit, onClose: () -> Unit,
    onDone: (Task) -> Unit, onStar: (Task) -> Unit, onSub: (Task, Int) -> Unit,
    onDelete: (Task) -> Unit, onEdit: (Task) -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val ink = cs.onBackground
    val today = remember { startOfDay(System.currentTimeMillis()) }

    val dated = remember(tasks) { tasks.filter { it.dueAt != null } }
    val byDay = remember(dated) { dated.groupBy { startOfDay(it.dueAt ?: 0L) } }
    val q = query.trim()
    val searching = q.isNotEmpty()
    val results = remember(dated, q) {
        if (q.isEmpty()) emptyList() else dated.filter { matchesQuery(it, q) }.sortedBy { it.dueAt }
    }
    val resultsByDay = remember(results) { results.groupBy { startOfDay(it.dueAt ?: 0L) } }
    val cells = remember(month) { monthCells(month) }
    val dayTasks = remember(byDay, selected) {
        byDay[selected].orEmpty().sortedWith(compareBy<Task>({ it.done }, { it.dueAt }))
    }

    fun jumpTo(day: Long) { onSelect(day); onMonth(startOfMonth(day)); onQuery("") }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 90.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ---- bar atas ----
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Tutup kalender", tint = th.primary) }
                Text("Kalender", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { jumpTo(today) }) {
                    Icon(Icons.Rounded.Today, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Hari ini")
                }
            }
        }

        // ---- pencarian ----
        item {
            OutlinedTextField(
                query, onQuery, Modifier.fillMaxWidth(),
                placeholder = { Text("Cari tugas, tanggal, bulan, jam…") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = if (query.isEmpty()) null else {
                    { IconButton(onClick = { onQuery("") }) { Icon(Icons.Rounded.Close, "Hapus pencarian") } }
                },
                singleLine = true, shape = RoundedCornerShape(24.dp)
            )
        }

        // ---- navigasi bulan + grid ----
        item {
            Column {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onMonth(addMonths(month, -1)) }) {
                        Icon(Icons.Rounded.ChevronLeft, "Bulan sebelumnya", tint = th.primary)
                    }
                    Text(
                        monthFmt.format(Date(month)).replaceFirstChar { it.uppercase() },
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                            .clickable { pickDate(ctx, th.dark, selected) { jumpTo(startOfDay(it)) } }
                            .padding(vertical = 8.dp),
                        textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold, fontSize = 18.sp
                    )
                    IconButton(onClick = { onMonth(addMonths(month, 1)) }) {
                        Icon(Icons.Rounded.ChevronRight, "Bulan berikutnya", tint = th.primary)
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    weekdays.forEach {
                        Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 12.sp,
                            fontWeight = FontWeight.Medium, color = ink.copy(alpha = 0.7f))
                    }
                }
                Spacer(Modifier.height(4.dp))
                cells.chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth()) {
                        week.forEach { day ->
                            Box(Modifier.weight(1f).height(46.dp), contentAlignment = Alignment.Center) {
                                if (day != null) DayCell(
                                    day, th, selected = day == selected, isToday = day == today,
                                    list = byDay[day], hit = searching && day in resultsByDay, dim = searching && day !in resultsByDay
                                ) { onSelect(day); if (searching) onQuery("") }
                            }
                        }
                    }
                }
            }
        }

        if (searching) {
            // ---- hasil pencarian (semua tanggal) ----
            item {
                Text(
                    if (results.isEmpty()) "Tidak ada hasil untuk \"$q\"" else "${results.size} hasil untuk \"$q\"",
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                )
            }
            resultsByDay.forEach { (day, list) ->
                item(key = "hdr$day") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(shortDayFmt.format(Date(day)), Modifier.weight(1f), fontSize = 13.sp,
                            fontWeight = FontWeight.Medium, color = th.primary)
                        TextButton(onClick = { jumpTo(day) }) { Text("Lihat di kalender") }
                    }
                }
                items(list, key = { it.id }) { t ->
                    TaskCard(t, th, { onDone(t) }, { onStar(t) }, { i -> onSub(t, i) }, { onDelete(t) }) { onEdit(t) }
                }
            }
        } else {
            // ---- tugas pada tanggal terpilih ----
            item {
                Text(
                    dayFmt.format(Date(selected)) +
                        (if (selected == today) " (hari ini)" else "") +
                        (if (dayTasks.isEmpty()) "" else " • ${dayTasks.size} tugas"),
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold
                )
            }
            if (dayTasks.isEmpty()) item {
                Text(
                    "Tidak ada tugas di tanggal ini\nTekan ＋ untuk menambah",
                    Modifier.fillMaxWidth().padding(vertical = 24.dp), textAlign = TextAlign.Center,
                    color = ink.copy(alpha = 0.7f)
                )
            }
            items(dayTasks, key = { it.id }) { t ->
                TaskCard(t, th, { onDone(t) }, { onStar(t) }, { i -> onSub(t, i) }, { onDelete(t) }) { onEdit(t) }
            }
        }
    }
}

@Composable
private fun DayCell(
    day: Long, th: CuteTheme, selected: Boolean, isToday: Boolean,
    list: List<Task>?, hit: Boolean, dim: Boolean, onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val num = remember(day) { Calendar.getInstance().apply { timeInMillis = day }.get(Calendar.DAY_OF_MONTH) }
    val bg = when { selected -> th.primary; hit -> th.accent; else -> Color.Transparent }
    val fg = if (selected) cs.onPrimary else cs.onBackground
    val now = System.currentTimeMillis()
    val allDone = !list.isNullOrEmpty() && list.all { it.done }
    val hasOverdue = list?.any { !it.done && (it.dueAt ?: Long.MAX_VALUE) < now } == true
    val dotColor = when {
        selected -> cs.onPrimary
        allDone -> cs.onBackground.copy(alpha = 0.4f)
        hasOverdue -> th.overdue
        else -> th.primary
    }
    Box(
        Modifier.size(42.dp).clip(CircleShape).background(bg)
            .then(if (isToday && !selected) Modifier.border(1.5.dp, th.primary, CircleShape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                num.toString(), fontSize = 15.sp,
                fontWeight = if (isToday || selected) FontWeight.Bold else FontWeight.Normal,
                color = fg.copy(alpha = if (dim && !selected) 0.35f else 1f)
            )
            Spacer(Modifier.height(2.dp))
            val dots = minOf(list?.size ?: 0, 3)
            if (dots == 0) Spacer(Modifier.height(4.dp))
            else Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(dots) { Box(Modifier.size(4.dp).clip(CircleShape).background(dotColor)) }
            }
        }
    }
}
