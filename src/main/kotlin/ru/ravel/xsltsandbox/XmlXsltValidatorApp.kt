package ru.ravel.xsltsandbox

import javafx.application.Application
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Orientation
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.stage.Modality
import javafx.stage.Stage
import org.fxmisc.flowless.VirtualizedScrollPane
import org.fxmisc.richtext.CodeArea
import org.fxmisc.richtext.LineNumberFactory
import org.fxmisc.richtext.model.StyleSpansBuilder
import org.fxmisc.richtext.model.TwoDimensional.Bias
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import java.io.StringWriter
import java.util.regex.Pattern
import javax.xml.parsers.SAXParserFactory
import javax.xml.transform.ErrorListener
import javax.xml.transform.TransformerException
import javax.xml.transform.TransformerFactory
import javax.xml.transform.stream.StreamResult
import javax.xml.transform.stream.StreamSource


class XmlXsltValidatorApp : Application() {

	private lateinit var xsltArea: CodeArea
	private lateinit var xmlArea: CodeArea
	private lateinit var resultArea: CodeArea
	private var currentArea: CodeArea? = null
	private var searchDialog: Stage? = null
	private var searchField: TextField? = null
	private var currentQuery: String = ""


	override fun start(primaryStage: Stage) {
		xsltArea = createHighlightingCodeArea(false)
		xmlArea = createHighlightingCodeArea(false)
		resultArea = createHighlightingCodeArea(true).apply { isEditable = false }

		listOf(xsltArea, xmlArea, resultArea).forEach { area ->
			area.setOnMouseClicked { currentArea = area }
			area.addEventHandler(KeyEvent.KEY_PRESSED) { currentArea = area }
		}
		currentArea = xmlArea

		val xsltBox = vBoxWithLabel("XSLT", xsltArea)
		val xmlBox = vBoxWithLabel("XML", xmlArea)
		val resultBox = vBoxWithLabel("Result", resultArea)

		val topSplit = SplitPane(xsltBox, xmlBox).apply {
			orientation = Orientation.HORIZONTAL
			setDividerPositions(0.5)
		}
		val mainSplit = SplitPane(topSplit, resultBox).apply {
			orientation = Orientation.VERTICAL
			setDividerPositions(0.7)
		}

		val validateBtn = Button("Validate & Transform").apply {
			setOnAction { doTransform(primaryStage) }
		}
		val searchBtn = Button("Search").apply {
			setOnAction { showSearchWindow(primaryStage, currentArea ?: xmlArea) }
		}
		val toolBar = HBox(5.0, validateBtn, searchBtn).apply {
			padding = Insets(10.0)
		}

		val root = BorderPane().apply {
			top = toolBar
			center = mainSplit
		}

		val scene = Scene(root, 900.0, 650.0)
		val cssUrl = javaClass.classLoader.getResource("xml-highlighting.css")
		if (cssUrl != null) {
			scene.stylesheets += cssUrl.toExternalForm()
		} else {
			System.err.println("⚠️ xml-highlighting.css not found in classpath!")
		}

		scene.addEventFilter(KeyEvent.KEY_PRESSED) { event ->
			if (event.code == KeyCode.F && event.isControlDown) {
				showSearchWindow(primaryStage, currentArea ?: xmlArea)
				event.consume()
			}
		}

		primaryStage.apply {
			title = "XSLT Sandbox"
			this.scene = scene
			show()
		}
	}


	/**
	 *  Creates a CodeArea with line numbers and XML syntax highlighting
	 */
	private fun createHighlightingCodeArea(highlightNaN: Boolean): CodeArea =
		CodeArea().apply {
			paragraphGraphicFactory = LineNumberFactory.get(this)
			textProperty().addListener { _, _, _ ->
				highlightAllMatches(this, "", highlightNaN)
			}
			highlightAllMatches(this, "", highlightNaN)
		}

	/**
	 * Wraps a CodeArea in a VBox with a label and VirtualizedScrollPane
	 */
	private fun vBoxWithLabel(labelText: String, area: CodeArea): VBox {
		val label = Label(labelText)
		val scrolled = VirtualizedScrollPane(area)
		VBox.setVgrow(scrolled, Priority.ALWAYS)
		return VBox(4.0, label, scrolled).apply { padding = Insets(8.0) }
	}


