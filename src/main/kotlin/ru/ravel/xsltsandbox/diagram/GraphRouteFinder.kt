package ru.ravel.xsltsandbox.diagram

import java.util.ArrayDeque

class GraphRouteFinder(private val graph: FlowGraph) {

	fun findAllShortestRoutes(fromUid: String, toUid: String, firstExit: String?, maxRoutes: Int): List<Route> {
		if (fromUid == toUid) return listOf(Route(nodes = listOf(fromUid), edges = emptyList()))

		val dist = mutableMapOf<String, Int>()
		val q: ArrayDeque<String> = ArrayDeque()
		dist[fromUid] = 0
		q.add(fromUid)

		while (q.isNotEmpty()) {
			val u = q.removeFirst()
			val du = dist[u] ?: continue
			for (e in graph.outs[u].orEmpty()) {
				if (u == fromUid && firstExit != null && e.exitName != firstExit) continue
				val v = e.toUid
				if (v !in dist) {
					dist[v] = du + 1
					q.add(v)
				}
			}
		}

		val targetDist = dist[toUid] ?: return emptyList()

		val parents = mutableMapOf<String, MutableList<DirEdge>>()
		for (e in graph.edges) {
			if (e.fromUid == fromUid && firstExit != null && e.exitName != firstExit) continue
			val du = dist[e.fromUid] ?: continue
			val dv = dist[e.toUid] ?: continue
			if (dv == du + 1) parents.getOrPut(e.toUid) { mutableListOf() }.add(e)
		}

		if (parents[toUid].isNullOrEmpty()) return emptyList()

		val out = mutableListOf<Route>()
		val seen = linkedSetOf<String>()
		val nodesRev = mutableListOf(toUid)
		val edgesRev = mutableListOf<DirEdge>()

		fun dfs(cur: String) {
			if (out.size >= maxRoutes) return
			if (cur == fromUid) {
				val nodes = nodesRev.asReversed().toList()
				val edges = edgesRev.asReversed().toList()
				val key = nodes.joinToString("|") + "::" + edges.joinToString("|") { "${it.fromUid}->${it.toUid}#${it.exitName}" }
				if (seen.add(key)) out += Route(nodes = nodes, edges = edges)
				return
			}
			for (e in parents[cur].orEmpty()) {
				val du = dist[e.fromUid] ?: continue
				val dv = dist[cur] ?: continue
				if (dv != du + 1) continue

				nodesRev.add(e.fromUid)
				edgesRev.add(e)
				dfs(e.fromUid)
				edgesRev.removeAt(edgesRev.lastIndex)
				nodesRev.removeAt(nodesRev.lastIndex)

				if (out.size >= maxRoutes) return
			}
		}

		dfs(toUid)
		return out.filter { it.edges.size == targetDist }
	}
}
