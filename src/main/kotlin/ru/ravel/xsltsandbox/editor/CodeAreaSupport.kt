package ru.ravel.xsltsandbox.editor

import java.util.Collections
import java.util.HashMap
import java.util.WeakHashMap
import java.util.function.IntFunction
import java.util.regex.Pattern
import javafx.animation.AnimationTimer
import javafx.animation.PauseTransition
import javafx.scene.shape.Rectangle
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Cursor
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.ContextMenu
import javafx.scene.control.Label
import javafx.scene.control.Labeled
import javafx.scene.control.MenuItem
import javafx.scene.control.ProgressBar
import javafx.scene.control.Tooltip
import javafx.scene.input.Clipboard
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.text.Text
import javafx.scene.layout.HBox
import javafx.scene.layout.Pane
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.stage.Modality
import javafx.stage.Stage
import javafx.util.Duration
import org.fxmisc.richtext.CodeArea
import org.fxmisc.richtext.LineNumberFactory
import org.fxmisc.richtext.model.StyleSpansBuilder
import org.fxmisc.richtext.model.TwoDimensional.Bias.Forward
import ru.ravel.xsltsandbox.AppContext

/**
 * Фабрика и поведение [CodeArea]: подсветка XML, парных тегов и поиска, сворачивание веток, вставка из буфера.
 */
class CodeAreaSupport(private val ctx: AppContext) {
	private val state get() = ctx.editor

	/**
	 *  Creates a CodeArea with line numbers and XML syntax highlighting
	 */
	fun createHighlightingCodeArea(highlightNaN: Boolean): CodeArea {
		return CodeArea().apply codeArea@{
			// стандартная нумерация строк с кнопкой сворачивания
			installFolding(this)
			textProperty().addListener { _, _, _ ->
				if (state.suspendHighlighting == 0) {
					highlightAllMatches(this, state.query, highlightNaN)
				}
			}
			caretPositionProperty().addListener { _, _, newPos ->
				highlightTagPair(this, newPos.toInt())
			}
			installFoldedLineCaretGuard(this)
			// в больших документах стили считаются только для видимой части — обновляем при прокрутке
			val scheduleViewportHighlight = {
				if (text.length > FULL_HIGHLIGHT_LIMIT && state.suspendHighlighting == 0) {
					highlightOf(this).scrollTimer.playFromStart()
				}
			}
			estimatedScrollYProperty().addListener { _, _, _ -> scheduleViewportHighlight() }
			heightProperty().addListener { _, _, _ -> scheduleViewportHighlight() }
			val self = this
			// Ctrl+Shift+минус и Alt+0 — свернуть всё рекурсивно в активной области
			addEventFilter(KeyEvent.KEY_PRESSED) { e ->
				val ctrlShiftMinus = (e.isControlDown || e.isMetaDown) && e.isShiftDown &&
					(e.code == KeyCode.MINUS || e.code == KeyCode.SUBTRACT)
				val altZero = e.isAltDown && !e.isControlDown && !e.isMetaDown && e.code == KeyCode.DIGIT0
				if ((ctrlShiftMinus || altZero) && isFocused) {
					e.consume()
					foldAll(this)
				}
			}
			// Ctrl+минус / Ctrl+равно — свернуть / развернуть элемент под кареткой
			addEventFilter(KeyEvent.KEY_PRESSED) { e ->
				val ctrl = (e.isControlDown || e.isMetaDown) && !e.isShiftDown && !e.isAltDown
				if (ctrl && isFocused) {
					when (e.code) {
						KeyCode.MINUS, KeyCode.SUBTRACT -> {
							e.consume()
							foldAtCaret(this)
						}

						KeyCode.EQUALS, KeyCode.PLUS, KeyCode.ADD -> {
							e.consume()
							unfoldAtCaret(this)
						}

						else -> {}
					}
				}
			}
			addEventFilter(KeyEvent.KEY_PRESSED) { e ->
				val ctrlV = e.code == KeyCode.V && e.isControlDown
				val shiftIns = e.code == KeyCode.INSERT && e.isShiftDown
				if ((ctrlV || shiftIns) && isFocused) {
					e.consume()
					pasteFromClipboardWithProgress(this) // ← теперь this — CodeArea!
				}
			}

			contextMenu = ContextMenu(
				MenuItem("Paste").apply {
					setOnAction { pasteFromClipboardWithProgress(self) }
				}
			)

			highlightAllMatches(this, state.query, highlightNaN)
		}
	}


