package ru.ravel.xsltsandbox

import javafx.application.Application
import javafx.application.Platform
import javafx.geometry.Orientation
import javafx.geometry.Insets
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.input.KeyEvent
import javafx.scene.layout.*
import javafx.stage.Modality
import javafx.stage.Stage
import org.fxmisc.flowless.VirtualizedScrollPane
import org.fxmisc.richtext.CodeArea
import org.fxmisc.richtext.LineNumberFactory
import org.fxmisc.richtext.model.TwoDimensional.Bias
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import java.io.StringWriter
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
		xsltArea   = createCodeArea()
		xmlArea    = createCodeArea()
		resultArea = createCodeArea().apply { isEditable = false }

		// Track focus so search knows which area to target
		listOf(xsltArea, xmlArea, resultArea).forEach { area ->
			area.setOnMouseClicked { currentArea = area }
			area.addEventHandler(KeyEvent.KEY_PRESSED) { currentArea = area }
		}
		currentArea = xmlArea

		val xsltBox   = vBoxWithLabel("XSLT", xsltArea)
		val xmlBox    = vBoxWithLabel("XML",  xmlArea)
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
			setOnAction {
				showSearchWindow(primaryStage, currentArea ?: xmlArea)
			}
		}

		val toolBar = HBox(5.0, validateBtn, searchBtn).apply {
			padding = Insets(10.0)
		}

		val root = BorderPane().apply {
			top    = toolBar
			center = mainSplit
		}

		primaryStage.apply {
			title = "XML ↔ XSLT Validator"
			scene = Scene(root, 900.0, 650.0)
			show()
		}
	}

	private fun createCodeArea(): CodeArea {
		val area = CodeArea()
		area.paragraphGraphicFactory = LineNumberFactory.get(area)
		return area
	}

	private fun vBoxWithLabel(labelText: String, area: CodeArea): VBox {
		val label = Label(labelText)
		val scrolled = VirtualizedScrollPane(area)
		VBox.setVgrow(scrolled, Priority.ALWAYS)
		return VBox(4.0, label, scrolled).apply { padding = Insets(8.0) }
	}

	private fun doTransform(owner: Stage) {
		val status = StringBuilder()

		// 1) Well-formedness via SAX
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
						status.append("ERROR in XML [line=${e.lineNumber},col=${e.columnNumber}]: ${e.message}\n")
					}
					override fun fatalError(e: SAXParseException) {
						status.append("FATAL in XML [line=${e.lineNumber},col=${e.columnNumber}]: ${e.message}\n")
					}
				}
			)
			status.append("XML is well-formed.\n")
		} catch (ex: Exception) {
			status.append("XML parsing halted: ${ex.message}\n")
		}

		// 2) Compile XSLT with Saxon HE
		val tfFactory: TransformerFactory = TransformerFactory.newInstance(
			"net.sf.saxon.TransformerFactoryImpl",
			XmlXsltValidatorApp::class.java.classLoader
		).apply {
			setErrorListener(object : ErrorListener {
				override fun warning(ex: TransformerException) = report("WARNING in XSLT", ex)
				override fun error(ex: TransformerException)   = report("ERROR in XSLT", ex)
				override fun fatalError(ex: TransformerException) = report("FATAL in XSLT", ex)
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

		// 3) Transform
		val writer = StringWriter()
		try {
			templates.newTransformer().apply {
				errorListener = tfFactory.errorListener
			}.transform(
				StreamSource(StringReader(xmlArea.text)),
				StreamResult(writer)
			)
		} catch (ex: TransformerException) {
			// errors already appended
		}

		Platform.runLater {
			resultArea.replaceText(writer.toString())
			showStatus(owner, status.toString())
		}
	}

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

	private fun showSearchWindow(owner: Stage, target: CodeArea) {
		val dialog = Stage().apply {
			initOwner(owner)
			initModality(Modality.WINDOW_MODAL)
			title = "Search"
		}
		val field   = TextField().apply { promptText = "Find..." }
		val nextBtn = Button("Find Next").apply {
			setOnAction { search(target, field.text, forward = true) }
		}
		val prevBtn = Button("Find Previous").apply {
			setOnAction { search(target, field.text, forward = false) }
		}
		val closeBtn = Button("Close").apply {
			setOnAction { dialog.close() }
		}
		val hbox = HBox(5.0, field, nextBtn, prevBtn, closeBtn).apply {
			padding = Insets(10.0)
		}
		dialog.scene = Scene(hbox)
		dialog.show()
	}

	private fun search(area: CodeArea, query: String, forward: Boolean) {
		if (query.isEmpty()) return
		val text  = area.text
		val start = if (forward) area.caretPosition else area.selection.start - 1
		val idx   = if (forward) {
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
}

fun main() {
	Application.launch(XmlXsltValidatorApp::class.java)
}
