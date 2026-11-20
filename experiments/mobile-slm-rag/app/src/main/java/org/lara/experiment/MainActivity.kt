package org.lara.experiment

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { Screen(vm) } } }
    }
}

@Composable
private fun Screen(vm: MainViewModel) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(vm.chat.size, vm.chat.lastOrNull()?.text) { if (vm.chat.isNotEmpty()) listState.animateScrollToItem(vm.chat.size - 1) }
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(horizontal = 12.dp)) {
        Text(stringResource(R.string.banner), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 4.dp))
        Setup(vm)
        Text(vm.status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
        if (vm.runStatus.isNotEmpty()) Text(vm.runStatus, style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(vm.chat) { line ->
                Box(Modifier.fillMaxWidth(), contentAlignment = if (line.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
                    Surface(shape = RoundedCornerShape(12.dp), color = if (line.fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                        SelectionContainer { Text(line.text, Modifier.padding(10.dp)) }
                    }
                }
            }
        }
        if (vm.teacherOpen) TeacherView(vm)
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text(stringResource(R.string.input_hint)) }, maxLines = 3)
            if (vm.busy) Button({ vm.stop() }) { Text(stringResource(R.string.stop)) }
            else Button({ vm.send(input); input = "" }, enabled = vm.ready) { Text(stringResource(R.string.send)) }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ vm.teacherOpen = !vm.teacherOpen }) { Text(stringResource(R.string.teacher_view)) }
            OutlinedButton({ vm.runKey() }, enabled = vm.ready && !vm.busy) { Text(stringResource(R.string.run_key)) }
        }
    }
}

@Composable
private fun Setup(vm: MainViewModel) {
    var menu by remember { mutableStateOf(false) }
    Column {
        if (vm.models.isEmpty()) Text(stringResource(R.string.no_models, vm.dir.absolutePath), color = MaterialTheme.colorScheme.error)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                OutlinedButton({ menu = true }, Modifier.fillMaxWidth(), enabled = vm.models.isNotEmpty()) {
                    Text(vm.selected?.let { "${it.name} (${it.length() / 1048576} MB)" } ?: stringResource(R.string.model_label), maxLines = 1)
                }
                DropdownMenu(menu, { menu = false }) {
                    vm.models.forEach { m -> DropdownMenuItem({ Text("${m.name} (${m.length() / 1048576} MB)") }, { vm.select(m); menu = false }) }
                }
            }
            TextButton({ vm.refresh() }) { Text(stringResource(R.string.refresh)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.threads_label), style = MaterialTheme.typography.labelMedium)
            listOf(2, 4, 6, 8).forEach { n -> FilterChip(vm.threads == n, { vm.threads = n }, { Text("$n") }) }
            Text(stringResource(R.string.ctx_label), style = MaterialTheme.typography.labelMedium)
            listOf(2048, 4096).forEach { n -> FilterChip(vm.ctxSize == n, { vm.ctxSize = n }, { Text("$n") }) }
        }
        Button({ vm.load() }, enabled = vm.selected != null && !vm.busy) { Text(stringResource(R.string.load)) }
    }
}

@Composable
private fun TeacherView(vm: MainViewModel) {
    val t = vm.lastTurn
    Column(Modifier.fillMaxWidth().heightIn(max = 280.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(8.dp).verticalScroll(rememberScrollState())) {
        if (t == null) { Text(stringResource(R.string.tv_empty)); return@Column }
        val b = t.built
        val mono = FontFamily.Monospace
        Text(stringResource(R.string.tv_guard), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.tv_guard_line, b.whyGuard, b.promptTokens, b.budget), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.tv_chunks), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
        if (b.sources.isEmpty()) Text(stringResource(R.string.tv_none), style = MaterialTheme.typography.bodySmall)
        b.sources.forEach { Text("p${it.page} · ${it.heading} · ${it.tokens} tok" + if (it.id == b.grounded?.id) " ★" else "", style = MaterialTheme.typography.bodySmall) }
        Text(stringResource(R.string.tv_timings), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
        val s = t.stats
        Text(if (s == null) stringResource(R.string.tv_timing_forced) else stringResource(R.string.tv_timing_line, t.retrieveMs, t.ttftS, s.promptMs, s.promptTokens, s.genTokens, s.tokPerSec, t.redactedSentences), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.tv_raw), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
        SelectionContainer { Text(t.raw, fontFamily = mono, fontSize = 11.sp) }
        Text(stringResource(R.string.tv_prompt), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
        SelectionContainer { Text(b.promptText, fontFamily = mono, fontSize = 11.sp) }
    }
}
