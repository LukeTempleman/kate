package tech.gonxt.kate.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.gonxt.kate.KateViewModel
import tech.gonxt.kate.core.OrbState
import tech.gonxt.kate.core.Role
import tech.gonxt.kate.ui.orb.Orb
import tech.gonxt.kate.ui.theme.KateColors

/**
 * Driving Mode v1 (spec M1.1): full-screen orb, giant text, zero small tap targets.
 */
@Composable
fun DrivingScreen(vm: KateViewModel, onExit: () -> Unit) {
    val orbState by vm.engine.orbState.collectAsStateWithLifecycle()
    val amplitude by vm.engine.speakingAmplitude.collectAsStateWithLifecycle()
    val messages by vm.engine.messages.collectAsStateWithLifecycle()
    val partial by vm.engine.partialUserText.collectAsStateWithLifecycle()

    val lastKate = messages.lastOrNull { it.role == Role.KATE }?.text.orEmpty()
    val lastUser = messages.lastOrNull { it.role == Role.USER }?.text.orEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KateColors.Base)
            .padding(24.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("DRIVING", style = MaterialTheme.typography.labelLarge, color = KateColors.CyanDim)
            Spacer(Modifier.weight(1f))
            OutlinedButton(
                onClick = onExit,
                modifier = Modifier.size(width = 120.dp, height = 64.dp),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, KateColors.Line),
            ) {
                Text("EXIT", style = MaterialTheme.typography.labelLarge, color = KateColors.TextDim)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            Orb(state = orbState, amplitude = amplitude, modifier = Modifier.size(320.dp))
        }

        Text(
            text = when {
                partial.isNotEmpty() -> "“$partial”"
                orbState == OrbState.LISTENING -> "listening…"
                lastKate.isNotEmpty() -> lastKate
                else -> "tap TALK and speak"
            },
            fontSize = 32.sp,
            lineHeight = 42.sp,
            fontFamily = tech.gonxt.kate.ui.theme.Mono,
            color = KateColors.Text,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 20.dp),
        )
        if (lastUser.isNotEmpty() && partial.isEmpty()) {
            Text(
                "you: $lastUser",
                fontSize = 18.sp,
                fontFamily = tech.gonxt.kate.ui.theme.Mono,
                color = KateColors.TextDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        OutlinedButton(
            onClick = vm::onTalkPressed,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp)
                .height(96.dp),
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(2.dp, KateColors.Cyan),
        ) {
            Text("TALK", fontSize = 28.sp, fontFamily = tech.gonxt.kate.ui.theme.Mono, color = KateColors.Cyan)
        }
    }
}
