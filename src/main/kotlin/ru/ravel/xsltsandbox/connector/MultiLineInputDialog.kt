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

/** Простой многострочный ввод — одна запись на строку (например несколько имён JBoss-модулей разом). */
object MultiLineInputDialog {

	/** @return непустые строки (обрезанные), или `null`, если пользователь отменил. */
	fun show(owner: Stage, title: String, prompt: String, example: String): List<String>? {
		var result: List<String>? = null

		val dlg = Stage().apply {
			initOwner(owner)
			initModality(Modality.WINDOW_MODAL)
			this.title = title
		}

		val area = TextArea().apply {
			promptText = example
			prefRowCount = 6
		}

		val okBtn = Button("OK").apply {
			isDefaultButton = true
			setOnAction {
				result = area.text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
				dlg.close()
			}
		}
		val cancelBtn = Button("Отмена").apply {
			isCancelButton = true
			setOnAction { result = null; dlg.close() }
		}

		val root = VBox(
			8.0,
			Label(prompt),
			area,
			HBox(8.0, okBtn, cancelBtn).apply { alignment = Pos.CENTER_RIGHT },
		).apply {
			padding = Insets(12.0)
			VBox.setVgrow(area, Priority.ALWAYS)
		}

		dlg.scene = Scene(root, 480.0, 320.0)
		dlg.showAndWait()
		return result
	}
}
