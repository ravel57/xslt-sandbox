package ru.ravel.xsltsandbox.datadocs

import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.stage.Modality
import javafx.stage.Stage
import javax.xml.parsers.SAXParserFactory
import kotlin.io.path.exists
import kotlin.io.path.name
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import ru.ravel.xsltsandbox.AppContext
import ru.ravel.xsltsandbox.editor.CodeAreaSupport
import ru.ravel.xsltsandbox.ui.Dialogs.showStatus
import ru.ravel.xsltsandbox.ui.FileChoosers
import ru.ravel.xsltsandbox.utils.ProcessPaths
import ru.ravel.xsltsandbox.utils.XmlUtil

/**
 * Редактор Mock.xml для активности Data Source.
 */
class MockXmlEditor(
	private val ctx: AppContext,
	private val support: CodeAreaSupport,
) {
	/** Если в activityDir нет Mock.xml — открывает редактор и сохраняет. Возвращает путь к Mock.xml или null при отмене. */
	fun ensureMockXml(activityDir: Path): Path? {
		// активность могла быть открыта из снимка ветки — Mock.xml читаем и пишем в реальном процессе
		val realDir = ProcessPaths.inRealProcess(ctx.processPath, activityDir)
		val mock = realDir.resolve("Mock.xml")
		return if (Files.exists(mock)) {
			mock
		} else {
			showMockXmlEditor(realDir)
		}
	}


	/** Окно редактора Mock.xml для активности (папки). Сохраняет UTF-8 с BOM. */
	private fun showMockXmlEditor(activityDir: Path): Path? {
		var result: Path? = null
		val dlg = Stage().apply {
			initOwner(ctx.stage)
			initModality(Modality.WINDOW_MODAL)
			title = "Создайте Mock.xml для ${activityDir.name}"
		}
		val area = support.createHighlightingCodeArea(false).apply {
			prefWidth = 820.0
			prefHeight = 520.0
			replaceText("<ConnectorOutput>\n</ConnectorOutput>\n")
		}
		val loadBtn = Button("Load from file").apply {
			setOnAction {
				val file = FileChoosers.create("Open XML…", null, "XML Files (*.xml)", "*.xml")
					.showOpenDialog(ctx.stage) ?: return@setOnAction
				area.replaceText(XmlUtil.readXmlSafe(file))
			}
		}
		val pasteBtn = Button("Вставить из буфера").apply {
			setOnAction { support.pasteFromClipboardWithProgress(area) }
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
					showStatus(ctx.stage, "XML is well-formed.")
				} catch (e: Exception) {
					showStatus(ctx.stage, "XML error: ${e.message}")
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
}
