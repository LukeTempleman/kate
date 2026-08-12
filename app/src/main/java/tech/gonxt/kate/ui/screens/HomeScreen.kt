package tech.gonxt.kate.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.gonxt.kate.KateViewModel
import tech.gonxt.kate.core.ChatMessage
import tech.gonxt.kate.core.OrbState
import tech.gonxt.kate.core.Role
import tech.gonxt.kate.ui.orb.Orb
import tech.gonxt.kate.ui.theme.KateColors

@Composable
fun HomeScreen(
    vm: KateViewModel,
    onOpenSettings: () -> Unit,
    onOpenDriving: () -> Unit,
    onOpenDashboard: () -> Unit = {},
    onOpenSetup: () -> Unit = {},
) {
    val orbState by vm.engine.orbState.collectAsStateWithLifecycle()
    val amplitude by vm.engine.speakingAmplitude.collectAsStateWithLifecycle()
    val messages by vm.engine.messages.collectAsStateWithLifecycle()
    val partial by vm.engine.partialUserText.collectAsStateWithLifecycle()
    val brainLabel by vm.activeBrainLabel.collectAsStateWithLifecycle()
    val earsStatus by vm.voicePipeline.status.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KateColors.Base)
            .padding(horizontal = 20.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("ATHENA", style = MaterialTheme.typography.titleLarge, color = KateColors.Cyan)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    "BRAIN·$brainLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = KateColors.TextDim,
                )
                Text(
                    earsStatus.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = KateColors.CyanDim,
                )
            }
            Spacer(Modifier.weight(1f))
            OutlinedButton(
                onClick = onOpenDashboard,
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, KateColors.Line),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
            ) {
                Text("DASH", style = MaterialTheme.typography.labelMedium, color = KateColors.TextDim)
            }
            Spacer(Modifier.width(6.dp))
            OutlinedButton(
                onClick = onOpenDriving,
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, KateColors.Line),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
            ) {
                Text("DRIVE", style = MaterialTheme.typography.labelMedium, color = KateColors.Cyan)
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = KateColors.TextDim)
            }
        }

        if (!vm.setupComplete()) {
            OutlinedButton(
                onClick = onOpenSetup,
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, KateColors.Cyan),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            ) {
                Text(
                    "FINISH SETTING HER UP →",
                    style = MaterialTheme.typography.labelMedium,
                    color = KateColors.Cyan,
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.9f),
            contentAlignment = Alignment.Center,
        ) {
            Orb(state = orbState, amplitude = amplitude, modifier = Modifier.size(240.dp))
        }

        Text(
            text = when {
                partial.isNotEmpty() -> "“$partial”"
                orbState == OrbState.LISTENING -> "listening…"
                orbState == OrbState.THINKING -> "thinking…"
                orbState == OrbState.SPEAKING -> "speaking"
                else -> " "
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (partial.isNotEmpty()) KateColors.Text else KateColors.TextDim,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        )

        val listState = rememberLazyListState()
        LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(messages, key = { it.id }) { msg -> TranscriptRow(msg) }
        }

        InputBar(
            onSend = vm::sendText,
            onTalk = vm::onTalkPressed,
        )
    }
}

@Composable
private fun TranscriptRow(msg: ChatMessage) {
    val isKate = msg.role == Role.KATE
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            if (isKate) "ATHENA" else "YOU",
            style = MaterialTheme.typography.labelSmall,
            color = if (isKate) KateColors.CyanDim else KateColors.TextDim,
        )
        Text(
            msg.text + if (msg.streaming) "▍" else "",
            style = MaterialTheme.typography.bodyLarge,
            color = if (isKate) KateColors.Text else KateColors.TextDim,
        )
    }
}

@Composable
private fun InputBar(onSend: (String) -> Unit, onTalk: () -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = onTalk,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.height(56.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, KateColors.Cyan),
        ) {
            Text("TALK", style = MaterialTheme.typography.labelLarge, color = KateColors.Cyan)
        }
        Spacer(Modifier.width(10.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("type to athena…", color = KateColors.TextDim) },
            singleLine = true,
            modifier = Modifier.weight(1f),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = KateColors.CyanDim,
                unfocusedBorderColor = KateColors.Line,
                cursorColor = KateColors.Cyan,
            ),
        )
        IconButton(
            onClick = {
                onSend(text)
                text = ""
            },
            modifier = Modifier
                .padding(start = 6.dp)
                .size(48.dp)
                .border(1.dp, KateColors.Line, RoundedCornerShape(16.dp)),
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = KateColors.Cyan)
        }
    }
}
