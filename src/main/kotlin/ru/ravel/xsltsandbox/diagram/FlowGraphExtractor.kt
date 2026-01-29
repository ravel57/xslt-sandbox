package ru.ravel.xsltsandbox.diagram

import ru.ravel.xsltsandbox.models.layout.DiagramLayout

object FlowGraphExtractor {

	fun extractDirectedEdges(layout: DiagramLayout): List<DirEdge> {
		val res = mutableListOf<DirEdge>()
		val rawConnections = layout.connections?.diagramConnections.orEmpty()

		rawConnections.forEach { c ->
			val pts = c.endPoints?.points.orEmpty()

			// "Enter" — это куда входит связь (to)
			val toUid = pts.firstOrNull { it.exitPointRef == "Enter" }?.elementRef
				?: return@forEach

			// Остальные точки — "from" (могут быть несколькими = ветвления)
			pts.asSequence()
				.filter { p ->
					val exit = p.exitPointRef
					val el = p.elementRef
					exit != null &&
							exit != "Enter" &&
							exit != "Start" &&
							el != null &&
							el != toUid
				}
				.forEach { fp ->
					res += DirEdge(
						fromUid = fp.elementRef!!,
						toUid = toUid,
						exitName = fp.exitPointRef
					)
				}
		}

		return res
	}
}