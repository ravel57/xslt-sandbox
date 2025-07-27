package ru.ravel.xsltsandbox

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import javafx.animation.AnimationTimer
import javafx.application.Application
import javafx.application.Platform
import javafx.concurrent.Task
import javafx.geometry.Insets
import javafx.geometry.Orientation
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.input.Clipboard
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.stage.FileChooser
import javafx.stage.Modality
import javafx.stage.Stage
import net.sf.saxon.s9api.*
import org.fxmisc.flowless.VirtualizedScrollPane
import org.fxmisc.richtext.CodeArea
import org.fxmisc.richtext.LineNumberFactory
import org.fxmisc.richtext.model.StyleSpansBuilder
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import java.io.StringWriter
import java.nio.file.*
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import javax.xml.parsers.SAXParserFactory
import javax.xml.transform.ErrorListener
import javax.xml.transform.TransformerException
import javax.xml.transform.TransformerFactory
import javax.xml.transform.sax.SAXSource
import javax.xml.transform.stream.StreamResult
import javax.xml.transform.stream.StreamSource


class XmlXsltValidatorApp : Application() {

	private lateinit var tabPane: TabPane
	private val sessions = mutableMapOf<Tab, DocSession>()
	private val plusTab = Tab("+").apply { isClosable = false }
	private val currentSession: DocSession
		get() = sessions[tabPane.selectionModel.selectedItem]!!
	private var currentArea: CodeArea? = null
	private var searchInfoLabel: Label? = null
	private var searchMatches: List<IntRange> = emptyList()
	private var searchTarget: CodeArea? = null

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

