package com.example.gps.laneengine
import java.util.PriorityQueue

class LanePlanner {
    private data class State(val laneId: String, val cost: Double)

    fun plan(
        startLaneIds: Set<String>,
        targetLaneIds: Set<String>,
        connections: List<LaneConnection>,
    ): LanePlan? {
        if (startLaneIds.isEmpty() || targetLaneIds.isEmpty()) return null
        val adjacency = connections.filter { it.legal }.groupBy { it.fromLaneId }
        val queue = PriorityQueue<State>(compareBy { it.cost })
        val dist = mutableMapOf<String, Double>()
        val previous = mutableMapOf<String, String?>()

        for (start in startLaneIds) {
            dist[start] = 0.0
            previous[start] = null
            queue += State(start, 0.0)
        }

        var found: String? = null
        while (queue.isNotEmpty()) {
            val state = queue.remove()
            if (state.cost != dist[state.laneId]) continue
            if (state.laneId in targetLaneIds) { found = state.laneId; break }
            for (edge in adjacency[state.laneId].orEmpty()) {
                val next = state.cost + edge.cost
                if (next < dist.getOrDefault(edge.toLaneId, Double.POSITIVE_INFINITY)) {
                    dist[edge.toLaneId] = next
                    previous[edge.toLaneId] = state.laneId
                    queue += State(edge.toLaneId, next)
                }
            }
        }

        val end = found ?: return null
        val path = mutableListOf<String>()
        var cursor: String? = end
        while (cursor != null) {
            path += cursor
            cursor = previous[cursor]
        }
        path.reverse()
        return LanePlan(path, dist.getValue(end))
    }
}