	/** Состояние подсветки одной области */
	private class Highlight(area: CodeArea, support: CodeAreaSupport) {
		var query = ""
		var nan = false
		var pair: List<IntRange> = emptyList()

		/** Отложенная перерисовка при прокрутке */
		val scrollTimer = PauseTransition(Duration.millis(30.0)).apply {
			setOnFinished { support.applyHighlight(area) }
		}
	}

	private val highlights = WeakHashMap<CodeArea, Highlight>()

	private fun highlightOf(area: CodeArea): Highlight = highlights.getOrPut(area) { Highlight(area, this) }


	/**
	 * Запоминает параметры подсветки (поиск, NaN) и перерисовывает стили области.
	 * В больших документах стили считаются только для видимой части (см. [highlightWindow]).
	 */
	fun highlightAllMatches(area: CodeArea, query: String, highlightNaN: Boolean) {
		val h = highlightOf(area)
		h.query = query
		h.nan = highlightNaN
		applyHighlight(area)
	}


	/** Диапазон текста, для которого считаются стили: весь текст или видимые абзацы с запасом */
	private fun highlightWindow(area: CodeArea, text: String): IntRange {
		if (text.length <= FULL_HIGHLIGHT_LIMIT) return 0 until text.length
		val lastPar = area.paragraphs.size - 1
		val (first, last) = try {
			area.firstVisibleParToAllParIndex() to area.lastVisibleParToAllParIndex()
		} catch (_: Exception) {
			area.currentParagraph to area.currentParagraph
		}
		val from = (first - WINDOW_MARGIN_PARS).coerceIn(0, lastPar)
		val to = (last + WINDOW_MARGIN_PARS).coerceIn(from, lastPar)
		val start = area.getAbsolutePosition(from, 0)
		val end = area.getAbsolutePosition(to, area.getParagraphLength(to))
		return start until end.coerceAtMost(text.length)
	}


	private fun applyHighlight(area: CodeArea) {
		val text = area.text
		val h = highlightOf(area)
		if (text.isEmpty()) {
			area.setStyleSpans(0, StyleSpansBuilder<Collection<String>>().add(emptyList(), 0).create())
			return
		}
		val window = highlightWindow(area, text)
		if (window.isEmpty()) return
		val ws = window.first
		val we = window.last + 1
		val masks = IntArray(we - ws)

		fun mark(from: Int, to: Int, bit: Int) {
			for (i in maxOf(from, ws) until minOf(to, we)) masks[i - ws] = masks[i - ws] or bit
		}

		if (!state.disableSyntaxHighlighting) {
			val matcher = XML_PATTERN.matcher(text).region(ws, we)
			while (matcher.find()) {
				val bit = SYNTAX_GROUPS.firstOrNull { matcher.group(it.first) != null }?.second ?: continue
				mark(matcher.start(), matcher.end(), bit)
			}
		}
		if (h.nan) {
			NAN_REGEX.findAll(text.substring(ws, we)).forEach { m -> mark(ws + m.range.first, ws + m.range.last + 1, BIT_NAN) }
		}
		// Подсветка поиска (для всех полей)
		if (h.query.isNotEmpty()) {
			var from = text.indexOf(h.query, ws, ignoreCase = true)
			while (from in 0 until we) {
				mark(from, from + h.query.length, BIT_SEARCH)
				from = text.indexOf(h.query, from + h.query.length, ignoreCase = true)
			}
		}
		h.pair.forEach { mark(it.first, it.last + 1, BIT_PAIR) }

		// Сборка стилей по сериям одинаковых масок
		val spans = StyleSpansBuilder<Collection<String>>()
		var runStart = 0
		for (i in 1..masks.size) {
			if (i == masks.size || masks[i] != masks[runStart]) {
				spans.add(stylesOf(masks[runStart]), i - runStart)
				runStart = i
			}
		}
		area.setStyleSpans(ws, spans.create())
	}


	private fun stylesOf(mask: Int): Collection<String> = STYLE_CACHE.getOrPut(mask) {
		if (mask == 0) emptyList() else STYLE_NAMES.filter { mask and it.first != 0 }.map { it.second }
	}


