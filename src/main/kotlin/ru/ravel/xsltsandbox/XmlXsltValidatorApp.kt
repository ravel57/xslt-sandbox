package ru.ravel.xsltsandbox

import javafx.application.Application
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Orientation
import javafx.scene.Scene
import javafx.scene.control.*
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

	override fun start(primaryStage: Stage) {
		// 1) Initialize CodeAreas with syntax highlighting & line numbers
		xsltArea = createHighlightingCodeArea()
		xmlArea = createHighlightingCodeArea()
		resultArea = createHighlightingCodeArea().apply { isEditable = false }

		// 2) Track focus for search
		listOf(xsltArea, xmlArea, resultArea).forEach { area ->
			area.setOnMouseClicked { currentArea = area }
			area.addEventHandler(KeyEvent.KEY_PRESSED) { currentArea = area }
		}
		currentArea = xmlArea

		// 3) Wrap in labeled panes
		val xsltBox = vBoxWithLabel("XSLT", xsltArea)
		val xmlBox = vBoxWithLabel("XML", xmlArea)
		val resultBox = vBoxWithLabel("Result", resultArea)

		// 4) SplitPanes for resizable layout
		val topSplit = SplitPane(xsltBox, xmlBox).apply {
			orientation = Orientation.HORIZONTAL
			setDividerPositions(0.5)
		}
		val mainSplit = SplitPane(topSplit, resultBox).apply {
			orientation = Orientation.VERTICAL
			setDividerPositions(0.7)
		}

		// 5) Toolbar buttons
		val validateBtn = Button("Validate & Transform").apply {
			setOnAction { doTransform(primaryStage) }
		}
		val searchBtn = Button("Search").apply {
			setOnAction { showSearchWindow(primaryStage, currentArea ?: xmlArea) }
		}
		val toolBar = HBox(5.0, validateBtn, searchBtn).apply {
			padding = Insets(10.0)
		}

		// 6) Main layout
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

		primaryStage.apply {
			title = "XML ↔ XSLT Validator"
			this.scene = scene
			show()
		}
	}

	/** Creates a CodeArea with line numbers and XML syntax highlighting */
	private fun createHighlightingCodeArea(): CodeArea =
		CodeArea().apply {
			paragraphGraphicFactory = LineNumberFactory.get(this)
			// Recompute styles on text change
			textProperty().addListener { _, _, _ ->
				setStyleSpans(0, computeHighlighting(text))
			}
			// Highlight any existing text immediately
			setStyleSpans(0, computeHighlighting(text))
		}

	/** Wraps a CodeArea in a VBox with a label and VirtualizedScrollPane */
	private fun vBoxWithLabel(labelText: String, area: CodeArea): VBox {
		val label = Label(labelText)
		val scrolled = VirtualizedScrollPane(area)
		VBox.setVgrow(scrolled, Priority.ALWAYS)
		return VBox(4.0, label, scrolled).apply { padding = Insets(8.0) }
	}

	/** Performs XML well-formed check, XSLT compilation & transformation */
	private fun doTransform(owner: Stage) {
		val status = StringBuilder()

		// 1) Check XML well-formedness via SAX
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

		// 2) Compile XSLT with Saxon HE for XPath 2.0+ support
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

		// 3) Perform the transformation
		val writer = StringWriter()
		try {
			templates.newTransformer().apply {
				errorListener = tfFactory.errorListener
			}.transform(
				StreamSource(StringReader(xmlArea.text)),
				StreamResult(writer)
			)
		} catch (ex: TransformerException) {
			// details already in status
		}

		// 4) Update UI on JavaFX thread
		Platform.runLater {
			resultArea.replaceText(writer.toString())
			resultArea.setStyleSpans(0, computeHighlighting(resultArea.text))
			showStatus(owner, status.toString())
		}
	}

	/** Shows a modal dialog with validation/transformation status */
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

	/** Opens a modal search window for the given CodeArea */
	private fun showSearchWindow(owner: Stage, target: CodeArea) {
		val dialog = Stage().apply {
			initOwner(owner)
			initModality(Modality.WINDOW_MODAL)
			title = "Search"
		}
		val field = TextField().apply { promptText = "Find..." }
		val nextBtn = Button("Find Next").apply {
			setOnAction { search(target, field.text, forward = true) }
		}
		val prevBtn = Button("Find Previous").apply {
			setOnAction { search(target, field.text, forward = false) }
		}
		val closeBtn = Button("Close").apply { setOnAction { dialog.close() } }
		val hbox = HBox(5.0, field, nextBtn, prevBtn, closeBtn).apply {
			padding = Insets(10.0)
		}

		dialog.scene = Scene(hbox)
		dialog.show()
	}

	/** Finds the query in the CodeArea (forward/backward) and scrolls to it */
	private fun search(area: CodeArea, query: String, forward: Boolean) {
		if (query.isEmpty()) return
		val text = area.text
		val start = if (forward) area.caretPosition else area.selection.start - 1
		val idx = if (forward) {
			text.indexOf(query, start, ignoreCase = true)
		} else {
			text.lastIndexOf(query, start.coerceAtLeast(0), ignoreCase = true)
		}
		if (idx >= 0) {
			area.selectRange(idx, idx + query.length)
			val pos = area.offsetToPosition(idx, Bias.Forward)
			area.showParagraphAtCenter(pos.major)
		}
	}

	companion object {
		private val XML_PATTERN: Pattern = Pattern.compile(
			// комментарии и CDATA
			"(?<COMMENT><!--[\\s\\S]*?-->)" +
					"|(?<CDATA><!\\[CDATA\\[[\\s\\S]*?]]>)" +
					// начало/конец тега, без локального имени
					"|(?<TAG></?\\w+)" +
					// захватываем двоеточие + локальную часть (value-of, template и т.п.)
					"|(?<LOCAL>:[\\w-]+)" +
					// атрибуты и их значения
					"|(?<ATTR>\\b\\w+(?==))" +
					"|(?<VALUE>\"[^\"]*\")" +
					// сами скобки '>' или '/>'
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
