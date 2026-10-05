package ru.ravel.xsltsandbox.ui

import javafx.concurrent.Task
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ListView
import javafx.scene.control.ProgressBar
import javafx.scene.control.SelectionMode
import javafx.scene.control.TextArea
import javafx.scene.input.KeyCode
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.stage.Modality
import javafx.stage.Stage

object Dialogs {
	fun showChoiceDialog(owner: Stage, choices: List<String>, title: String): String? {
		var selected: String? = null

		val dlg = Stage().apply {
			initOwner(owner)
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


	fun <T> runWithProgress(
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


	/**
	 * Shows a modal dialog with validation/transformation status,
	 * и позволяет закрыть его по нажатию ESC.
	 */
	fun showStatus(owner: Stage, text: String) {
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
}