	/** Подсвечивает парный тег для тега под кареткой (поиск ограничен [PAIR_SEARCH_LIMIT] символами) */
	private fun highlightTagPair(area: CodeArea, caretPos: Int) {
		val text = area.text
		if (text.isEmpty()) return
		val h = highlightOf(area)

		val ranges = mutableListOf<IntRange>()
		val before = text.lastIndexOf('<', caretPos).takeIf { it >= 0 }
		val after = before?.let { text.indexOf('>', it).takeIf { i -> i >= 0 } }
		if (before != null && after != null) {
			val fragment = text.substring(before, after + 1)
			val openName = XmlMarkupScanner.openTagName(fragment)
			val mClose = CLOSE_TAG_REGEX.matchEntire(fragment)
			if (openName != null) {
				// курсор на открывающем
				val matcher = tagPattern(openName).matcher(text)
					.region(after + 1, minOf(text.length, after + 1 + PAIR_SEARCH_LIMIT))
				var depth = 0
				while (matcher.find()) {
					val value = matcher.group()
					when {
						value.startsWith("</") -> {
							if (depth == 0) {
								ranges += (before..after)
								ranges += matcher.start() until matcher.end()
								break
							} else depth--
						}

						SELF_CLOSING_REGEX.matches(value) -> {}
						else -> depth++
					}
				}
			} else if (mClose != null) {
				// курсор на закрывающем
				val from = maxOf(0, before - PAIR_SEARCH_LIMIT)
				val matcher = tagPattern(mClose.groupValues[1]).matcher(text).region(from, before)
				val all = mutableListOf<IntRange>()
				val values = mutableListOf<String>()
				while (matcher.find()) {
					all += matcher.start() until matcher.end()
					values += matcher.group()
				}
				var depth = 0
				for (idx in values.indices.reversed()) {
					val value = values[idx]
					when {
						value.startsWith("</") -> depth++
						SELF_CLOSING_REGEX.matches(value) -> {}
						else -> {
							if (depth == 0) {
								ranges += all[idx]
								ranges += (before..after)
								break
							} else depth--
						}
					}
				}
			}
		}

		if (ranges == h.pair) return
		h.pair = ranges
		applyHighlight(area)
	}


	private fun tagPattern(tagName: String): Pattern = Pattern.compile("</?" + Pattern.quote(tagName) + "\\b[^>]*?/?>")


	/**
	 * Нумерация строк и кнопка сворачивания XML-веток в левом поле.
	 */
	fun installFolding(area: CodeArea) {
		val lineNoFactory = LineNumberFactory.get(area)

		// индекс сворачиваемых элементов пересчитывается с задержкой после правок текста
		val index = foldIndexes.getOrPut(area) { FoldIndex() }
		index.refresh.setOnFinished {
			index.ranges = null
			refreshGutters(area)
		}
		area.textProperty().addListener { _, _, _ -> index.refresh.playFromStart() }
		area.estimatedScrollXProperty().addListener { _, _, x ->
			if (index.chips.isNotEmpty()) index.chips.forEach { it.translateX = -x }
		}

		val factory = IntFunction<Node?> { line ->
			// У свёрнутого абзаца гуттера нет совсем: даже пустой узел у каждого из тысяч скрытых абзацев
			// делает горизонтальную прокрутку мучительно медленной (VirtualFlow перекладывает их все на каждый кадр)
			if (area.isFolded(line)) return@IntFunction null

			val lineNo = lineNoFactory.apply(line)
			// стандартный индикатор свёрнутости рядом с номером не нужен — есть наш маркер
			(lineNo as? Labeled)?.let { l ->
				l.graphic = null
				l.graphicProperty().addListener { _, _, g -> if (g != null) l.graphic = null }
			}

			val range = foldRangeOf(area, line)
			val canFold = range != null
			val folded = canFold && isFoldedStart(area, line)

			val marker: Node = if (canFold) {
				Label(if (folded) "▸" else "▾").apply {
					styleClass.setAll("fold-glyph")
					// крупная кликабельная зона на всю высоту строки
					prefWidth = FOLD_MARKER_WIDTH
					minWidth = FOLD_MARKER_WIDTH
					maxWidth = FOLD_MARKER_WIDTH
					maxHeight = Double.MAX_VALUE
					alignment = Pos.CENTER
					cursor = Cursor.HAND
				}
			} else {
				Region().apply {
					prefWidth = FOLD_MARKER_WIDTH
					minWidth = FOLD_MARKER_WIDTH
					maxWidth = FOLD_MARKER_WIDTH
				}
			}

			// порядок: [индикатор] [номер строки] [заглушка свёрнутой ветки]
			HBox(2.0, marker, lineNo).apply {
				alignment = Pos.CENTER_LEFT
				isFillHeight = true
				styleClass.add(VISIBLE_GUTTER)
				properties[GUTTER_LINE] = line
				properties[GUTTER_FOLDABLE] = canFold
				if (range != null && folded) children += foldedPlaceholder(area, line, range.name)
				if (canFold) {
					// Нажатие (а не клик): клик не срабатывает, если между нажатием и отпусканием
					// гуттер был пересоздан — из-за этого кнопка иногда реагировала только со второго раза
					addEventFilter(MouseEvent.MOUSE_PRESSED) { e ->
						if (e.button == MouseButton.PRIMARY && e.x <= FOLD_MARKER_WIDTH + FOLD_CLICK_SLACK) {
							toggleFold(area, line)
							e.consume()
						}
					}
				}
			}
		}
		// RichTextFX допускает null (абзац без гуттера), но Java-сигнатура этого не выражает
		@Suppress("UNCHECKED_CAST")
		area.paragraphGraphicFactory = factory as IntFunction<Node>
	}


