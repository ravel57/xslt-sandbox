package ru.ravel.xsltsandbox.files

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties
import javafx.application.Platform
import javafx.concurrent.Task
import javafx.scene.control.Tab
import kotlin.io.path.exists
import org.apache.commons.text.StringEscapeUtils
import org.fxmisc.richtext.CodeArea
import ru.ravel.xsltsandbox.AppContext
import ru.ravel.xsltsandbox.config.ConfigStore
import ru.ravel.xsltsandbox.editor.CodeAreaSupport
import ru.ravel.xsltsandbox.models.ActivityType
import ru.ravel.xsltsandbox.models.AppConfig
import ru.ravel.xsltsandbox.models.DocSession
import ru.ravel.xsltsandbox.models.TabState
import ru.ravel.xsltsandbox.models.TransformMode
import ru.ravel.xsltsandbox.br.RuleParser
import ru.ravel.xsltsandbox.models.bizrule.BizRule
import ru.ravel.xsltsandbox.models.segmentationtree.BusinessRule
import ru.ravel.xsltsandbox.models.bizrule.Connective
import ru.ravel.xsltsandbox.models.bizrule.Quantifier
import ru.ravel.xsltsandbox.ui.Dialogs.runWithProgress
import ru.ravel.xsltsandbox.ui.Dialogs.showStatus
import ru.ravel.xsltsandbox.ui.FileChoosers
import ru.ravel.xsltsandbox.utils.LayoutUtil
import ru.ravel.xsltsandbox.utils.TreeUtil.expandAll
import ru.ravel.xsltsandbox.utils.TreeUtil.toTreeItem
import ru.ravel.xsltsandbox.utils.XmlUtil
import ru.ravel.xsltsandbox.log.AppLog

/**
 * Загрузка и сохранение файлов сессий (XML, XSLT, бизнес-правил) и восстановление вкладок.
 */
