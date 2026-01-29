package ru.ravel.xsltsandbox.diagram

class DirectedGraph(
	edges: List<GEdge>
) {
	private val outMap: Map<String, List<GEdge>> = edges.groupBy { it.from }
	val nodes: Set<String> = buildSet {
		edges.forEach { add(it.from); add(it.to) }
	}

	fun out(u: String): List<GEdge> = outMap[u].orEmpty()
}