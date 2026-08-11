package tech.gonxt.kate.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Stage timings for one voice turn. Stages are marked as the pipeline advances;
 * durations are deltas between consecutive marks. Feeds the settings latency readout.
 */
class LatencyTracker(private val clock: () -> Long = System::currentTimeMillis) {

    data class Turn(val marks: List<Pair<String, Long>> = emptyList()) {
        fun durations(): List<Pair<String, Long>> =
            marks.zipWithNext { a, b -> b.first to (b.second - a.second) }

        fun total(): Long =
            if (marks.size < 2) 0 else marks.last().second - marks.first().second
    }

    private val _current = MutableStateFlow(Turn())
    private val _lastCompleted = MutableStateFlow(Turn())
    val lastCompleted: StateFlow<Turn> = _lastCompleted

    fun begin(stage: String) {
        _current.value = Turn(listOf(stage to clock()))
    }

    fun mark(stage: String) {
        _current.value = Turn(_current.value.marks + (stage to clock()))
    }

    fun complete() {
        _lastCompleted.value = _current.value
    }
}
