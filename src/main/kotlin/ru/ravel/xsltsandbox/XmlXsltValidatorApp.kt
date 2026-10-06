package ru.ravel.xsltsandbox

import javafx.application.Application
import javafx.scene.Scene
import javafx.scene.control.TabPane
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.BorderPane
import javafx.stage.Stage
import ru.ravel.xsltsandbox.config.ConfigStore
import ru.ravel.xsltsandbox.datadocs.MockXmlEditor
import ru.ravel.xsltsandbox.debug.DebugBridge
import ru.ravel.xsltsandbox.debugger.ActivityDebugger
import ru.ravel.xsltsandbox.editor.CodeAreaSupport
import ru.ravel.xsltsandbox.editor.DataDocsViewer
import ru.ravel.xsltsandbox.editor.SearchDialog
import ru.ravel.xsltsandbox.files.FileWatcher
import ru.ravel.xsltsandbox.files.SessionFiles
import ru.ravel.xsltsandbox.models.AppConfig
import ru.ravel.xsltsandbox.models.DocSession
import ru.ravel.xsltsandbox.models.TransformMode
import ru.ravel.xsltsandbox.transform.Transformer
import ru.ravel.xsltsandbox.ui.AppIcon
import ru.ravel.xsltsandbox.ui.FileTreePanel
import ru.ravel.xsltsandbox.ui.SessionTabFactory
import ru.ravel.xsltsandbox.ui.ToolBarBuilder
import ru.ravel.xsltsandbox.xml.XPathTools
import java.io.File
import java.nio.file.Path


class XmlXsltValidatorApp : Application() {
	private val ctx = AppContext()
	private val configStore = ConfigStore()
	private lateinit var config: AppConfig

	private val support = CodeAreaSupport(ctx)
	private val search = SearchDialog(ctx, support)
	private val viewer = DataDocsViewer(support, search)
	private val xpath = XPathTools(ctx)
	private val mockEditor = MockXmlEditor(ctx, support)
	private val transformer = Transformer(ctx, support)
	private val watcher = FileWatcher(ctx, support)
	private val tabFactory = SessionTabFactory(ctx, support)
	private val files = SessionFiles(ctx, support, watcher, configStore, tabFactory::create)
	private val debugger = ActivityDebugger(ctx, transformer, files, viewer, mockEditor)
	private val toolBar = ToolBarBuilder(ctx, support, search, xpath, files, transformer, debugger)
	private val fileTreePanel = FileTreePanel(ctx, files, configStore, tabFactory::create)


	override fun init() {
		config = configStore.load()
		tabFactory.onCreated = { session ->
			session.onDebugStep = { debugger.goToNextActivity() }
			session.onDebugRun = { debugger.runDebugAllPathsBackground() }
			session.onOpenDataDocsViewer = { owner, title, text -> viewer.show(owner, title, text) }
		}
	}


	override fun stop() {
		try {
			configStore.saveFrom(ctx)
			watcher.close()
			ctx.debugBridge?.close()
		} catch (e: Exception) {
			System.err.println(e.localizedMessage)
			System.err.println(e.stackTrace)
		}
		super.stop()
	}


	override fun start(primaryStage: Stage) {
		ctx.stage = primaryStage
		AppIcon.apply(primaryStage)

		val first = initTabs()

		val root = BorderPane().apply {
			top = toolBar.build()
			center = ctx.tabPane
			left = fileTreePanel.build()
		}

		val scene = Scene(root, 1200.0, 800.0).apply {
			addEventFilter(KeyEvent.KEY_PRESSED) { event ->
				if (event.code == KeyCode.F && event.isControlDown) {
					search.showSearchWindow(primaryStage, ctx.currentArea ?: ctx.currentSession.xmlArea)
					event.consume()
				}
			}
			addEventFilter(KeyEvent.KEY_PRESSED) { event ->
				if (event.code == KeyCode.ENTER && event.isControlDown) {
					transformer.doTransform(primaryStage)
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

		val args = parameters.raw
		openInputArgs(args)

		// восстановим последнюю сессию из config (если нужно)
		if (!args.contains("--no-restore")) {
			files.restorePreviouslyOpenedFiles(config, first)
			watcher.start()
		}
	}


	/** Создаёт панель вкладок с первой вкладкой и вкладкой «+» */
	private fun initTabs(): DocSession {
		ctx.tabPane = TabPane().apply {
			tabClosingPolicy = TabPane.TabClosingPolicy.ALL_TABS
		}
		val first = tabFactory.create("Tab 1")
		ctx.tabPane.tabs.add(first.tab)
		ctx.tabPane.selectionModel.select(first.tab)
		ctx.plusTab.setOnSelectionChanged {
			if (ctx.plusTab.isSelected) {
				val created = tabFactory.create("Tab ${ctx.sessions.size + 1}")
				ctx.tabPane.tabs.add(ctx.tabPane.tabs.size - 1, created.tab)
				ctx.tabPane.selectionModel.select(created.tab)
			}
		}
		ctx.tabPane.tabs.add(ctx.plusTab)
		ctx.tabPane.selectionModel.selectedItemProperty().addListener { _, _, newTab ->
			val session = ctx.sessions[newTab] ?: return@addListener
			when (session.mode) {
				TransformMode.XSLT -> {
					ctx.xsltRadio.isSelected = true
					showXsltBox(session, true)
				}

				TransformMode.BR -> {
					ctx.brRadio.isSelected = true
					showXsltBox(session, false)
				}

				else -> showXsltBox(session, true)
			}
		}
		return first
	}


	/** Показывает XSLT-редактор (или дерево бизнес-правила) выбранной сессии */
	private fun showXsltBox(session: DocSession, xslt: Boolean) {
		session.xsltBox?.isVisible = xslt
		session.xsltBox?.isManaged = xslt
		session.brBox?.isVisible = !xslt
		session.brBox?.isManaged = !xslt
	}


	private fun openInputArgs(args: List<String>) {
		val callStack = mutableListOf<Path>()
		var fullDataDocs: File? = null
		for (index in args.indices) {
			val path = File(args.getOrNull(index + 1).toString()).toPath()
			when (args[index]) {
				"--input-xslt-path" -> files.openXsltFile(ctx.currentSession, path)
				"--input-properties-path" -> files.openBrFile(ctx.currentSession, path)
				"--input-data-path" -> files.openXmlFile(ctx.currentSession, path)
				"--debug-port" -> args.getOrNull(index + 1)?.toIntOrNull()
					?.takeIf { it in 1..65535 }
					?.let { ctx.debugBridge = DebugBridge(it) }
				"--call-stack-item" -> callStack.add(path)
				"--input-full-datadocs-path" -> fullDataDocs = path.toFile()
			}
		}
		if (callStack.isNotEmpty()) {
			debugger.setInitialCallStack(ctx.currentSession, callStack)
		}
		// После остальных аргументов: подстановка входных документов нужна уже открытой активности.
		fullDataDocs?.takeIf { it.isFile }?.let { toolBar.selectDataDocsFile(it) }
	}
}

fun main(vararg args: String) {
	Application.launch(XmlXsltValidatorApp::class.java, *args)
}