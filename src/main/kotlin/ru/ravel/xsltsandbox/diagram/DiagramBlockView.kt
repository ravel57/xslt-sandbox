package ru.ravel.xsltsandbox.diagram

import javafx.event.EventHandler
import javafx.scene.effect.DropShadow
import javafx.geometry.Point2D
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.control.OverrunStyle
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.Pane
import javafx.scene.paint.Color
import javafx.scene.shape.Rectangle
import kotlin.math.max
import kotlin.math.min

class DiagramBlockView(
	val uid: String,
	val name: String,
	private val w: Double = 180.0,
	private val baseH: Double = 44.0,
) : Pane() {

	// Параметры "облачков" выходов (внутри блока)
	private val chipH = 18.0
	private val chipGap = 6.0
	private val chipPadX = 10.0
	private val chipMaxW = 140.0
	private val minVPad = 10.0

	// ВАЖНО: не ограничиваем высоту, иначе при большом числе выходов startY получает пустой диапазон
	// (если хочешь лимит — лучше делать скролл внутри блока, но это отдельная доработка)
	private val maxH = Double.POSITIVE_INFINITY

	private val rect = Rectangle(w, baseH).apply {
		arcWidth = 14.0
		arcHeight = 14.0
		fill = Color.web("#ffffff")
		stroke = Color.web("#334155")
		strokeWidth = 2.0
	}

	private val label = Label(name).apply {
		layoutX = 12.0
		style = "-fx-text-fill: #0f172a;"
	}

	private val exitChips = mutableListOf<Label>()
	private var exitNames: List<String> = emptyList()

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
		// Базовые цвета
		val baseStroke = Color.web("#334155")
		val baseFill = Color.web("#ffffff")

		// Статусы start/end
		val startStroke = Color.web("#16a34a") // green-600
		val endStroke = Color.web("#dc2626")   // red-600

		// Debug highlight
		val hlStroke = Color.web("#f59e0b")    // amber-500

		rect.fill = baseFill
		rect.strokeWidth = 2.0
		rect.stroke = when {
			highlight -> hlStroke
			isStart -> startStroke
			isEnd -> endStroke
			else -> baseStroke
		}

		// Легкая подсветка тенью только в debug-highlight, чтобы не шумело постоянно
		rect.effect = if (highlight) {
			DropShadow().apply {
				radius = 18.0
				offsetX = 0.0
				offsetY = 0.0
				color = Color.web("#f59e0b", 0.55)
			}
		} else null
	}

	private var dragOffsetX = 0.0
	private var dragOffsetY = 0.0

	init {
		prefWidth = w
		prefHeight = baseH

		children.addAll(rect, label)
		centerLabelVertically()
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

		fun attachHandlers(n: Node) {
			n.onMousePressed = press
			n.onMouseDragged = drag
			n.onMouseClicked = click
		}

		attachHandlers(rect)
		attachHandlers(label)
	}

	private fun centerLabelVertically() {
		val hNow = rect.height
		label.layoutY = (hNow - 18.0) / 2.0
	}

	/**
	 * Передай реальные имена выходов — они станут "облачками" внутри блока,
	 * высота блока автоматически увеличится.
	 */
	fun setExits(exits: List<String>) {
		exitNames = exits
		rebuildExitChips()
		relayoutForExitCount(exitNames.size)
	}

	/**
	 * Совместимость: только количество выходов (имена будут пустые).
	 */
	fun setExitCount(count: Int) {
		val c = max(1, count)
		if (exitNames.size != c) {
			exitNames = List(c) { "" }
		}
		rebuildExitChips()
		relayoutForExitCount(c)
	}

	private fun relayoutForExitCount(count: Int) {
		val c = max(1, count)

		val exitsArea = c * chipH + (c - 1) * chipGap
		val requiredH = max(baseH, exitsArea + minVPad * 2.0)
		val newH = min(maxH, requiredH)

		rect.height = newH
		prefHeight = newH

		centerLabelVertically()
		layoutExitChips()
	}

	private fun rebuildExitChips() {
		children.removeAll(exitChips)
		exitChips.clear()

		val c = max(1, exitNames.size)
		for (i in 0 until c) {
			val chip = Label(exitNames.getOrNull(i).orEmpty()).apply {
				textOverrun = OverrunStyle.ELLIPSIS
				maxWidth = chipMaxW
				minHeight = chipH
				prefHeight = chipH
				style =
					"-fx-background-color: rgba(247,247,251,0.95);" +
							"-fx-padding: 2 8 2 8;" +
							"-fx-border-color: rgba(51,65,85,0.25);" +
							"-fx-border-radius: 10;" +
							"-fx-background-radius: 10;" +
							"-fx-text-fill: #0f172a;" +
							"-fx-font-size: 11px;"
			}

			// чтобы тянуть блок можно было и за "облачко"
			chip.onMousePressed = rect.onMousePressed
			chip.onMouseDragged = rect.onMouseDragged
			chip.onMouseClicked = rect.onMouseClicked

			exitChips += chip
		}

		children.addAll(exitChips)
	}

	private fun layoutExitChips() {
		if (exitChips.isEmpty()) return

		val c = exitChips.size
		val hNow = rect.height

		val exitsArea = c * chipH + (c - 1) * chipGap

		// Безопасный расчёт startY:
		// если "облачков" больше, чем помещается — стартуем от minVPad, без coerceIn по пустому диапазону
		val maxStartY = hNow - exitsArea - minVPad
		val startY = if (maxStartY <= minVPad) {
			minVPad
		} else {
			((hNow - exitsArea) / 2.0).coerceIn(minVPad, maxStartY)
		}

		for (i in 0 until c) {
			val chip = exitChips[i]
			chip.text = exitNames.getOrNull(i).orEmpty()

			chip.applyCss()
			val pw = min(chipMaxW, chip.prefWidth(-1.0))
			chip.prefWidth = pw

			chip.layoutX = w - chipPadX - pw
			chip.layoutY = startY + i * (chipH + chipGap)
		}
	}

	fun inputPoint(): Point2D {
		val p = localToParent(0.0, rect.height / 2.0)
		return Point2D(p.x, p.y)
	}

	fun outputPoint(exitIndex: Int, exitCount: Int): Point2D {
		val c = max(1, exitCount)
		val i = exitIndex.coerceIn(0, c - 1)

		// Если есть облачка — берём центр конкретного облачка
		if (exitChips.isNotEmpty() && i < exitChips.size) {
			val chip = exitChips[i]
			val localY = chip.layoutY + chipH / 2.0
			val p = localToParent(w, localY)
			return Point2D(p.x, p.y)
		}

		// fallback
		val p = localToParent(w, rect.height / 2.0)
		return Point2D(p.x, p.y)
	}

	override fun layoutChildren() {
		super.layoutChildren()
		layoutExitChips()
		centerLabelVertically()
	}
}