class SessionFiles(
	private val ctx: AppContext,
	private val support: CodeAreaSupport,
	private val watcher: FileWatcher,
	private val configStore: ConfigStore,
	private val newTab: (String) -> DocSession,
) {
	private val state get() = ctx.editor

	fun openXmlFile(session: DocSession, path: Path, onLoaded: () -> Unit = {}) {
		loadFileIntoAreaAsync(session, path, session.xmlArea) {
			session.xmlPath = it
			onLoaded()
		}
	}


	fun openFormOrWaitFile(session: DocSession, path: Path): Boolean {
		val mode = when (LayoutUtil.getActivityType(path.toFile())) {
			ActivityType.FORM -> TransformMode.FM
			ActivityType.WAIT -> TransformMode.WA
			else -> return false
		}
		session.mode = mode
		loadFileIntoAreaAsync(session, path, session.xsltArea) {
			session.xsltPath = it
			ctx.refreshActivityButtons()
		}
		session.mappingPropertyFile = path
		session.otherActivityPath = path
		ctx.xsltRadio.isSelected = true
		session.updateTabTitle()
		return true
	}


	/**
	 * Открывает Properties.xml бизнес-правила в [session] в режиме BR — так же, как по клику в
	 * дереве файлов: правило разбирается, включается переключатель BR, кнопки отладки обновляются.
	 */
	fun openBrActivity(session: DocSession, path: Path): Boolean {
		if (!openBrFile(session, path)) return false
		session.mode = TransformMode.BR
		ctx.brRadio.isSelected = true
		ctx.refreshActivityButtons()
		return true
	}


	fun openXsltFile(session: DocSession, path: Path) {
		loadFileIntoAreaAsync(session, path, session.xsltArea) { loaded ->
			session.xsltPath = loaded
			session.mappingPropertyFile = loaded.parent.resolve("Properties.xml")
			session.updateTabTitle()
			ctx.refreshActivityButtons()
		}
	}


	/**
	 * Читает бизнес-правило и строит дерево в [session].
	 *
	 * @return `false`, если в дерево нечего показывать
	 */
	fun openBrFile(session: DocSession, path: Path): Boolean {
		// Properties.xml активности BR или файл бизнес-правила из BusinessRules (правила ST)
		val isBusinessRule = LayoutUtil.getActivityType(path.toFile()) == ActivityType.BUSINESS_RULE
		val xmlRule = if (isBusinessRule) {
			ctx.xmlMapper.readValue(path.toFile(), BusinessRule::class.java).xmlRule
				?: return false
		} else {
			ctx.xmlMapper.readValue(path.toFile(), BizRule::class.java).xmlRule.value
		}
		session.brPath = path
		// у правила из BusinessRules нет Properties.xml рядом: список его документов лежит в нём самом
		session.mappingPropertyFile = if (isBusinessRule) path else path.parent.resolve("Properties.xml")
		session.updateTabTitle()
		return applyBrXml(session, StringEscapeUtils.unescapeXml(xmlRule))
	}


	/**
	 * Разбирает XML бизнес-правила ([Quantifier] или [Connective]) и показывает его деревом в [session].
	 *
	 * @return `false`, если у сессии нет дерева
	 */
	fun applyBrXml(session: DocSession, innerXml: String): Boolean {
		val rootNode: Any = RuleParser.parse(ctx.xmlMapper, innerXml)

		when (rootNode) {
			is Quantifier -> {
				session.brRootQuant = rootNode
				session.brRoot = null
			}

			is Connective -> {
				session.brRoot = rootNode
				session.brRootQuant = null
			}
		}
		val tree = session.brTree ?: return false
		tree.root = toTreeItem(rootNode)
		tree.isShowRoot = true
		expandAll(tree.root)
		return true
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
		watcher.register(path, session, area)
	}


	fun loadFileIntoAreaAsync(
		session: DocSession,
		path: Path,
		area: CodeArea,
		setPath: (Path) -> Unit,
	) {
		var encoding: java.nio.charset.Charset? = null
		val task = object : Task<String>() {
			override fun call(): String {
				updateMessage("Reading ${path.fileName}…")
				updateProgress(-1.0, 1.0)
				val bytes = Files.readAllBytes(path)
				if (isCancelled) return ""
				// Декодируем целиком: кодировка определяется по началу файла (BOM), а порции по 64 КБ
				// ломали многобайтовые символы и UTF-16 после первой порции
				updateMessage("Decoding ${bytes.size / 1024} KB…")
				encoding = XmlUtil.getEncoding(bytes)
				return XmlUtil.readXmlSafe(bytes)
			}
		}

		ctx.pendingLoads++
		task.stateProperty().addListener { _, _, state ->
			if (state == javafx.concurrent.Worker.State.SUCCEEDED ||
				state == javafx.concurrent.Worker.State.FAILED ||
				state == javafx.concurrent.Worker.State.CANCELLED
			) {
				ctx.pendingLoads--
			}
		}

		runWithProgress(ctx.stage, "Opening ${path.fileName}", task) { text ->
			text ?: return@runWithProgress
			if (area === session.xsltArea) session.xsltEncoding = encoding
			else if (area === session.xmlArea) session.xmlEncoding = encoding
			state.suspendHighlighting++
			try {
				area.replaceText(text)
			} finally {
				state.suspendHighlighting--
				support.highlightAllMatches(area, state.query, area === session.resultArea)
			}
			setPath(path)
			watcher.register(path, session, area)
		}
	}


	fun restorePreviouslyOpenedFiles(config: AppConfig, first: DocSession) {
		val tabs = config.tabs
		if (tabs.isEmpty()) return
		loadTabStateIntoSession(first, tabs[0])
		for (i in 1 until tabs.size) {
			val s = newTab("Tab ${i + 1}")
			ctx.tabPane.tabs.add(ctx.tabPane.tabs.size - 1, s.tab) // перед '+'
			loadTabStateIntoSession(s, tabs[i])
		}
		val workTabs = ctx.tabPane.tabs.filter { it != ctx.plusTab }
		val toSelect = config.activeIndex.coerceIn(0, workTabs.lastIndex)
		ctx.tabPane.selectionModel.select(workTabs[toSelect])
	}


	fun loadTabStateIntoSession(session: DocSession, state: TabState) {
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
					session.mappingPropertyFile = loaded.parent.resolve("Properties.xml")
					session.updateTabTitle()
				}
			}
		}
		state.brPath?.let { p ->
			val path = Paths.get(p)
			if (Files.exists(path) && openBrFile(session, path)) {
				session.mode = TransformMode.BR
			}
		}
		state.process?.let { p ->
			openProcessDir(Paths.get(p))
		}
	}


	/** Делает [procPath] папкой процесса: дерево файлов и поиск работают от неё. false, если это не папка. */
	fun openProcessDir(procPath: Path): Boolean {
		if (!Files.isDirectory(procPath)) {
			AppLog.warn("папка процесса не найдена: $procPath")
			return false
		}
		AppLog.info("папка процесса: $procPath")
		ctx.processPath = procPath
		Platform.runLater {
			ctx.dirField.text = procPath.toAbsolutePath().toString()
			ctx.fileTreeSearch.isDisable = false
			ctx.rebuildFileTree(ctx.fileTreeSearch.text)
		}
		return true
	}


	fun saveCurrentXslt() {
		val path: Path = ctx.currentSession.xsltPath ?: run {
			val file = FileChoosers.create(
				"Save XSLT…",
				ctx.currentSession.xsltPath,
				"XSLT Files (*.xsl, *.xslt)", "*.xsl", "*.xslt"
			).showSaveDialog(ctx.stage) ?: return
			file.toPath()
		}
		Files.createDirectories(path.parent)

		val charset = ctx.currentSession.xsltEncoding ?: Charsets.UTF_8
		XmlUtil.writeXmlWithBom(
			file = path.toFile(),
			text = ctx.currentSession.xsltArea.text,
			charset = charset
		)
		// обновляем путь в сессии и подписываемся на изменения файла
		if (ctx.currentSession.xsltPath != path) {
			ctx.currentSession.xsltPath = path
			watcher.register(path, ctx.currentSession, ctx.currentSession.xsltArea)
		}
		configStore.saveFrom(ctx)
		showStatus(ctx.stage, "XSLT saved:\n$path")
	}
}
