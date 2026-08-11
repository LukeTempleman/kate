package tech.gonxt.kate.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.gonxt.kate.KateViewModel
import tech.gonxt.kate.settings.BrainMode
import tech.gonxt.kate.settings.KateVoice
import tech.gonxt.kate.ui.theme.KateColors

@Composable
fun SettingsScreen(vm: KateViewModel, onBack: () -> Unit, onOpenVoiceLab: () -> Unit = {}) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val lastTurn by vm.engine.lastTurnLatency.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KateColors.Base)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = onBack,
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, KateColors.Line),
            ) {
                Text("BACK", style = MaterialTheme.typography.labelMedium, color = KateColors.TextDim)
            }
            Spacer(Modifier.width(14.dp))
            Text("SETTINGS", style = MaterialTheme.typography.titleLarge, color = KateColors.Cyan)
        }

        SectionLabel("VOICE")
        ChoiceRow(
            options = KateVoice.entries.map { it.label },
            selected = s.voice.ordinal,
            onSelect = { vm.setVoice(KateVoice.entries[it]) },
        )
        OutlinedButton(
            onClick = onOpenVoiceLab,
            shape = RoundedCornerShape(6.dp),
            border = BorderStroke(1.dp, KateColors.CyanDim),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("OPEN VOICE LAB · MODELS & TEST", style = MaterialTheme.typography.labelMedium, color = KateColors.Cyan)
        }

        SectionLabel("BRAIN")
        ChoiceRow(
            options = BrainMode.entries.map { it.label },
            selected = s.brainMode.ordinal,
            onSelect = { vm.setBrainMode(BrainMode.entries[it]) },
        )

        SectionLabel("BEHAVIOUR")
        ToggleRow("Wake word (“Kate”)", s.wakeWordEnabled, vm::setWakeWord)
        ToggleRow("Latency readout", s.latencyReadout, vm::setLatencyReadout)
        ToggleRow("Auto driving mode on car Bluetooth", s.drivingModeAuto, vm::setDrivingModeAuto)

        if (s.latencyReadout) {
            SectionLabel("LAST TURN")
            if (lastTurn.marks.isEmpty()) {
                Text("no turns yet", style = MaterialTheme.typography.bodySmall, color = KateColors.TextDim)
            } else {
                lastTurn.durations().forEach { (stage, ms) ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(stage, style = MaterialTheme.typography.bodySmall, color = KateColors.TextDim)
                        Spacer(Modifier.weight(1f))
                        Text("${ms}ms", style = MaterialTheme.typography.bodySmall, color = KateColors.Text)
                    }
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text("total", style = MaterialTheme.typography.bodySmall, color = KateColors.CyanDim)
                    Spacer(Modifier.weight(1f))
                    Text("${lastTurn.total()}ms", style = MaterialTheme.typography.bodySmall, color = KateColors.Cyan)
                }
            }
        }

        SectionLabel("KEYS")
        KeyField("Groq API key (online brain)", s.groqApiKey, vm::setGroqApiKey)
        KeyField("Picovoice access key (wake word)", s.picovoiceAccessKey, vm::setPicovoiceAccessKey)

        Text(
            "kate 0.1.0 · iteration 1 · milestone 1.1",
            style = MaterialTheme.typography.labelSmall,
            color = KateColors.TextDim,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = KateColors.CyanDim)
}

@Composable
private fun ChoiceRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, label ->
            val active = i == selected
            OutlinedButton(
                onClick = { onSelect(i) },
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, if (active) KateColors.Cyan else KateColors.Line),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) KateColors.Cyan else KateColors.TextDim,
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = KateColors.Text, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = KateColors.CyanDim,
                checkedThumbColor = KateColors.Cyan,
                uncheckedTrackColor = KateColors.Surface,
                uncheckedThumbColor = KateColors.TextDim,
                uncheckedBorderColor = KateColors.Line,
            ),
        )
    }
}

@Composable
private fun KeyField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, style = MaterialTheme.typography.labelSmall, color = KateColors.TextDim) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = KateColors.CyanDim,
            unfocusedBorderColor = KateColors.Line,
            cursorColor = KateColors.Cyan,
        ),
    )
}