	/**
	 * Вид свёрнутой ветки как в IDEA: `<Тег` + серая плашка `...` + `>`.
	 * Настоящий текст строки скрыт стилем `fold-start`, а эта заглушка рисуется на его месте.
	 */
	private fun foldedPlaceholder(area: CodeArea, line: Int, name: String): Node {
		val indent = area.getParagraph(line).text.takeWhile { it.isWhitespace() }
		fun text(t: String, vararg styles: String) = Text(t).apply { styleClass.addAll("fold-chip-text", *styles) }
		val chip = Label("...").apply { styleClass.add("fold-chip") }
		val content = HBox(0.0, text(indent), text("<$name", "tag"), chip, text(">", "bracket")).apply {
			alignment = Pos.CENTER_LEFT
			cursor = Cursor.HAND
			setOnMousePressed { e ->
				if (e.button == MouseButton.PRIMARY) {
					toggleFold(area, line)
					e.consume()
				}
			}
		}
		// левое поле не прокручивается, а плашка должна ехать вместе с текстом: сдвигаем её по общему
		// слушателю прокрутки (см. installFolding). Отдельная привязка на каждую плашку копилась бы
		// при пересоздании гуттеров и тормозила горизонтальную прокрутку.
		content.translateX = -area.estimatedScrollX
		foldIndexes.getOrPut(area) { FoldIndex() }.chips.add(content)
		// контейнер обрезает плашку по своей границе — слева от неё номера строк, поверх них она не заезжает
		return Pane(content).apply {
			clip = Rectangle().also {
				it.widthProperty().bind(widthProperty())
				it.heightProperty().bind(heightProperty())
			}
		}
	}


	/**
	 * Гуттеры уже созданных абзацев не знают о сворачивании: у свёрнутых они остались бы на экране
	 * поверх видимых строк (и перехватывали бы клики), а у развёрнутых — остались бы пустыми.
	 * Пересоздаём гуттер только у тех материализованных абзацев, где состояние разошлось.
	 */
	private fun refreshGutters(area: CodeArea) {
		for (gutter in area.lookupAll(".$VISIBLE_GUTTER").toList()) {
			val line = gutter.properties[GUTTER_LINE] as? Int ?: continue
			if (line >= area.paragraphs.size) continue
			val foldable = gutter.properties[GUTTER_FOLDABLE] as? Boolean
			val outdated = area.isFolded(line) ||
				(foldable != null && foldable != (foldRangeOf(area, line) != null))
			if (outdated) area.recreateParagraphGraphic(line)
		}
	}


	/** Элемент, который можно свернуть: открывается в одной строке, а закрывается в одной из следующих */
	private class FoldRange(val endLine: Int, val name: String, val startOffset: Int)

	private class FoldIndex {
		var ranges: Map<Int, FoldRange>? = null
		val refresh = PauseTransition(Duration.millis(300.0))

		/** Живые плашки свёрнутых строк (слабые ссылки — исчезнувшие узлы не копятся) */
		val chips: MutableSet<Node> = Collections.newSetFromMap(WeakHashMap())
	}

