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
import net.sf.saxon.s9api.Processor
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
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.*
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import javax.xml.parsers.SAXParserFactory
import javax.xml.transform.ErrorListener
import javax.xml.transform.TransformerException
import javax.xml.transform.TransformerFactory
import javax.xml.transform.stream.StreamResult
import javax.xml.transform.stream.StreamSource


class XmlXsltValidatorApp : Application() {

	private lateinit var tabPane: TabPane
	private val sessions = mutableMapOf<Tab, DocSession>()
	private val plusTab = Tab("+").apply { isClosable = false }
	private val currentSession: DocSession
		get() = sessions[tabPane.selectionModel.selectedItem]!!
	private var currentArea: CodeArea? = null

	//	private var currentArea: CodeArea? = null
	private var searchDialog: Stage? = null
	private var searchField: TextField? = null
	private var currentQuery: String = ""
	private val watchService = FileSystems.getDefault().newWatchService()
	private val watchMap = ConcurrentHashMap<Path, Pair<DocSession, CodeArea>>()
	private val watchDirs = mutableSetOf<Path>()
	private lateinit var currentStage: Stage
	private val configPath: Path = Paths.get(System.getenv("APPDATA"), "xslt-sandbox", "config.json")
	private lateinit var config: AppConfig
	private var disableSyntaxHighlighting = false


	override fun init() {
		config = runCatching {
			jacksonObjectMapper().readValue(configPath.toFile(), AppConfig::class.java)
		}.getOrElse { AppConfig() }
	}


	override fun stop() {
		try {
			saveConfig()
			watchService.close()
		} catch (e: Exception) {
			System.err.println(e.localizedMessage)
			System.err.println(e.stackTrace)
		}
		super.stop()
	}


	override fun start(primaryStage: Stage) {
		currentStage = primaryStage

		tabPane = TabPane().apply {
			tabClosingPolicy = TabPane.TabClosingPolicy.ALL_TABS
		}
		val first = createNewSessionTab("Tab 1")
		tabPane.tabs += first.tab
		plusTab.setOnSelectionChanged {
			if (plusTab.isSelected) {
				val created = createNewSessionTab("Tab ${sessions.size + 1}")
				tabPane.tabs.add(tabPane.tabs.size - 1, created.tab)
				tabPane.selectionModel.select(created.tab)
			}
		}
		tabPane.tabs += plusTab

		val topBar = buildToolBar()

		val root = BorderPane().apply {
			top = topBar
			center = tabPane //mainSplit
		}

		val scene = Scene(root, 1200.0, 800.0).apply {
			addEventFilter(KeyEvent.KEY_PRESSED) { event ->
				if (event.code == KeyCode.F && event.isControlDown) {
					showSearchWindow(primaryStage, currentSession.currentAreaOr(xml = true))
					event.consume()
				}
			}
			// если подключаете css подсветки
			javaClass.classLoader.getResource("xml-highlighting.css")?.let {
				stylesheets += it.toExternalForm()
			}
		}

		primaryStage.title = "XSLT Sandbox"
		primaryStage.scene = scene
		primaryStage.show()

		// восстановим последнюю сессию из config (если нужно)
		restorePreviouslyOpenedFiles(first)
	}


