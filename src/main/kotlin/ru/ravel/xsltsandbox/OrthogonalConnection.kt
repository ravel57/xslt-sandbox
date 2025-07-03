package ru.ravel.xsltsandbox

import javafx.beans.value.ChangeListener
import javafx.scene.Group
import javafx.scene.input.MouseButton
import javafx.scene.layout.Pane
import javafx.scene.paint.Color
import javafx.scene.shape.Line
import javafx.scene.shape.Polygon
import javafx.scene.shape.Polyline
import ru.ravel.xsltsandbox.model.ConnectionSerialized
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Соединение с ортогональной ломаной-90°.
 * @param splits  абсолютные координаты поворотных точек (X, Y) из XML-тега <DiagramSplit>.
 */
class OrthogonalConnection(
	val from: BlockNode,
	val to: BlockNode,
	host: Pane,
	val fromPort: Int = 0,
	val toPort: Int = 0,
	private val splits: List<Pair<Double, Double>> = emptyList()
) : Group() {

	/* -------- совместимость со старым Connection -------- */

	/** «Виртуальная» линия — остаётся для обработчиков и сериализации. */
	val line = Line().apply { isVisible = false }

	var selected: Boolean = false
		set(v) {
			field = v
			val col = if (v) Color.RED else Color.BLUE
			val w   = if (v) 4.0       else 2.0
			poly.stroke = col; poly.strokeWidth = w
			arrow.fill  = col
		}

	fun updateLine() = rebuild()

	fun toSerialized() = ConnectionSerialized(
		fromId = from.serializedId,
		toId   = to.serializedId,
		fromOutputIndex = fromPort,
		toInputIndex    = toPort
	)

	/* ---------------- внутренние элементы ---------------- */

	private val poly  = Polyline().apply { stroke = Color.BLUE; strokeWidth = 2.0 }
	private val arrow = Polygon().apply { fill = Color.BLUE }

	init {
		children.addAll(poly, arrow, line)
		host.children.add(this)
		rebuild()
		bindBlocks()
		enableClickSelection(host)
	}

	/* ---------------- построение ломаной ---------------- */

	/** перестраиваем ломаную с ортогональными сегментами */
	private fun rebuild() {
		val start = from.outputPoint(fromPort)
		val end   = to.inputPoint(toPort)

		val pts = mutableListOf<Double>()
		var (cx, cy) = start      // current point
		pts += cx; pts += cy

		// helper → добавляет 1 или 2 ортогональных сегмента
		fun addOrth(toX: Double, toY: Double) {
			if (cx == toX || cy == toY) {
				// один прямой (уже ортогональный) отрезок
				pts += toX; pts += toY
			} else {
				// два колена: сначала по X, затем по Y
				pts += toX; pts += cy   // горизонталь
				pts += toX; pts += toY  // вертикаль
			}
			cx = toX; cy = toY
		}

		// 1) между всеми сплит-точками
		for ((sx, sy) in splits) addOrth(sx, sy)

		// 2) последний участок к концу линии
		addOrth(end.first, end.second)

		poly.points.setAll(pts)
		line.startX = pts[0]; line.startY = pts[1]
		line.endX   = end.first; line.endY = end.second
		updateArrow()
	}


	private fun updateArrow() {
		val p = poly.points; if (p.size < 4) return
		val ax = p[p.size - 2]; val ay = p[p.size - 1]     // наконечник
		val bx = p[p.size - 4]; val by = p[p.size - 3]     // предыдущая точка
		val ang = atan2(ay - by, ax - bx); val L = 12.0
		val p1x = ax - L * cos(ang - Math.PI / 6); val p1y = ay - L * sin(ang - Math.PI / 6)
		val p2x = ax - L * cos(ang + Math.PI / 6); val p2y = ay - L * sin(ang + Math.PI / 6)
		arrow.points.setAll(ax, ay, p1x, p1y, p2x, p2y)
	}

	/* ------------- реагируем на перемещение блоков ------------- */

	private fun bindBlocks() {
		val l = ChangeListener<Number> { _, _, _ -> rebuild() }
		from.layoutXProperty().addListener(l); from.layoutYProperty().addListener(l)
		to.layoutXProperty().addListener(l);   to.layoutYProperty().addListener(l)
	}

	/* ------------- выделение соединения кликом ------------- */

	private fun enableClickSelection(host: Pane) {
		val handler: (javafx.scene.input.MouseEvent) -> Unit = { e ->
			if (e.button == MouseButton.PRIMARY) {
				(host.userData as? LayoutEditor)?.selectConnection(this)
				host.requestFocus(); e.consume()
			}
		}
		poly.setOnMouseClicked(handler)
		line.setOnMouseClicked(handler)   // клики по невидимой Line тоже
	}
}
