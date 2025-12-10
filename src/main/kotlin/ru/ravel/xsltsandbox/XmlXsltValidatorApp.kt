package ru.ravel.xsltsandbox

import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import javafx.animation.AnimationTimer
import javafx.application.Application
import javafx.application.Platform
import javafx.concurrent.Task
import javafx.event.ActionEvent
import javafx.geometry.Insets
import javafx.geometry.Orientation
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.canvas.Canvas
import javafx.scene.canvas.GraphicsContext
import javafx.scene.control.*
import javafx.scene.input.Clipboard
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.*
import javafx.scene.paint.Color
import javafx.stage.DirectoryChooser
import javafx.stage.FileChooser
import javafx.stage.Modality
import javafx.stage.Stage
import net.sf.saxon.s9api.*
import org.apache.commons.text.StringEscapeUtils
import org.fxmisc.flowless.VirtualizedScrollPane
import org.fxmisc.richtext.CodeArea
import org.fxmisc.richtext.LineNumberFactory
import org.fxmisc.richtext.model.StyleSpansBuilder
import org.fxmisc.richtext.model.TwoDimensional
import org.fxmisc.richtext.model.TwoDimensional.Bias.Forward
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid
import org.kordamp.ikonli.javafx.FontIcon
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import ru.ravel.xsltsandbox.models.wait.Wait
import ru.ravel.xsltsandbox.models.*
import ru.ravel.xsltsandbox.models.ReferredDocument
import ru.ravel.xsltsandbox.models.bizrule.*
import ru.ravel.xsltsandbox.models.datamapping.DataMapping
import ru.ravel.xsltsandbox.models.datasource.DataSource
import ru.ravel.xsltsandbox.models.form.Form
import ru.ravel.xsltsandbox.models.layout.DiagramLayout
import ru.ravel.xsltsandbox.models.procedure.ProcedureCall
import ru.ravel.xsltsandbox.models.procedurereturn.ProcedureReturn
import ru.ravel.xsltsandbox.models.segmentationtree.BusinessRule
import ru.ravel.xsltsandbox.models.segmentationtree.SegmentationTree
import ru.ravel.xsltsandbox.models.setvalue.SetValueActivity
import ru.ravel.xsltsandbox.utils.LayoutUtil
import ru.ravel.xsltsandbox.utils.XmlUtil
import java.io.ByteArrayInputStream
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import java.nio.file.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.function.IntFunction
import java.util.regex.Pattern
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.SAXParserFactory
import javax.xml.transform.ErrorListener
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerException
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.sax.SAXSource
import javax.xml.transform.stream.StreamResult
import javax.xml.transform.stream.StreamSource
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.name
import org.w3c.dom.Node as DomNode


class XmlXsltValidatorApp : Application() {
	private val xmlMapper = XmlMapper().registerKotlinModule()

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
	private val foldedParagraphs = mutableSetOf<Int>()
	private lateinit var dirField: TextField
	private lateinit var fileTree: TreeView<Path>
	private lateinit var xsltRadio: RadioButton
	private lateinit var brRadio: RadioButton
	private var processPath: Path? = null
	private val activitiesDebugProcedureStack = mutableMapOf<DocSession, Stack<Path>>()
	private lateinit var fileTreeSearch: TextField

	@Volatile
	private var suspendHighlighting = 0

	@Volatile
	private var watcherRunning = true


	override fun init() {
		config = runCatching {
			jacksonObjectMapper().readValue(configPath.toFile(), AppConfig::class.java)
		}.getOrElse { AppConfig() }
	}


