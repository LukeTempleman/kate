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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.gonxt.kate.KateViewModel
import tech.gonxt.kate.core.OrbState
import tech.gonxt.kate.models.ModelSpec
import tech.gonxt.kate.models.ModelStatus
import tech.gonxt.kate.models.Models
import tech.gonxt.kate.ui.orb.Orb
import tech.gonxt.kate.ui.theme.KateColors

/** Plain-language onboarding: voice → ears → brain, no model names, no jargon. */
@Composable
fun SetupScreen(vm: KateViewModel, onDone: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KateColors.Base)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Orb(state = OrbState.IDLE, amplitude = 0f, modifier = Modifier.size(120.dp))
        }
        Text(
            "Let's set up Athena",
            style = MaterialTheme.typography.titleLarge,
            color = KateColors.Cyan,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Three quick steps. Big downloads are best on Wi-Fi — you can leave and come back any time.",
            style = MaterialTheme.typography.bodyMedium,
            color = KateColors.TextDim,
        )

        SetupCard(
            step = "1 · HER VOICE",
            blurb = "So she can talk to you. About 150 MB.",
            specs = listOf(Models.KOKORO, Models.PIPER),
            vm = vm,
            buttonLabel = "GET HER VOICE",
        )

        SetupCard(
            step = "2 · HER EARS",
            blurb = "So you can just say “Athena” and talk. About 650 MB — Wi-Fi recommended.",
            specs = listOf(Models.KWS_ZIPFORMER, Models.SILERO_VAD, Models.WHISPER_SMALL_EN),
            vm = vm,
            buttonLabel = "GET HER EARS",
        )

        // Step 3: brain — online key OR offline download.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(KateColors.Surface, RoundedCornerShape(22.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val brainReady = s.groqApiKey.isNotBlank() || vm.anyOfflineBrainReady()
            Text(
                if (brainReady) "3 · HER BRAIN ✓" else "3 · HER BRAIN",
                style = MaterialTheme.typography.labelLarge,
                color = KateColors.Cyan,
            )
            Text(
                "Easiest: paste a free Groq key — she answers instantly when you have signal. Get one at console.groq.com.",
                style = MaterialTheme.typography.bodySmall,
                color = KateColors.TextDim,
            )
            OutlinedTextField(
                value = s.groqApiKey,
                onValueChange = vm::setGroqApiKey,
                label = { Text("Paste Groq key here", color = KateColors.TextDim) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = KateColors.CyanDim,
                    unfocusedBorderColor = KateColors.Line,
                    cursorColor = KateColors.Cyan,
                ),
            )
            Text(
                "Or give her an offline brain — works with no signal at all:",
                style = MaterialTheme.typography.bodySmall,
                color = KateColors.TextDim,
            )
            InlineModelButton(vm, Models.LLM_FALLBACK, "SMALL · 550 MB")
            InlineModelButton(vm, Models.LLM_PRIMARY, "SMART · 3.9 GB")
        }

        OutlinedButton(
            onClick = onDone,
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, KateColors.Cyan),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("DONE — TALK TO HER", style = MaterialTheme.typography.labelLarge, color = KateColors.Cyan)
        }
        Spacer(Modifier.size(12.dp))
    }
}

@Composable
private fun SetupCard(
    step: String,
    blurb: String,
    specs: List<ModelSpec>,
    vm: KateViewModel,
    buttonLabel: String,
) {
    val statuses = specs.map { spec -> vm.modelManager.status(spec).collectAsStateWithLifecycle().value }
    val allReady = statuses.all { it == ModelStatus.Ready }
    val downloading = statuses.any { it is ModelStatus.Downloading || it == ModelStatus.Extracting }
    val failed = statuses.filterIsInstance<ModelStatus.Failed>().firstOrNull()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(KateColors.Surface, RoundedCornerShape(22.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            if (allReady) "$step ✓" else step,
            style = MaterialTheme.typography.labelLarge,
            color = KateColors.Cyan,
        )
        Text(blurb, style = MaterialTheme.typography.bodySmall, color = KateColors.TextDim)
        when {
            allReady -> Text("All set.", style = MaterialTheme.typography.bodySmall, color = KateColors.Cyan)
            downloading -> {
                val progress = statuses.filterIsInstance<ModelStatus.Downloading>().firstOrNull()?.progress
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = KateColors.Cyan,
                        trackColor = KateColors.Line,
                    )
                } else {
                    Text("unpacking…", style = MaterialTheme.typography.bodySmall, color = KateColors.TextDim)
                }
            }
            else -> {
                if (failed != null) {
                    Text(
                        "That didn't finish (${failed.reason.take(60)}) — tap again to retry; finished parts are kept.",
                        style = MaterialTheme.typography.bodySmall,
                        color = KateColors.Danger,
                    )
                }
                OutlinedButton(
                    onClick = { specs.forEach { vm.downloadModel(it) } },
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, KateColors.Cyan),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(buttonLabel, style = MaterialTheme.typography.labelMedium, color = KateColors.Cyan)
                }
            }
        }
    }
}

@Composable
private fun InlineModelButton(vm: KateViewModel, spec: ModelSpec, label: String) {
    val status by vm.modelManager.status(spec).collectAsStateWithLifecycle()
    val st = status
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        when (st) {
            ModelStatus.Ready -> Text("✓ $label", style = MaterialTheme.typography.labelMedium, color = KateColors.Cyan)
            is ModelStatus.Downloading -> {
                Text(label, style = MaterialTheme.typography.labelMedium, color = KateColors.TextDim)
                Spacer(Modifier.width(10.dp))
                LinearProgressIndicator(
                    progress = { st.progress },
                    modifier = Modifier.weight(1f),
                    color = KateColors.Cyan,
                    trackColor = KateColors.Line,
                )
            }
            ModelStatus.Extracting -> Text("$label · unpacking…", style = MaterialTheme.typography.labelMedium, color = KateColors.TextDim)
            else -> OutlinedButton(
                onClick = { vm.downloadModel(spec) },
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, KateColors.Line),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("GET $label", style = MaterialTheme.typography.labelMedium, color = KateColors.TextDim)
            }
        }
    }
}
