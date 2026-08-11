package tech.gonxt.kate.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.gonxt.kate.KateViewModel
import tech.gonxt.kate.ui.graph.MemoryGraphView
import tech.gonxt.kate.ui.theme.KateColors

private val TABS = listOf("GRAPH", "ANSWERS", "BUILDS", "QUEUE")

/** Iteration 4: the in-app dashboard — memory tree visible and editable. */
@Composable
fun DashboardScreen(vm: KateViewModel, onBack: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KateColors.Base)
            .padding(horizontal = 16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 12.dp, bottom = 10.dp),
        ) {
            OutlinedButton(
                onClick = onBack,
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, KateColors.Line),
            ) {
                Text("BACK", style = MaterialTheme.typography.labelMedium, color = KateColors.TextDim)
            }
            Spacer(Modifier.width(12.dp))
            Text("DASHBOARD", style = MaterialTheme.typography.titleLarge, color = KateColors.Cyan)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            TABS.forEachIndexed { i, label ->
                val active = tab == i
                OutlinedButton(
                    onClick = { tab = i },
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, if (active) KateColors.Cyan else KateColors.Line),
                    modifier = Modifier.weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (active) KateColors.Cyan else KateColors.TextDim,
                    )
                }
            }
        }

        when (tab) {
            0 -> GraphTab(vm)
            1 -> AnswersTab(vm)
            2 -> BuildsTab(vm)
            3 -> QueueTab(vm)
        }
    }
}

@Composable
private fun GraphTab(vm: KateViewModel) {
    val nodes by vm.graphNodes.collectAsStateWithLifecycle()
    val edges by vm.graphEdges.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<Long?>(null) }
    var detail by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(selected) {
        detail = selected?.let { vm.nodeDetail(it) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (nodes.isEmpty()) {
            Text(
                "no memories yet — talk to kate first",
                style = MaterialTheme.typography.bodyMedium,
                color = KateColors.TextDim,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            MemoryGraphView(
                nodes = nodes,
                edges = edges,
                selectedId = selected,
                onSelect = { selected = it },
            )
        }
        val sel = selected
        if (sel != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .background(KateColors.Surface, RoundedCornerShape(10.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    nodes.firstOrNull { it.id == sel }?.let { "${it.kind.uppercase()} · ${it.label}" } ?: "",
                    style = MaterialTheme.typography.labelMedium,
                    color = KateColors.Cyan,
                )
                Text(
                    detail ?: "…",
                    style = MaterialTheme.typography.bodySmall,
                    color = KateColors.Text,
                    maxLines = 4,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { vm.pinNode(sel) },
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, KateColors.CyanDim),
                    ) { Text("PIN", style = MaterialTheme.typography.labelSmall, color = KateColors.Cyan) }
                    OutlinedButton(
                        onClick = {
                            vm.deleteNode(sel)
                            selected = null
                        },
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, KateColors.Line),
                    ) { Text("DELETE", style = MaterialTheme.typography.labelSmall, color = KateColors.Danger) }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(
                        onClick = { selected = null },
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, KateColors.Line),
                    ) { Text("CLOSE", style = MaterialTheme.typography.labelSmall, color = KateColors.TextDim) }
                }
            }
        }
    }
}

@Composable
private fun AnswersTab(vm: KateViewModel) {
    val answers by vm.kateAnswers.collectAsStateWithLifecycle()
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize().padding(top = 12.dp),
    ) {
        items(answers, key = { it.id }) { t ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(KateColors.Surface, RoundedCornerShape(8.dp))
                    .padding(12.dp),
            ) {
                Text(t.text, style = MaterialTheme.typography.bodySmall, color = KateColors.Text, maxLines = 4)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Text(
                        "${t.modelUsed ?: "?"} · ${t.latencyMs ?: 0}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = KateColors.TextDim,
                    )
                    Spacer(Modifier.weight(1f))
                    RatingButton("👍", t.rating == 1) { vm.rateTurn(t.id, if (t.rating == 1) 0 else 1) }
                    Spacer(Modifier.width(6.dp))
                    RatingButton("👎", t.rating == -1) { vm.rateTurn(t.id, if (t.rating == -1) 0 else -1) }
                }
            }
        }
    }
}

@Composable
private fun RatingButton(glyph: String, active: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, if (active) KateColors.Cyan else KateColors.Line),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 2.dp),
    ) { Text(glyph, style = MaterialTheme.typography.labelMedium) }
}

@Composable
private fun BuildsTab(vm: KateViewModel) {
    val skills by vm.skills.collectAsStateWithLifecycle()
    val runs by vm.skillRuns.collectAsStateWithLifecycle()
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize().padding(top = 12.dp),
    ) {
        items(skills, key = { "s" + it.id }) { s ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(KateColors.Surface, RoundedCornerShape(8.dp))
                    .padding(12.dp),
            ) {
                Text("${s.name} · v${s.version}", style = MaterialTheme.typography.bodyMedium, color = KateColors.Cyan)
                Text(
                    "via ${s.createdVia} · say “run ${s.name.lowercase()} on …”",
                    style = MaterialTheme.typography.labelSmall,
                    color = KateColors.TextDim,
                )
                val skillRuns = runs.filter { it.skillId == s.id }
                if (skillRuns.isNotEmpty()) {
                    Text(
                        "runs: " + skillRuns.take(5).joinToString { "#${it.id} ${it.status}" },
                        style = MaterialTheme.typography.labelSmall,
                        color = KateColors.Text,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueTab(vm: KateViewModel) {
    val runs by vm.skillRuns.collectAsStateWithLifecycle()
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize().padding(top = 12.dp),
    ) {
        items(runs, key = { it.id }) { r ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(KateColors.Surface, RoundedCornerShape(8.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("#${r.id} ${r.skillId}", style = MaterialTheme.typography.bodySmall, color = KateColors.Text)
                    Text(
                        "${r.inputsJson.take(60)} · ${r.ranOn}",
                        style = MaterialTheme.typography.labelSmall,
                        color = KateColors.TextDim,
                    )
                }
                Text(
                    r.status.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = when (r.status) {
                        "done", "announced" -> KateColors.Cyan
                        "failed" -> KateColors.Danger
                        else -> KateColors.TextDim
                    },
                )
            }
        }
    }
}
