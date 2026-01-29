package ru.ravel.xsltsandbox.diagram

import javafx.event.EventHandler
import javafx.geometry.Point2D
import javafx.scene.control.Label
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.Pane
import javafx.scene.paint.Color
import javafx.scene.shape.Circle
import javafx.scene.shape.Rectangle

class DiagramBlockView(
	val uid: String,
	val name: String,
	private val w: Double = 180.0,
	private val h: Double = 44.0,
) : Pane() {

	private val rect = Rectangle(w, h).apply {
		arcWidth = 14.0
		arcHeight = 14.0
		fill = Color.web("#ffffff")
		stroke = Color.web("#334155")
		strokeWidth = 2.0
	}

	private val label = Label(name).apply {
		layoutX = 12.0
		layoutY = 12.0
		style = "-fx-text-fill: #0f172a;"
	}

	private val inCircle = Circle(6.0).apply {
		centerX = 0.0
		centerY = h / 2
		fill = Color.web("#64748b")
	}

	private val outCircle = Circle(6.0).apply {
		centerX = w
		centerY = h / 2
		fill = Color.web("#64748b")
	}

	var onMoved: (() -> Unit)? = null
	var onPicked: ((String) -> Unit)? = null

	var highlight: Boolean = false
		set(value) {
			field = value
			applyStyle()
		}

	var isStart: Boolean = false
		set(value) {
			field = value
			applyStyle()
		}

	var isEnd: Boolean = false
		set(value) {
			field = value
			applyStyle()
		}

	fun applyStyle() {
		when {
			highlight -> {
				rect.stroke = Color.web("#22c55e")
				rect.strokeWidth = 4.0
			}

			isStart && isEnd -> {
				rect.stroke = Color.web("#f97316")
				rect.strokeWidth = 4.0
			}

			isStart -> {
				rect.stroke = Color.web("#2563eb")
				rect.strokeWidth = 3.0
			}

			isEnd -> {
				rect.stroke = Color.web("#a855f7")
				rect.strokeWidth = 3.0
			}

			else -> {
				rect.stroke = Color.web("#334155")
				rect.strokeWidth = 2.0
			}
		}
	}

	private var dragOffsetX = 0.0
	private var dragOffsetY = 0.0

	init {
		prefWidth = w
		prefHeight = h
		children.addAll(rect, label, inCircle, outCircle)

		applyStyle()

		val press = EventHandler<MouseEvent> { e ->
			if (e.button == MouseButton.PRIMARY) {
				toFront()
				dragOffsetX = e.sceneX - layoutX
				dragOffsetY = e.sceneY - layoutY
				e.consume()
			}
		}
		val drag = EventHandler<MouseEvent> { e ->
			if (e.button == MouseButton.PRIMARY) {
				layoutX = e.sceneX - dragOffsetX
				layoutY = e.sceneY - dragOffsetY
				onMoved?.invoke()
				e.consume()
			}
		}
		val click = EventHandler<MouseEvent> { e ->
			if (e.button == MouseButton.PRIMARY && e.clickCount == 1) {
				onPicked?.invoke(name)
				e.consume()
			}
		}

		listOf(rect, label).forEach {
			it.onMousePressed = press
			it.onMouseDragged = drag
			it.onMouseClicked = click
		}
	}

	fun inputPoint(): Point2D {
		val p = localToParent(inCircle.centerX, inCircle.centerY)
		return Point2D(p.x, p.y)
	}

	fun outputPoint(): Point2D {
		val p = localToParent(outCircle.centerX, outCircle.centerY)
		return Point2D(p.x, p.y)
	}
}
