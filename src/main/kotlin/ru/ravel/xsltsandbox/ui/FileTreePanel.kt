package ru.ravel.xsltsandbox.ui

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javafx.animation.PauseTransition
import javafx.concurrent.Task
import javafx.geometry.Insets
import javafx.scene.control.Button
import javafx.scene.control.TextField
import javafx.scene.control.TreeCell
import javafx.scene.control.TreeItem
import javafx.scene.control.TreeView
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.stage.DirectoryChooser
import javafx.util.Duration
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.name
import org.apache.commons.text.StringEscapeUtils
import ru.ravel.xsltsandbox.AppContext
import ru.ravel.xsltsandbox.config.ConfigStore
import ru.ravel.xsltsandbox.files.SessionFiles
import ru.ravel.xsltsandbox.models.ActivityType
import ru.ravel.xsltsandbox.models.DocSession
import ru.ravel.xsltsandbox.models.TransformMode
import ru.ravel.xsltsandbox.models.bizrule.BizRule
import ru.ravel.xsltsandbox.models.segmentationtree.BusinessRule
import ru.ravel.xsltsandbox.utils.FileTreeBuilder
import ru.ravel.xsltsandbox.utils.FileTreeBuilder.buildFileTree
import ru.ravel.xsltsandbox.utils.FileTreeBuilder.buildFilteredFileTree
import ru.ravel.xsltsandbox.utils.LayoutUtil
import ru.ravel.xsltsandbox.utils.TreeUtil.expandAll

/**
 * Левая панель с деревом файлов рабочей папки: выбор папки, поиск и открытие файлов во вкладках.
 */
class FileTreePanel(
	private val ctx: AppContext,
	private val files: SessionFiles,
	private val configStore: ConfigStore,
	private val newTab: (String) -> DocSession,
) {

	private val debounce = PauseTransition(Duration.millis(250.0))
	private var pending: Task<TreeItem<Path>>? = null


	fun build(): VBox {
		ctx.rebuildFileTree = ::rebuildByQuery
		ctx.dirField = TextField().apply {
			promptText = "Выберите папку..."
			isEditable = false
		}
		ctx.fileTree = TreeView<Path>().apply {
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
					val item = selectionModel.selectedItem?.value ?: return@setOnMouseClicked
					if (Files.isDirectory(item)) return@setOnMouseClicked
					openItem(item)
				}
			}
		}
		val chooseBtn = Button("Open…").apply {
			setOnAction { chooseWorkDir() }
		}
		ctx.fileTreeSearch = TextField().apply {
			promptText = "Search"
			isDisable = ctx.processPath == null
			textProperty().addListener { _, _, q ->
				debounce.stop()
				debounce.setOnFinished { rebuildByQuery(q) }
				debounce.playFromStart()
			}
		}
		return VBox(
			HBox(5.0, ctx.dirField, chooseBtn).apply { padding = Insets(5.0) },
			ctx.fileTreeSearch,
			ctx.fileTree
		).apply {
			VBox.setVgrow(ctx.fileTree, Priority.ALWAYS)
			prefWidth = 260.0
		}
	}


	private fun chooseWorkDir() {
		val initialDir = ctx.processPath?.absolutePathString()?.let { File(it).parentFile }
		val chooser = DirectoryChooser().apply {
			title = "Выберите рабочую папку"
			if (initialDir?.exists() == true) {
				initialDirectory = initialDir
			}
		}
		val dir = chooser.showDialog(ctx.stage) ?: return
		ctx.dirField.text = dir.name
		ctx.processPath = dir.toPath()
		ctx.fileTreeSearch.isDisable = false
		rebuildByQuery(ctx.fileTreeSearch.text)
		configStore.saveFrom(ctx)
	}


	/** Открывает файл активности или данных в новой вкладке в зависимости от его типа */
	private fun openItem(item: Path) {
		val session = newTab(item.fileName.toString())
		ctx.tabPane.tabs.add(ctx.tabPane.tabs.size - 1, session.tab)
		ctx.tabPane.selectionModel.select(session.tab)

		val type = LayoutUtil.getActivityType(item.toFile())
		val isBr = type in arrayOf(ActivityType.BIZ_RULE, ActivityType.BUSINESS_RULE)
		val isFormOrWait = type in arrayOf(ActivityType.FORM, ActivityType.WAIT)
		when {
			item.extension.lowercase() in arrayOf("xsl", "xslt") -> {
				files.openXsltFile(session, item)
				session.mode = TransformMode.XSLT
				ctx.xsltRadio.isSelected = true
			}

			item.extension.equals("xml", true) && isBr -> {
				val innerXml = when (type) {
					ActivityType.BIZ_RULE -> {
						val bizRule = ctx.xmlMapper.readValue(item.toFile(), BizRule::class.java)
						StringEscapeUtils.unescapeXml(bizRule.xmlRule.value)
					}

					ActivityType.BUSINESS_RULE -> {
						val businessRule = ctx.xmlMapper.readValue(item.toFile(), BusinessRule::class.java)
						StringEscapeUtils.unescapeXml(businessRule.xmlRule)
					}

					else -> return
				}
				if (!files.applyBrXml(session, innerXml)) return
				session.mode = TransformMode.BR
				ctx.brRadio.isSelected = true
				session.brPath = item
				session.mappingPropertyFile = item.parent.resolve("Properties.xml")
				session.updateTabTitle()
			}

			item.extension.equals("xml", true) && isFormOrWait -> {
				when (type) {
					ActivityType.FORM -> session.mode = TransformMode.FM
					ActivityType.WAIT -> session.mode = TransformMode.WA
					else -> return
				}
				files.loadFileIntoAreaAsync(session, item, session.xsltArea) { session.xsltPath = it }
				session.mappingPropertyFile = item
				session.otherActivityPath = item
				ctx.xsltRadio.isSelected = true
				session.updateTabTitle()
			}

			else -> files.openXmlFile(session, item)
		}
	}


	/**
	 * Применяет фильтр к текущему processPath в фоне и разворачивает дерево для видимости результатов.
	 * Предыдущее ещё не завершённое построение отменяется.
	 */
	fun rebuildByQuery(query: String) {
		val rootPath = ctx.processPath ?: return
		pending?.cancel()
		val task = object : Task<TreeItem<Path>>() {
			override fun call(): TreeItem<Path> =
				if (query.isBlank()) buildFileTree(rootPath) { isCancelled }
				else buildFilteredFileTree(rootPath, query) { isCancelled }
		}
		pending = task
		task.setOnSucceeded {
			if (pending !== task) return@setOnSucceeded
			val root = task.value
			ctx.fileTree.root = root
			// разворачиваем только небольшие результаты, чтобы не повесить интерфейс
			if (query.isNotBlank() && countNodes(root, EXPAND_LIMIT) < EXPAND_LIMIT) expandAll(root)
		}
		task.setOnFailed {
			if (task.exception !is FileTreeBuilder.BuildCancelledException) {
				Dialogs.showStatus(ctx.stage, "Не удалось построить дерево файлов:\n${task.exception?.message}")
			}
		}
		Thread(task, "file-tree-builder").apply { isDaemon = true }.start()
	}


	/** Считает узлы, прекращая обход при достижении [limit] */
	private fun countNodes(item: TreeItem<*>, limit: Int): Int {
		var n = 1
		for (c in item.children) {
			if (n >= limit) break
			n += countNodes(c, limit - n)
		}
		return n
	}


	private companion object {
		const val EXPAND_LIMIT = 2000
	}
}
