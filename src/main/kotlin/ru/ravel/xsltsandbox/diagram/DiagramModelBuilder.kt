package ru.ravel.xsltsandbox.diagram

class DiagramModelBuilder {
	fun build(
		start: String,
		end: String,
		currentActivityName: String?,
		unionEdges: List<GEdge>,
		layout: DiagramLayout,
		nameByUid: (String) -> String
	): DiagramModel {
		val nodes = layout.positions.entries.map { (uid, pos) ->
			val title = nameByUid(uid)
			DiagramNodeModel(
				uid = uid,
				title = title,
				x = pos.x,
				y = pos.y,
				isStart = uid == start,
				isEnd = uid == end,
				isCurrent = currentActivityName != null && title == currentActivityName
			)
		}.sortedWith(compareBy({ it.x }, { it.y }))

		val edges = unionEdges.map { DiagramEdgeModel(it.from, it.to, it.exit) }

		return DiagramModel(
			nodes = nodes,
			edges = edges,
			width = layout.width,
			height = layout.height
		)
	}
}