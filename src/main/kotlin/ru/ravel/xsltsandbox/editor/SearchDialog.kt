package ru.ravel.xsltsandbox.editor

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.input.KeyCode
import javafx.scene.layout.HBox
import javafx.stage.Modality
import javafx.stage.Stage
import org.fxmisc.richtext.CodeArea
import ru.ravel.xsltsandbox.AppContext

/**
 * Окно поиска по тексту [CodeArea].
 */
class SearchDialog(
	private val ctx: AppContext,
	private val support: CodeAreaSupport,
) {
	private val state get() = ctx.editor

	private var searchInfoLabel: Label? = null
	private var searchMatches: List<IntRange> = emptyList()
	private var searchTarget: CodeArea? = null
	private var searchDialog: Stage? = null
	private var searchField: TextField? = null


	/**
	 * Opens a modal search window for the given CodeArea
	 */
	fun showSearchWindow(owner: Stage, target: CodeArea) {
		searchTarget = target
		if (searchDialog != null) {
			val selected = target.selectedText.takeIf { it.isNotEmpty() } ?: ""
			searchField?.apply {
				text = selected
				requestFocus()
				selectAll()
			}
			searchMatches = allMatches(target.text, searchField?.text ?: "")
			searchInfoLabel?.text = if (searchMatches.isEmpty()) "0/0" else "1/${searchMatches.size}"
			searchDialog?.toFront()
			searchDialog?.requestFocus()
			return
		}
		val dialog = Stage().apply {
			initOwner(owner)
			initModality(Modality.NONE)
//			isAlwaysOnTop = true
			title = "Search…"
		}
		searchDialog = dialog
		val initial = target.selectedText.takeIf { it.isNotEmpty() } ?: ""
		val field = TextField(initial).apply { promptText = "Find..." }
		searchField = field
		val nextBtn = Button("Find Next").apply {
			setOnAction { search(searchTarget ?: target, field.text, forward = true) }
		}
		val prevBtn = Button("Find Previous").apply {
			setOnAction { search(searchTarget ?: target, field.text, forward = false) }
		}
		val infoLbl = Label("0/0").apply {
			minWidth = 60.0
			alignment = Pos.CENTER
		}
		searchInfoLabel = infoLbl
		val closeBtn = Button("Close").apply { setOnAction { dialog.close() } }
		field.textProperty().addListener { _, _, newValue ->
			state.query = newValue
			val area = searchTarget ?: target
			support.highlightAllMatches(area, state.query, area === ctx.currentSession.resultArea)
			searchMatches = allMatches(area.text, newValue)
			searchInfoLabel?.text = if (searchMatches.isEmpty()) "0/0" else "1/${searchMatches.size}"
		}
		dialog.setOnHidden {
			searchDialog = null
			searchField = null
			searchInfoLabel = null
			searchMatches = emptyList()
			searchTarget = null
		}
		field.setOnKeyPressed { event ->
			when {
				event.code == KeyCode.ENTER && !event.isShiftDown -> {
					search(target, field.text, forward = true)
					event.consume()
				}

				event.code == KeyCode.ENTER && event.isShiftDown -> {
					search(target, field.text, forward = false)
					event.consume()
				}
			}
		}

		val hbox = HBox(5.0, field, nextBtn, prevBtn, infoLbl, closeBtn).apply {
			padding = Insets(10.0)
			alignment = Pos.CENTER
		}
		val scene = Scene(hbox)
		scene.setOnKeyPressed { event ->
			if (event.code == KeyCode.ESCAPE) {
				dialog.close()
			}
		}
		dialog.scene = scene
		dialog.show()

		Platform.runLater {
			field.requestFocus()
			field.selectAll()
			support.highlightAllMatches(target, field.text, target === ctx.currentSession.resultArea)
		}
	}


	private fun allMatches(text: String, query: String): List<IntRange> {
		return if (query.isBlank()) {
			emptyList()
		} else {
			Regex(Regex.escape(query), RegexOption.IGNORE_CASE)
				.findAll(text).map { it.range }.toList()
		}
	}


	/**
	 * Finds the query in the CodeArea (forward/backward) and scrolls to it
	 */
	private fun search(area: CodeArea, query: String, forward: Boolean) {
		if (query.isBlank()) {
			searchInfoLabel?.text = "0/0"
			return
		}

		// Всегда пересчитываем список под актуальный текст
		val matches = allMatches(area.text, query)
		searchMatches = matches
		val total = matches.size
		if (total == 0) {
			searchInfoLabel?.text = "0/0"
			return
		}

		// Используем границы текущего выделения, а не caretPosition
		val selStart = area.selection.start
		val selEnd = area.selection.end

		val anchor = if (forward) selEnd else (selStart - 1).coerceAtLeast(0)

		val idx = if (forward) {
			// Следующее совпадение со стартом >= anchor, иначе — первое (wrap)
			matches.indexOfFirst { it.first >= anchor }.let { if (it == -1) 0 else it }
		} else {
			// Предыдущее со стартом < anchor, иначе — последнее (wrap)
			matches.indexOfLast { it.first < anchor }.let { if (it == -1) total - 1 else it }
		}

		val r = matches[idx]
		showMatch(area, r.first, r.last + 1)
		searchInfoLabel?.text = "${idx + 1}/$total"
	}


	/** Прокручивает и по вертикали (стандартно), и по горизонтали (вручную) к каретке. */
	private fun CodeArea.followCaretBothAxes() {
		// Вертикаль (и часть горизонтали) — стандартно
		requestFollowCaret()
		// Горизонталь — руками
		Platform.runLater {
			caretBounds.ifPresent { b ->
				// хотим видеть каретку не у самого края, а с небольшим отступом
				val targetX = kotlin.math.max(0.0, b.minX - this.width / 3.0)
				scrollXToPixel(targetX)
			}
		}
	}


	private fun showMatch(area: CodeArea, start: Int, end: Int) {
		area.moveTo(start)
		area.selectRange(start, end)
		area.followCaretBothAxes()
	}
}