	private val foldIndexes = WeakHashMap<CodeArea, FoldIndex>()

	private fun foldRangeOf(area: CodeArea, line: Int): FoldRange? {
		val index = foldIndexes.getOrPut(area) { FoldIndex() }
		val ranges = index.ranges ?: computeFoldRanges(area.text).also { index.ranges = it }
		return ranges[line]
	}


	/**
	 * Один проход по тексту: пары открывающий/закрывающий тег, лежащие в разных строках.
	 * Самозакрывающиеся теги, теги без пары и однострочные элементы не сворачиваются.
	 * Комментарии, CDATA и инструкции обработки пропускаются.
	 */
	private fun computeFoldRanges(text: String): Map<Int, FoldRange> {
		class Open(val name: String, val line: Int, val offset: Int)

		val result = HashMap<Int, FoldRange>()
		val stack = ArrayList<Open>()
		var pos = 0
		var line = 0
		XmlMarkupScanner.scan(text) { start, end, name, closing, selfClosing ->
			for (i in pos until start) if (text[i] == '\n') line++
			if (name != null) {
				if (closing) {
					val at = stack.indexOfLast { it.name == name }
					if (at >= 0) {
						val open = stack[at]
						while (stack.size > at) stack.removeAt(stack.size - 1)
						var tagLines = 0
						for (k in start until end) if (text[k] == '\n') tagLines++
						val endLine = line + tagLines
						if (endLine > open.line) {
							val prev = result[open.line]
							if (prev == null || open.offset < prev.startOffset) {
								result[open.line] = FoldRange(endLine, name, open.offset)
							}
						}
					}
				} else if (!selfClosing) {
					stack += Open(name, line, start)
				}
			}
			// переводы строк внутри самого совпадения (многострочные теги, комментарии)
			for (k in start until end) if (text[k] == '\n') line++
			pos = end
		}
		return result
	}


	/** Свёрнута ли ветка, начинающаяся с абзаца [line]: следующий абзац скрыт */
	private fun isFoldedStart(area: CodeArea, line: Int): Boolean =
		line + 1 < area.paragraphs.size && area.isFolded(line + 1)


	/**
	 * Сворачивает/разворачивает ветку, открывающий тег которой начинается в абзаце [line].
	 * Закрывающий тег ищется с учётом вложенности одноимённых тегов. Подсветка на время операции приостанавливается:
	 * сворачивание заменяет весь диапазон текста, и без этого он перекрашивался бы целиком.
	 */
	private fun toggleFold(area: CodeArea, line: Int) {
		foldIndexes[area]?.ranges = null // правки могли случиться меньше задержки назад
		if (isFoldedStart(area, line)) {
			// вложенные свёрнутые ветки тоже разворачиваются — запоминаем весь свёрнутый диапазон
			var last = line + 1
			while (last + 1 < area.paragraphs.size && area.isFolded(last + 1)) last++
			runSuspended(area) { area.unfoldParagraphs(line) }
			// стиль «начало свёрнутой ветки» должен остаться у тех вложенных веток, которые всё ещё свёрнуты
			for (p in line..last) setFoldStartStyle(area, p, isFoldedStart(area, p))
			// у развёрнутых абзацев гуттера не было — создаём (для невидимых в окне вызов ничего не делает)
			for (p in line + 1..last) if (!area.isFolded(p)) area.recreateParagraphGraphic(p)
			refreshGutters(area)
			area.recreateParagraphGraphic(line)
			area.deselect()
			return
		}
		val endPar = foldRangeOf(area, line)?.endLine ?: return

		runSuspended(area) { area.foldParagraphs(line, endPar) }
		setFoldStartStyle(area, line, true)
		refreshGutters(area)
		area.recreateParagraphGraphic(line)
		area.deselect()
	}


