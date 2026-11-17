package ru.ravel.xsltsandbox.connector

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.control.Tooltip
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.stage.DirectoryChooser
import javafx.stage.Modality
import javafx.stage.Stage
import ru.ravel.xsltsandbox.ui.Dialogs.showStatus
import ru.ravel.xsltsandbox.ui.FileChoosers
import java.io.File

/**
 * Настройки реального прогона коннекторов через crif-runner: путь к самому crif-runner.jar
 * (дебагеру), к `java`, которым его запускать, и classpath проверяемого коннектора. Имя Java-класса
 * коннектора сюда не входит — это параметр конкретного прогона, задаётся аргументом запуска sandbox
 * `--input-connector-class-name`. Spring context тоже не входит: crif-runner сам находит XML-файлы
 * бинов в META-INF/spring внутри jar/classpath коннектора.
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

		val vmOptionsField = TextField(current.vmOptions).apply {
			promptText = "Доп. опции JVM для crif-runner: -Dkey=value -Xmx512m …"
		}
		val importStandaloneBtn = Button("Импорт из standalone.xml…").apply {
			tooltip = Tooltip(
				"Переносит <system-properties> из JBoss standalone.xml в VM options (-Dname=value) — " +
					"их иначе пришлось бы переносить вручную по одной на каждую ошибку NoClassDefFound/FileNotFound"
			)
			setOnAction {
				val file = FileChoosers.create("Выберите standalone.xml", null, "XML Files (*.xml)", "*.xml")
					.showOpenDialog(owner) ?: return@setOnAction
				val properties = try {
					JBossStandaloneImport.readSystemProperties(file)
				} catch (e: Exception) {
					showStatus(owner, "Не удалось разобрать файл:\n${e.message}")
					return@setOnAction
				}
				if (properties.isEmpty()) {
					showStatus(owner, "В файле не найден раздел <system-properties> — ничего импортировать.")
					return@setOnAction
				}

				val envVarNames = JBossStandaloneImport.envVarNames(properties)
				val envFromOs = envVarNames.mapNotNull { name -> System.getenv(name)?.let { name to it } }.toMap()
				val missing = envVarNames.filter { it !in envFromOs }
				val envFromUser = if (missing.isNotEmpty()) {
					EnvVarsDialog.show(owner, missing) ?: return@setOnAction
				} else {
					emptyMap()
				}

				val resolved = JBossStandaloneImport.resolve(properties, envFromOs + envFromUser)
				vmOptionsField.text = mergeVmOptions(vmOptionsField.text, resolved)
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

		val okBtn = Button("Сохранить").apply {
			isDefaultButton = true
			setOnAction {
				result = current.copy(
					runnerJarPath = runnerJarField.text.trim(),
					javaExecutablePath = javaExecField.text.trim(),
					classpathEntries = classpathArea.text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList(),
					vmOptions = vmOptionsField.text.trim(),
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
			Label("VM options:"),
			vmOptionsField,
			importStandaloneBtn,
			Label("Classpath (jar-файлы и/или папки с коннектором — как у crif-runner's classpath.entries):"),
			classpathArea,
			HBox(8.0, addJarBtn, addFolderBtn),
			HBox(8.0, okBtn, cancelBtn).apply { alignment = Pos.CENTER_RIGHT },
		).apply {
			padding = Insets(12.0)
			VBox.setVgrow(classpathArea, Priority.ALWAYS)
		}

		dlg.scene = Scene(root, 620.0, 520.0)
		dlg.showAndWait()
		return result
	}


	/** Добавляет/заменяет `-Dname=value` в строке VM options, не трогая прочие опции (-Xmx и т.п.). */
	private fun mergeVmOptions(existing: String, newProps: List<Pair<String, String>>): String {
		val tokens = existing.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
		for ((name, value) in newProps) {
			val token = "-D$name=$value"
			val prefix = "-D$name="
			val index = tokens.indexOfFirst { it.startsWith(prefix) }
			if (index >= 0) tokens[index] = token else tokens.add(token)
		}
		return tokens.joinToString(" ")
	}
}