	override fun stop() {
		try {
			saveConfig()
			watcherRunning = false
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
		tabPane.selectionModel.select(first.tab)
		plusTab.setOnSelectionChanged {
			if (plusTab.isSelected) {
				val created = createNewSessionTab("Tab ${sessions.size + 1}")
				tabPane.tabs.add(tabPane.tabs.size - 1, created.tab)
				tabPane.selectionModel.select(created.tab)
			}
		}
		tabPane.tabs.add(plusTab)
		tabPane.selectionModel.selectedItemProperty().addListener { _, _, newTab ->
			val session = sessions[newTab] ?: return@addListener
			when (session.mode) {
				TransformMode.XSLT -> {
					xsltRadio.isSelected = true
					session.xsltBox?.isVisible = true
					session.xsltBox?.isManaged = true
					session.brBox?.isVisible = false
					session.brBox?.isManaged = false
				}

				TransformMode.BR -> {
					brRadio.isSelected = true
					session.xsltBox?.isVisible = false
					session.xsltBox?.isManaged = false
					session.brBox?.isVisible = true
					session.brBox?.isManaged = true
				}

				else -> {
					TODO()
				}
			}
		}

		val topBar = buildToolBar()

		dirField = TextField().apply {
			promptText = "Выберите папку..."
			isEditable = false
		}
		fileTree = TreeView<Path>().apply {
			isShowRoot = false
			prefWidth = 250.0
			setCellFactory {
				object : TreeCell<Path>() {
					override fun updateItem(item: Path?, empty: Boolean) {
						super.updateItem(item, empty)
						text = if (empty || item == null) {
							null
						} else {
							item.fileName?.toString() ?: item.toString()
						}
					}
				}
			}
			setOnMouseClicked { event ->
				if (event.clickCount == 2) {
					val item = fileTree.selectionModel.selectedItem?.value ?: return@setOnMouseClicked
					if (Files.isDirectory(item)) return@setOnMouseClicked

					// создаём новую вкладку
					val newSession = createNewSessionTab(item.fileName.toString())
					tabPane.tabs.add(tabPane.tabs.size - 1, newSession.tab)
					tabPane.selectionModel.select(newSession.tab)

					val type = LayoutUtil.getActivityType(item.toFile())
					val isBr = type in arrayOf(ActivityType.BIZ_RULE, ActivityType.BUSINESS_RULE)
					val isFormOrWait = type in arrayOf(ActivityType.FORM, ActivityType.WAIT)
					when {
						item.extension.lowercase() in arrayOf("xsl", "xslt") -> {
							loadFileIntoAreaAsync(newSession, item, newSession.xsltArea) { loaded ->
								newSession.xsltPath = loaded
								newSession.mappingPropertyFile = loaded.parent.resolve("Properties.xml")
								newSession.updateTabTitle()
							}
							newSession.mode = TransformMode.XSLT
							xsltRadio.isSelected = true
						}

						item.extension.equals("xml", true) && isBr -> {
							val innerXml = when (type) {
								ActivityType.BIZ_RULE -> {
									val bizRule = xmlMapper.readValue(item.toFile(), BizRule::class.java)
									StringEscapeUtils.unescapeXml(bizRule.xmlRule.value)
								}

								ActivityType.BUSINESS_RULE -> {
									val businessRule = xmlMapper.readValue(item.toFile(), BusinessRule::class.java)
									StringEscapeUtils.unescapeXml(businessRule.xmlRule)
								}

								else -> return@setOnMouseClicked
							}
							val rootNode: Any = if (innerXml.trim().startsWith("<Quantifier")) {
								xmlMapper.readValue(innerXml, Quantifier::class.java)
							} else {
								xmlMapper.readValue(innerXml, Connective::class.java)
							}

							when (rootNode) {
								is Quantifier -> {
									newSession.brRootQuant = rootNode
									newSession.brRoot = null
									newSession.brTree?.root = toTreeItem(rootNode)
								}

								is Connective -> {
									newSession.brRoot = rootNode
									newSession.brRootQuant = null
									newSession.brTree?.root = toTreeItem(rootNode)
								}
							}
							newSession.brTree?.isShowRoot = true
							expandAll(newSession.brTree?.root ?: return@setOnMouseClicked)
							newSession.mode = TransformMode.BR
							brRadio.isSelected = true
							newSession.brPath = item
							newSession.mappingPropertyFile = item.parent.resolve("Properties.xml")
							newSession.updateTabTitle()
						}

						item.extension.equals("xml", true) && isFormOrWait -> {
							when (type) {
								ActivityType.FORM -> newSession.mode = TransformMode.FM
								ActivityType.WAIT -> newSession.mode = TransformMode.WA
								else -> return@setOnMouseClicked
							}
							loadFileIntoAreaAsync(newSession, item, newSession.xsltArea) { newSession.xsltPath = it }
							newSession.mappingPropertyFile = item
							newSession.otherActivityPath = item
							xsltRadio.isSelected = true
							newSession.updateTabTitle()
						}

						else -> {
							loadFileIntoAreaAsync(newSession, item, newSession.xmlArea) { newSession.xmlPath = it }
						}
					}
				}
			}
		}
		val chooseBtn = Button("Open…").apply {
			setOnAction {
				val initialDir = processPath?.absolutePathString()?.let { File(it).parentFile }
				val chooser = DirectoryChooser().apply {
					title = "Выберите рабочую папку"
					if (initialDir?.exists() == true) {
						initialDirectory = initialDir
					}
				}
				val dir = chooser.showDialog(primaryStage) ?: return@setOnAction
				dirField.text = dir.name
				fileTree.root = buildFileTree(dir.toPath())
				processPath = dir.toPath()
				fileTreeSearch.isDisable = false
				rebuildFileTreeByQuery(fileTreeSearch.text)
				saveConfig()
			}
		}
		fileTreeSearch = TextField().apply {
			promptText = "Search"
			isDisable = processPath == null
			textProperty().addListener { _, _, q -> rebuildFileTreeByQuery(q) }
		}
		val fileTreeBox = VBox(
			HBox(5.0, dirField, chooseBtn).apply { padding = Insets(5.0) },
			fileTreeSearch,
			fileTree
		).apply {
			VBox.setVgrow(fileTree, Priority.ALWAYS)
			prefWidth = 260.0
		}


		val root = BorderPane().apply {
			top = topBar
			center = tabPane
			left = fileTreeBox
		}

		val scene = Scene(root, 1200.0, 800.0).apply {
			addEventFilter(KeyEvent.KEY_PRESSED) { event ->
				if (event.code == KeyCode.F && event.isControlDown) {
					showSearchWindow(primaryStage, currentSession.currentAreaOr(xml = true))
					event.consume()
				}
			}
			addEventFilter(KeyEvent.KEY_PRESSED) { event ->
				if (event.code == KeyCode.ENTER && event.isControlDown) {
					doTransform(currentStage)
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
		startWatchThread()
	}


	private fun buildToolBar(): HBox {
		val validateAntTransformBtn = Button().apply {
			graphic = FontIcon(FontAwesomeSolid.CHECK_CIRCLE)
			tooltip = Tooltip("Validate & Transform")
			setOnAction { doTransform(currentStage) }
		}
		val searchBtn = Button().apply {
			graphic = FontIcon(FontAwesomeSolid.SEARCH)
			tooltip = Tooltip("Search")
			setOnAction { showSearchWindow(currentStage, currentSession.currentAreaOr(xml = true)) }
		}

		val xpathMenu = MenuButton("XPath…").apply {
			items.addAll(
				MenuItem("Edit XPath…").apply {
					setOnAction { openXPathEditor(currentStage) }
				},
				MenuItem("Execute XPath…").apply {
					setOnAction { executeXpath(currentStage) }
				}
			)
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
					currentSession.mappingPropertyFile = path.parent.resolve("Properties.xml")
					currentSession.updateTabTitle()
				}
			}
		}
		val openBrBtn = Button("Open BR…").apply {
			isVisible = false
			isManaged = false
			setOnAction {
				val file = createChooser("Open BR…", currentSession.xsltPath, "XML Files (*.xml)", "*.xml")
					.showOpenDialog(currentStage) ?: return@setOnAction
				val mapper = xmlMapper
				val bizRule = mapper.readValue(file, BizRule::class.java)
				currentSession.brPath = file.toPath()
				currentSession.mappingPropertyFile = file.toPath().parent.resolve("Properties.xml")
				currentSession.updateTabTitle()
				val innerXml = StringEscapeUtils.unescapeXml(bizRule.xmlRule.value)

				val rootNode: Any = if (innerXml.trim().startsWith("<Quantifier")) {
					mapper.readValue(innerXml, Quantifier::class.java)
				} else {
					mapper.readValue(innerXml, Connective::class.java)
				}

				when (rootNode) {
					is Quantifier -> {
						currentSession.brRootQuant = rootNode
						currentSession.brRoot = null
						currentSession.brTree?.root = toTreeItem(rootNode)
					}

					is Connective -> {
						currentSession.brRoot = rootNode
						currentSession.brRootQuant = null
						currentSession.brTree?.root = toTreeItem(rootNode)
					}
				}
				currentSession.brTree?.isShowRoot = true
				expandAll(currentSession.brTree?.root ?: return@setOnAction)
			}
		}
		val openStack = StackPane(openXsltBtn, openBrBtn).apply {
			minWidth = Region.USE_PREF_SIZE
			prefWidth = 100.0
		}
		HBox.setMargin(openXsltBtn, Insets(0.0, 0.0, 0.0, 16.0))

		val saveXsltBtn = Button("Save XSLT…").apply {
			setOnAction { saveCurrentXslt() }
		}

		val saveStack = StackPane(saveXsltBtn).apply {
			minWidth = Region.USE_PREF_SIZE
			prefWidth = 100.0
		}

		xsltRadio = RadioButton("XSLT").apply {
			isSelected = true
		}
		brRadio = RadioButton("BR")
		val modeGroup = ToggleGroup().apply {
			xsltRadio.toggleGroup = this
			brRadio.toggleGroup = this
		}
		modeGroup.selectedToggleProperty().addListener { _, _, new ->
			when (new) {
				xsltRadio -> {
					currentSession.mode = TransformMode.XSLT
					openXsltBtn.isVisible = true
					openXsltBtn.isManaged = true
					openBrBtn.isVisible = false
					openBrBtn.isManaged = false
					saveXsltBtn.isVisible = true
					saveXsltBtn.isManaged = true

					currentSession.xsltBox?.isVisible = true
					currentSession.xsltBox?.isManaged = true
					currentSession.brBox?.isVisible = false
					currentSession.brBox?.isManaged = false
				}

				brRadio -> {
					currentSession.mode = TransformMode.BR
					openXsltBtn.isVisible = false
					openXsltBtn.isManaged = false
					openBrBtn.isVisible = true
					openBrBtn.isManaged = true
					saveXsltBtn.isVisible = false
					saveXsltBtn.isManaged = false

					currentSession.xsltBox?.isVisible = false
					currentSession.xsltBox?.isManaged = false
					currentSession.brBox?.isVisible = true
					currentSession.brBox?.isManaged = true
				}
			}
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
		HBox.setMargin(disableHighlightCheck, Insets(0.0, 0.0, 0.0, 16.0))

		val activitySeparator = Separator(Orientation.VERTICAL)
		val activityLabel = Label("Activities debugger:")
		val nextActivityBtn = Button().apply {
			graphic = FontIcon(FontAwesomeSolid.ARROW_RIGHT)
			tooltip = Tooltip("Next layout activity")
			setOnAction { goToNextActivity() }
		}
		val dataDocsActivityBtn = MenuButton().apply {
			graphic = FontIcon(FontAwesomeSolid.FILE_CODE)
			tooltip = Tooltip("Open DataDocs")

			val manualItem = MenuItem("Input text").apply {
				setOnAction {
					val dlg = Stage().apply {
						initOwner(currentStage)
						initModality(Modality.WINDOW_MODAL)
						title = "DataDocs Editor"
					}
					val area = createHighlightingCodeArea(false).apply {
						prefWidth = 600.0
						prefHeight = 400.0
						replaceText(currentSession.dataDocs ?: "")
					}
					val okBtn = Button("OK").apply {
						setOnAction {
							currentSession.dataDocs = area.text
							val properties = currentSession.mappingPropertyFile?.toFile()
							if (properties != null) {
								val dataDocs = getDataDocsInOut(properties)
									.filter { it.access in arrayOf("Input", "InOut") }
									.map { it.referenceName }
								val neededDataDocs = extractNeededDataDocs(currentSession.dataDocs!!, dataDocs)
								currentSession.xmlArea.replaceText(neededDataDocs)
							}
							dlg.close()
						}
					}
					dlg.scene = Scene(VBox(8.0, area, okBtn).apply { padding = Insets(10.0) })
					dlg.show()
				}
			}

			val fileItem = MenuItem("Select file").apply {
				setOnAction {
					val file = createChooser("Open DataDocs", null, "XML Files (*.xml)", "*.xml")
						.showOpenDialog(currentStage)
						?: return@setOnAction
					currentSession.dataDocs = XmlUtil.readXmlSafe(file)
					val properties = currentSession.mappingPropertyFile?.toFile()
					if (properties != null) {
						val dataDocs = getDataDocsInOut(properties)
							.filter { it.access in arrayOf("Input", "InOut") }
							.map { it.referenceName }
						val neededDataDocs = extractNeededDataDocs(currentSession.dataDocs!!, dataDocs)
						currentSession.xmlArea.replaceText(neededDataDocs)
					}
				}
			}

			val exportDataDocs = MenuItem("Export to file").apply {
				setOnAction {
					val file = createChooser("Export DataDocs", null, "XML Files (*.xml)", "*.xml")
						.showSaveDialog(currentStage)
						?: return@setOnAction
					file.writeText(currentSession.dataDocs ?: "")
				}
			}

			items.addAll(manualItem, fileItem, SeparatorMenuItem(), exportDataDocs)
		}

		fun updateActivityButtons() {
			val xsltLoaded = currentSession.xsltPath != null
			val brLoaded = currentSession.brRoot != null || currentSession.brRootQuant != null
			nextActivityBtn.isDisable = !(xsltLoaded || brLoaded)
		}

		dataDocsActivityBtn.items.forEach { item ->
			item.addEventHandler(ActionEvent.ACTION) {
				updateActivityButtons()
			}
		}

		updateActivityButtons()
		modeGroup.selectedToggleProperty().addListener { _, _, _ ->
			updateActivityButtons()
		}

		return HBox(
			8.0,
			validateAntTransformBtn,
			searchBtn,
			xpathMenu,
			xsltRadio,
			brRadio,
			openStack,
			saveStack,
			openXmlBtn,
			disableHighlightCheck,
			activitySeparator,
			activityLabel,
			nextActivityBtn,
			dataDocsActivityBtn,
		).apply {
			alignment = Pos.CENTER_LEFT
			padding = Insets(10.0)
		}
	}


	private fun createNewSessionTab(title: String): DocSession {
		val xsltArea = createHighlightingCodeArea(false)
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
			// Ctrl+C
			addEventFilter(KeyEvent.KEY_PRESSED) { e ->
				if (e.code == KeyCode.C && e.isControlDown) {
					val selected = selectionModel.selectedItem
					if (selected != null) {
						val clip = Clipboard.getSystemClipboard()
						val content = javafx.scene.input.ClipboardContent()
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
							val content = javafx.scene.input.ClipboardContent()
							content.putString(selected.value)
							clip.setContent(content)
						}
					}
				}
				items.add(copyItem)
			}
		}
		val brHeader = Label("Business Rule")
		val brBox = VBox(4.0, brHeader, brTreeView).apply {
			padding = Insets(8.0)
			minWidth = 0.0
			isVisible = false
			isManaged = false
		}
		val xmlArea = createHighlightingCodeArea(false)
		val resultArea = createHighlightingCodeArea(true).apply {
			isEditable = false
			minHeight = 0.0
		}

		// чтобы Ctrl+F искал по активной области
		listOf(xsltArea, xmlArea, resultArea).forEach { area ->
			area.setOnMouseClicked { currentArea = area }
			area.addEventHandler(KeyEvent.KEY_PRESSED) { currentArea = area }
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
				sessions.remove(this)
				if (tabPane?.tabs?.size == 1) { // остался только '+'
					val t = createNewSessionTab("Tab 1")
					tabPane.tabs.add(tabPane.tabs.size - 1, t.tab)
					tabPane.selectionModel.select(t.tab)
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
		sessions[tab] = session
		hookOverlayRedraw(session)
		return session
	}


	private fun buildFileTree(path: Path): TreeItem<Path> {
		val root = TreeItem(path)
		if (Files.isDirectory(path)) {
			try {
				Files.list(path).forEach { child ->
					root.children.add(buildFileTree(child))
				}
			} catch (_: Exception) {
			}
		}
		return root
	}


	private fun startWatchThread() {
		Thread({
			try {
				while (watcherRunning) {
					val key = try {
						watchService.take()
					} catch (_: ClosedWatchServiceException) {
						break // выходим из цикла
					}
					val dir = key.watchable() as Path
					for (event in key.pollEvents()) {
						val ev = event as WatchEvent<Path>
						val changed = dir.resolve(ev.context())
						val entry = watchMap[changed] ?: continue
						val (session, area) = entry

						Platform.runLater {
							try {
								val file = changed.toFile()
								val text = XmlUtil.readXmlSafe(file)
								session.xsltEncoding = XmlUtil.getEncoding(file.readBytes())
								suspendHighlighting++
								try {
									area.replaceText(text)
								} finally {
									suspendHighlighting--
									highlightAllMatches(area, currentQuery, area === session.resultArea)
								}
							} catch (ex: Exception) {
								showStatus(currentStage, "Не удалось обновить файл:\n$changed\n${ex.message}")
							}
						}
					}
					key.reset()
				}
			} catch (ex: Exception) {
				if (watcherRunning) ex.printStackTrace()
			}
		}, "watch-thread").apply { isDaemon = true }.start()
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
			padding = Insets(14.0)
			alignment = Pos.CENTER_LEFT
		}
		val dlg = Stage().apply {
			initOwner(currentStage)
			initModality(Modality.WINDOW_MODAL)
			title = "Pasting large text"
			scene = Scene(box)
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
			// стандартная нумерация строк с кнопкой сворачивания
			installFolding(this)
			textProperty().addListener { _, _, _ ->
				if (suspendHighlighting == 0) {
					highlightAllMatches(this, currentQuery, highlightNaN)
				}
			}
			caretPositionProperty().addListener { _, _, newPos ->
				highlightTagPair(this, newPos.toInt())
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
		val scrolled = VirtualizedScrollPane(area).apply { minWidth = 0.0 }
		VBox.setVgrow(scrolled, Priority.ALWAYS)
		return VBox(4.0, label, scrolled).apply {
			padding = Insets(8.0)
			minWidth = 0.0
		}
	}


	/**
	 * Создаёт кнопку «Open …».
	 */
	private fun createChooser(
		title: String,
		lastPath: Path?,
		description: String,
		vararg masks: String,
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
					xmlPath = s.xmlPath?.toString(),
					xsltPath = s.xsltPath?.toString(),
					brPath = s.brPath?.toString(),
					process = processPath?.toString(),
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
		setPath: (Path) -> Unit,
	) {
		val file = path.toFile()
		if (area == session.xsltArea) {
			session.xsltEncoding = XmlUtil.getEncoding(file.readBytes())
		} else if (area == session.xmlArea) {
			session.xmlEncoding = XmlUtil.getEncoding(file.readBytes())
		}
		area.replaceText(XmlUtil.readXmlSafe(file))
		setPath(path)
		registerWatch(path, session, area)
	}


	private fun loadFileIntoAreaAsync(
		session: DocSession,
		path: Path,
		area: CodeArea,
		setPath: (Path) -> Unit,
	) {
		val task = object : Task<String>() {
			override fun call(): String {
				updateMessage("Reading ${path.fileName}…")
				val bytes = Files.readAllBytes(path)
				val total = bytes.size.toDouble()
				val chunk = 64 * 1024

				val sb = StringBuilder(total.toInt())
				var i = 0
				while (i < bytes.size && !isCancelled) {
					val next = (i + chunk).coerceAtMost(bytes.size)
					sb.append(XmlUtil.readXmlSafe(bytes.copyOfRange(i, next)))
					updateMessage("Loaded ${next / 1024} KB")
					updateProgress(next.toDouble(), total)
					i = next
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
		state.xmlPath?.let { p ->
			val path = Paths.get(p)
			if (Files.exists(path)) {
				loadFileIntoAreaAsync(session, path, session.xmlArea) { session.xmlPath = it }
			}
		}
		state.xsltPath?.let { p ->
			val path = Paths.get(p)
			if (Files.exists(path)) {
				loadFileIntoAreaAsync(session, path, session.xsltArea) { loaded ->
					session.xsltPath = loaded
					session.updateTabTitle()
				}
			}
		}
		state.brPath?.let { p ->
			val path = Paths.get(p)
			if (Files.exists(path)) {
				val mapper = xmlMapper
				val bizRule = mapper.readValue(path.toFile(), BizRule::class.java)
				val innerXml = StringEscapeUtils.unescapeXml(bizRule.xmlRule.value)
				val rootNode: Any = if (innerXml.trim().startsWith("<Quantifier")) {
					mapper.readValue(innerXml, Quantifier::class.java)
				} else {
					mapper.readValue(innerXml, Connective::class.java)
				}
				when (rootNode) {
					is Quantifier -> {
						session.brRootQuant = rootNode
						session.brRoot = null
						session.brTree?.root = toTreeItem(rootNode)
					}

					is Connective -> {
						session.brRoot = rootNode
						session.brRootQuant = null
						session.brTree?.root = toTreeItem(rootNode)
					}
				}
				session.brTree?.isShowRoot = true
				expandAll(session.brTree?.root ?: return@let)
				session.brPath = path
				session.mode = TransformMode.BR
			}
		}
		state.process?.let { p ->
			val procPath = Paths.get(p)
			if (Files.isDirectory(procPath)) {
				processPath = procPath
				Platform.runLater {
					dirField.text = procPath.toAbsolutePath().toString()
					fileTreeSearch.isDisable = false
					if (fileTreeSearch.text.isNotBlank()) {
						fileTree.root = buildFilteredFileTree(procPath, fileTreeSearch.text)
						expandAll(fileTree.root)
					} else {
						fileTree.root = buildFileTree(procPath)
					}
				}
			}
		}
	}


	private fun saveCurrentXslt() {
		val path: Path = currentSession.xsltPath ?: run {
			val file = createChooser(
				"Save XSLT…",
				currentSession.xsltPath,
				"XSLT Files (*.xsl, *.xslt)", "*.xsl", "*.xslt"
			).showSaveDialog(currentStage) ?: return
			file.toPath()
		}
		Files.createDirectories(path.parent)

		val charset = currentSession.xsltEncoding ?: Charsets.UTF_8
		XmlUtil.writeXmlWithBom(
			file = path.toFile(),
			text = currentSession.xsltArea.text,
			charset = charset
		)
		// обновляем путь в сессии и подписываемся на изменения файла
		if (currentSession.xsltPath != path) {
			currentSession.xsltPath = path
			registerWatch(path, currentSession, currentSession.xsltArea)
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
	 *
	 * 	Работает с [currentSession]
	 *
	 */
	private fun doTransform(owner: Stage): String {
		val s = currentSession

		when (s.mode) {
			TransformMode.BR -> {
				try {
					val xml = currentSession.xmlArea.text
					val br = currentSession.brRoot ?: currentSession.brRootQuant
					val result = when (br) {
						is Connective -> {
							evaluateBR(xml, br)
						}

						is Quantifier -> {
							val proc = Processor(false)
							val doc = buildDocForXPath(proc, xml)
							val compiler = proc.newXPathCompiler()
							setDefaultNsFromDoc(compiler, doc)
							evalQuantifier(br, compiler, doc)
						}

						else -> {
							false
						}
					}
					Platform.runLater {
						s.resultArea.replaceText(result.toString())
						highlightAllMatches(s.resultArea, currentQuery, true)
						s.nanCountLabel.text = ""
						s.nanCountLabel.isVisible = false
					}
					return result.toString()
				} catch (e: Exception) {
					Platform.runLater {
						showStatus(owner, e.localizedMessage)
					}
					throw e
				}
			}

			TransformMode.XSLT -> {
				val status = StringBuilder()
				val saxonWarnAcc = mutableListOf<IntRange>()
				val saxonErrAcc = mutableListOf<IntRange>()
				val saxonFatalAcc = mutableListOf<IntRange>()

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
				} catch (ex: Exception) {
					status.append("XML parsing halted: ${ex.message}\n")
				}

				val tfFactory: TransformerFactory = TransformerFactory.newInstance(
					"net.sf.saxon.TransformerFactoryImpl",
					XmlXsltValidatorApp::class.java.classLoader
				).apply {
					errorListener = object : ErrorListener {
						override fun warning(ex: TransformerException) = report(Sev.WARNING, ex)
						override fun error(ex: TransformerException) = report(Sev.ERROR, ex)
						override fun fatalError(ex: TransformerException) = report(Sev.FATAL, ex)

						private fun report(sev: Sev, ex: TransformerException) {
							val loc = ex.locator
							val levelText = when (sev) {
								Sev.WARNING -> "WARNING in XSLT"
								Sev.ERROR -> "ERROR   in XSLT"
								Sev.FATAL -> "FATAL   in XSLT"
							}
							if (loc != null) {
								status.append("$levelText [line=${loc.lineNumber},col=${loc.columnNumber}]: ${ex.message}\n")
								val r = computeXsltErrorRange(s.xsltArea.text, loc.lineNumber, loc.columnNumber)
								when (sev) {
									Sev.WARNING -> saxonWarnAcc += r
									Sev.ERROR -> saxonErrAcc += r
									Sev.FATAL -> saxonFatalAcc += r
								}
							} else {
								status.append("$levelText: ${ex.message}\n")
							}
						}
					}
				}

				val templates = try {
					tfFactory.newTemplates(StreamSource(StringReader(s.xsltArea.text))).also {
						status.append("XSLT compiled successfully.\n")
						s.xsltSyntaxErrorRanges = saxonErrAcc + saxonFatalAcc
						highlightAllMatches(s.xsltArea, currentQuery, false)
						appendBadSelectWarnings(s, status)
						saxonWarnAcc.addAll(s.xsltBadSelectRanges)
						s.xsltWarningRanges = saxonWarnAcc
						Platform.runLater {
							val errs = s.xsltSyntaxErrorRanges.size
							val warns = s.xsltWarningRanges.size
							s.xsltStatusLabel?.text = "Errors: $errs, Warnings: $warns"
							s.xsltStatusLabel?.isVisible = (errs + warns) > 0
						}
					}
				} catch (ex: TransformerException) {
					s.xsltSyntaxErrorRanges = saxonErrAcc + saxonFatalAcc
					s.xsltWarningRanges = saxonWarnAcc + s.xsltBadSelectRanges
					highlightAllMatches(s.xsltArea, currentQuery, false)
					appendBadSelectWarnings(s, status)
					Platform.runLater {
						val errs = s.xsltSyntaxErrorRanges.size
						val warns = s.xsltWarningRanges.size
						s.xsltStatusLabel?.text = buildString {
							if (errs > 0) append("Errors: $errs")
							if (warns > 0) {
								if (errs > 0) append(", ")
								append("Warnings: $warns")
							}
						}
						s.xsltStatusLabel?.isVisible = (errs + warns) > 0
						redrawXsltOverlay(s)
						showStatus(owner, status.toString())
					}
					throw ex
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
					System.err.println(ex.localizedMessage)
				}

				val resultText = writer.toString()
				Platform.runLater {
					s.resultArea.replaceText(resultText)
					highlightAllMatches(s.resultArea, currentQuery, true)
					val nanCount = Regex("\\bNaN\\b").findAll(resultText).count()
					s.nanCountLabel.text = "NaNs: $nanCount"
					s.nanCountLabel.isVisible = nanCount > 0
					val errs = s.xsltSyntaxErrorRanges.size
					val warns = s.xsltWarningRanges.size
					s.xsltStatusLabel?.text = buildString {
						if (errs > 0) {
							append("Errors: $errs")
						}
						if (warns > 0) {
							if (errs > 0) {
								append(", ")
							}
							append("Warnings: $warns")
						}
					}
					s.xsltStatusLabel?.isVisible = (errs + warns) > 0
					if (errs > 0) {
						showStatus(owner, status.toString())
					}
					appendBadSelectWarnings(s, status)
				}
				return resultText
			}

			TransformMode.ST -> {
				try {
					val xml = currentSession.xmlArea.text
					val path = currentSession.otherActivityPath
					val st = xmlMapper.readValue(path?.toFile(), SegmentationTree::class.java)
					val results = st.rules?.ruleList?.mapNotNull { rule ->
						val file = path?.parent?.parent?.parent?.parent?.resolve("BusinessRules")?.resolve("${rule.ruleID}.xml")
							?.toFile()
						if (file?.exists() == true) {
							xmlMapper.readValue(file, BusinessRule::class.java)
						} else {
							null
						}
					}?.mapNotNull { rule ->
						val rootNode: Any = if (rule.xmlRule?.trim()?.startsWith("<Quantifier") == true) {
							xmlMapper.readValue(rule.xmlRule, Quantifier::class.java)
						} else {
							xmlMapper.readValue(rule.xmlRule, Connective::class.java)
						}
						val result = when (rootNode) {
							is Connective -> {
								evaluateBR(xml, rootNode)
							}

							is Quantifier -> {
								val proc = Processor(false)
								val doc = buildDocForXPath(proc, xml)
								val compiler = proc.newXPathCompiler()
								setDefaultNsFromDoc(compiler, doc)
								evalQuantifier(rootNode, compiler, doc)
							}

							else -> false
						}
						if (result) {
							rule.businessRuleID
						} else {
							null
						}
					}

					val stResult = if (results?.isNotEmpty() == true) {
						results.first()
					} else {
						"AllFalse"
					}

					Platform.runLater {
						showStatus(owner, "ST result:\n$stResult")
					}

					return stResult
				} catch (e: Exception) {
					Platform.runLater {
						showStatus(owner, e.localizedMessage)
					}
					throw e
				}
			}

			TransformMode.SV -> {
				try {
					val propertyFile = currentSession.otherActivityPath?.toFile()
						?: return ""
					val nextActivityDataDocsInputs = getDataDocsInOut(propertyFile)
						.filter { it.access in arrayOf("Input", "InOut") }
						.map { it.referenceName }
					val neededDataDocs = extractNeededDataDocs(currentSession.dataDocs!!, nextActivityDataDocsInputs)
					return applySetValues(propertyFile, neededDataDocs)
				} catch (e: Exception) {
					Platform.runLater {
						showStatus(owner, e.localizedMessage)
					}
					throw e
				}
			}

			TransformMode.PR -> {
				val file = currentSession.otherActivityPath?.toFile()
				val procedureReturn = xmlMapper.readValue(file, ProcedureReturn::class.java)
				val connectionId = if (procedureReturn.connectionId?.isNotBlank() == true) {
					procedureReturn.connectionId
				} else {
					"Completed"
				}
				return connectionId
			}

			TransformMode.WA -> {
				val wait = xmlMapper.readValue(currentSession.otherActivityPath?.toFile(), Wait::class.java)
				val options = wait.commands?.items
					?.map { cmd -> cmd.value!! }
					?: emptyList()
				return showChoiceDialog(options, "Choose action for: ${wait.referenceName}") ?: ""
			}

			TransformMode.FM -> {
				val form = xmlMapper.readValue(currentSession.otherActivityPath?.toFile(), Form::class.java)
				val options = form.commands?.activityCommands
					?.map { cmd -> cmd.value!! }
					?: emptyList()
				return showChoiceDialog(options, "Choose action for: ${form.referenceName}") ?: ""
			}

			TransformMode.PROCEDURE_RETURN, TransformMode.OTHER -> {
				return ""
			}

		}
	}


	private fun stripStringLiterals(s: String): String {
		val out = StringBuilder(s.length)
		var i = 0
		while (i < s.length) {
			val ch = s[i]
			if (ch == '\'' || ch == '"') {
				out.append(' ')
				i++
				while (i < s.length) {
					val d = s[i]
					out.append(' ')
					i++
					if (d == ch) break
				}
			} else {
				out.append(ch)
				i++
			}
		}
		return out.toString()
	}


	/**
	 * Проверка «умным» правилом: абсолютен ли путь или «якорен» функцией
	 */
	private fun isOkBySmartRule(expr: String, opt: SmartOptions): Boolean {
		val t = expr.trim()
		if (t.isEmpty()) return true

		val clean = stripStringLiterals(t)

		// Явно абсолютные пути
		if (clean.startsWith("/")) return true         // /... или //...
		// Абсолютный путь как аргумент функции: ищем '/' сразу после начала/скобки/запятой
		if (Regex("(^|[,(])\\s*/").containsMatchIn(clean)) return true

		// root()/..., doc()/..., document()/...
		if (Regex("\\b(?:fn:)?(?:root|doc(?:ument)?)\\s*\\(").containsMatchIn(clean) && clean.contains(")")) {
			return true
		}

		// «Короткий» доступ к атрибуту: @id
		if (opt.allowAttributeShortcut && Regex("^\\s*@[\\w:.-]+\\s*(\\|\\s*@[\\w:.-]+\\s*)*\$").matches(clean)) {
			return true
		}

		// Явные относительные конструкции — считаем нарушением (если не разрешены)
		if (!opt.allowDot && (clean == "." || clean.startsWith("./"))) return false
		if (!opt.allowDotDot && (clean.startsWith(".."))) return false

		// Иначе — относительное выражение
		return false
	}

	/**
	 * Ищет все xsl:value-of/@select, которые НЕ проходят умную проверку.
	 */
	private fun collectBadValueOfSelectsSmart(xsltText: String, opt: SmartOptions): List<ValueOfWarning> {
		val selectRe = Regex(
			"""<(?:xsl:)?(?:value-of|copy-of|for-each|apply-templates|sort|attribute|param|with-param)\b[^>]*\bselect\s*=\s*(["'])(.*?)\1""",
			setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
		)
		val testRe = Regex(
			"""<(?:xsl:)?(?:if|when)\b[^>]*\btest\s*=\s*(["'])(.*?)\1""",
			setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
		)
		val useRe = Regex(
			"""<(?:xsl:)?key\b[^>]*\buse\s*=\s*(["'])(.*?)\1""",
			setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
		)
		val valueRe = Regex(
			"""<(?:xsl:)?number\b[^>]*\bvalue\s*=\s*(["'])(.*?)\1""",
			setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
		)
		val matchRe = Regex(
			"""<(?:xsl:)?template\b[^>]*\bmatch\s*=\s*(["'])(.*?)\1""",
			setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
		)
		val useWhenRe = Regex(
			"""\buse-when\s*=\s*(["'])(.*?)\1""",
			RegexOption.IGNORE_CASE
		)
		val patterns = listOf(
			selectRe,   // …/@select
			testRe,     // …/@test
			useRe,      // xsl:key/@use
			valueRe,    // xsl:number/@value
			matchRe,    // xsl:template/@match
			useWhenRe   // …/@use-when (XSLT 3.0)
		)
		val matches = patterns
			.asSequence()
			.flatMap { it.findAll(xsltText) }
			.sortedBy { it.range.first }
		val out = mutableListOf<ValueOfWarning>()
		for (m in matches) {
			val exprGroup = m.groups[2] ?: continue
			val raw = exprGroup.value
			val startOffset = exprGroup.range.first
			if (!isOkBySmartRule(raw, opt)) {
				val before = xsltText.substring(0, startOffset)
				val line = before.count { it == '\n' } + 1
				val lastNl = before.lastIndexOf('\n')
				val col = if (lastNl >= 0) startOffset - lastNl else startOffset + 1
				out.add(ValueOfWarning(exprGroup.range, line, col, raw))
			}
		}
		return out
	}


	/** Старт и конец (исключительно) строки lineIdx (0-based) в тексте */
	private fun lineBounds(text: String, lineIdx: Int): IntRange {
		var start = 0
		repeat(lineIdx) {
			val nl = text.indexOf('\n', start)
			if (nl < 0) return (text.length..text.length)
			start = nl + 1
		}
		val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
		return start until end
	}


	/** Смещает в абсолютный offset; colIdx может быть 0 при loc.columnNumber=0 */
	private fun offsetFor(text: String, lineIdx: Int, colIdx: Int): Int {
		val lb = lineBounds(text, lineIdx)
		val base = lb.first
		// защищаемся от выхода за границы строки
		return (base + colIdx).coerceIn(lb.first, lb.last)
	}


	/** Возвращает диапазон символов, который стоит подчеркнуть для ошибки Saxon */
	private fun computeXsltErrorRange(xslt: String, lineNo: Int, colNo: Int): IntRange {
		val lineIdx = (lineNo - 1).coerceAtLeast(0)
		val colIdx = (colNo - 1).coerceAtLeast(0)

		// Если колонка известна (>0) — хотя бы 1 символ там
		if (colNo > 0) {
			val pos = offsetFor(xslt, lineIdx, colIdx)
			return pos..(pos + 1)
		}

		// col==0 → эвристика: подсветить значение match|select|test|use-when в этой строке
		val lb = lineBounds(xslt, lineIdx)
		val line = xslt.substring(lb.first, lb.last)
		val m = Regex("""\b(match|select|test|use-when)\s*=\s*(["'])(.*?)\2""")
			.find(line)
		if (m != null) {
			val valRangeInLine = m.groups[3]!!.range // только содержимое в кавычках
			val start = lb.first + valRangeInLine.first
			val endEx = lb.first + valRangeInLine.last + 1
			return start until endEx
		}

		// запасной вариант — первый символ тега в строке
		val lt = line.indexOf('<')
		val pos = if (lt >= 0) lb.first + lt else lb.first
		return pos..(pos + 1)
	}


	private fun appendBadSelectWarnings(
		session: DocSession,
		status: StringBuilder,
	) {
		val smart = SmartOptions(
			allowAttributeShortcut = true,
			allowDot = false,
			allowDotDot = false
		)
		val bad = collectBadValueOfSelectsSmart(session.xsltArea.text, smart)
		session.xsltBadSelectRanges = bad.map { it.range }
		bad.forEach {
			status.append(
				"WARNING in XSLT [line=${it.line},col=${it.col}]: xsl:value-of select='${it.raw}' не является абсолютным/якорным.\n"
			)
		}
		highlightAllMatches(session.xsltArea, currentQuery, false)
		Platform.runLater { redrawXsltOverlay(session) }
	}


	/** Вызывает перерисовку при скролле/изменениях размера/текста */
	private fun hookOverlayRedraw(s: DocSession) {
		val area = s.xsltArea
		val overlay = s.xsltOverlay ?: return

		var dirty = true
		val timer = object : AnimationTimer() {
			override fun handle(now: Long) {
				if (dirty) {
					dirty = false
					redrawXsltOverlay(s)
				}
			}
		}
		timer.start()

		val markDirty: () -> Unit = { dirty = true }

		overlay.widthProperty().addListener { _, _, _ -> markDirty() }
		overlay.heightProperty().addListener { _, _, _ -> markDirty() }

		area.estimatedScrollXProperty().addListener { _, _, _ -> markDirty() }
		area.estimatedScrollYProperty().addListener { _, _, _ -> markDirty() }
		area.widthProperty().addListener { _, _, _ -> markDirty() }
		area.heightProperty().addListener { _, _, _ -> markDirty() }
		area.textProperty().addListener { _, _, _ -> markDirty() }
	}


	/** Главный рендер: ошибки красным, предупреждения оранжевым */
	private fun redrawXsltOverlay(s: DocSession) {
		val area = s.xsltArea
		val overlay = s.xsltOverlay ?: return
		val gc = overlay.graphicsContext2D

		// Очистка
		gc.clearRect(0.0, 0.0, overlay.width, overlay.height)

		// Собираем предупреждения (включая «умные» предупреждения про select)
		val warnRanges = s.xsltWarningRanges + s.xsltBadSelectRanges
		warnRanges.forEach { r -> drawUnderlineForRange(area, overlay, r.first, r.last + 1, Color.ORANGE) }
		s.xsltSyntaxErrorRanges.forEach { r -> drawUnderlineForRange(area, overlay, r.first, r.last + 1, Color.RED) }
	}

	/** Рисует подчёркивание для диапазона, разбивая по параграфам */
	private fun drawUnderlineForRange(
		area: CodeArea,
		overlay: Canvas,
		start: Int,
		endEx: Int,
		color: Color,
	) {
		if (start >= endEx) return

		val sPos = area.offsetToPosition(start, TwoDimensional.Bias.Forward)
		val ePos = area.offsetToPosition(endEx, TwoDimensional.Bias.Backward)
		val gc = overlay.graphicsContext2D

		for (par in sPos.major..ePos.major) {
			val parStart = if (par == sPos.major) start else area.getAbsolutePosition(par, 0)
			val parEnd = if (par == ePos.major) endEx else area.getAbsolutePosition(par, area.getParagraphLength(par))
			if (parStart >= parEnd) continue

			val bScreenOpt = area.getCharacterBoundsOnScreen(parStart, parEnd)
			if (!bScreenOpt.isPresent) continue
			val bScreen = bScreenOpt.get()


			// screen -> scene -> overlay
			val root = overlay.scene.root
			val bScene = root.screenToLocal(bScreen)
			val b = overlay.sceneToLocal(bScene)

			drawZigZag(gc, b.minX, b.maxX, b.maxY + UNDER_OFFSET, color)
		}
	}

	/** Треугольная волна */
	private fun drawZigZag(
		gc: GraphicsContext,
		x0: Double,
		x1: Double,
		y: Double,
		color: Color,
	) {
		val step = UNDER_STEP
		val amp = UNDER_AMP
		if (x1 - x0 <= 1.0) return

		gc.stroke = color
		gc.lineWidth = UNDER_WIDTH
		gc.beginPath()
		var x = x0
		var sign = 1.0
		gc.moveTo(x, y)

		while (x + step <= x1) {
			val mid = x + step / 2.0
			gc.lineTo(mid, y - amp * sign)
			gc.lineTo(x + step, y)
			sign = -sign
			x += step
		}
		// Хвост
		if (x < x1) {
			val mid = (x + x1) / 2.0
			gc.lineTo(mid, y - amp * sign)
			gc.lineTo(x1, y)
		}
		gc.stroke()
	}


	/**
	 * Shows a modal dialog with validation/transformation status,
	 * и позволяет закрыть его по нажатию ESC.
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
		val scene = Scene(box, 500.0, 300.0).apply {
			setOnKeyPressed { ev ->
				if (ev.code == KeyCode.ESCAPE) {
					dialog.close()
				}
			}
		}

		dialog.scene = scene
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
			showStatus(owner, "Failed to build XPath")
			return
		}
		searchDialog?.let { dlg ->
			(dlg.scene.lookup("#xpathField") as TextField).text = meta.xpath
			dlg.toFront()
			dlg.requestFocus()
			return
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
			cb.items.addAll(opts)
			cb.value = opts[0]
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
				val selector = compiler.compile(expr).load()
				selector.contextItem = doc
				val result = selector.evaluate()

				val out = buildString {
					for (item in result) {
						when (item) {
							is XdmNode -> {
								val sw = StringWriter()
								val serializer = proc.newSerializer(sw).apply {
									setOutputProperty(Serializer.Property.METHOD, "xml")
									setOutputProperty(Serializer.Property.INDENT, "yes")
									setOutputProperty(Serializer.Property.OMIT_XML_DECLARATION, "yes")
								}
								serializer.serializeNode(item)
								append(sw.toString().trim()).append("\n\n")
							}
							else -> append(item.stringValue).append('\n')
						}

					}
				}.ifBlank { "— no results —" }

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
			sc.setOnKeyPressed {
				if (it.code == KeyCode.ESCAPE) {
					dlg.close()
				}
			}
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


	private fun highlightTagPair(area: CodeArea, caretPos: Int) {
		val text = area.text
		if (text.isEmpty()) {
			return
		}

		val openTagRegex = Regex("<([A-Za-z_][\\w:.-]*)[^>/]*?>")
		val closeTagRegex = Regex("</([A-Za-z_][\\w:.-]*)\\s*>")
		val selfClosingRegex = Regex("<([A-Za-z_][\\w:.-]*)[^>]*/>")

		val before = text.lastIndexOf('<', caretPos).takeIf { it >= 0 } ?: return
		val after = text.indexOf('>', before).takeIf { it >= 0 } ?: return
		val fragment = text.substring(before, after + 1)

		val ranges = mutableListOf<IntRange>()

		val mOpen = openTagRegex.matchEntire(fragment)
		val mClose = closeTagRegex.matchEntire(fragment)

		if (mOpen != null) {
			// курсор на открывающем
			val tagName = mOpen.groupValues[1]
			var depth = 0
			val matcher = Regex("</?$tagName\\b[^>]*?/?>")

			val all = matcher.findAll(text, after + 1)
			for (m in all) {
				when {
					m.value.startsWith("</") -> {
						if (depth == 0) {
							ranges += (before..after)
							ranges += m.range
							break
						} else depth--
					}

					selfClosingRegex.matches(m.value) -> {
						// игнорируем самозакрывающий
					}

					else -> {
						depth++
					}
				}
			}
		} else if (mClose != null) {
			// курсор на закрывающем
			val tagName = mClose.groupValues[1]
			var depth = 0
			val matcher = Regex("</?$tagName\\b[^>]*?/?>")

			val all = matcher.findAll(text.substring(0, before))
			for (m in all.toList().asReversed()) {
				when {
					m.value.startsWith("</") -> depth++
					selfClosingRegex.matches(m.value) -> {
						// игнорируем самозакрывающий
					}

					else -> {
						if (depth == 0) {
							ranges += m.range
							ranges += (before..after)
							break
						} else depth--
					}
				}
			}
		}

		// стандартная подсветка
		val base = computeSyntaxHighlightingChars(text)
		// доп. подсветка для парных тегов
		val extra = MutableList(text.length) { mutableListOf<String>() }
		for (r in ranges) {
			for (i in r) {
				if (i in extra.indices) {
					extra[i].add("tag-match-highlight")
				}
			}
		}

		// пересобираем стили
		val builder = StyleSpansBuilder<Collection<String>>()
		var prev: List<String>? = null
		var runStart = 0

		for (i in text.indices) {
			val merged = base[i] + extra[i]
			if (merged != prev) {
				if (prev != null) {
					builder.add(prev, i - runStart)
				}
				prev = merged
				runStart = i
			}
		}
		if (prev != null) {
			builder.add(prev, text.length - runStart)
		}

		area.setStyleSpans(0, builder.create())
	}


	private fun installFolding(area: CodeArea) {
		val lineNoFactory = LineNumberFactory.get(area)

		area.paragraphGraphicFactory = IntFunction { line ->
			val lineNo = lineNoFactory.apply(line)

			val paragraphText = if (line < area.paragraphs.size) area.getParagraph(line).text else ""
			val openTag = Regex("^\\s*<([A-Za-z_][\\w:.-]*)[^>]*?>").find(paragraphText)
			val canFold = openTag != null

			// этот рядок — старт свёрнутого участка?
			val isFoldedStart = foldedParagraphs.contains(line)

			val marker: Node = if (canFold) {
				Label(if (isFoldedStart) "+" else "–").apply {
					// одинаковый внешний вид для + и –
					styleClass.setAll("fold-glyph")
					// клик по индикатору — сложить/разложить
					setOnMouseClicked { toggleFold(area, line) }
					// компактная ширина, чтобы оказаться в том же месте, что и «плюс»
					prefWidth = 12.0
					minWidth = 12.0
					maxWidth = 12.0
				}
			} else {
				Region().apply {
					prefWidth = 12.0
					minWidth = 12.0
					maxWidth = 12.0
				}
			}

			// порядок: [индикатор] [номер строки]
			HBox(4.0, marker, lineNo).apply { alignment = Pos.CENTER_LEFT }
		}
	}


	private fun toggleFold(area: CodeArea, line: Int) {
		val text = area.text
		val startOffset = area.getAbsolutePosition(line, 0)

		val openMatch = Regex("<([A-Za-z_][\\w:.-]*)[^>]*?>").find(text, startOffset) ?: return
		val tagName = openMatch.groupValues[1]
		val closeMatch = Regex("</$tagName\\s*>").find(text, openMatch.range.last) ?: return

		val startPar = area.offsetToPosition(openMatch.range.first, Forward).major
		val endPar = area.offsetToPosition(closeMatch.range.last, Forward).major

		if (foldedParagraphs.contains(startPar)) {
			area.unfoldParagraphs(startPar) // тут по API достаточно стартового параграфа
			foldedParagraphs.remove(startPar)
		} else {
			area.foldParagraphs(startPar, endPar) // нужно передать start и end
			foldedParagraphs.add(startPar)
		}

		// обновим гуттер, чтобы на этой строке «–» сменился на «+» и наоборот
		installFolding(area)
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
			val childCounters: MutableMap<String, Int> = hashMapOf(),
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
			if (lt < 0) {
				break
			}

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
		when {
			(xsltPath != null) -> {
				tab.text = "${xsltPath?.parent?.fileName} / ${xsltPath?.fileName}"
				tab.tooltip = Tooltip(xsltPath?.absolutePathString())
			}

			(brPath != null) -> {
				tab.text = "${brPath?.parent?.fileName} / ${brPath?.fileName}"
				tab.tooltip = Tooltip(brPath?.absolutePathString())
			}
		}
	}


	private fun <T> runWithProgress(
		owner: Stage,
		title: String,
		task: Task<T>,
		onDone: (T?) -> Unit = {},
	) {
		val bar = ProgressBar().apply { prefWidth = 380.0 }
		val msg = Label("Starting…")
		bar.progressProperty().bind(task.progressProperty())
		msg.textProperty().bind(task.messageProperty())
		val cancelBtn = Button("Cancel").apply { setOnAction { task.cancel() } }
		val box = VBox(10.0, msg, bar, HBox(10.0, cancelBtn)).apply {
			padding = Insets(14.0)
			alignment = Pos.CENTER_LEFT
		}
		val dlg = Stage().apply {
			initOwner(owner)
			initModality(Modality.WINDOW_MODAL)
			this.title = title
			scene = Scene(box)
		}
		task.setOnSucceeded {
			dlg.close()
			onDone(task.value)
		}
		task.setOnFailed {
			dlg.close()
			showStatus(owner, "Operation failed:\n${task.exception?.message}")
		}
		task.setOnCancelled { dlg.close() }
		Thread(task, "progress-task").apply { isDaemon = true }.start()
		dlg.show()
	}


	// ───────────────── BR → TreeView ─────────────────
	private fun toTreeItem(node: Any): TreeItem<String> = when (node) {
		is Connective -> {
			TreeItem(kindOf(node.type)).apply {
				node.predicates?.forEach { children.add(toTreeItem(it)) }
				node.quantifiers?.forEach { children.add(toTreeItem(it)) }
				node.connectives?.forEach { children.add(toTreeItem(it)) }
			}
		}

		is Quantifier -> {
			TreeItem(kindOf(node.type)).apply {
				node.variableDefinition?.let { children.add(toTreeItem(it)) }
				node.predicates?.forEach { children.add(toTreeItem(it)) }
				node.quantifiers?.forEach { children.add(toTreeItem(it)) }
				node.connectives?.forEach { children.add(toTreeItem(it)) }
			}
		}

		is Predicate -> {
			TreeItem(kindOf(node.type)).apply {
				node.variables.forEach { v -> v.value.let { children.add(TreeItem(it)) } }
				node.constant?.value?.let { children.add(TreeItem(it)) }
			}
		}

		is VariableDefinition -> {
			TreeItem(node.name).apply {
				node.xpath?.value?.let { children.add(TreeItem(it)) }
			}
		}

		else -> {
			TreeItem(node.toString())
		}
	}


	private fun expandAll(item: TreeItem<*>) {
		item.isExpanded = true
		item.children.forEach { expandAll(it) }
	}


	/** Predicate (в т.ч. True/False/TextEquality/TextInequality) */
	private fun evaluateBR(xml: String, root: Connective): Boolean {
		val proc = Processor(false)
		val compiler = proc.newXPathCompiler()
		val doc: XdmNode = proc.newDocumentBuilder().build(StreamSource(StringReader(xml)))
		return evalConnective(root, compiler, doc)
	}


	private fun kindOf(type: String?): String =
		type?.lowercase()?.split(", ")?.first()?.substringAfterLast(".") ?: ""


	private fun xpathToValues(expr: String, compiler: XPathCompiler, doc: XdmNode): List<String> {
		val selector = compiler.compile(expr).load()
		selector.contextItem = doc
		val result = selector.evaluate()
		return result.map { it.stringValue }
	}


	private fun evalPredicate(
		p: Predicate,
		compiler: XPathCompiler,
		doc: XdmNode,
		vmap: Map<String, String>,
	): Boolean {
		return when (kindOf(p.type)) {
			"true" -> {
				true
			}

			"false" -> {
				false
			}

			"textequality" -> {
				val vars = p.variables.map { it.value }
				val constVal = p.constant?.value ?: ""

				when {
					vars.size == 1 && constVal.isNotEmpty() -> {
						(vmap[vars[0]] ?: "") == constVal
					}

					vars.size == 2 -> {
						(vmap[vars[0]] ?: "") == (vmap[vars[1]] ?: "")
					}

					else -> {
						false
					}
				}
			}

			"textinequality" -> {
				val vars = p.variables.map { it.value }
				val constVal = p.constant?.value ?: ""
				when (vars.size) {
					1 -> {
						(vmap[vars[0]] ?: "").trim() != constVal.trim()
					}

					2 -> {
						(vmap[vars[0]] ?: "").trim() != (vmap[vars[1]] ?: "").trim()
					}

					else -> {
						false
					}
				}
			}

			"numberequality" -> {
				val vars = p.variables.map { it.value }
				val constVal = p.constant?.value?.toDoubleOrNull()
				if (vars.size == 1 && constVal != null) {
					(vmap[vars[0]] ?: "").toDoubleOrNull() == constVal
				} else {
					false
				}
			}

			"numberinequality" -> {
				val vars = p.variables.map { it.value }
				val constVal = p.constant?.value?.toDoubleOrNull()
				if (vars.size == 1 && constVal != null) {
					(vmap[vars[0]] ?: "").toDoubleOrNull() != constVal
				} else {
					false
				}
			}

			"greaterthan" -> {
				val vars = p.variables.map { it.value }
				val constVal = p.constant?.value?.toDoubleOrNull()
				if (vars.size == 1 && constVal != null) {
					(vmap[vars[0]] ?: "").toDoubleOrNull()?.let { it > constVal } ?: false
				} else false
			}

			"lessthan" -> {
				val vars = p.variables.map { it.value }
				val constVal = p.constant?.value?.toDoubleOrNull()
				if (vars.size == 1 && constVal != null) {
					(vmap[vars[0]] ?: "").toDoubleOrNull()?.let { it < constVal } ?: false
				} else {
					false
				}
			}

			else -> {
				false
			}
		}
	}


	private fun evalQuantifier(
		q: Quantifier,
		compiler: XPathCompiler,
		doc: XdmNode,
		parentVars: Map<String, String> = emptyMap(),
	): Boolean {
		val vd = q.variableDefinition ?: return false
		val xpath = vd.xpath?.value ?: return false

		val values = xpathToValues(xpath, compiler, doc)
		val preds = q.predicates.orEmpty()
		val quants = q.quantifiers.orEmpty()
		val conns = q.connectives.orEmpty()

		fun okFor(value: String): Boolean {
			val vmap = parentVars + mapOf(vd.name to value)
			val predsOk = preds.all { evalPredicate(it, compiler, doc, vmap) }
			val quantsOk = quants.all { evalQuantifier(it, compiler, doc, vmap) }
			val connsOk = conns.all { evalConnective(it, compiler, doc, vmap) }
			return predsOk && quantsOk && connsOk
		}

		return when (kindOf(q.type)) {
			"some", "exists", "the" -> {
				values.any(::okFor)
			}

			"all", "forall" -> {
				values.isNotEmpty() && values.all(::okFor)
			}

			"no" -> {
				values.none(::okFor)
			}

			"exactlyone" -> {
				values.count(::okFor) == 1
			}

			else -> false
		}
	}


	private fun evalConnective(
		c: Connective,
		compiler: XPathCompiler,
		doc: XdmNode,
		vmap: Map<String, String> = emptyMap(),
	): Boolean {
		val preds = c.predicates.orEmpty()
		val quants = c.quantifiers.orEmpty()
		val conns = c.connectives.orEmpty()

		val predsAll = preds.all { evalPredicate(it, compiler, doc, vmap) }
		val predsAny = preds.any { evalPredicate(it, compiler, doc, vmap) }
		val quantsAll = quants.all { evalQuantifier(it, compiler, doc, vmap) }
		val quantsAny = quants.any { evalQuantifier(it, compiler, doc, vmap) }
		val connsAll = conns.all { evalConnective(it, compiler, doc, vmap) }
		val connsAny = conns.any { evalConnective(it, compiler, doc, vmap) }

		return when (kindOf(c.type)) {
			"and" -> {
				predsAll && quantsAll && connsAll
			}

			"or" -> {
				predsAny || quantsAny || connsAny
			}

			"not", "no" -> {
				!(predsAny || quantsAny || connsAny)
			}

			"some", "exists",
			"the",
			-> {
				predsAny || quantsAny || connsAny
			}

			"all", "forall" -> {
				predsAll && quantsAll && connsAll
			}

			else -> {
				predsAll
			}
		}
	}


	private fun goToNextActivity() {
		setNextActivity(null)
	}


	/**
	 * Работает с [currentSession]
	 */
	private fun setNextActivity(incomeResult: String?) {
		val selectedActivityPath = when (currentSession.mode) {
			TransformMode.XSLT -> currentSession.xsltPath
			TransformMode.BR -> currentSession.brPath
			TransformMode.ST,
			TransformMode.SV,
			TransformMode.PR,
			TransformMode.PROCEDURE_RETURN,
			TransformMode.WA,
			TransformMode.FM,
			TransformMode.OTHER,
			-> currentSession.otherActivityPath
		} ?: return

		val result = doTransform(currentStage)

		val exitName = when (currentSession.mode) {
			in arrayOf(TransformMode.BR, TransformMode.ST, TransformMode.WA, TransformMode.FM) -> result
			in arrayOf(TransformMode.PR, TransformMode.PROCEDURE_RETURN) -> incomeResult ?: "Completed"
			else -> null
		}

		val nextActivityName = LayoutUtil(currentSession)
			.getNextActivity(selectedActivityPath, currentSession.mode, exitName)
			?: return

		val nextActivityDir = selectedActivityPath.parent?.parent?.resolve(nextActivityName)
		val nextActivityPropertiesPath = nextActivityDir?.resolve("Properties.xml")
			?: return

		val nextActivityType = LayoutUtil.getActivityType(nextActivityPropertiesPath.toFile())

		if (currentSession.mode == TransformMode.XSLT) {
			val dataDocsOutputs = getDataDocsInOut(nextActivityPropertiesPath.toFile())
				.filter { it.access in arrayOf("InOut", "Input") }
				.map { it.referenceName }
   				.distinct()
			if (nextActivityType in arrayOf(ActivityType.DATA_MAPPING, ActivityType.DATA_SOURCE)) {
				currentSession.dataDocs = replaceDataDocsInString(currentSession.dataDocs!!, result, dataDocsOutputs)
			}
		}

		processNextActivity(nextActivityPropertiesPath, nextActivityType, nextActivityDir)
	}


	private fun getDataDocsInOut(propertyFile: File): List<ReferredDocument> {
		val type = LayoutUtil.getActivityType(propertyFile)
		when (type) {
			ActivityType.BIZ_RULE -> {
				return xmlMapper.readValue(propertyFile, BizRule::class.java).referredDocuments.documents
					.map { ReferredDocument(it.referenceName, it.access) }
			}

			ActivityType.DATA_MAPPING -> {
				return xmlMapper.readValue(propertyFile, DataMapping::class.java).referredDocuments?.items
					?.map { ReferredDocument(it.referenceName, it.access) }
					?: emptyList()
			}

			ActivityType.DATA_SOURCE -> {
				return xmlMapper.readValue(propertyFile, DataSource::class.java).referredDocuments?.referredDocuments
					?.map { ReferredDocument(it.referenceName!!, it.access!!) }
					?: emptyList()
			}

			ActivityType.SET_VALUE -> {
				return xmlMapper.readValue(propertyFile, SetValueActivity::class.java).referredDocuments?.documents
					?.map { ReferredDocument(it.referenceName!!, it.access!!) }
					?: emptyList()
			}

			else -> {
				return emptyList()
			}
		}
	}


	private fun mergeMockWithDataDocs(mockXml: String, dataDocsXml: String, wanted: List<String>): String {
		val dbf = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
		val builder = dbf.newDocumentBuilder()

		fun parse(xml: String) = builder.parse(xml.byteInputStream(Charsets.UTF_8))

		val mockDoc = parse(mockXml)
		val dataDoc = parse(dataDocsXml)

		val mockRoot = mockDoc.documentElement
		val dataRoot = dataDoc.documentElement

		fun findChild(root: Element, name: String): Element? {
			val nodes = root.childNodes
			for (i in 0 until nodes.length) {
				val n = nodes.item(i)
				if (n is Element) {
					val ln = n.localName ?: n.nodeName
					if (ln == name) return n
				}
			}
			return null
		}

		for (docName in wanted) {
			if (findChild(mockRoot, docName) == null) {
				val src = findChild(dataRoot, docName)
				val nodeToAdd = if (src != null) mockDoc.importNode(src, true) else mockDoc.createElement(docName)
				mockRoot.appendChild(nodeToAdd)
			}
		}

		val tf = TransformerFactory.newInstance().newTransformer().apply {
			setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
			setOutputProperty(OutputKeys.INDENT, "yes")
		}
		val sw = StringWriter()
		tf.transform(DOMSource(mockDoc), StreamResult(sw))
		return sw.toString()
	}


	/**
	 * Собирает <Data> только из указанных датадоков, в порядке wantedDocs.
	 *
	 * @param xmlContent исходный XML как строка
	 * @param wantedDocs список требуемых имен узлов (например ["ApplicationData","DocumentCodebook"])
	 * @param createEmptyIfMissing если true — добавит пустой узел, когда нужный не найден в исходнике
	 */
	private fun extractNeededDataDocs(
		xmlContent: String,
		wantedDocs: List<String>,
		createEmptyIfMissing: Boolean = false,
	): String {
		val dbf = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
		val srcDoc = dbf.newDocumentBuilder()
			.parse(ByteArrayInputStream(xmlContent.toByteArray(Charsets.UTF_8)))
		srcDoc.documentElement.normalize()

		val destDoc = dbf.newDocumentBuilder().newDocument()
		val outRoot = destDoc.createElement("Data")
		destDoc.appendChild(outRoot)

		val srcRoot = srcDoc.documentElement
		val children = srcRoot.childNodes

		// сохраняем порядок, заданный списком wantedDocs
		for (wanted in wantedDocs) {
			var found = false
			for (i in 0 until children.length) {
				val n = children.item(i)
				if (n.nodeType == Element.ELEMENT_NODE) {
					val name = (n as Element).localName ?: n.nodeName
					if (name == wanted) {
						outRoot.appendChild(destDoc.importNode(n, true))
						found = true
					}
				}
			}
			if (!found && createEmptyIfMissing) {
				outRoot.appendChild(destDoc.createElement(wanted))
			}
		}

		val tf = TransformerFactory.newInstance().newTransformer().apply {
			setOutputProperty(OutputKeys.INDENT, "yes")
			setOutputProperty(OutputKeys.ENCODING, "UTF-8")
		}
		return StringWriter().use { w ->
			tf.transform(DOMSource(destDoc), StreamResult(w))
			w.toString()
		}
	}


	private fun replaceDataDocsInString(
		xmlContent: String,
		newDataDocsXml: String,
		wantedDocs: List<String>,
	): String {
		val dbf = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
		// Исходный документ
		val srcDoc = dbf.newDocumentBuilder()
			.parse(ByteArrayInputStream(xmlContent.toByteArray(Charsets.UTF_8)))
		srcDoc.documentElement.normalize()
		val srcRoot = srcDoc.documentElement
		// Новые датадоки
		val newDoc = dbf.newDocumentBuilder()
			.parse(ByteArrayInputStream(newDataDocsXml.toByteArray(Charsets.UTF_8)))
		newDoc.documentElement.normalize()
		val newRoot = newDoc.documentElement
		// Удаляем в исходнике только те датадоки, которые указаны
		val toRemove = mutableListOf<DomNode>()
		val children = srcRoot.childNodes
		for (i in 0 until children.length) {
			val n = children.item(i)
			if (n.nodeType == DomNode.ELEMENT_NODE && wantedDocs.contains(n.nodeName)) {
				toRemove.add(n)
			}
		}
		toRemove.forEach { srcRoot.removeChild(it) }
		// Из нового документа берём только нужные датадоки и вставляем в исходный
		val newChildren = newRoot.childNodes
		for (i in 0 until newChildren.length) {
			val n = newChildren.item(i)
			if (n.nodeType == DomNode.ELEMENT_NODE && wantedDocs.contains(n.nodeName)) {
				srcRoot.appendChild(srcDoc.importNode(n, true))
			}
		}
		// В строку
		val transformer = TransformerFactory.newInstance().newTransformer().apply {
			setOutputProperty(OutputKeys.INDENT, "yes")
			setOutputProperty(OutputKeys.ENCODING, "UTF-8")
		}
		return StringWriter().use { w ->
			transformer.transform(DOMSource(srcDoc), StreamResult(w))
			w.toString()
		}
	}


	private fun applySetValues(propertiesFile: File, dataDocsXml: String): String {
		val xmlMapper = XmlMapper()
		val activity: SetValueActivity = xmlMapper.readValue(propertiesFile, SetValueActivity::class.java)
		// Парсим входной XML-текст
		val dbf = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
		val doc: Document = dbf.newDocumentBuilder()
			.parse(InputSource(StringReader(dataDocsXml)))
		val root = doc.documentElement               // <Data> или иной корень
		val rootName = root.nodeName

		val xPath = XPathFactory.newInstance().newXPath()

		activity.setValues?.items?.forEach { sv ->
			val raw = sv.xPath ?: return@forEach
			val expr = normalizeExpr(raw, rootName)
			// Атрибут?
			val atPos = expr.lastIndexOf("/@")
			if (atPos >= 0) {
				val parentExpr = expr.substring(0, atPos)
				val attrName = expr.substring(atPos + 2) // после "/@"
				// ВАЖНО: вычисляем относительно document (узел-документ) и абсолютным путём:
				val parent = xPath.evaluate(parentExpr, doc, XPathConstants.NODE) as? Element
					?: run {
						// Фолбэк: игнорировать NS — через local-name()
						val local = parentExpr.split('/').filter { it.isNotBlank() }.joinToString("/", prefix = "/") {
							if (it == rootName) it else "*[local-name()='$it']"
						}
						xPath.evaluate(local, doc, XPathConstants.NODE) as? Element
					}
				parent?.setAttribute(attrName, sv.otherwiseValue?.valueConstant ?: return@forEach)
			} else {
				// Узел-элемент
				val node = (xPath.evaluate(expr, doc, XPathConstants.NODE) as? Element)
					?: run {
						val local = expr.split('/').filter { it.isNotBlank() }.joinToString("/", prefix = "/") {
							if (it == rootName) it else "*[local-name()='$it']"
						}
						xPath.evaluate(local, doc, XPathConstants.NODE) as? Element
					}
				if (node != null) {
					node.textContent = sv.otherwiseValue?.valueConstant ?: return@forEach
				}
			}
		}
		// Обратно в строку
		val tf = TransformerFactory.newInstance().newTransformer().apply {
			setOutputProperty(OutputKeys.INDENT, "yes")
			setOutputProperty(OutputKeys.ENCODING, "UTF-8")
		}
		return StringWriter().use { w ->
			tf.transform(DOMSource(doc), StreamResult(w))
			w.toString()
		}
	}


	private fun normalizeExpr(expr: String, rootName: String): String {
		val t = expr.trim().removePrefix("./")
		if (t.startsWith("/")) {
			return t
		}
		return if (t.startsWith("$rootName/")) {
			"/$t"
		} else {
			"/$rootName/$t"
		}
	}


	private fun processProcedure(path: Path) {
		val file = path.resolve("Properties.xml").toFile()
		val procedureToCall = xmlMapper.readValue(file, ProcedureCall::class.java).procedureToCall
		val procedureDir = if (path.parent?.name == "MainFlow") {
			procedureToCall?.let { path.parent?.parent?.resolve("Procedures")?.resolve(it) }
		} else {
			procedureToCall?.let { path.parent?.parent?.parent?.resolve("Procedures")?.resolve(it) }
		} ?: return
		val procedureLayout = procedureDir.resolve("Layout.xml")
		val layout = xmlMapper.readValue(procedureLayout.toFile(), DiagramLayout::class.java)
		val elementRef = layout.connections?.diagramConnections
			?.first { conn -> conn.endPoints?.points?.any { it.exitPointRef == "Start" } == true }
			?.endPoints?.points
			?.first { it.exitPointRef != "Start" }
			?.elementRef
			?: return
		val firstActivityName = layout.elements?.diagramElements
			?.first { el -> el.uid == elementRef }
			?.reference
			?: ""
		val firstActivityProperties = procedureDir.resolve(firstActivityName).resolve("Properties.xml")
			?: return
		val activityType = LayoutUtil.getActivityType(firstActivityProperties.toFile())

		processNextActivity(firstActivityProperties, activityType, procedureDir.resolve(firstActivityName))
	}


	private fun processNextActivity(
		nextActivityPropertiesPath: Path,
		nextActivityType: ActivityType,
		nextActivityDir: Path,
	) {
		val nextActivityDataDocsInputs = getDataDocsInOut(nextActivityPropertiesPath.toFile())
			.filter { it.access in arrayOf("Input", "InOut") }
			.map { it.referenceName }
		val neededDataDocs = extractNeededDataDocs(currentSession.dataDocs!!, nextActivityDataDocsInputs)
		currentSession.xmlArea.replaceText(neededDataDocs)

		if (nextActivityPropertiesPath.exists()) {
			when (nextActivityType) {
				ActivityType.BIZ_RULE -> {
					currentSession.mode = TransformMode.BR
					val state = TabState(
						xmlPath = null,
						xsltPath = null,
						brPath = nextActivityPropertiesPath.absolutePathString(),
						process = null
					)
					loadTabStateIntoSession(currentSession, state)
					currentSession.xsltPath = null
					currentSession.updateTabTitle()
					brRadio.isSelected = true
				}

				ActivityType.DATA_MAPPING -> {
					currentSession.mode = TransformMode.XSLT
					val xsltFile = nextActivityDir.resolve("Mapping.xslt")
					val state = TabState(
						xmlPath = null,
						xsltPath = xsltFile.absolutePathString(),
						brPath = null,
						process = null
					)
					loadTabStateIntoSession(currentSession, state)
					currentSession.brPath = null
					currentSession.updateTabTitle()
					xsltRadio.isSelected = true
				}

				ActivityType.DATA_SOURCE -> {
					currentSession.mode = TransformMode.XSLT
					val xsltFile = nextActivityDir.resolve("MappingOutput.xslt")
					val mockPath = ensureMockXml(nextActivityDir)
						?: return
					if (mockPath.exists()) {
						val mockText = mockPath.toFile().readText()
						val wantedDocs = getDataDocsInOut(nextActivityPropertiesPath.toFile())
							.filter { it.access in arrayOf("InOut", "Input", "Output") }
							.map { it.referenceName }
							.distinct()
						val sourceDocsXml = currentSession.xmlArea.text
							.takeIf { it.isNotBlank() }
							?: (currentSession.dataDocs ?: "")
						val merged = mergeMockWithDataDocs(mockText, sourceDocsXml, wantedDocs)
						mockPath.toFile().writeText(merged)
						val state = TabState(
							xmlPath = mockPath.absolutePathString(),
							xsltPath = xsltFile.absolutePathString(),
							brPath = null,
							process = null
						)
						loadTabStateIntoSession(currentSession, state)
						currentSession.brPath = null
						currentSession.updateTabTitle()
						xsltRadio.isSelected = true
					} else {
						Platform.runLater {
							showStatus(currentStage, "Mock.xml not found in directory:\n${nextActivityDir}")
						}
					}
				}

				ActivityType.SEGMENTATION_TREE -> {
					currentSession.mode = TransformMode.ST
					currentSession.otherActivityPath = nextActivityPropertiesPath
					setNextActivity(null)
					return
				}

				ActivityType.SET_VALUE -> {
					currentSession.mode = TransformMode.SV
					currentSession.otherActivityPath = nextActivityPropertiesPath
					setNextActivity(null)
					return
				}

				ActivityType.PROCEDURE_CALL -> {
					if (activitiesDebugProcedureStack.containsKey(currentSession)) {
						activitiesDebugProcedureStack[currentSession]?.push(nextActivityPropertiesPath)
					} else {
						activitiesDebugProcedureStack[currentSession] = Stack<Path>().apply { push(nextActivityPropertiesPath) }
					}
					processProcedure(nextActivityDir)
					return
				}

				ActivityType.PROCEDURE_RETURN -> {
					if (activitiesDebugProcedureStack[currentSession]?.peek() != null) {
						currentSession.mode = TransformMode.PR
						currentSession.otherActivityPath = nextActivityPropertiesPath
						val result = doTransform(currentStage)
						currentSession.otherActivityPath = activitiesDebugProcedureStack[currentSession]?.pop()
						currentSession.mode = TransformMode.PROCEDURE_RETURN
						setNextActivity(result)
						return
					}
				}

				ActivityType.END_PROCEDURE -> {
					TODO()
				}

				ActivityType.FORM -> {
					currentSession.mode = TransformMode.FM
					currentSession.xsltPath = nextActivityPropertiesPath
					currentSession.otherActivityPath = nextActivityPropertiesPath
					setNextActivity(null)
					return
				}

				ActivityType.WAIT -> {
					currentSession.mode = TransformMode.WA
					currentSession.xsltPath = nextActivityPropertiesPath
					currentSession.otherActivityPath = nextActivityPropertiesPath
					setNextActivity(null)
					return
				}

				else -> {
					currentSession.mode = TransformMode.PROCEDURE_RETURN
					currentSession.otherActivityPath = nextActivityPropertiesPath
					setNextActivity(null)
					return
				}
			}
		}
	}


	/** «Умное» сопоставление: символы запроса должны встречаться по порядку, возможны любые вставки между ними */
	private fun smartMatch(name: String, query: String): Boolean {
		if (query.isBlank()) return true
		val rx = query.asSequence()
			.map { Regex.escape(it.toString()) }
			.joinToString(".*", prefix = ".*", postfix = ".*")
		return Regex(rx, RegexOption.IGNORE_CASE).containsMatchIn(name)
	}


	/** Рекурсивно строит отфильтрованное дерево: оставляет узлы, которые сами матчатся, или содержат потомков, которые матчатся */
	private fun buildFilteredFileTree(path: Path, query: String): TreeItem<Path> {
		fun filterRec(p: Path): TreeItem<Path>? {
			val isDir = Files.isDirectory(p)
			val selfMatches = smartMatch(p.fileName?.toString() ?: p.toString(), query)
			val childrenItems = mutableListOf<TreeItem<Path>>()

			if (isDir) {
				// Надёжный и управляемый обход с сортировкой по имени (сначала папки, потом файлы)
				var dirs = mutableListOf<Path>()
				var files = mutableListOf<Path>()
				Files.newDirectoryStream(p).use { ds ->
					for (child in ds) {
						if (Files.isDirectory(child)) {
							dirs.add(child)
						} else {
							files.add(child)
						}
					}
				}
				dirs.sortBy { it.fileName.toString().lowercase() }
				files.sortBy { it.fileName.toString().lowercase() }

				(dirs + files).forEach { ch ->
					filterRec(ch)?.let { childrenItems += it }
				}
			}

			return when {
				selfMatches -> {
					// если папка сама совпала — показываем всё её содержимое
					val fullChildren = mutableListOf<TreeItem<Path>>()
					if (isDir) {
						Files.newDirectoryStream(p).use { ds ->
							for (child in ds) {
								fullChildren += TreeItem(child).apply {
									if (Files.isDirectory(child)) {
										children.add(TreeItem<Path>()) // пустой потомок → чтобы можно было раскрывать
									}
								}
							}
						}
					}
					TreeItem(p).apply { children.addAll(fullChildren) }
				}

				childrenItems.isNotEmpty() -> TreeItem(p).apply { children.addAll(childrenItems) }
				else -> null
			}
		}

		// Корневой элемент всегда присутствует (TreeView.isShowRoot=false, так что сам root не показывается)
		return TreeItem(path).apply {
			val rootFiltered = filterRec(path)
			children.setAll(rootFiltered?.children ?: emptyList())
		}
	}


	/** Применяет фильтр к текущему processPath и разворачивает дерево для видимости результатов */
	private fun rebuildFileTreeByQuery(query: String) {
		val rootPath = processPath ?: return
		fileTree.root = if (query.isBlank()) buildFileTree(rootPath) else buildFilteredFileTree(rootPath, query)
		expandAll(fileTree.root) // у тебя уже есть expandAll(TreeItem<*>) — используем её
	}


	private fun showChoiceDialog(choices: List<String>, title: String): String? {
		var selected: String? = null

		val dlg = Stage().apply {
			initOwner(currentStage)
			initModality(Modality.WINDOW_MODAL)
			this.title = title
		}

		val listView = ListView<String>().apply {
			items.addAll(choices)
			selectionModel.selectionMode = SelectionMode.SINGLE
		}

		val okBtn = Button("OK").apply {
			isDisable = true
			setOnAction {
				selected = listView.selectionModel.selectedItem
				dlg.close()
			}
		}

		val cancelBtn = Button("Cancel").apply {
			setOnAction {
				selected = null
				dlg.close()
			}
		}

		listView.selectionModel.selectedItemProperty().addListener { _, _, newValue ->
			okBtn.isDisable = (newValue == null)
		}

		val buttons = HBox(10.0, okBtn, cancelBtn).apply { alignment = Pos.CENTER_RIGHT }
		val root = VBox(10.0, listView, buttons).apply { padding = Insets(10.0) }

		dlg.scene = Scene(root, 400.0, 300.0)
		dlg.showAndWait() // блокирует до закрытия

		return selected
	}


	/** Если в activityDir нет Mock.xml — открывает редактор и сохраняет. Возвращает путь к Mock.xml или null при отмене. */
	private fun ensureMockXml(activityDir: Path): Path? {
		val mock = activityDir.resolve("Mock.xml")
		return if (Files.exists(mock)) {
			mock
		} else {
			showMockXmlEditor(activityDir)
		}
	}


	/** Окно редактора Mock.xml для активности (папки). Сохраняет UTF-8 с BOM. */
	private fun showMockXmlEditor(activityDir: Path): Path? {
		var result: Path? = null
		val dlg = Stage().apply {
			initOwner(currentStage)
			initModality(Modality.WINDOW_MODAL)
			title = "Создайте Mock.xml для ${activityDir.name}"
		}
		val area = createHighlightingCodeArea(false).apply {
			prefWidth = 820.0
			prefHeight = 520.0
			replaceText("<ConnectorOutput>\n</ConnectorOutput>\n")
		}
		val loadBtn = Button("Load from file").apply {
			setOnAction {
				val file = createChooser("Open XML…", null, "XML Files (*.xml)", "*.xml")
					.showOpenDialog(currentStage) ?: return@setOnAction
				area.replaceText(XmlUtil.readXmlSafe(file))
			}
		}
		val pasteBtn = Button("Вставить из буфера").apply {
			setOnAction { pasteFromClipboardWithProgress(area) }
		}
		val validateBtn = Button("Проверить XML").apply {
			setOnAction {
				try {
					SAXParserFactory.newInstance().apply {
						isNamespaceAware = true
						isValidating = false
					}.newSAXParser().parse(
						InputSource(StringReader(area.text)),
						object : DefaultHandler() {}
					)
					showStatus(currentStage, "XML is well-formed.")
				} catch (e: Exception) {
					showStatus(currentStage, "XML error: ${e.message}")
				}
			}
		}
		val saveBtn = Button("Сохранить").apply {
			isDefaultButton = true
			setOnAction {
				val out = activityDir.resolve("Mock.xml").toFile()
				XmlUtil.writeXmlWithBom(out, area.text, Charsets.UTF_8)
				result = out.toPath()
				dlg.close()
			}
		}
		val cancelBtn = Button("Отмена").apply {
			isCancelButton = true
			setOnAction { result = null; dlg.close() }
		}
		val spacer = Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }
		val buttons = HBox(8.0, loadBtn, pasteBtn, validateBtn, spacer, saveBtn, cancelBtn).apply {
			alignment = Pos.CENTER_RIGHT
		}
		dlg.scene = Scene(VBox(10.0, area, buttons).apply { padding = Insets(12.0) })
		dlg.showAndWait()
		return result
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
		private const val UNDER_OFFSET = 0.0   // отступ под текстом (px)
		private const val UNDER_WIDTH = 1.0  // толщина линии
		private const val UNDER_STEP = 3.0   // горизонтальный шаг «зубцов»
		private const val UNDER_AMP = 1.0   // амплитуда (высота «зубца»)
	}
}

fun main() {
	Application.launch(XmlXsltValidatorApp::class.java)
}