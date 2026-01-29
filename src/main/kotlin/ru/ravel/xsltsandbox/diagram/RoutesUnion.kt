package ru.ravel.xsltsandbox.diagram

object RoutesUnion {
	fun unionEdges(routes: List<Route>): List<GEdge> =
		routes.flatMap { it.edges }
			.distinctBy { "${it.from}|${it.to}|${it.exit.orEmpty()}" }

	fun unionNodes(start: String, end: String, edges: List<GEdge>): Set<String> =
		buildSet {
			add(start); add(end)
			edges.forEach { add(it.from); add(it.to) }
		}
}