package ru.ravel.xsltsandbox.diagram

class ShortestRoutesFinder(
	private val graph: DirectedGraph
) {
	/**
	 * firstExit: если задано — первый выход из start должен иметь такой label.
	 */
	fun findAllShortestRoutes(
		start: String,
		end: String,
		firstExit: String? = null
	): List<Route> {
		if (start == end) return listOf(Route(listOf(start), emptyList()))

		val dist = mutableMapOf<String, Int>()
		val pred = mutableMapOf<String, MutableList<GEdge>>() // pred edges leading to node

		val q: ArrayDeque<String> = ArrayDeque()
		dist[start] = 0
		q.add(start)

		while (q.isNotEmpty()) {
			val u = q.removeFirst()
			val du = dist[u] ?: continue

			for (e in graph.out(u)) {
				// фильтр первого выхода
				if (u == start && firstExit != null && e.exit != firstExit) continue

				val v = e.to
				val nd = du + 1
				val old = dist[v]

				when {
					old == null -> {
						dist[v] = nd
						pred.getOrPut(v) { mutableListOf() }.add(e)
						q.add(v)
					}
					nd == old -> {
						pred.getOrPut(v) { mutableListOf() }.add(e)
					}
				}
			}
		}

		val dEnd = dist[end] ?: return emptyList()

		// backtrack all shortest routes using predecessor edges
		val routes = mutableListOf<Route>()
		val pathNodes = ArrayDeque<String>()
		val pathEdges = ArrayDeque<GEdge>()

		fun dfs(v: String) {
			pathNodes.addFirst(v)
			if (v == start) {
				val nodes = pathNodes.toList()
				val edges = pathEdges.toList()
				routes.add(Route(nodes, edges))
				pathNodes.removeFirst()
				return
			}

			val p = pred[v].orEmpty()
			for (e in p) {
				// e: (from -> v)
				pathEdges.addFirst(e)
				dfs(e.from)
				pathEdges.removeFirst()
			}
			pathNodes.removeFirst()
		}

		dfs(end)

		// Важно: routes уже кратчайшие, потому что pred строился по dist.
		// Можно при желании отсечь дубликаты (на случай одинаковых ребер):
		return routes.distinctBy { r ->
			r.edges.joinToString("->") { "${it.from}|${it.to}|${it.exit.orEmpty()}" }
		}.filter { it.edges.size == dEnd }
	}
}