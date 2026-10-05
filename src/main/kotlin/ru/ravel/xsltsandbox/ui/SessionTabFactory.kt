package ru.ravel.xsltsandbox.ui

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Orientation
import javafx.geometry.Pos
import javafx.scene.canvas.Canvas
import javafx.scene.control.ContextMenu
import javafx.scene.control.Label
import javafx.scene.control.MenuItem
import javafx.scene.control.SplitPane
import javafx.scene.control.Tab
import javafx.scene.control.TreeCell
import javafx.scene.control.TreeView
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import org.fxmisc.flowless.VirtualizedScrollPane
import org.fxmisc.richtext.CodeArea
import ru.ravel.xsltsandbox.AppContext
import ru.ravel.xsltsandbox.editor.CodeAreaSupport
import ru.ravel.xsltsandbox.editor.XsltOverlay.hookOverlayRedraw
import ru.ravel.xsltsandbox.models.DocSession

/**
 * Создаёт вкладку сессии: XSLT/BR, XML и результат.
 */
class SessionTabFactory(
	private val ctx: AppContext,
	private val support: CodeAreaSupport,
) {
	/** Вызывается для каждой новой сессии — подключает обработчики отладчика и т.п. */
	var onCreated: (DocSession) -> Unit = {}


	fun create(title: String): DocSession {
		val xsltArea = support.createHighlightingCodeArea(false)
		val xsltScroll = VirtualizedScrollPane(xsltArea).apply { minWidth = 0.0 }
		val xsltOverlay = Canvas().apply { isMouseTransparent = true }
		val xsltStack = StackPane(xsltScroll, xsltOverlay).apply {
			minWidth = 0.0
			minHeight = 0.0
		}
		xsltOverlay.widthProperty().bind(xsltStack.widthProperty())
		xsltOverlay.heightProperty().bind(xsltStack.heightProperty())
		StackPane.setAlignment(xsltOverlay, Pos.TOP_LEFT)
		StackPane.setAlignment(xsltScroll, Pos.TOP_LEFT)
		VBox.setVgrow(xsltStack, Priority.ALWAYS)
		val xsltStatusLabel = Label().apply {
			isVisible = false
			padding = Insets(0.0, 0.0, 0.0, 8.0)
		}
		val xsltHeader = HBox(4.0, Label("XSLT"), xsltStatusLabel).apply {
			alignment = Pos.CENTER_LEFT
		}
		val xsltBox = VBox(4.0, xsltHeader, xsltStack).apply {
			padding = Insets(8.0)
			minWidth = 0.0
		}
		val brTreeView = TreeView<String>().apply {
			isShowRoot = true
			// текст узла рисуется цветной графикой (см. TreeUtil), поэтому обычный текст ячейки скрываем
			setCellFactory {
				object : TreeCell<String>() {
					override fun updateItem(item: String?, empty: Boolean) {
						super.updateItem(item, empty)
						if (empty || item == null) {
							text = null
							graphic = null
						} else {
							val g = treeItem?.graphic
							text = if (g == null) item else null
							graphic = g
						}
					}
				}
			}
			// Ctrl+C
			addEventFilter(KeyEvent.KEY_PRESSED) { e ->
				if (e.code == KeyCode.C && e.isControlDown) {
					val selected = selectionModel.selectedItem
					if (selected != null) {
						val clip = Clipboard.getSystemClipboard()
						val content = ClipboardContent()
						content.putString(selected.value)
						clip.setContent(content)
					}
					e.consume()
				}
			}

			// Контекстное меню ПКМ
			contextMenu = ContextMenu().apply {
				val copyItem = MenuItem("Copy").apply {
					setOnAction {
						val selected = selectionModel.selectedItem
						if (selected != null) {
							val clip = Clipboard.getSystemClipboard()
							val content = ClipboardContent()
							content.putString(selected.value)
							clip.setContent(content)
						}
					}
				}
				items.add(copyItem)
			}
		}
		VBox.setVgrow(brTreeView, Priority.ALWAYS)
		val brHeader = Label("Business Rule")
		val brBox = VBox(4.0, brHeader, brTreeView).apply {
			padding = Insets(8.0)
			minWidth = 0.0
			isVisible = false
			isManaged = false
		}
		val xmlArea = support.createHighlightingCodeArea(false)
		val resultArea = support.createHighlightingCodeArea(true).apply {
			isEditable = false
			minHeight = 0.0
		}

		// чтобы Ctrl+F искал по активной области
		listOf(xsltArea, xmlArea, resultArea).forEach { area ->
			area.setOnMouseClicked { ctx.currentArea = area }
			area.addEventHandler(KeyEvent.KEY_PRESSED) { ctx.currentArea = area }
		}

		val xmlBox = vBoxWithLabel("XML", xmlArea)

		val nanCountLabel = Label().apply {
			isVisible = false
			padding = Insets(0.0, 0.0, 0.0, 8.0)
		}
		val resultLabel = Label("Result")
		val resultHeader = HBox(4.0, resultLabel, nanCountLabel).apply { alignment = Pos.CENTER_LEFT }
		val resultScroll = VirtualizedScrollPane(resultArea).apply {
			minHeight = 0.0
		}
		VBox.setVgrow(resultScroll, Priority.ALWAYS)
		val resultBox = VBox(4.0, resultHeader, resultScroll).apply {
			padding = Insets(8.0)
			minHeight = 0.0
		}

		// теперь стек для XSLT/BR
		val xsltOrBrStack = StackPane(xsltBox, brBox)
		val topSplit = SplitPane(xsltOrBrStack, xmlBox).apply {
			orientation = Orientation.HORIZONTAL
			setDividerPositions(0.5)
			minHeight = 0.0
		}
		val mainSplit = SplitPane(topSplit, resultBox).apply {
			orientation = Orientation.VERTICAL
			setDividerPositions(0.5)
		}

		mainSplit.sceneProperty().addListener { _, _, scene ->
			if (scene != null) {
				Platform.runLater { mainSplit.setDividerPositions(0.5) }
			}
		}

		val tab = Tab(title, mainSplit).apply {
			isClosable = true
			setOnClosed {
				ctx.sessions.remove(this)
				if (ctx.tabPane.tabs.size == 1) { // остался только '+'
					val t = create("Tab 1")
					ctx.tabPane.tabs.add(ctx.tabPane.tabs.size - 1, t.tab)
					ctx.tabPane.selectionModel.select(t.tab)
				}
			}
		}

		val session = DocSession(tab, xsltArea, xmlArea, resultArea, nanCountLabel).also {
			it.brTree = brTreeView
			it.xsltOverlay = xsltOverlay
			it.xsltStatusLabel = xsltStatusLabel
			it.xsltBox = xsltBox
			it.brBox = brBox
		}
		ctx.sessions[tab] = session
		hookOverlayRedraw(session)
		onCreated(session)
		return session
	}


	/**
	 * Wraps a CodeArea in a VBox with a label and VirtualizedScrollPane
	 */
	private fun vBoxWithLabel(labelText: String, area: CodeArea): VBox {
		val label = Label(labelText)
		val scrolled = VirtualizedScrollPane(area).apply { minWidth = 0.0 }
		VBox.setVgrow(scrolled, Priority.ALWAYS)
		return VBox(4.0, label, scrolled).apply {
			padding = Insets(8.0)
			minWidth = 0.0
		}
	}
}