	/**
	 * Настоящий текст свёрнутой строки скрыт, а вместо него нарисована плашка, поэтому каретка внутри него
	 * оказалась бы в «пустом» месте далеко справа от плашки. Держим каретку в начале такой строки (сразу после
	 * плашки); стрелками вправо/вниз перескакиваем через свёрнутый блок, мышью просто остаёмся в начале строки.
	 */
	private fun installFoldedLineCaretGuard(area: CodeArea) {
		var fromMouse = false
		var guard = false
		area.addEventFilter(MouseEvent.MOUSE_PRESSED) { fromMouse = true }
		area.addEventFilter(KeyEvent.KEY_PRESSED) { fromMouse = false }
		area.caretPositionProperty().addListener { _, old, new ->
			if (guard || area.selection.length > 0) return@addListener
			val par = area.currentParagraph
			if (area.caretColumn == 0 || !isFoldedStart(area, par)) return@addListener
			guard = true
			try {
				if (!fromMouse && new.toInt() > old.toInt()) {
					// вправо: на первую строку после свёрнутого блока
					var last = par + 1
					while (last + 1 < area.paragraphs.size && area.isFolded(last + 1)) last++
					if (last + 1 < area.paragraphs.size) area.moveTo(last + 1, 0) else area.moveTo(par, 0)
				} else {
					area.moveTo(par, 0)
				}
			} finally {
				guard = false
			}
		}
	}


	/** Сворачивает самый вложенный ещё развёрнутый элемент, содержащий строку с кареткой */
	fun foldAtCaret(area: CodeArea) {
		val line = area.currentParagraph
		val index = foldIndexes.getOrPut(area) { FoldIndex() }
		val ranges = computeFoldRanges(area.text).also { index.ranges = it }
		val start = ranges.entries
			.filter { (s, r) -> s <= line && line <= r.endLine && !isFoldedStart(area, s) && !area.isFolded(s) }
			.maxOfOrNull { it.key } ?: return
		toggleFold(area, start)
		area.moveTo(start, 0)
	}


	/** Разворачивает свёрнутый элемент, у которого каретка стоит на первой строке */
	fun unfoldAtCaret(area: CodeArea) {
		val line = area.currentParagraph
		if (isFoldedStart(area, line)) toggleFold(area, line)
	}


	/**
	 * Сворачивает все ветки рекурсивно (как «Collapse All» в IDEA): остаются видны только корневые элементы,
	 * а вложенные ветки остаются свёрнутыми и после разворачивания внешних.
	 */
	fun foldAll(area: CodeArea) {
		val index = foldIndexes.getOrPut(area) { FoldIndex() }
		val ranges = computeFoldRanges(area.text).also { index.ranges = it }
		if (ranges.isEmpty()) return

		// глубина вложенности каждой ветки
		val sorted = ranges.entries.sortedWith(compareBy({ it.key }, { -it.value.endLine }))
		val ends = ArrayList<Int>()
		val items = sorted.map { (start, range) ->
			while (ends.isNotEmpty() && ends.last() <= start) ends.removeAt(ends.size - 1)
			val depth = ends.size
			ends += range.endLine
			Triple(start, range.endLine, depth)
		}
		state.suspendHighlighting++
		try {
			// от самых вложенных к внешним
			for ((start, end, _) in items.sortedWith(compareBy({ -it.third }, { -it.first }))) {
				if (area.isFolded(start + 1)) continue
				area.foldParagraphs(start, end)
				setFoldStartStyle(area, start, true)
			}
		} finally {
			state.suspendHighlighting--
		}
		applyHighlight(area)
		refreshGutters(area)
		area.deselect()
	}


	/** Помечает строку начала свёрнутой ветки: CSS скрывает её настоящий текст (его заменяет плашка в гуттере) */
	private fun setFoldStartStyle(area: CodeArea, paragraph: Int, on: Boolean) {
		val style = area.getParagraph(paragraph).paragraphStyle
		if (style.contains(FOLD_START_STYLE) == on) return
		area.setParagraphStyle(paragraph, if (on) style + FOLD_START_STYLE else style - FOLD_START_STYLE)
	}


	private fun runSuspended(area: CodeArea, action: () -> Unit) {
		state.suspendHighlighting++
		try {
			action()
		} finally {
			state.suspendHighlighting--
		}
		applyHighlight(area)
	}


