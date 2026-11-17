package ru.ravel.xsltsandbox.connector

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.stage.Modality
import javafx.stage.Stage
import ru.ravel.xsltsandbox.ui.FileChoosers
import java.io.File

/**
 * Настройки реального прогона коннекторов через crif-runner: путь к самому crif-runner.jar
 * (дебагеру), classpath проверяемого коннектора, Spring context, соответствие имени коннектора
 * его Java-классу.
 */
object ConnectorSettingsDialog {

	/** @return новую конфигурацию, или `null`, если пользователь отменил. */
	fun show(owner: Stage, current: ConnectorsConfig, focusConnectorName: String?): ConnectorsConfig? {
		var result: ConnectorsConfig? = null

		val dlg = Stage().apply {
			initOwner(owner)
			initModality(Modality.WINDOW_MODAL)
			title = "Настройки запуска коннекторов (реальный jar)"
		}

		val runnerJarField = TextField(current.runnerJarPath).apply {
			promptText = "Путь к crif-runner.jar"
		}
		val chooseRunnerJarBtn = Button("Обзор…").apply {
			setOnAction {
				val file = FileChoosers.create(
					"Выберите crif-runner.jar",
					runnerJarField.text.takeIf { it.isNotBlank() }?.let { File(it).toPath() },
					"JAR Files (*.jar)", "*.jar",
				).showOpenDialog(owner) ?: return@setOnAction
				runnerJarField.text = file.absolutePath
			}
		}

		val classpathArea = TextArea(current.classpathEntries.joinToString("\n")).apply {
			prefRowCount = 4
			promptText = "Один jar-файл или папка с классами коннектора на строку"
		}
		val addJarBtn = Button("Добавить jar…").apply {
			setOnAction {
				val file = FileChoosers.create("Выберите jar коннекторов", null, "JAR Files (*.jar)", "*.jar")
					.showOpenDialog(owner) ?: return@setOnAction
				classpathArea.appendText((if (classpathArea.text.isBlank()) "" else "\n") + file.absolutePath)
			}
		}

		val springArea = TextArea(current.springContextFiles.joinToString("\n")).apply {
			prefRowCount = 2
			promptText = "Необязательно: Spring XML context-файлы, по одному на строку"
		}
		val addSpringBtn = Button("Добавить Spring context…").apply {
			setOnAction {
				val file = FileChoosers.create("Выберите Spring context", null, "XML Files (*.xml)", "*.xml")
					.showOpenDialog(owner) ?: return@setOnAction
				springArea.appendText((if (springArea.text.isBlank()) "" else "\n") + file.absolutePath)
			}
		}

		val classNamesArea = TextArea(
			current.connectorClassNames.entries.joinToString("\n") { "${it.key}=${it.value}" }
		).apply {
			prefRowCount = 8
			promptText = "connectorName=полное.имя.Java.Класса, по одной строке"
		}
		if (focusConnectorName != null && focusConnectorName !in current.connectorClassNames) {
			classNamesArea.appendText((if (classNamesArea.text.isBlank()) "" else "\n") + "$focusConnectorName=")
		}

		val okBtn = Button("Сохранить").apply {
			isDefaultButton = true
			setOnAction {
				result = ConnectorsConfig(
					runnerJarPath = runnerJarField.text.trim(),
					classpathEntries = classpathArea.text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList(),
					springContextFiles = springArea.text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList(),
					connectorClassNames = classNamesArea.text.lineSequence().mapNotNull { line ->
						val trimmed = line.trim()
						if (trimmed.isEmpty() || '=' !in trimmed) return@mapNotNull null
						val (key, value) = trimmed.split("=", limit = 2)
						val name = key.trim()
						val className = value.trim()
						if (name.isEmpty() || className.isEmpty()) null else name to className
					}.toMap(),
				)
				dlg.close()
			}
		}
		val cancelBtn = Button("Отмена").apply {
			isCancelButton = true
			setOnAction { result = null; dlg.close() }
		}

		val root = VBox(
			8.0,
			Label("Путь к crif-runner.jar (дебагер, запускает реальный коннектор):"),
			HBox(8.0, runnerJarField, chooseRunnerJarBtn).apply { HBox.setHgrow(runnerJarField, Priority.ALWAYS) },
			Label("Classpath (jar-файлы и/или папки с коннектором — как у crif-runner's classpath.entries):"),
			classpathArea,
			addJarBtn,
			Label("Spring context (необязательно; имя Spring-бина берётся из ConnectorName активности):"),
			springArea,
			addSpringBtn,
			Label("Имя класса коннектора:"),
			classNamesArea,
			HBox(8.0, okBtn, cancelBtn).apply { alignment = Pos.CENTER_RIGHT },
		).apply {
			padding = Insets(12.0)
			VBox.setVgrow(classNamesArea, Priority.ALWAYS)
		}

		dlg.scene = Scene(root, 620.0, 660.0)
		dlg.showAndWait()
		return result
	}
}