	private fun buildToolBar(): HBox {
		val validateBtn = Button("Validate & Transform").apply {
			setOnAction { doTransform(currentStage) }
		}
		val searchBtn = Button("Search").apply {
			setOnAction { showSearchWindow(currentStage, currentSession.currentAreaOr(xml = true)) }
		}

		val xpathBtn = Button("XPath…").apply {
			setOnAction { openXPathEditor(currentStage) }
		}

		val executeXpathBtn = Button("Execute XPath…").apply {
			setOnAction { executeXpath(currentStage) }
		}

		val openXmlBtn = Button("Open XML…").apply {
			setOnAction {
				val file = createChooser(
					"Open XML…", currentSession.xmlPath,
					"XML Files (*.xml)", "*.xml"
				).showOpenDialog(currentStage) ?: return@setOnAction
				loadFileIntoArea(currentSession, file.toPath(), currentSession.xmlArea) { currentSession.xmlPath = it }
			}
		}
		val openXsltBtn = Button("Open XSLT…").apply {
			setOnAction {
				val file = createChooser(
					"Open XSLT…", currentSession.xsltPath,
					"XSLT Files (*.xsl, *.xslt)", "*.xsl", "*.xslt"
				).showOpenDialog(currentStage) ?: return@setOnAction
				loadFileIntoArea(currentSession, file.toPath(), currentSession.xsltArea) { path ->
					currentSession.xsltPath = path
					currentSession.updateTabTitle()
				}
			}
		}
		val saveXsltBtn = Button("Save XSLT…").apply {
			setOnAction { saveCurrentXslt() }
		}

		val disableHighlightCheck = CheckBox("Disable syntactic highlights").apply {
			isSelected = disableSyntaxHighlighting
			setOnAction {
				disableSyntaxHighlighting = isSelected
				sessions.values.forEach { s ->
					listOf(s.xsltArea, s.xmlArea, s.resultArea).forEach {
						highlightAllMatches(it, currentQuery, it === s.resultArea)
					}
				}
			}
		}

		return HBox(
			8.0,
			validateBtn, searchBtn, xpathBtn, executeXpathBtn,
			openXsltBtn, saveXsltBtn, openXmlBtn,
			disableHighlightCheck
		).apply {
			alignment = Pos.CENTER_LEFT
			padding = Insets(10.0)
		}
	}


	private fun createNewSessionTab(title: String): DocSession {
		val xsltArea = createHighlightingCodeArea(false)
		val xmlArea = createHighlightingCodeArea(false)
		val resultArea = createHighlightingCodeArea(true).apply { isEditable = false }

		// чтобы Ctrl+F искал по активной области
		listOf(xsltArea, xmlArea, resultArea).forEach { area ->
			area.setOnMouseClicked { currentArea = area }
			area.addEventHandler(KeyEvent.KEY_PRESSED) { currentArea = area }
		}

		val xsltBox = vBoxWithLabel("XSLT", xsltArea)
		val xmlBox = vBoxWithLabel("XML", xmlArea)

		val nanCountLabel = Label().apply {
			isVisible = false
			padding = Insets(0.0, 0.0, 0.0, 8.0)
		}
		val resultLabel = Label("Result")
		val resultHeader = HBox(4.0, resultLabel, nanCountLabel).apply { alignment = Pos.CENTER_LEFT }
		val resultScroll = VirtualizedScrollPane(resultArea)
		VBox.setVgrow(resultScroll, Priority.ALWAYS)
		val resultBox = VBox(4.0, resultHeader, resultScroll).apply { padding = Insets(8.0) }

		val topSplit = SplitPane(xsltBox, xmlBox).apply {
			orientation = Orientation.HORIZONTAL
			setDividerPositions(0.5)
		}
		val mainSplit = SplitPane(topSplit, resultBox).apply {
			orientation = Orientation.VERTICAL
			setDividerPositions(0.7)
		}

		val tab = Tab(title, mainSplit).apply {
			isClosable = true
			setOnClosed {
				sessions.remove(this)
				if (tabPane?.tabs?.size == 1) { // остался только '+'
					val t = createNewSessionTab("Tab 1")
					tabPane.tabs.add(tabPane.tabs.size - 1, t.tab)
					tabPane.selectionModel.select(t.tab)
				}
			}
		}

		val session = DocSession(tab, xsltArea, xmlArea, resultArea, nanCountLabel)
		sessions[tab] = session
		return session
	}


	private fun DocSession.currentAreaOr(xml: Boolean): CodeArea {
		return currentArea ?: if (xml) this.xmlArea else this.xsltArea
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
			?.let { initialDirectory = it.toFile() }
	}


	private fun saveConfig() {
		try {
			Files.createDirectories(configPath.parent)
			val workTabs = tabPane.tabs.filter { it != plusTab }
			val tabStates = workTabs.map { tab ->
				val s = sessions[tab]!!
				TabState(
					xml = s.xmlPath?.toString(),
					xslt = s.xsltPath?.toString()
				)
			}
			val activeIndex = workTabs.indexOf(tabPane.selectionModel.selectedItem).coerceAtLeast(0)
			val cfg = AppConfig(tabStates, activeIndex)
			Files.writeString(
				configPath,
				jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(cfg),
				StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE
			)
		} catch (ex: Exception) {
			println("Не удалось сохранить config.json → ${ex.message}")
		}
	}


