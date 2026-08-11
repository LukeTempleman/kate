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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.gonxt.kate.KateViewModel
import tech.gonxt.kate.models.ModelSpec
import tech.gonxt.kate.models.ModelStatus
import tech.gonxt.kate.models.Models
import tech.gonxt.kate.ui.orb.Orb
import tech.gonxt.kate.ui.theme.KateColors

/**
 * Dev screen from spec M1.2: type anything, hear Kate say it — used to tune
 * pacing, pitch, and chunking. Also hosts voice-model downloads.
 */
@Composable
fun VoiceLabScreen(vm: KateViewModel, onBack: () -> Unit) {
    val orbState by vm.engine.orbState.collectAsStateWithLifecycle()
    val amplitude by vm.engine.speakingAmplitude.collectAsStateWithLifecycle()
    val voiceLabel by vm.ttsRouter.activeLabel.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KateColors.Base)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
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
            Text("VOICE LAB", style = MaterialTheme.typography.titleLarge, color = KateColors.Cyan)
            Spacer(Modifier.weight(1f))
            Text("TTS·$voiceLabel", style = MaterialTheme.typography.labelSmall, color = KateColors.TextDim)
        }

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Orb(state = orbState, amplitude = amplitude, modifier = Modifier.size(150.dp))
        }

        var text by remember {
            mutableStateOf("Hello, I'm Kate. This is my real voice — synthesised entirely on your phone, no cloud involved.")
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = KateColors.CyanDim,
                unfocusedBorderColor = KateColors.Line,
                cursorColor = KateColors.Cyan,
            ),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { vm.speakDirect(text) },
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, KateColors.Cyan),
            ) {
                Text("SPEAK", style = MaterialTheme.typography.labelLarge, color = KateColors.Cyan)
            }
            OutlinedButton(
                onClick = { vm.engine.bargeIn() },
                modifier = Modifier.height(56.dp),
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, KateColors.Line),
            ) {
                Text("STOP", style = MaterialTheme.typography.labelLarge, color = KateColors.TextDim)
            }
        }

        Text("VOICE MODELS", style = MaterialTheme.typography.labelMedium, color = KateColors.CyanDim)
        ModelRow(vm, Models.KOKORO)
        ModelRow(vm, Models.PIPER)
    }
}

@Composable
fun ModelRow(vm: KateViewModel, spec: ModelSpec) {
    val status by vm.modelManager.status(spec).collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(KateColors.Surface, RoundedCornerShape(8.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(spec.displayName, style = MaterialTheme.typography.bodyMedium, color = KateColors.Text)
                Text(
                    "~${spec.approxMB} MB · " + when (val st = status) {
                        ModelStatus.NotDownloaded -> "not downloaded"
                        is ModelStatus.Downloading -> "downloading ${(st.progress * 100).toInt()}%"
                        ModelStatus.Extracting -> "extracting…"
                        ModelStatus.Ready -> "ready"
                        is ModelStatus.Failed -> "failed: ${st.reason}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status == ModelStatus.Ready) KateColors.Cyan else KateColors.TextDim,
                )
            }
            when (status) {
                ModelStatus.NotDownloaded, is ModelStatus.Failed -> OutlinedButton(
                    onClick = { vm.downloadModel(spec) },
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, KateColors.Cyan),
                ) {
                    Text("GET", style = MaterialTheme.typography.labelMedium, color = KateColors.Cyan)
                }
                ModelStatus.Ready -> OutlinedButton(
                    onClick = { vm.deleteModel(spec) },
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, KateColors.Line),
                ) {
                    Text("DEL", style = MaterialTheme.typography.labelMedium, color = KateColors.TextDim)
                }
                else -> {}
            }
        }
        if (status is ModelStatus.Downloading) {
            LinearProgressIndicator(
                progress = { (status as ModelStatus.Downloading).progress },
                modifier = Modifier.fillMaxWidth(),
                color = KateColors.Cyan,
                trackColor = KateColors.Line,
            )
        }
    }
}
