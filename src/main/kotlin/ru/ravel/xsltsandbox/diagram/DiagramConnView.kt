package ru.ravel.xsltsandbox.diagram

import javafx.scene.control.Label
import javafx.scene.paint.Color
import javafx.scene.shape.Line

class DiagramConnView(
	private val from: DiagramBlockView,
	private val to: DiagramBlockView,
	private val exitName: String?,
) {
	val line: Line = Line().apply {
		stroke = Color.web("#3b82f6")
		strokeWidth = 2.0
	}

	// simple arrow head (two small lines)
	val arrow1: Line = Line().apply {
		stroke = Color.web("#3b82f6")
		strokeWidth = 2.0
	}
	val arrow2: Line = Line().apply {
		stroke = Color.web("#3b82f6")
		strokeWidth = 2.0
	}

	val label: Label = Label(exitName ?: "").apply {
		style = "-fx-background-color: rgba(247,247,251,0.9); -fx-padding: 2 6 2 6; -fx-border-color: rgba(51,65,85,0.25); -fx-border-radius: 10; -fx-background-radius: 10; -fx-text-fill: #0f172a; -fx-font-size: 11px;"
		isMouseTransparent = true
	}

	val pick: Line = Line().apply {
		stroke = Color.TRANSPARENT
		strokeWidth = 12.0
	}

	fun update() {
		val outputPoint = from.outputPoint()
		val sx = outputPoint.x
		val sy = outputPoint.y
		val inputPoint = to.inputPoint()
		val ex = inputPoint.x
		val ey = inputPoint.y

		line.startX = sx
		line.startY = sy
		line.endX = ex
		line.endY = ey

		pick.startX = sx
		pick.startY = sy
		pick.endX = ex
		pick.endY = ey

		// label at midpoint
		val mx = (sx + ex) / 2.0
		val my = (sy + ey) / 2.0
		label.layoutX = mx - 20.0
		label.layoutY = my - 18.0

		// arrow head near end
		val dx = ex - sx
		val dy = ey - sy
		val len = kotlin.math.sqrt(dx * dx + dy * dy)
		if (len < 1.0) return

		val ux = dx / len
		val uy = dy / len

		val arrowLen = 10.0
		val arrowWidth = 6.0

		val ax = ex - ux * 14.0
		val ay = ey - uy * 14.0

		// perpendicular
		val px = -uy
		val py = ux

		arrow1.startX = ex
		arrow1.startY = ey
		arrow1.endX = ax + px * arrowWidth
		arrow1.endY = ay + py * arrowWidth

		arrow2.startX = ex
		arrow2.startY = ey
		arrow2.endX = ax - px * arrowWidth
		arrow2.endY = ay - py * arrowWidth
	}
}
