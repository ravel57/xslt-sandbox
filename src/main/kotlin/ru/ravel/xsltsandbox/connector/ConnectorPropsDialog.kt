package ru.ravel.xsltsandbox.connector

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.TextArea
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.stage.Modality
import javafx.stage.Stage

/**
 * Редактор свойств (`connProps`), передаваемых реальному коннектору через crif-runner. Не настройка
 * приложения — параметр конкретного прогона (изначально из `--input-connector-props-path`), живёт
 * только в памяти [ru.ravel.xsltsandbox.AppContext.connectorProps].
 */
object ConnectorPropsDialog {

	/** @return отредактированные свойства, или `null`, если пользователь отменил. */
	fun show(owner: Stage, current: LinkedHashMap<String, String>): LinkedHashMap<String, String>? {
		var result: LinkedHashMap<String, String>? = null

		val dlg = Stage().apply {
			initOwner(owner)
			initModality(Modality.WINDOW_MODAL)
			title = "Свойства коннектора (connProps)"
		}

		val propsArea = TextArea(current.entries.joinToString("\n") { (k, v) -> "$k=$v" }).apply {
			prefRowCount = 14
			prefColumnCount = 50
			promptText = "ключ=значение, по одной паре на строку"
			style = "-fx-font-family: monospace;"
		}

		val okBtn = Button("Сохранить").apply {
			isDefaultButton = true
			setOnAction {
				val parsed = LinkedHashMap<String, String>()
				propsArea.text.lineSequence()
					.map { it.trim() }
					.filter { it.isNotEmpty() && !it.startsWith("#") }
					.forEach { line ->
						val eq = line.indexOf('=')
						if (eq > 0) {
							parsed[line.substring(0, eq).trim()] = line.substring(eq + 1).trim()
						}
					}
				result = parsed
				dlg.close()
			}
		}
		val cancelBtn = Button("Отмена").apply {
			isCancelButton = true
			setOnAction { result = null; dlg.close() }
		}

		val root = VBox(
			8.0,
			Label("Свойства, передаваемые коннектору как java.util.Properties (connProps) — по одной паре ключ=значение на строку:"),
			propsArea,
			javafx.scene.layout.HBox(8.0, okBtn, cancelBtn).apply { alignment = Pos.CENTER_RIGHT },
		).apply {
			padding = Insets(12.0)
			VBox.setVgrow(propsArea, Priority.ALWAYS)
		}

		dlg.scene = Scene(root, 520.0, 440.0)
		dlg.showAndWait()
		return result
	}
}
