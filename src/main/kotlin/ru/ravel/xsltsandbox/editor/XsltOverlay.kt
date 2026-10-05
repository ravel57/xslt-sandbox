package ru.ravel.xsltsandbox.editor

import javafx.animation.AnimationTimer
import javafx.scene.canvas.Canvas
import javafx.scene.canvas.GraphicsContext
import javafx.scene.paint.Color
import org.fxmisc.richtext.CodeArea
import org.fxmisc.richtext.model.TwoDimensional
import org.fxmisc.richtext.model.TwoDimensional.Bias.Forward
import ru.ravel.xsltsandbox.models.DocSession

/**
 * Волнистое подчёркивание предупреждений и ошибок поверх XSLT-редактора.
 */
object XsltOverlay {
	private const val UNDER_OFFSET = 0.0   // отступ под текстом (px)
	private const val UNDER_WIDTH = 1.0  // толщина линии
	private const val UNDER_STEP = 3.0   // горизонтальный шаг «зубцов»
	private const val UNDER_AMP = 1.0   // амплитуда (высота «зубца»)


	/** Вызывает перерисовку при скролле/изменениях размера/текста */
	fun hookOverlayRedraw(s: DocSession) {
		val area = s.xsltArea
		val overlay = s.xsltOverlay ?: return

		var dirty = true
		val timer = object : AnimationTimer() {
			override fun handle(now: Long) {
				if (dirty) {
					dirty = false
					redrawXsltOverlay(s)
				}
			}
		}
		timer.start()

		val markDirty: () -> Unit = { dirty = true }

		overlay.widthProperty().addListener { _, _, _ -> markDirty() }
		overlay.heightProperty().addListener { _, _, _ -> markDirty() }

		area.estimatedScrollXProperty().addListener { _, _, _ -> markDirty() }
		area.estimatedScrollYProperty().addListener { _, _, _ -> markDirty() }
		area.widthProperty().addListener { _, _, _ -> markDirty() }
		area.heightProperty().addListener { _, _, _ -> markDirty() }
		area.textProperty().addListener { _, _, _ -> markDirty() }
	}


	/** Главный рендер: ошибки красным, предупреждения оранжевым */
	fun redrawXsltOverlay(s: DocSession) {
		val area = s.xsltArea
		val overlay = s.xsltOverlay ?: return
		val gc = overlay.graphicsContext2D

		// Очистка
		gc.clearRect(0.0, 0.0, overlay.width, overlay.height)

		// Собираем предупреждения (включая «умные» предупреждения про select)
		val warnRanges = s.xsltWarningRanges + s.xsltBadSelectRanges
		warnRanges.forEach { r -> drawUnderlineForRange(area, overlay, r.first, r.last + 1, Color.ORANGE) }
		s.xsltSyntaxErrorRanges.forEach { r -> drawUnderlineForRange(area, overlay, r.first, r.last + 1, Color.RED) }
	}


	/** Рисует подчёркивание для диапазона, разбивая по параграфам */
	private fun drawUnderlineForRange(
		area: CodeArea,
		overlay: Canvas,
		start: Int,
		endEx: Int,
		color: Color,
	) {
		if (start >= endEx) return

		val sPos = area.offsetToPosition(start, TwoDimensional.Bias.Forward)
		val ePos = area.offsetToPosition(endEx, TwoDimensional.Bias.Backward)
		val gc = overlay.graphicsContext2D

		for (par in sPos.major..ePos.major) {
			val parStart = if (par == sPos.major) start else area.getAbsolutePosition(par, 0)
			val parEnd = if (par == ePos.major) endEx else area.getAbsolutePosition(par, area.getParagraphLength(par))
			if (parStart >= parEnd) continue

			val bScreenOpt = area.getCharacterBoundsOnScreen(parStart, parEnd)
			if (!bScreenOpt.isPresent) continue
			val bScreen = bScreenOpt.get()


			// screen -> scene -> overlay
			val root = overlay.scene.root
			val bScene = root.screenToLocal(bScreen)
			val b = overlay.sceneToLocal(bScene)

			drawZigZag(gc, b.minX, b.maxX, b.maxY + UNDER_OFFSET, color)
		}
	}


	/** Треугольная волна */
	private fun drawZigZag(
		gc: GraphicsContext,
		x0: Double,
		x1: Double,
		y: Double,
		color: Color,
	) {
		val step = UNDER_STEP
		val amp = UNDER_AMP
		if (x1 - x0 <= 1.0) return

		gc.stroke = color
		gc.lineWidth = UNDER_WIDTH
		gc.beginPath()
		var x = x0
		var sign = 1.0
		gc.moveTo(x, y)

		while (x + step <= x1) {
			val mid = x + step / 2.0
			gc.lineTo(mid, y - amp * sign)
			gc.lineTo(x + step, y)
			sign = -sign
			x += step
		}
		// Хвост
		if (x < x1) {
			val mid = (x + x1) / 2.0
			gc.lineTo(mid, y - amp * sign)
			gc.lineTo(x1, y)
		}
		gc.stroke()
	}
}