	private fun loadFileIntoArea(
		session: DocSession,
		path: Path,
		area: CodeArea,
		setPath: (Path) -> Unit
	) {
		area.replaceText(Files.readString(path, Charsets.UTF_8))
		setPath(path)

		val dir = path.parent
		watchMap[path] = session to area
		if (!watchDirs.contains(dir)) {
			dir.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY)
			watchDirs.add(dir)
		}
	}


	private fun restorePreviouslyOpenedFiles(first: DocSession) {
		val tabs = config.tabs
		if (tabs.isEmpty()) return
		loadTabStateIntoSession(first, tabs[0])
		for (i in 1 until tabs.size) {
			val s = createNewSessionTab("Tab ${i + 1}")
			tabPane.tabs.add(tabPane.tabs.size - 1, s.tab) // перед '+'
			loadTabStateIntoSession(s, tabs[i])
		}
		val workTabs = tabPane.tabs.filter { it != plusTab }
		val toSelect = config.activeIndex.coerceIn(0, workTabs.lastIndex)
		tabPane.selectionModel.select(workTabs[toSelect])
	}


	private fun loadTabStateIntoSession(session: DocSession, state: TabState) {
		state.xml?.let { p ->
			val path = Paths.get(p)
			if (Files.exists(path)) {
				loadFileIntoArea(session, path, session.xmlArea) { session.xmlPath = it }
			}
		}
		state.xslt?.let { p ->
			val path = Paths.get(p)
			if (Files.exists(path)) {
				loadFileIntoArea(session, path, session.xsltArea) { loaded ->
					session.xsltPath = loaded
					session.updateTabTitle()
				}
			}
		}
	}


	private fun saveCurrentXslt() {
		val s = currentSession
		val path: Path = s.xsltPath ?: run {
			val file = createChooser(
				"Save XSLT…",
				s.xsltPath,
				"XSLT Files (*.xsl, *.xslt)", "*.xsl", "*.xslt"
			).showSaveDialog(currentStage) ?: return
			file.toPath()
		}
		Files.createDirectories(path.parent)
		Files.writeString(
			path,
			s.xsltArea.text,
			StandardOpenOption.CREATE,
			StandardOpenOption.TRUNCATE_EXISTING,
			StandardOpenOption.WRITE
		)
		// обновляем путь в сессии и подписываемся на изменения файла
		if (s.xsltPath != path) {
			s.xsltPath = path
			registerWatch(path, s, s.xsltArea)
		}
		saveConfig()
		showStatus(currentStage, "XSLT сохранён:\n$path")
	}


	private fun registerWatch(path: Path, session: DocSession, area: CodeArea) {
		val dir = path.parent
		watchMap[path] = session to area
		if (watchDirs.add(dir)) {
			dir.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY)
		}
	}


	/**
	 * Performs XML well-formed check, XSLT compilation & transformation
	 */
	private fun doTransform(owner: Stage) {
		val s = currentSession
		val status = StringBuilder()

		try {
			SAXParserFactory.newInstance().apply {
				isNamespaceAware = true
				isValidating = false
			}.newSAXParser().parse(
				InputSource(StringReader(s.xmlArea.text)),
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
			tfFactory.newTemplates(StreamSource(StringReader(s.xsltArea.text))).also {
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
				StreamSource(StringReader(s.xmlArea.text)),
				StreamResult(writer)
			)
		} catch (ex: TransformerException) {
			System.err.println(ex.message)
		}

		Platform.runLater {
			val resultText = writer.toString()
			s.resultArea.replaceText(resultText)
			highlightAllMatches(s.resultArea, currentQuery, true)
			val nanCount = Regex("\\bNaN\\b").findAll(resultText).count()
			s.nanCountLabel.text = "NaNs: $nanCount"
			s.nanCountLabel.isVisible = nanCount > 0
			showStatus(owner, status.toString())
		}
	}


	private fun readTextRespectingXmlDecl(path: Path): String {
		val bytes = Files.readAllBytes(path)
		fun bomCharset(): Pair<Int, Charset>? = when {
			bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
			-> 3 to StandardCharsets.UTF_8

			bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()
			-> 2 to StandardCharsets.UTF_16BE

			bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()
			-> 2 to StandardCharsets.UTF_16LE

			else -> null
		}
		bomCharset()?.let { (skip, cs) -> return String(bytes, skip, bytes.size - skip, cs) }
		val probe = String(bytes, 0, minOf(bytes.size, 256), Charsets.ISO_8859_1)
		val enc = Regex("""encoding\s*=\s*['"]([\w\-\d]+)['"]""", RegexOption.IGNORE_CASE)
			.find(probe)
			?.groupValues
			?.get(1)
			?.let { runCatching { Charset.forName(it) }.getOrNull() }
		return String(bytes, enc ?: StandardCharsets.UTF_8)
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
			highlightAllMatches(target, currentQuery, target === currentSession.resultArea)
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
		val styles = if (disableSyntaxHighlighting) {
			MutableList(text.length) { mutableListOf() }
		} else {
			computeSyntaxHighlightingChars(text)
		}
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
		val area = currentArea ?: return
		if (area !== currentSession.xmlArea && area !== currentSession.resultArea) {
			return
		}
		val meta = buildXPathWithMeta(area.text, area.selection.start)
		if (meta.xpath.isBlank()) {
			showStatus(owner, "Не удалось построить XPath"); return
		}
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


	private fun executeXpath(primaryStage: Stage) {
		// ───────── создание диалога ─────────
		val dlg = Stage().apply {
			initOwner(primaryStage)
			initModality(Modality.WINDOW_MODAL)
			title = "Execute XPath"
		}

		val xpathField = TextField().apply { promptText = "Input XPath…" }

		val xmlRadio = RadioButton("XML").apply { isSelected = currentArea !== currentSession.resultArea }
		val resultRadio = RadioButton("Result").apply { isSelected = !xmlRadio.isSelected }
		ToggleGroup().also { tg -> tg.toggles.addAll(xmlRadio, resultRadio) }
		val runBtn = Button("Run")
		val closeBtn = Button("Close")
		// ───────── выполнение XPath ─────────
		runBtn.setOnAction {
			val xmlText = if (resultRadio.isSelected) {
				currentSession.resultArea.text
			} else {
				currentSession.xmlArea.text
			}
			val expr = xpathField.text.trim()
			if (expr.isEmpty()) {
				showStatus(primaryStage, "Empty expression."); return@setOnAction
			}
			try {
				val proc = Processor(false)
				val builder = proc.newDocumentBuilder()
				val doc = builder.build(StreamSource(StringReader(xmlText)))
				val compiler = proc.newXPathCompiler()
				val value = compiler.evaluate(expr, doc)

				val out = buildString { value.forEach { append(it.stringValue).append('\n') } }
					.ifBlank { "— no results —" }
				showStatus(primaryStage, out)
				dlg.close()
			} catch (ex: Exception) {
				showStatus(primaryStage, "Error XPath:\n${ex.message}")
			}
		}
		closeBtn.setOnAction { dlg.close() }

		dlg.scene = Scene(
			VBox(
				10.0,
				xpathField,
				HBox(10.0, xmlRadio, resultRadio),
				HBox(10.0, runBtn, closeBtn)
			).apply { padding = Insets(12.0) }
		).also { sc ->
			sc.setOnKeyPressed { if (it.code == KeyCode.ESCAPE) dlg.close() }
		}
		dlg.show()
	}


	/**
	 * Формирует абсолютный XPath до узла (или атрибута) под курсором
	 * и возвращает метаданные сегментов для GUI-редактора.
	 *
	 * @param xml    полное содержимое XML-документа
	 * @param offset позиция курсора (selection.start) в этом тексте
	 */
	private fun buildXPathWithMeta(xml: String, offset: Int): XPathMeta {

		/* ────────── структуры и regex ────────── */

		data class Node(
			val name: String,
			val attrs: Map<String, String>,
			val children: MutableList<Node> = mutableListOf(),
			var parent: Node? = null,
			var start: Int = 0,
			var end: Int = 0
		)

		val openRx = Regex("""<([A-Za-z_][\w\-.]*)([^>/]*?)>""")
		val selfRx = Regex("""<([A-Za-z_][\w\-.]*)([^>]*?)/>""")
		val closeRx = Regex("""</([A-Za-z_][\w\-.]*)\s*>""")
		val attrRx = Regex("""([\w:-]+)\s*=\s*(['"])(.*?)\2""")

		fun attrsOf(tag: String) =
			attrRx.findAll(tag).associate { it.groupValues[1] to it.groupValues[3] }

		/* ────────── строим дерево только до offset ────────── */

		val root = Node("ROOT", emptyMap())
		var cur = root                                 // вершина стека
		var i = 0

		while (i < xml.length) {
			val lt = xml.indexOf('<', i).takeIf { it >= 0 } ?: break
			if (offset in i until lt) break              // курсор попал в текст

			when {
				/* <tag .../> */
				selfRx.matchAt(xml, lt) != null -> {
					val m = selfRx.matchAt(xml, lt)!!
					val nod = Node(
						m.groupValues[1], attrsOf(m.value),
						start = m.range.first, end = m.range.last,
						parent = cur
					)
					cur.children += nod
					i = m.range.last + 1
				}

				/* <tag ...> */
				openRx.matchAt(xml, lt) != null -> {
					val m = openRx.matchAt(xml, lt)!!
					val nod = Node(
						m.groupValues[1], attrsOf(m.value),
						start = m.range.first, parent = cur
					)
					cur.children += nod
					cur = nod                            // пуш
					i = m.range.last + 1
				}

				/* </tag> */
				closeRx.matchAt(xml, lt) != null -> {
					val m = closeRx.matchAt(xml, lt)!!
					cur.end = m.range.last
					cur = cur.parent ?: root        // поп
					i = m.range.last + 1
				}

				else -> i = lt + 1                      // не тег
			}
		}

		/* ────────── ищем путь до узла под offset ────────── */

		fun findPath(n: Node, path: MutableList<Node>): Boolean {
			if (offset !in n.start..(n.end.takeIf { it > 0 } ?: Int.MAX_VALUE)) return false
			path += n
			for (c in n.children) if (findPath(c, path)) return true
			return true
		}

		val chain = mutableListOf<Node>()
		findPath(root, chain)
		if (chain.size <= 1) return XPathMeta("/", emptyList())  // курсор вне тегов

		/* ────────── атрибут под курсором? ────────── */

		var attrPred = ""
		run {
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

		/* ────────── формируем сегменты и XPath ────────── */

		val segs = chain.drop(1).map { n ->
			val sameName = n.parent!!.children.filter { it.name == n.name }
			val idx = sameName.indexOf(n) + 1
			SegMeta(n.name, "[$idx]", n.attrs)
		}

		val xpath = buildString {
			segs.forEach { append('/').append(it.name).append(it.predicate) }
			append(attrPred)
		}

		return XPathMeta(xpath, segs)
	}


	private fun DocSession.updateTabTitle() {
		val name = xsltPath?.fileName?.toString() ?: return
		tab.text = name
		tab.tooltip = Tooltip(xsltPath.toString())
	}


	data class SegMeta(
		val name: String,          // имя элемента
		val predicate: String,     // исходный индекс в виде "[n]"
		val attrs: Map<String, String>  // найденные атрибуты
	)


	data class XPathMeta(
		val xpath: String,         // итоговый путь
		val segs: List<SegMeta>   // метаданные для GUI-редактора
	)


	private data class DocSession(
		val tab: Tab,
		val xsltArea: CodeArea,
		val xmlArea: CodeArea,
		val resultArea: CodeArea,
		val nanCountLabel: Label,
		var xmlPath: Path? = null,
		var xsltPath: Path? = null
	)


	data class AppConfig(
		val tabs: List<TabState> = emptyList(),
		val activeIndex: Int = 0
	)

	data class TabState(
		val xml: String? = null,
		val xslt: String? = null
	)


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

fun main() {
	Application.launch(XmlXsltValidatorApp::class.java)
}
