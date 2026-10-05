package ru.ravel.xsltsandbox.editor

import javafx.geometry.Insets
import javafx.scene.Scene
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.stage.Stage
import org.fxmisc.flowless.VirtualizedScrollPane

/**
 * Окно только для чтения с текстом DataDocs (или отчёта) и поиском по Ctrl+F.
 */
class DataDocsViewer(
	private val support: CodeAreaSupport,
	private val search: SearchDialog,
) {
	fun show(owner: Stage, title: String, text: String) {
		val area = support.createHighlightingCodeArea(highlightNaN = false).apply {
			isEditable = false
			replaceText(text)
		}

		val root = VBox(
			VirtualizedScrollPane(area).apply { VBox.setVgrow(this, Priority.ALWAYS) }
		).apply { padding = Insets(8.0) }

		val st = Stage().apply {
			initOwner(owner)
			this.title = title
		}

		val scene = Scene(root, 900.0, 700.0)
		scene.addEventFilter(KeyEvent.KEY_PRESSED) { e ->
			if (e.isControlDown && e.code == KeyCode.F) {
				search.showSearchWindow(st, area)
				e.consume()
			}
		}
		st.scene = scene
		st.show()
		st.toFront()
	}
}
