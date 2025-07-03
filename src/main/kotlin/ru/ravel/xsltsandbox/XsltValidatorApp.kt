package ru.ravel.xsltsandbox

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import javafx.application.Application
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Orientation
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.stage.FileChooser
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
import ru.ravel.xsltsandbox.dto.editor.AppConfig
import ru.ravel.xsltsandbox.dto.editor.SegMeta
import ru.ravel.xsltsandbox.dto.editor.XPathMeta
import java.io.StringReader
import java.io.StringWriter
import java.nio.file.*
import java.util.regex.Pattern
import javax.xml.parsers.SAXParserFactory
import javax.xml.transform.ErrorListener
import javax.xml.transform.TransformerException
import javax.xml.transform.TransformerFactory
import javax.xml.transform.stream.StreamResult
import javax.xml.transform.stream.StreamSource


class XsltValidatorApp(
	private val isOpenedInEditorMode: Boolean = true,
	private val configPath: Path = Paths.get(System.getenv("APPDATA"), "xslt-sandbox", "config.json"),
) : Application() {

	private lateinit var currentStage: Stage
	private val watchService: WatchService = FileSystems.getDefault().newWatchService()

	private lateinit var xsltArea: CodeArea
	private lateinit var xmlArea: CodeArea
	private lateinit var resultArea: CodeArea
	private var currentArea: CodeArea? = null

	private var searchDialog: Stage? = null
	private var searchField: TextField? = null
	private var currentQuery: String = ""

	private var xmlPath: Path? = null
	private var xsltPath: Path? = null

	private val watchMap = mutableMapOf<WatchKey, Path>()
	private lateinit var config: AppConfig


	override fun init() {
		config = runCatching {
			jacksonObjectMapper().readValue(configPath.toFile(), AppConfig::class.java)
		}.getOrElse { AppConfig() }

		// Восстанавливаем пути, даже если не читаем файлы сразу
		config.xml?.let { p ->
			val path = Paths.get(p)
			if (Files.exists(path)) xmlPath = path
		}
		config.xslt?.let { p ->
			val path = Paths.get(p)
			if (Files.exists(path)) xsltPath = path
		}
		config.xml?.let { xmlPath = Paths.get(it) }
		config.xslt?.let { xsltPath = Paths.get(it) }

		// запускаем ещё до start()
		Thread {
			while (!Thread.currentThread().isInterrupted) {
				val key = watchService.take()          // блокируется
				val dir = watchMap[key]               // путь файла, за которым следим
				if (dir != null) {
					key.pollEvents().forEach { ev ->
						if (ev.kind() == StandardWatchEventKinds.OVERFLOW) return@forEach
						val changed = (ev.context() as Path)
						val fullPath = dir.parent.resolve(changed)
						when (fullPath) {
							xmlPath -> reloadFileIntoArea(fullPath, xmlArea)
							xsltPath -> reloadFileIntoArea(fullPath, xsltArea)
						}
					}
				}
				key.reset()
			}
		}.apply { isDaemon = true }.start()
	}


	override fun stop() {
		try {
			saveConfig()
			watchService.close()
		} catch (_: Exception) {
		}
	}


	override fun start(primaryStage: Stage) {
		currentStage = primaryStage
		xsltArea = createHighlightingCodeArea(false)
		xmlArea = createHighlightingCodeArea(false)
		resultArea = createHighlightingCodeArea(true).apply { isEditable = false }
		restorePreviouslyOpenedFiles()
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
		val xpathBtn = Button("XPath…").apply {
			setOnAction { openXPathEditor(primaryStage) }
		}
		val openXmlBtn = Button("Open XML…").apply {
			setOnAction {
				val file = createChooser(
					"Open XML…", xmlPath, "XML Files (*.xml)", "*.xml"
				).showOpenDialog(currentStage) ?: return@setOnAction

				loadFileIntoArea(file.toPath(), xmlArea) { xmlPath = it }
			}
		}
		val openXsltBtn = Button("Open XSLT…").apply {
			setOnAction {
				val file = createChooser(
					"Open XSLT…", xsltPath,
					"XSLT Files (*.xsl, *.xslt)", "*.xsl", "*.xslt"
				).showOpenDialog(currentStage) ?: return@setOnAction

				loadFileIntoArea(file.toPath(), xsltArea) { xsltPath = it }
			}
		}
		val saveXsltBtn = Button("Save XSLT").apply {
			setOnAction { saveCurrentXslt() }
		}
		val toolBar = HBox(5.0, validateBtn, searchBtn, xpathBtn).apply {
			padding = Insets(10.0)
		}
		val filesBar = if (isOpenedInEditorMode) {
			HBox(5.0, openXsltBtn, saveXsltBtn, openXmlBtn).apply { padding = Insets(10.0) }
		} else {
			HBox(5.0, saveXsltBtn).apply { padding = Insets(10.0) }
		}
		val editorRoot = BorderPane().apply {
			top = HBox(5.0, toolBar, filesBar)
			center = BorderPane().apply {
				center = mainSplit
			}
		}

		val dataDocsRoot = VBox(Label("Тут могут быть DataDocs")).apply {
			alignment = Pos.CENTER
		}

		val tabs = TabPane().apply {
			tabs += Tab("Editor", editorRoot).apply { isClosable = false }
			tabs += Tab("DataDocs", dataDocsRoot).apply { isClosable = false }
			tabClosingPolicy = TabPane.TabClosingPolicy.UNAVAILABLE
		}

		val scene = if (!isOpenedInEditorMode) {
			Scene(tabs, 900.0, 650.0)
		} else {
			Scene(editorRoot, 900.0, 650.0)
		}
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
	 * Создаёт кнопку «Open …».
	 *
	 * @param caption        текст на кнопке
	 * @param extDescription подпись фильтра (например "XML Files (*.xml)")
	 * @param targetArea     CodeArea, в который нужно загрузить текст
	 * @param exts           vararg расширений ("*.xml", "*.xsl" …)
	 * @param pathSetter     лямбда, куда сохраняем выбранный Path
	 */
	private fun createChooser(
		title: String,
		lastPath: Path?,
		description: String,
		vararg masks: String
	): FileChooser = FileChooser().apply {
		this.title = title
		extensionFilters += FileChooser.ExtensionFilter(description, *masks)
		lastPath?.parent
			?.takeIf { Files.isDirectory(it) }
			?.let { initialDirectory = it.toFile() } // ❷ папка из конфига
	}


	private fun saveConfig() {
		try {
			Files.createDirectories(configPath.parent)
			Files.writeString(
				configPath,
				jacksonObjectMapper().writerWithDefaultPrettyPrinter()
					.writeValueAsString(
						AppConfig(xmlPath?.toString(), xsltPath?.toString())
					),
				StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
				StandardOpenOption.WRITE
			)
		} catch (ex: Exception) {
			println("Не удалось сохранить config.json → ${ex.message}")
		}
	}


	private fun loadFileIntoArea(path: Path, area: CodeArea, setter: (Path) -> Unit) {
		runCatching { Files.readString(path) }.onSuccess {
			setter(path)                 // xmlPath или xsltPath
			path.registerWatch()         // слежение
			Platform.runLater {
				area.replaceText(it)
				highlightAllMatches(area, currentQuery, area === resultArea)
			}
			saveConfig()                 // обновляем config.json
		}.onFailure {
			showStatus(currentStage, "Не удалось открыть файл:\n${it.message}")
		}
	}


	private fun restorePreviouslyOpenedFiles() {
		xmlPath?.takeIf { Files.exists(it) }?.let {
			loadFileIntoArea(it, xmlArea) { xmlPath = it }
		}
		xsltPath?.takeIf { Files.exists(it) }?.let {
			loadFileIntoArea(it, xsltArea) { xsltPath = it }
		}
	}


	private fun saveCurrentXslt() {
		val path = xsltPath ?: run {
			val file = createChooser(
				"Save XSLT…", xsltPath,
				"XSLT Files (*.xsl, *.xslt)", "*.xsl", "*.xslt"
			).showSaveDialog(currentStage) ?: return
			file.toPath()
		}
		Files.createDirectories(path.parent)
		Files.writeString(
			path, xsltArea.text,
			StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING
		)
		xsltPath = path                    // путь для следующих сеансов
		path.registerWatch()
		saveConfig()
		showStatus(currentStage, "XSLT сохранён:\n$path")
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
			XsltValidatorApp::class.java.classLoader
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
//			isAlwaysOnTop = true
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


	private fun openXPathEditor(owner: Stage) {
		if (currentArea !== xmlArea) return

		val meta = buildXPathWithMeta(xmlArea.text, xmlArea.selection.start)
		if (meta.xpath.isBlank()) {
			showStatus(owner, "Не удалось построить XPath"); return
		}

		/* если окно уже есть – обновляем строку и выводим на-передний-план */
		searchDialog?.let { dlg ->
			(dlg.scene.lookup("#xpathField") as TextField).text = meta.xpath
			dlg.toFront(); dlg.requestFocus(); return
		}

		/* ─────────────── GUI ─────────────── */
		/** одна строка «имя + ChoiceBox» */
		fun segRow(seg: SegMeta): HBox {
			val lbl = Label(seg.name)
			val cb = ChoiceBox<String>()
			val opts = mutableListOf<String>()
			if (seg.predicate.isNotEmpty()) opts += "по индексу ${seg.predicate}"
			opts += "без предиката"
			seg.attrs.forEach { (k, v) -> opts += "@$k='$v'" }
			cb.items.addAll(opts); cb.value = opts[0]
			return HBox(6.0, lbl, cb)
		}

		val rows = meta.segs.map(::segRow)
		val rowsBox = VBox(4.0, *rows.toTypedArray())
		val rowsScroll = ScrollPane(rowsBox).apply {
			isFitToWidth = true
			hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
		}
		VBox.setVgrow(rowsScroll, Priority.ALWAYS)   // ← растягивается по высоте

		val resultField = TextField(meta.xpath).apply {
			id = "xpathField"; isEditable = false
		}

		/* перестраиваем путь при изменении выбора */
		fun rebuild() {
			val sb = StringBuilder()
			rows.forEachIndexed { i, row ->
				val name = (row.children[0] as Label).text
				val sel = (row.children[1] as ChoiceBox<*>).value as String
				sb.append('/').append(name)
				when {
					sel.startsWith("@") -> sb.append("[$sel]")
					sel.startsWith("по индексу") ->
						sb.append(meta.segs[i].predicate)
					/* «без предиката» – ничего */
				}
			}
			resultField.text = sb.toString()
		}
		rows.forEach { r ->
			(r.children[1] as ChoiceBox<*>).valueProperty()
				.addListener { _, _, _ -> rebuild() }
		}

		val okBtn = Button("Copy & Close")

		val dlg = Stage()
		searchDialog = dlg
		okBtn.setOnAction {
			val clip = javafx.scene.input.Clipboard.getSystemClipboard()
			clip.setContent(javafx.scene.input.ClipboardContent().apply {
				putString(resultField.text)
			})
			dlg.close()
		}

		dlg.apply {
			initOwner(owner)
			initModality(Modality.WINDOW_MODAL)
			title = "XPath editor"
			scene = Scene(VBox(8.0, rowsScroll, resultField, okBtn).apply {
				padding = Insets(12.0)
			})

			/* Esc — закрыть */
			scene.setOnKeyPressed { ev ->
				if (ev.code == KeyCode.ESCAPE) close()
			}
			setOnHidden { searchDialog = null }
			sizeToScene()
			show()
		}
	}

	/**
	 *  Строит абсолютный XPath до узла/атрибута под курсором
	 *  и одновременно возвращает метаданные каждого сегмента
	 *  (имя, исходный индекс-предикат, все найденные атрибуты).
	 */
	private fun buildXPathWithMeta(xml: String, offset: Int): XPathMeta {

		data class Frame(val name: String, var idx: Int, val attrs: Map<String, String>)

		val selfRx = Regex("""<([A-Za-z_][\w\-.]*)([^>]*)?/>""")
		val openRx = Regex("""<([A-Za-z_][\w\-.]*)([^>]*)?>""")
		val closeRx = Regex("""</([A-Za-z_][\w\-.]*)\s*>""")
		val attrRx = Regex("""([\w:-]+)\s*=\s*(['"])(.*?)\2""")

		val stack = ArrayDeque<Frame>()
		var i = 0
		var resultPath: String? = null

		fun attrsOf(tag: String) =
			attrRx.findAll(tag).associate { it.groupValues[1] to it.groupValues[3] }

		fun curPath() = stack.joinToString("") { "/${it.name}[${it.idx}]" }

		while (i < xml.length) {
			val lt = xml.indexOf('<', i); if (lt < 0) break
			if (offset in i until lt) {
				i = lt; continue
			}

			var handled = false

			selfRx.matchAt(xml, lt)?.also { m ->
				val name = m.groupValues[1]
				val idx = stack.count { it.name == name } + 1
				stack.addLast(Frame(name, idx, attrsOf(m.value)))
				if (offset in lt..m.range.last) resultPath = curPath()
				stack.removeLast()
				i = m.range.last + 1; handled = true
			}

			if (!handled) openRx.matchAt(xml, lt)?.also { m ->
				val name = m.groupValues[1]
				val idx = stack.count { it.name == name } + 1
				stack.addLast(Frame(name, idx, attrsOf(m.value)))
				if (offset in lt..m.range.last) resultPath = curPath()
				i = m.range.last + 1; handled = true
			}

			if (!handled) closeRx.matchAt(xml, lt)?.also {
				if (stack.isNotEmpty()) stack.removeLast()
				i = it.range.last + 1; handled = true
			}

			if (!handled) i = lt + 1
			if (resultPath != null) break
		}

		/*  атрибут под курсором  */
		var attrPred = ""
		if (stack.isNotEmpty()) {
			val tagStart = xml.lastIndexOf('<', offset).coerceAtLeast(0)
			val tagEnd = xml.indexOf('>', tagStart).coerceAtLeast(tagStart)
			if (offset in tagStart..tagEnd) {
				attrRx.findAll(xml.substring(tagStart, tagEnd + 1)).forEach { a ->
					val s = tagStart + a.range.first
					val e = tagStart + a.range.last
					if (offset in s..e) attrPred = "/@${a.groupValues[1]}"
				}
			}
		}

		/*  ── СЕГМЕНТЫ ТОЛЬКО ИЗ СТЕКА ──  */
		val segs = stack.map { SegMeta(it.name, "[${it.idx}]", it.attrs) }
		return XPathMeta((resultPath ?: curPath()) + attrPred, segs)
	}


	private fun Path.registerWatch() {
		val key = parent.register(
			watchService,
			StandardWatchEventKinds.ENTRY_MODIFY,
			StandardWatchEventKinds.ENTRY_DELETE
		)
		watchMap[key] = this        // запоминаем: по key найдём полный путь
	}


	private fun reloadFileIntoArea(path: Path, area: CodeArea) {
		// Читаем целиком; Platform.runLater, т.к. мы в фоновой нитке
		try {
			val txt = Files.readString(path)
			Platform.runLater {
				area.replaceText(txt)
				highlightAllMatches(area, currentQuery, area === resultArea)
			}
		} catch (ex: Exception) {
			println("Cannot read $path → ${ex.message}")
		}
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

	}
}