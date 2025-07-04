package ru.ravel.xsltsandbox

import javafx.scene.paint.Color
import javafx.scene.shape.Polyline
import ru.ravel.xsltsandbox.model.ConnectionSerialized

class Connection(
	val from: BlockNode,
	val to: BlockNode,
	val line: Polyline,
	val fromPort: Int = 0,
	val toPort: Int = 0,
	val splitPts: List<Pair<Double, Double>> = emptyList()
) {
	var selected: Boolean = false
		set(value) {
			field = value
			if (value) {
				line.stroke = Color.RED
				line.strokeWidth = 4.0
			} else {
				line.stroke = Color.GRAY
				line.strokeWidth = 4.0
			}
		}


	fun updateLine() {
		val (sx, sy) = from.outputPoint(fromPort)
		val (ex, ey) = to.inputPoint(toPort)
		val pts = mutableListOf<Double>().apply {
			add(sx)
			add(sy)
			splitPts.forEach { (x, y) -> add(x); add(y) }
			add(ex)
			add(ey)
		}
		line.points.setAll(pts)
	}


	fun toSerialized(): ConnectionSerialized = ConnectionSerialized(
		fromId = from.serializedId,
		toId = to.serializedId,
		fromOutputIndex = fromPort,
		toInputIndex = toPort
	)

}