	fun pasteFromClipboardWithProgress(area: CodeArea) {
		val clip = Clipboard.getSystemClipboard()
		val text = clip.string ?: return

		// Небольшие вставки — мгновенно, без диалога
		if (text.length < 200_000) {
			state.suspendHighlighting++
			try {
				area.replaceSelection(text)
			} finally {
				state.suspendHighlighting--
				highlightAllMatches(area, state.query, area === ctx.currentSession.resultArea)
			}
			return
		}

		// Крупная вставка — по блокам, с прогрессом
		val total = text.length
		val chunk = 64 * 1024

		val bar = ProgressBar(0.0).apply { prefWidth = 380.0 }
		val msg = Label("Pasting… 0%")
		val cancelBtn = Button("Cancel")
		val box = VBox(10.0, msg, bar, HBox(10.0, cancelBtn)).apply {
			padding = Insets(14.0)
			alignment = Pos.CENTER_LEFT
		}
		val dlg = Stage().apply {
			initOwner(ctx.stage)
			initModality(Modality.WINDOW_MODAL)
			title = "Pasting large text"
			scene = Scene(box)
		}

		val startSel = area.selection.start
		val endSel = area.selection.end
		state.suspendHighlighting++

		var i = 0
		// Сначала очищаем выделение и ставим каретку в начало вставки
		area.replaceText(startSel, endSel, "")
		area.moveTo(startSel)

		val timer = object : AnimationTimer() {
			override fun handle(now: Long) {
				val next = (i + chunk).coerceAtMost(total)
				val part = text.substring(i, next)
				area.insertText(area.caretPosition, part)
				i = next

				val p = i.toDouble() / total
				bar.progress = p
				msg.text = "Pasting… ${(p * 100).toInt()}%"

				if (i >= total) {
					stop()
					dlg.close()
					state.suspendHighlighting--
					highlightAllMatches(area, state.query, area === ctx.currentSession.resultArea)
					area.requestFollowCaret()
				}
			}
		}

		cancelBtn.setOnAction {
			timer.stop()
			dlg.close()
			state.suspendHighlighting--
			highlightAllMatches(area, state.query, area === ctx.currentSession.resultArea)
		}

		dlg.show()
		timer.start()
	}


	companion object {
		private val XML_PATTERN: Pattern = Pattern.compile(
			"(?<COMMENT><!--[\\s\\S]*?-->)" +
				"|(?<CDATA><!\\[CDATA\\[[\\s\\S]*?]]>)" +
				"|(?<TAG></?\\w+)" +
				"|(?<LOCAL>:[\\w-]+)" +
				"|(?<ATTR>\\b\\w+(?==))" +
				"|(?<VALUE>\"[^\"]*\")" +
				"|(?<BRACKET>/?>)"
		)

		/** Тексты длиннее этого красятся только в видимой области */
		private const val FULL_HIGHLIGHT_LIMIT = 100_000
		private const val WINDOW_MARGIN_PARS = 100
		private const val PAIR_SEARCH_LIMIT = 200_000

		private const val FOLD_START_STYLE = "fold-start"
		private const val GUTTER_FOLDABLE = "fold-gutter-foldable"
		private const val GUTTER_LINE = "fold-gutter-line"
		private const val VISIBLE_GUTTER = "fold-gutter"
		private const val FOLD_MARKER_WIDTH = 18.0
		private const val FOLD_CLICK_SLACK = 6.0

		private const val BIT_NAN = 1 shl 7
		private const val BIT_SEARCH = 1 shl 8
		private const val BIT_PAIR = 1 shl 9

		private val SYNTAX_GROUPS = listOf(
			"COMMENT" to 1, "CDATA" to (1 shl 1), "TAG" to (1 shl 2), "LOCAL" to (1 shl 3),
			"ATTR" to (1 shl 4), "VALUE" to (1 shl 5), "BRACKET" to (1 shl 6),
		)

		/** Порядок классов важен: сначала синтаксис, затем NaN, поиск, парный тег */
		private val STYLE_NAMES = listOf(
			1 to "comment", (1 shl 1) to "cdata", (1 shl 2) to "tag", (1 shl 3) to "local",
			(1 shl 4) to "attribute", (1 shl 5) to "value", (1 shl 6) to "bracket",
			BIT_NAN to "nan-highlight", BIT_SEARCH to "search-result", BIT_PAIR to "tag-match-highlight",
		)
		private val STYLE_CACHE = HashMap<Int, Collection<String>>()

		private val NAN_REGEX = Regex("\\bNaN\\b")
		// значения атрибутов могут содержать «/» (пути XPath), поэтому кавычки разбираются отдельно
		private val CLOSE_TAG_REGEX = Regex("</([A-Za-z_][\\w:.-]*)\\s*>")
		private val SELF_CLOSING_REGEX = Regex("<([A-Za-z_][\\w:.-]*)[^>]*/>")
	}
}
