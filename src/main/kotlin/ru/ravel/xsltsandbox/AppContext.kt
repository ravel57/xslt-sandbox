package ru.ravel.xsltsandbox

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.nio.file.Path
import javafx.scene.control.RadioButton
import javafx.scene.control.Tab
import javafx.scene.control.TabPane
import javafx.scene.control.TextField
import javafx.scene.control.TreeView
import javafx.stage.Stage
import org.fxmisc.richtext.CodeArea
import ru.ravel.xsltsandbox.debug.DebugBridge
import ru.ravel.xsltsandbox.editor.EditorState
import ru.ravel.xsltsandbox.models.DocSession

/**
 * Состояние приложения, общее для всех его компонентов.
 */
class AppContext {
	val xmlMapper: ObjectMapper = XmlMapper().registerKotlinModule()
	val editor = EditorState()

	lateinit var stage: Stage
	lateinit var tabPane: TabPane
	val sessions = mutableMapOf<Tab, DocSession>()
	val plusTab = Tab("+").apply { isClosable = false }

	val currentSession: DocSession
		get() = sessions[tabPane.selectionModel.selectedItem]!!

	/** Область, с которой пользователь работал последней (для поиска и XPath) */
	var currentArea: CodeArea? = null

	lateinit var xsltRadio: RadioButton
	lateinit var brRadio: RadioButton

	lateinit var dirField: TextField
	lateinit var fileTree: TreeView<Path>
	lateinit var fileTreeSearch: TextField
	var processPath: Path? = null

	var debugBridge: DebugBridge? = null

	/**
	 * Пересчитывает доступность кнопок отладки (next activity, run debug). Нужен коду, который
	 * открывает активность не кликом по переключателю режима — например, аргументам запуска:
	 * сами переключатели в этом случае не меняются и кнопки не обновляются.
	 */
	var refreshActivityButtons: () -> Unit = {}

	var rebuildFileTree: (String) -> Unit = {}
}