	/**
	 * Performs XML well-formed check, XSLT compilation & transformation
	 */
	private fun doTransform(owner: Stage) {
		val status = StringBuilder()

		try {
			SAXParserFactory.newInstance().apply {
				isNamespaceAware = true
				isValidating = false
			}.newSAXParser().parse(
				InputSource(StringReader(xmlArea.text)),
				object : DefaultHandler() {
					override fun warning(e: SAXParseException) {
						status.append("WARNING in XML [line=${e.lineNumber},col=${e.columnNumber}]: ${e.message}\n")
					}

					override fun error(e: SAXParseException) {
						status.append("ERROR   in XML [line=${e.lineNumber},col=${e.columnNumber}]: ${e.message}\n")
					}

					override fun fatalError(e: SAXParseException) {
						status.append("FATAL   in XML [line=${e.lineNumber},col=${e.columnNumber}]: ${e.message}\n")
					}
				}
			)
			status.append("XML is well-formed.\n")
		} catch (ex: Exception) {
			status.append("XML parsing halted: ${ex.message}\n")
		}

		val tfFactory: TransformerFactory = TransformerFactory.newInstance(
			"net.sf.saxon.TransformerFactoryImpl",
			XmlXsltValidatorApp::class.java.classLoader
		).apply {
			setErrorListener(object : ErrorListener {
				override fun warning(ex: TransformerException) = report("WARNING in XSLT", ex)
				override fun error(ex: TransformerException) = report("ERROR   in XSLT", ex)
				override fun fatalError(ex: TransformerException) = report("FATAL   in XSLT", ex)
				private fun report(level: String, ex: TransformerException) {
					val loc = ex.locator
					if (loc != null) {
						status.append("$level [line=${loc.lineNumber},col=${loc.columnNumber}]: ${ex.message}\n")
					} else {
						status.append("$level: ${ex.message}\n")
					}
				}
			})
		}

		val templates = try {
			tfFactory.newTemplates(StreamSource(StringReader(xsltArea.text))).also {
				status.append("XSLT compiled successfully.\n")
			}
		} catch (ex: TransformerException) {
			showStatus(owner, status.toString())
			return
		}

		val writer = StringWriter()
		try {
			templates.newTransformer().apply {
				errorListener = tfFactory.errorListener
			}.transform(
				StreamSource(StringReader(xmlArea.text)),
				StreamResult(writer)
			)
		} catch (ex: TransformerException) {
			System.err.println(ex.message)
		}

		Platform.runLater {
			resultArea.replaceText(writer.toString())
			highlightAllMatches(resultArea, currentQuery, true)
//			resultArea.setStyleSpans(0, computeHighlighting(resultArea.text))
			showStatus(owner, status.toString())
		}
	}


	/**
	 * Shows a modal dialog with validation/transformation status
	 */
	private fun showStatus(owner: Stage, text: String) {
		val dialog = Stage().apply {
			initOwner(owner)
			initModality(Modality.WINDOW_MODAL)
			title = "Status"
		}
		val ta = TextArea(text).apply {
			isEditable = false
			isWrapText = true
		}
		val box = VBox(ta).apply {
			padding = Insets(10.0)
			VBox.setVgrow(ta, Priority.ALWAYS)
		}
		dialog.scene = Scene(box, 500.0, 300.0)
		dialog.show()
	}


	/**
	 * Opens a modal search window for the given CodeArea
	 */
	private fun showSearchWindow(owner: Stage, target: CodeArea) {
		if (searchDialog != null) {
			val selected = target.selectedText.takeIf { it.isNotEmpty() } ?: ""
			searchField?.apply {
				text = selected
				requestFocus()
				selectAll()
			}
			searchDialog?.toFront()
			searchDialog?.requestFocus()
			return
		}
		val dialog = Stage().apply {
			initOwner(owner)
			initModality(Modality.NONE)
			isAlwaysOnTop = true
			title = "Search"
		}
		searchDialog = dialog
		val initial = target.selectedText.takeIf { it.isNotEmpty() } ?: ""
		val field = TextField(initial).apply { promptText = "Find..." }
		searchField = field
		val nextBtn = Button("Find Next").apply {
			setOnAction { search(target, field.text, forward = true) }
		}
		val prevBtn = Button("Find Previous").apply {
			setOnAction { search(target, field.text, forward = false) }
		}
		val closeBtn = Button("Close").apply { setOnAction { dialog.close() } }
		field.textProperty().addListener { _, _, newValue ->
			currentQuery = newValue
			highlightAllMatches(target, currentQuery, target === resultArea)
		}
		dialog.setOnHidden {
			searchDialog = null
			searchField = null
			highlightAllMatches(target, "", true)
		}
		field.setOnKeyPressed { event ->
			when {
				event.code == javafx.scene.input.KeyCode.ENTER && !event.isShiftDown -> {
					search(target, field.text, forward = true)
					event.consume()
				}

				event.code == javafx.scene.input.KeyCode.ENTER && event.isShiftDown -> {
					search(target, field.text, forward = false)
					event.consume()
				}
			}
		}

		val hbox = HBox(5.0, field, nextBtn, prevBtn, closeBtn).apply {
			padding = Insets(10.0)
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
			highlightAllMatches(target, field.text, true)
		}
	}