	@Volatile
	private var suspendHighlighting = 0


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
		tabPane.tabs.add(first.tab)
		plusTab.setOnSelectionChanged {
			if (plusTab.isSelected) {
				val created = createNewSessionTab("Tab ${sessions.size + 1}")
				tabPane.tabs.add(tabPane.tabs.size - 1, created.tab)
				tabPane.selectionModel.select(created.tab)
			}
		}
		tabPane.tabs.add(plusTab)

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
				stylesheets.add(it.toExternalForm())
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
				val file = createChooser("Open XML…", currentSession.xmlPath, "XML Files (*.xml)", "*.xml")
					.showOpenDialog(currentStage) ?: return@setOnAction
				loadFileIntoAreaAsync(currentSession, file.toPath(), currentSession.xmlArea) { currentSession.xmlPath = it }
			}
		}
		val openXsltBtn = Button("Open XSLT…").apply {
			setOnAction {
				val file = createChooser("Open XSLT…", currentSession.xsltPath, "XSLT Files (*.xsl, *.xslt)", "*.xsl", "*.xslt")
					.showOpenDialog(currentStage) ?: return@setOnAction
				loadFileIntoAreaAsync(currentSession, file.toPath(), currentSession.xsltArea) { path ->
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


	private fun pasteFromClipboardWithProgress(area: CodeArea) {
		val clip = Clipboard.getSystemClipboard()
		val text = clip.string ?: return

		// Небольшие вставки — мгновенно, без диалога
		if (text.length < 200_000) {
			suspendHighlighting++
			try {
				area.replaceSelection(text)
			} finally {
				suspendHighlighting--
				highlightAllMatches(area, currentQuery, area === currentSession.resultArea)
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
			padding = Insets(14.0); alignment = Pos.CENTER_LEFT
		}
		val dlg = Stage().apply {
			initOwner(currentStage); initModality(Modality.WINDOW_MODAL)
			title = "Pasting large text"; scene = Scene(box)
		}

		val startSel = area.selection.start
		val endSel = area.selection.end
		suspendHighlighting++

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
					suspendHighlighting--
					highlightAllMatches(area, currentQuery, area === currentSession.resultArea)
					area.requestFollowCaret()
				}
			}
		}

		cancelBtn.setOnAction {
			timer.stop()
			dlg.close()
			suspendHighlighting--
			highlightAllMatches(area, currentQuery, area === currentSession.resultArea)
		}

		dlg.show()
		timer.start()
	}


	private fun DocSession.currentAreaOr(xml: Boolean): CodeArea {
		return currentArea ?: if (xml) this.xmlArea else this.xsltArea
	}

	/**
	 *  Creates a CodeArea with line numbers and XML syntax highlighting
	 */
	private fun createHighlightingCodeArea(highlightNaN: Boolean): CodeArea {
		return CodeArea().apply codeArea@{
			paragraphGraphicFactory = LineNumberFactory.get(this)
			textProperty().addListener { _, _, _ ->
				if (suspendHighlighting == 0) {
					highlightAllMatches(this, currentQuery, highlightNaN)
				}
			}
			val self = this
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

			highlightAllMatches(this, currentQuery, highlightNaN)
		}
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
	 */
	private fun createChooser(
		title: String,
		lastPath: Path?,
		description: String,
		vararg masks: String
	): FileChooser = FileChooser().apply {
		this.title = title
		extensionFilters.add(FileChooser.ExtensionFilter(description, *masks))
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
			System.err.println("Не удалось сохранить config.json -> ${ex.message}")
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


	private fun loadFileIntoAreaAsync(
		session: DocSession,
		path: Path,
		area: CodeArea,
		setPath: (Path) -> Unit
	) {
		val total = runCatching { Files.size(path).coerceAtLeast(1) }.getOrDefault(1L).toDouble()

		val task = object : Task<String>() {
			override fun call(): String {
				updateMessage("Reading ${path.fileName}…")
				val sb = StringBuilder()
				Files.newBufferedReader(path, Charsets.UTF_8).use { r ->
					val buf = CharArray(64 * 1024)
					var read = r.read(buf)
					var acc = 0L
					while (read >= 0 && !isCancelled) {
						sb.appendRange(buf, 0, read)
						acc += read
						updateMessage("Loaded ${acc / 1024} KB")
						updateProgress(acc.toDouble(), total)
						read = r.read(buf)
					}
				}
				return sb.toString()
			}
		}

		runWithProgress(currentStage, "Opening ${path.fileName}", task) { text ->
			text ?: return@runWithProgress
			suspendHighlighting++
			try {
				area.replaceText(text)
			} finally {
				suspendHighlighting--
				highlightAllMatches(area, currentQuery, area === session.resultArea)
			}
			setPath(path)
			registerWatch(path, session, area)
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
		showStatus(currentStage, "XSLT saved:\n$path")
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
			errorListener = object : ErrorListener {
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
			}
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
			title = "Search"
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
			currentQuery = newValue
			val area = searchTarget ?: target
			highlightAllMatches(area, currentQuery, area === currentSession.resultArea)
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
			highlightAllMatches(target, field.text, true)
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
			if (seg.predicate.isNotEmpty()) opts.add("by index ${seg.predicate}")
			opts.add("no predicate")
			seg.attrs.forEach { (k, v) -> opts.add("@$k='$v'") }
			cb.items.addAll(opts); cb.value = opts[0]
			return HBox(6.0, lbl, cb)
		}

		val rows = meta.segs.map(::segRow)
		val rowsBox = VBox(4.0, *rows.toTypedArray())
		val rowsScroll = ScrollPane(rowsBox).apply {
			isFitToWidth = true
			hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
		}
		VBox.setVgrow(rowsScroll, Priority.ALWAYS)

		val resultField = TextField(meta.xpath).apply {
			id = "xpathField"
			isEditable = true
		}

		fun rebuild() {
			val sb = StringBuilder()
			rows.forEachIndexed { i, row ->
				val name = (row.children[0] as Label).text
				val sel = (row.children[1] as ChoiceBox<*>).value as String
				sb.append('/').append(name)
				when {
					sel.startsWith("@") -> sb.append("[$sel]")
					sel.startsWith("by index") -> sb.append(meta.segs[i].predicate)
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
			val clip = Clipboard.getSystemClipboard()
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
				if (ev.code == KeyCode.ESCAPE) {
					close()
				}
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

		val xmlRadio = RadioButton("XML")
		val resultRadio = RadioButton("Result")
		val tg = ToggleGroup().also { g ->
			xmlRadio.toggleGroup = g
			resultRadio.toggleGroup = g
		}
		if (currentArea === currentSession.resultArea) {
			tg.selectToggle(resultRadio)
		} else {
			tg.selectToggle(xmlRadio)
		}

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
				showStatus(primaryStage, "Empty expression.")
				return@setOnAction
			}
			try {
				val proc = Processor(false)
				val doc = buildDocForXPath(proc, xmlText)
				val compiler = proc.newXPathCompiler()
				setDefaultNsFromDoc(compiler, doc)
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

		val root = VBox(
			10.0,
			xpathField,
			HBox(10.0, xmlRadio, resultRadio),
			HBox(10.0, runBtn, closeBtn)
		).apply { padding = Insets(12.0) }

		dlg.scene = Scene(root).also { sc ->
			sc.setOnKeyPressed { if (it.code == KeyCode.ESCAPE) dlg.close() }
		}
		dlg.show()
	}

	/**
	 * Универсальный разбор текста для XPath: сначала XML; если похоже на HTML — TagSoup;
	 * иначе — оборачиваем XML‑фрагмент без корня и вырезаем DOCTYPE.
	 * */
	private fun buildDocForXPath(proc: Processor, text: String): XdmNode {
		val builder = proc.newDocumentBuilder()
		val trimmed = text.trim()
		require(trimmed.isNotEmpty()) { "Selected text is empty." }

		// 1) Обычный XML
		try {
			return builder.build(StreamSource(StringReader(trimmed)))
		} catch (_: SaxonApiException) {
			// пробуем дальше
		}

		// 2) HTML → TagSoup
		val looksHtml =
			Regex("""<!DOCTYPE\s+html""", RegexOption.IGNORE_CASE).containsMatchIn(trimmed) ||
					Regex("""<html(\s|>)""", RegexOption.IGNORE_CASE).containsMatchIn(trimmed) ||
					Regex("""<body(\s|>)""", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)
		if (looksHtml) {
			val parser = org.ccil.cowan.tagsoup.Parser()
			val src = SAXSource(parser, InputSource(StringReader(trimmed)))
			return builder.build(src)
		}

		// 3) XML‑фрагмент без общего корня
		val noDoctype = trimmed.replace(Regex("""<!DOCTYPE[\s\S]*?>""", RegexOption.IGNORE_CASE), "")
		val wrapped = "<__root>$noDoctype</__root>"
		return builder.build(StreamSource(StringReader(wrapped)))
	}

	/**
	 * Автонастройка default element namespace для XPath из корневого элемента документа.
	 * Для HTML (TagSoup/XHTML) это включит поддержку выражений без префикса: //h1, //section/p.
	 * */
	private fun setDefaultNsFromDoc(compiler: XPathCompiler, doc: XdmNode) {
		val it = doc.axisIterator(Axis.CHILD)
		while (it.hasNext()) {
			val n = it.next() as XdmNode
			if (n.nodeKind == XdmNodeKind.ELEMENT) {
				val ns = n.nodeName?.namespaceUri?.toString()
				if (!ns.isNullOrEmpty()) {
					compiler.declareNamespace("", ns)
					compiler.declareNamespace("h", ns)
				}
				break
			}
		}
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


	/**
	 * Формирует абсолютный XPath до узла (или атрибута) под курсором
	 * и возвращает метаданные сегментов для GUI-редактора.
	 *
	 * @param xml    полное содержимое XML-документа
	 * @param offset позиция курсора (selection.start) в этом тексте
	 */
	private fun buildXPathWithMeta(xml: String, offset: Int): XPathMeta {
		data class Frame(
			val name: String,
			val attrs: Map<String, String>,
			val indexInSiblings: Int,
			val openStart: Int,
			val openEnd: Int,
			val childCounters: MutableMap<String, Int> = hashMapOf()
		)

		val openRx = Regex("""<([A-Za-z_][\w:.\-]*)([^>]*?)>""")
		val selfRx = Regex("""<([A-Za-z_][\w:.\-]*)([^>]*?)/>""")
		val closeRx = Regex("""</([A-Za-z_][\w:.\-]*)\s*>""")
		val attrRx = Regex("""([\w:-]+)\s*=\s*(['"])(.*?)\2""")

		fun attrsOf(tag: String) =
			attrRx.findAll(tag).associate { it.groupValues[1] to it.groupValues[3] }

		// Предикат по атрибуту, если курсор внутри головы тега
		var attrPred = ""
		run {
			val ts = xml.lastIndexOf('<', offset).coerceAtLeast(0)
			val te = xml.indexOf('>', ts).let { if (it == -1) ts else it }
			if (offset in ts..te) {
				attrRx.findAll(xml.substring(ts, te + 1)).forEach { a ->
					val s = ts + a.range.first
					val e = ts + a.range.last
					if (offset in s..e) attrPred = "/@${a.groupValues[1]}"
				}
			}
		}

		val stack = mutableListOf<Frame>()
		val rootCounters = hashMapOf<String, Int>()

		var i = 0
		var pathAtOffset: List<Frame>? = null

		fun nextIndexFor(parent: Frame?, name: String): Int {
			val counters = parent?.childCounters ?: rootCounters
			val n = (counters[name] ?: 0) + 1
			counters[name] = n
			return n
		}

		while (i < xml.length && pathAtOffset == null) {
			val lt = xml.indexOf('<', i)
			if (lt < 0) break

			// offset в тексте между тегами — путь это текущий стек
			if (offset in i until lt) {
				pathAtOffset = stack.toList()
				break
			}

			// ---- Самозакрывающийся тег ----
			val mSelf = selfRx.matchAt(xml, lt)
			if (mSelf != null) {
				val name = mSelf.groupValues[1]
				val attrs = attrsOf(mSelf.value)
				val idx = nextIndexFor(stack.lastOrNull(), name)
				val leaf = Frame(name, attrs, idx, mSelf.range.first, mSelf.range.last, hashMapOf())
				if (offset in mSelf.range) {
					pathAtOffset = stack + leaf
					break
				}
				i = mSelf.range.last + 1
				continue
			}

			// ---- Открывающий тег ----
			val mOpen = openRx.matchAt(xml, lt)
			if (mOpen != null) {
				val name = mOpen.groupValues[1]
				val attrs = attrsOf(mOpen.value)
				val idx = nextIndexFor(stack.lastOrNull(), name)
				val fr = Frame(name, attrs, idx, mOpen.range.first, mOpen.range.last)
				stack.add(fr)
				if (offset in mOpen.range) {
					pathAtOffset = stack.toList()
					break
				}
				i = mOpen.range.last + 1
				continue
			}

			// ---- Закрывающий тег ----
			val mClose = closeRx.matchAt(xml, lt)
			if (mClose != null) {
				if (offset in mClose.range && stack.isNotEmpty()) {
					pathAtOffset = stack.toList()
					break
				}
				val closeName = mClose.groupValues[1]
				// Поп до совпадающего имени (защита от «битого» XML)
				for (k in stack.indices.reversed()) {
					if (stack[k].name == closeName) {
						while (stack.size > k) stack.removeAt(stack.lastIndex)
						break
					}
				}
				i = mClose.range.last + 1
				continue
			}

			// Не тег — просто сдвигаемся после '<'
			i = lt + 1
		}

		// Если ничего не зафиксировали, но есть открытый контекст — используем его
		if (pathAtOffset == null && stack.isNotEmpty()) {
			pathAtOffset = stack.toList()
		}

		val path = pathAtOffset ?: return XPathMeta("/", emptyList())
		val segs = path.map { SegMeta(it.name, "[${it.indexInSiblings}]", it.attrs) }

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


	private fun <T> runWithProgress(
		owner: Stage,
		title: String,
		task: Task<T>,
		onDone: (T?) -> Unit = {}
	) {
		val bar = ProgressBar().apply { prefWidth = 380.0 }
		val msg = Label("Starting…")
		bar.progressProperty().bind(task.progressProperty())
		msg.textProperty().bind(task.messageProperty())
		val cancelBtn = Button("Cancel").apply { setOnAction { task.cancel() } }
		val box = VBox(10.0, msg, bar, HBox(10.0, cancelBtn)).apply {
			padding = Insets(14.0); alignment = Pos.CENTER_LEFT
		}
		val dlg = Stage().apply {
			initOwner(owner); initModality(Modality.WINDOW_MODAL)
			this.title = title; scene = Scene(box)
		}
		task.setOnSucceeded { dlg.close(); onDone(task.value) }
		task.setOnFailed { dlg.close(); showStatus(owner, "Operation failed:\n${task.exception?.message}") }
		task.setOnCancelled { dlg.close() }
		Thread(task, "progress-task").apply { isDaemon = true }.start()
		dlg.show()
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
		var xsltPath: Path? = null,
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
