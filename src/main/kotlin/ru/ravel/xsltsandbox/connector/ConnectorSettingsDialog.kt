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
import javafx.stage.DirectoryChooser
import javafx.stage.Modality
import javafx.stage.Stage
import ru.ravel.xsltsandbox.ui.FileChoosers
import java.io.File

/**
 * Настройки реального прогона коннекторов через crif-runner: путь к самому crif-runner.jar
 * (дебагеру), к `java`, которым его запускать, classpath проверяемого коннектора и Spring context.
 * Имя Java-класса коннектора сюда не входит — это параметр конкретного прогона, задаётся
 * аргументом запуска sandbox `--input-connector-class-name`.
 */
object ConnectorSettingsDialog {

	/** @return новую конфигурацию, или `null`, если пользователь отменил. */
	fun show(owner: Stage, current: ConnectorsConfig): ConnectorsConfig? {
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

		val javaExecField = TextField(current.javaExecutablePath).apply {
			promptText = "Пусто — использовать java, на которой запущен sandbox"
		}
		val chooseJavaExecBtn = Button("Обзор…").apply {
			setOnAction {
				val file = FileChoosers.create(
					"Выберите java / java.exe",
					javaExecField.text.takeIf { it.isNotBlank() }?.let { File(it).toPath() },
					"Executable files", "*",
				).showOpenDialog(owner) ?: return@setOnAction
				javaExecField.text = file.absolutePath
			}
		}

		val classpathArea = TextArea(current.classpathEntries.joinToString("\n")).apply {
			prefRowCount = 4
			promptText = "Один jar-файл или папка с классами коннектора на строку"
		}
		fun appendClasspathEntry(path: String) {
			classpathArea.appendText((if (classpathArea.text.isBlank()) "" else "\n") + path)
		}
		val addJarBtn = Button("Добавить jar(ы)…").apply {
			setOnAction {
				val files = FileChoosers.create("Выберите jar-файлы коннектора", null, "JAR Files (*.jar)", "*.jar")
					.showOpenMultipleDialog(owner) ?: return@setOnAction
				files.forEach { appendClasspathEntry(it.absolutePath) }
			}
		}
		val addFolderBtn = Button("Добавить папку…").apply {
			setOnAction {
				val dir = DirectoryChooser().apply { title = "Выберите папку с классами/jar коннектора" }
					.showDialog(owner) ?: return@setOnAction
				appendClasspathEntry(dir.absolutePath)
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

		val okBtn = Button("Сохранить").apply {
			isDefaultButton = true
			setOnAction {
				result = ConnectorsConfig(
					runnerJarPath = runnerJarField.text.trim(),
					javaExecutablePath = javaExecField.text.trim(),
					classpathEntries = classpathArea.text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList(),
					springContextFiles = springArea.text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList(),
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
			Label("Путь к crif-runner.jar"),
			HBox(8.0, runnerJarField, chooseRunnerJarBtn).apply { HBox.setHgrow(runnerJarField, Priority.ALWAYS) },
			Label("Путь к java.exe (jdk-home):"),
			HBox(8.0, javaExecField, chooseJavaExecBtn).apply { HBox.setHgrow(javaExecField, Priority.ALWAYS) },
			Label("Classpath (jar-файлы и/или папки с коннектором — как у crif-runner's classpath.entries):"),
			classpathArea,
			HBox(8.0, addJarBtn, addFolderBtn),
			Label("Spring context (необязательно; имя Spring-бина берётся из ConnectorName активности):"),
			springArea,
			addSpringBtn,
			HBox(8.0, okBtn, cancelBtn).apply { alignment = Pos.CENTER_RIGHT },
		).apply {
			padding = Insets(12.0)
			VBox.setVgrow(classpathArea, Priority.ALWAYS)
		}

		dlg.scene = Scene(root, 620.0, 540.0)
		dlg.showAndWait()
		return result
	}
}
