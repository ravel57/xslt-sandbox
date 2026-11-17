package ru.ravel.xsltsandbox.connector

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.TextArea
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.stage.Modality
import javafx.stage.Stage

/**
 * Разово спрашивает значения переменных окружения сервера (`${env.NAME}` в standalone.xml),
 * которые не удалось взять из окружения самого sandbox'а — обычно это реальные пути на сервере.
 */
object EnvVarsDialog {

	/** @return имя → значение, или `null`, если пользователь отменил. */
	fun show(owner: Stage, varNames: List<String>): Map<String, String>? {
		var result: Map<String, String>? = null

		val dlg = Stage().apply {
			initOwner(owner)
			initModality(Modality.WINDOW_MODAL)
			title = "Переменные окружения сервера"
		}

		val area = TextArea(varNames.joinToString("\n") { "$it=" }).apply {
			prefRowCount = varNames.size.coerceIn(3, 12)
			promptText = "имя_переменной=значение, по одной на строку"
		}

		val okBtn = Button("Продолжить").apply {
			isDefaultButton = true
			setOnAction {
				result = area.text.lineSequence()
					.map { it.trim() }
					.filter { it.isNotEmpty() }
					.mapNotNull { line ->
						val eq = line.indexOf('=')
						if (eq > 0) line.substring(0, eq).trim() to line.substring(eq + 1).trim() else null
					}
					.toMap()
				dlg.close()
			}
		}
		val cancelBtn = Button("Отмена").apply {
			isCancelButton = true
			setOnAction { result = null; dlg.close() }
		}

		val root = VBox(
			8.0,
			Label("В standalone.xml встречаются переменные окружения сервера (\${env.NAME}) — укажите их реальные значения:"),
			area,
			HBox(8.0, okBtn, cancelBtn).apply { alignment = Pos.CENTER_RIGHT },
		).apply {
			padding = Insets(12.0)
			VBox.setVgrow(area, Priority.ALWAYS)
		}

		dlg.scene = Scene(root, 560.0, 340.0)
		dlg.showAndWait()
		return result
	}
}