	/**
	 * Finds the query in the CodeArea (forward/backward) and scrolls to it
	 */
	private fun search(area: CodeArea, query: String, forward: Boolean) {
		if (query.isEmpty()) return
		val text = area.text
		val len = query.length
		val caret = area.caretPosition
		val anchor = if (forward) caret else caret - len - 1

		val idx = if (forward) {
			text.indexOf(query, anchor.coerceAtLeast(0), ignoreCase = true)
				.let { if (it < 0) text.indexOf(query, 0, ignoreCase = true) else it }
		} else {
			text.lastIndexOf(query, anchor.coerceAtLeast(-1), ignoreCase = true)
				.let { if (it < 0) text.lastIndexOf(query, text.lastIndex, ignoreCase = true) else it }
		}
		if (idx >= 0) {
			area.selectRange(idx, idx + len)
			val pos = area.offsetToPosition(idx, Bias.Forward)
			area.showParagraphAtCenter(pos.major)
		}
	}


	private fun highlightAllMatches(area: CodeArea, query: String, highlightNaN: Boolean) {
		val text = area.text
		if (text.isEmpty()) {
			val spans = StyleSpansBuilder<Collection<String>>()
			spans.add(emptyList(), 0)
			area.setStyleSpans(0, spans.create())
			return
		}
		val styles = computeSyntaxHighlightingChars(text)
		// Подсветка NaN всегда (НЕ только при поиске)
		if (highlightNaN) {
			Regex("\\bNaN\\b").findAll(text).forEach { m ->
				for (i in m.range) {
					if (i in styles.indices) styles[i].add("nan-highlight")
				}
			}
		}
		// Подсветка поиска (для всех полей)
		if (query.isNotEmpty()) {
			Regex(Regex.escape(query), RegexOption.IGNORE_CASE)
				.findAll(text)
				.forEach { m ->
					for (i in m.range) {
						if (i in styles.indices) styles[i].add("search-result")
					}
				}
		}
		// Сборка
		val spans = StyleSpansBuilder<Collection<String>>()
		var prev: List<String>? = null
		var runStart = 0
		for (i in styles.indices) {
			if (prev == null || prev != styles[i]) {
				if (prev != null && i > runStart) {
					spans.add(prev, i - runStart)
				}
				prev = styles[i]
				runStart = i
			}
		}
		if (prev != null && runStart < styles.size) {
			spans.add(prev, styles.size - runStart)
		}
		area.setStyleSpans(0, spans.create())
	}


	private fun computeSyntaxHighlightingChars(text: String): List<MutableList<String>> {
		val styles = MutableList(text.length) { mutableListOf<String>() }
		val matcher = XML_PATTERN.matcher(text)
		while (matcher.find()) {
			val style = when {
				matcher.group("COMMENT") != null -> "comment"
				matcher.group("CDATA") != null -> "cdata"
				matcher.group("TAG") != null -> "tag"
				matcher.group("LOCAL") != null -> "local"
				matcher.group("ATTR") != null -> "attribute"
				matcher.group("VALUE") != null -> "value"
				matcher.group("BRACKET") != null -> "bracket"
				else -> null
			}
			if (style != null) {
				for (i in matcher.start() until matcher.end()) {
					if (i in styles.indices) styles[i].add(style)
				}
			}
		}
		return styles
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

		private fun computeHighlighting(text: String) =
			StyleSpansBuilder<Collection<String>>().apply {
				var lastEnd = 0
				val m = XML_PATTERN.matcher(text)
				while (m.find()) {
					add(emptyList(), m.start() - lastEnd)
					val styleClass = when {
						m.group("COMMENT") != null -> "comment"
						m.group("CDATA") != null -> "cdata"
						m.group("TAG") != null -> "tag"
						m.group("LOCAL") != null -> "local"
						m.group("ATTR") != null -> "attribute"
						m.group("VALUE") != null -> "value"
						m.group("BRACKET") != null -> "bracket"
						else -> ""
					}
					add(listOf(styleClass), m.end() - m.start())
					lastEnd = m.end()
				}
				add(emptyList(), text.length - lastEnd)
			}.create()
	}
}

fun main() {
	Application.launch(XmlXsltValidatorApp::class.java)
}
