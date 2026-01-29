package ru.ravel.xsltsandbox.diagram

import java.util.LinkedHashSet

class BranchingLayout private constructor(
	val nodesByLevel: Map<Int, List<String>>,
	val edges: List<DirEdge>
) {
	companion object {
		fun fromRoutes(routes: List<Route>): BranchingLayout {
			val levels = mutableMapOf<String, Int>()
			val edges = LinkedHashSet<DirEdge>()

			routes.forEach { r ->
				r.nodes.forEachIndexed { idx, uid ->
					val cur = levels[uid]
					levels[uid] = if (cur == null) idx else minOf(cur, idx)
				}
				r.edges.forEach { e ->
					edges.add(DirEdge(fromUid = e.from, toUid = e.to, exitName = e.exit))
				}
			}

			val grouped = levels.entries.groupBy({ it.value }, { it.key })
			val nodesByLevel = grouped.entries.sortedBy { it.key }.associate { (lvl, uids) ->
				lvl to uids.sorted()
			}
			return BranchingLayout(nodesByLevel = nodesByLevel, edges = edges.toList())
		}
	}
}