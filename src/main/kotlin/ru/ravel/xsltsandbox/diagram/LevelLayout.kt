package ru.ravel.xsltsandbox.diagram

class LevelLayout(
	private val graph: DirectedGraph,
	private val params: LayoutParams = LayoutParams()
) {
	fun layout(
		start: String,
		nodes: Set<String>,
		nameByUid: (String) -> String
	): DiagramLayout {
		val level = mutableMapOf<String, Int>()
		val q: ArrayDeque<String> = ArrayDeque()
		level[start] = 0
		q.add(start)

		while (q.isNotEmpty()) {
			val u = q.removeFirst()
			val lu = level[u] ?: 0
			for (e in graph.out(u)) {
				val v = e.to
				if (v !in nodes) continue
				val nl = lu + 1
				val old = level[v]
				if (old == null || nl < old) {
					level[v] = nl
					q.add(v)
				}
			}
		}

		val maxLevel = level.values.maxOrNull() ?: 0
		nodes.forEach { if (level[it] == null) level[it] = maxLevel + 1 }

		val groups = level.entries
			.filter { it.key in nodes }
			.groupBy({ it.value }, { it.key })
			.toSortedMap()

		val positions = mutableMapOf<String, NodePos>()
		var maxRows = 1

		groups.forEach { (col, uids) ->
			val sorted = uids.sortedBy { nameByUid(it) }
			maxRows = maxOf(maxRows, sorted.size)
			sorted.forEachIndexed { row, uid ->
				val x = params.padX + col * params.dx
				val y = params.padY + row * params.dy
				positions[uid] = NodePos(x, y, col, row)
			}
		}

		val maxCol = groups.keys.maxOrNull() ?: 0
		val width = params.padX * 2 + (maxCol + 1) * params.dx + 260.0
		val height = params.padY * 2 + maxRows * params.dy + 120.0

		return DiagramLayout(positions, width, height)
	}
}