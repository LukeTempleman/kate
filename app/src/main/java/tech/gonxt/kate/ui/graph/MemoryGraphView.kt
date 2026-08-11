package tech.gonxt.kate.ui.graph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.android.awaitFrame
import tech.gonxt.kate.memory.db.GraphEdgeEntity
import tech.gonxt.kate.memory.db.GraphNodeEntity
import tech.gonxt.kate.ui.theme.KateColors

/**
 * Obsidian-style force-directed canvas (spec Iteration 4): topic clusters,
 * conversation and entity nodes; pinch-zoom, drag-pan, tap to select.
 */
@Composable
fun MemoryGraphView(
    nodes: List<GraphNodeEntity>,
    edges: List<GraphEdgeEntity>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sim = remember { GraphSim() }
    var scale by remember { mutableFloatStateOf(0.8f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var tick by remember { mutableLongStateOf(0L) }

    LaunchedEffect(nodes, edges) {
        sim.setGraph(nodes.map { it.id }, edges.map { it.fromNode to it.toNode })
        while (!sim.settled) {
            sim.step()
            tick++
            awaitFrame()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, panDelta, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.15f, 4f)
                    pan += panDelta
                }
            }
            .pointerInput(nodes) {
                detectTapGestures { tap ->
                    val cx = size.width / 2f + pan.x
                    val cy = size.height / 2f + pan.y
                    val wx = (tap.x - cx) / scale
                    val wy = (tap.y - cy) / scale
                    onSelect(sim.hitTest(wx, wy, radius = 48f / scale.coerceAtLeast(0.3f)))
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            tick // read so settling frames invalidate the canvas
            translate(size.width / 2f + pan.x, size.height / 2f + pan.y) {
                scale(scale, scale, pivot = Offset.Zero) {
                    for (e in edges) {
                        val a = sim.positions[e.fromNode] ?: continue
                        val b = sim.positions[e.toNode] ?: continue
                        drawLine(
                            color = KateColors.Line,
                            start = Offset(a.x, a.y),
                            end = Offset(b.x, b.y),
                            strokeWidth = (0.5f + e.weight * 0.2f).coerceAtMost(2.5f),
                        )
                    }
                    val paint = android.graphics.Paint().apply {
                        color = android.graphics.Color.argb(255, 110, 140, 150)
                        textSize = 16f
                        typeface = android.graphics.Typeface.MONOSPACE
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                    for (n in nodes) {
                        val p = sim.positions[n.id] ?: continue
                        val (radius, alpha) = when (n.kind) {
                            "conversation" -> 12f to 1f
                            "topic" -> 9f to 0.95f
                            "entity" -> 7f to 0.8f
                            "skill" -> 10f to 1f
                            else -> 3.5f to 0.4f // memory dots
                        }
                        val selected = n.id == selectedId
                        if (selected) {
                            drawCircle(
                                KateColors.Cyan.copy(alpha = 0.25f),
                                radius * 2.6f,
                                Offset(p.x, p.y),
                            )
                        }
                        drawCircle(
                            color = if (n.kind == "memory") KateColors.TextDim.copy(alpha = alpha)
                            else KateColors.Cyan.copy(alpha = alpha),
                            radius = radius,
                            center = Offset(p.x, p.y),
                            style = if (n.kind == "conversation") Stroke(width = 2f) else androidx.compose.ui.graphics.drawscope.Fill,
                        )
                        if (n.kind != "memory" && scale > 0.45f) {
                            drawContext.canvas.nativeCanvas.drawText(
                                n.label.take(18),
                                p.x,
                                p.y - radius - 8f,
                                paint,
                            )
                        }
                    }
                }
            }
        }
    }
}
