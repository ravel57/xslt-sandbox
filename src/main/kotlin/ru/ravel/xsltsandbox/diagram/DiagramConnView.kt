package ru.ravel.xsltsandbox.diagram

import javafx.geometry.Point2D
import javafx.scene.Cursor
import javafx.scene.control.Label
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.paint.Color
import javafx.scene.shape.MoveTo
import javafx.scene.shape.Path
import javafx.scene.shape.Polygon
import javafx.scene.shape.StrokeLineJoin
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class DiagramConnView(
	private val from: DiagramBlockView,
	private val to: DiagramBlockView,
	private val exitName: String?,
	private val exitIndex: Int,
	private val exitCount: Int,
	private val routeYProvider: () -> Double,
) {
	var selected: Boolean = false
		set(value) {
			field = value
			applyVisualState()
		}

	private var manualOutX: Double? = null
	private var manualInX: Double? = null
	private var manualMidY: Double? = null

	private enum class DragHandle { V_OUT, V_IN, H_MID, NONE }

	private var dragHandle: DragHandle = DragHandle.NONE

	private var pressSceneX = 0.0
	private var pressSceneY = 0.0

	private var startOutX = 0.0
	private var startInX = 0.0
	private var startMidY = 0.0

	private var isDragging = false

	private var lastPts: List<Point2D> = emptyList()

	private val hitTol = 10.0
	private val directionThreshold = 4.0

	var onPicked: ((DiagramConnView) -> Unit)? = null

	val path: Path = Path().apply {
		fill = Color.TRANSPARENT
		stroke = Color.web("#3b82f6")
		strokeWidth = 2.0
		strokeLineJoin = StrokeLineJoin.ROUND
		isMouseTransparent = true
	}

	val arrow: Polygon = Polygon().apply {
		fill = Color.web("#3b82f6")
		isMouseTransparent = true
	}

	val label: Label = Label(exitName ?: "").apply {
		style =
			"-fx-background-color: rgba(247,247,251,0.9); -fx-padding: 2 6 2 6; " +
					"-fx-border-color: rgba(51,65,85,0.25); -fx-border-radius: 10; -fx-background-radius: 10; " +
					"-fx-text-fill: #0f172a; -fx-font-size: 11px;"
		isMouseTransparent = true
		isVisible = !exitName.isNullOrBlank()
	}

	/** Толстая невидимая линия для удобного попадания мышью */
	val pick: Path = Path().apply {
		fill = Color.TRANSPARENT
		stroke = Color.TRANSPARENT
		strokeWidth = 14.0
		cursor = Cursor.DEFAULT
		isPickOnBounds = false
	}

	init {
		// Выделение
		pick.addEventHandler(MouseEvent.MOUSE_CLICKED) { e ->
			if (e.button == MouseButton.PRIMARY && e.clickCount == 1) {
				selected = true
				onPicked?.invoke(this)
				e.consume()
			}
		}

		// Динамический курсор по "грани", над которой курсор
		pick.addEventHandler(MouseEvent.MOUSE_MOVED) { e ->
			if (isDragging) return@addEventHandler
			if (lastPts.isEmpty()) {
				pick.cursor = Cursor.DEFAULT
				return@addEventHandler
			}

			val h = pickHandle(lastPts, e.x, e.y)
			pick.cursor = when (h) {
				DragHandle.V_OUT, DragHandle.V_IN -> Cursor.E_RESIZE
				DragHandle.H_MID -> Cursor.S_RESIZE
				DragHandle.NONE -> Cursor.DEFAULT
			}
		}

		// Захват конкретной "грани"
		pick.addEventHandler(MouseEvent.MOUSE_PRESSED) { e ->
			if (e.button == MouseButton.PRIMARY) {
				selected = true
				onPicked?.invoke(this)

				// Поднимаем именно это ребро, чтобы последующий drag не перехватили другие ребра
				path.toFront()
				pick.toFront()
				arrow.toFront()
				label.toFront()

				pressSceneX = e.sceneX
				pressSceneY = e.sceneY

				// Определяем, какой сегмент схвачен
				dragHandle = if (lastPts.isNotEmpty()) pickHandle(lastPts, e.x, e.y) else DragHandle.NONE

				// Запоминаем стартовые значения (если ручных нет — берём из последней геометрии)
				startOutX = manualOutX ?: lastPts.getOrNull(1)?.x ?: 0.0
				startInX = manualInX ?: lastPts.getOrNull(3)?.x ?: 0.0
				startMidY = manualMidY ?: lastPts.getOrNull(2)?.y ?: routeYProvider()

				isDragging = true

				// На старте показываем MOVE, далее — по направлению
				pick.cursor = Cursor.MOVE

				e.consume()
			}
		}

		pick.addEventHandler(MouseEvent.MOUSE_DRAGGED) { e ->
			if (e.button == MouseButton.PRIMARY && isDragging) {
				val dx = e.sceneX - pressSceneX
				val dy = e.sceneY - pressSceneY

				// Курсор относительно направления фактического движения
				val adx = abs(dx)
				val ady = abs(dy)
				pick.cursor = when {
					adx > ady + directionThreshold -> Cursor.E_RESIZE
					ady > adx + directionThreshold -> Cursor.S_RESIZE
					else -> Cursor.MOVE
				}

				when (dragHandle) {
					DragHandle.V_OUT -> manualOutX = startOutX + dx
					DragHandle.V_IN -> manualInX = startInX + dx
					DragHandle.H_MID -> manualMidY = startMidY + dy
					DragHandle.NONE -> {
						// если пользователь нажал не на управляемую грань — ничего не двигаем
					}
				}

				update()
				e.consume()
			}
		}

		pick.addEventHandler(MouseEvent.MOUSE_RELEASED) { e ->
			if (e.button == MouseButton.PRIMARY) {
				isDragging = false
				dragHandle = DragHandle.NONE
				// после отпускания курсор вернётся по MOUSE_MOVED
				e.consume()
			}
		}

		// Двойной клик — сброс ручных правок
		pick.addEventHandler(MouseEvent.MOUSE_CLICKED) { e ->
			if (e.button == MouseButton.PRIMARY && e.clickCount == 2) {
				manualOutX = null
				manualInX = null
				manualMidY = null
				selected = true
				onPicked?.invoke(this)
				update()
				e.consume()
			}
		}

		applyVisualState()
	}

	fun update() {
		val start = from.outputPoint(exitIndex, exitCount)
		val end = to.inputPoint()

		val sx = start.x
		val sy = start.y
		val ex = end.x
		val ey = end.y

		val gap = 10.0

		// Коридор по X между блоками, чтобы вертикальные сегменты не заходили на блоки
		val minX = from.boundsInParent.maxX + 10.0
		val maxX = to.boundsInParent.minX - 10.0

		// Если коридора нет (блоки пересекаются по X) — всё равно рисуем, но зажмём в ближайшие значения
		val hasCorridor = minX < maxX

		// ===== Ручной режим? =====
		val hasManual = manualOutX != null || manualInX != null || manualMidY != null

		// ===== Авто (короткий) =====
		// Для forward (ex >= sx) midY должен быть либо sy (HV), либо ey (VH) — это даёт минимум по Y
		// Для backward/cycle — midY чуть ниже блоков (минимально возможный), чтобы не залезать на них
		val minY = max(from.boundsInParent.maxY, to.boundsInParent.maxY) + 20.0
		val maxY = minY + 800.0

		fun manhattan(pts: List<Point2D>): Double {
			var s = 0.0
			for (i in 0 until pts.size - 1) {
				s += abs(pts[i + 1].x - pts[i].x) + abs(pts[i + 1].y - pts[i].y)
			}
			return s
		}

		// Базовые X для сегментов
		fun clampX(v: Double): Double = if (hasCorridor) v.coerceIn(minX, maxX) else v

		// ============ Выбор auto-точек ============
		val ptsAuto: List<Point2D> = run {
			val isForward = ex >= sx

			if (isForward) {
				// Короткий HV: midY = sy, outX=minX, inX=maxX
				val outX_HV = clampX(max(sx + gap, minX))
				val inX_HV = clampX(min(ex - gap, maxX))
				val midY_HV = sy

				val hv = listOf(
					Point2D(sx, sy),
					Point2D(outX_HV, sy),
					Point2D(outX_HV, midY_HV),  // вертикаль нулевая
					Point2D(inX_HV, midY_HV),
					Point2D(inX_HV, ey),
					Point2D(ex, ey),
				)

				// Короткий VH: midY = ey, outX=minX (вертикаль у выхода), inX=outX
				val outX_VH = clampX(max(sx + gap, minX))
				val inX_VH = outX_VH
				val midY_VH = ey

				val vh = listOf(
					Point2D(sx, sy),
					Point2D(outX_VH, sy),
					Point2D(outX_VH, midY_VH),
					Point2D(inX_VH, midY_VH),   // горизонталь нулевая
					Point2D(inX_VH, ey),        // вертикаль нулевая
					Point2D(ex, ey),
				)

				// Берём реально более короткий
				if (manhattan(hv) <= manhattan(vh)) hv else vh
			} else {
				// backward/cycle: делаем минимальный обход вниз (НЕ через routeYProvider(), чтобы не провисало)
				val outX = clampX(max(sx + gap, minX))
				val inX = clampX(min(ex - gap, maxX))
				val midY = minY.coerceIn(minY, maxY)

				listOf(
					Point2D(sx, sy),
					Point2D(outX, sy),
					Point2D(outX, midY),
					Point2D(inX, midY),
					Point2D(inX, ey),
					Point2D(ex, ey),
				)
			}
		}

		// ============ Ручные координаты (если есть) ============
		val pts = if (!hasManual) {
			ptsAuto
		} else {
			val isForward = ex >= sx

			val outXBase = clampX(max(sx + gap, minX))
			val inXBase = clampX(min(ex - gap, maxX))

			val outX = clampX(manualOutX ?: outXBase)
			val inX = clampX(manualInX ?: inXBase)

			val midY = if (isForward) {
				// В forward разрешаем midY быть где угодно (иначе опять будет провисание из-за minY)
				manualMidY ?: sy
			} else {
				// В cycle ограничиваем снизу, чтобы не заходить на блоки
				(manualMidY ?: minY).coerceIn(minY, maxY)
			}

			listOf(
				Point2D(sx, sy),
				Point2D(outX, sy),
				Point2D(outX, midY),
				Point2D(inX, midY),
				Point2D(inX, ey),
				Point2D(ex, ey),
			)
		}

		lastPts = pts

		path.elements.clear()
		pick.elements.clear()

		path.elements.add(MoveTo(pts[0].x, pts[0].y))
		pick.elements.add(MoveTo(pts[0].x, pts[0].y))
		for (i in 1 until pts.size) {
			val p = pts[i]
			path.elements.add(javafx.scene.shape.LineTo(p.x, p.y))
			pick.elements.add(javafx.scene.shape.LineTo(p.x, p.y))
		}

		updateArrow(pts[pts.size - 2], pts.last())

		if (label.isVisible) {
			val anchor = pts.getOrNull(1) ?: Point2D(sx, sy)
			label.layoutX = anchor.x + 6.0
			label.layoutY = anchor.y - 22.0
		}

		applyVisualState()
	}

	private fun pickHandle(pts: List<Point2D>, x: Double, y: Double): DragHandle {
		// сегменты: 0-1 H, 1-2 V_OUT, 2-3 H_MID, 3-4 V_IN, 4-5 H
		fun distToH(a: Point2D, b: Point2D): Double {
			val y0 = a.y
			val minX = min(a.x, b.x)
			val maxX = max(a.x, b.x)
			if (x < minX - hitTol || x > maxX + hitTol) return Double.POSITIVE_INFINITY
			return abs(y - y0)
		}

		fun distToV(a: Point2D, b: Point2D): Double {
			val x0 = a.x
			val minY = min(a.y, b.y)
			val maxY = max(a.y, b.y)
			if (y < minY - hitTol || y > maxY + hitTol) return Double.POSITIVE_INFINITY
			return abs(x - x0)
		}

		val dVOut = distToV(pts[1], pts[2])
		val dHMid = distToH(pts[2], pts[3])
		val dVIn = distToV(pts[3], pts[4])

		val best = listOf(
			DragHandle.V_OUT to dVOut,
			DragHandle.H_MID to dHMid,
			DragHandle.V_IN to dVIn,
		).minBy { it.second }

		return if (best.second <= hitTol) best.first else DragHandle.NONE
	}

	private fun applyVisualState() {
		if (selected) {
			path.strokeWidth = 3.5
			path.stroke = Color.web("#0ea5e9")
			arrow.fill = Color.web("#0ea5e9")
		} else {
			path.strokeWidth = 2.0
			path.stroke = Color.web("#3b82f6")
			arrow.fill = Color.web("#3b82f6")
		}
	}

	private fun updateArrow(a: Point2D, b: Point2D) {
		val dx = b.x - a.x
		val dy = b.y - a.y
		val len = sqrt(dx * dx + dy * dy).coerceAtLeast(0.0001)
		val ux = dx / len
		val uy = dy / len

		val size = 10.0
		val w = 6.0

		val tipX = b.x
		val tipY = b.y
		val baseX = tipX - ux * size
		val baseY = tipY - uy * size

		val px = -uy
		val py = ux

		val x2 = baseX + px * w
		val y2 = baseY + py * w
		val x3 = baseX - px * w
		val y3 = baseY - py * w

		arrow.points.setAll(tipX, tipY, x2, y2, x3, y3)
	}
}