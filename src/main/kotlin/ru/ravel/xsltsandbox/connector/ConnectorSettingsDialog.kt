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

	/**
	 * @param jarPath jar проверяемого коннектора (`AppContext.connectorJarPath`), если уже известен —
	 *   нужен только для кнопки поиска `jndi-name` в его собственном Spring-контексте; без него кнопка
	 *   ищет только по настроенному здесь Classpath.
	 * @return новую конфигурацию, или `null`, если пользователь отменил.
	 */
	fun show(owner: Stage, current: ConnectorsConfig, jarPath: String? = null): ConnectorsConfig? {
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
		val rememberedEnvValues = mutableMapOf<String, String>()
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
					EnvVarsDialog.show(owner, missing, rememberedEnvValues) ?: return@setOnAction
				} else {
					emptyMap()
				}
				rememberedEnvValues.putAll(envFromUser)

				val resolved = JBossStandaloneImport.resolve(properties, envFromOs + envFromUser)
				val (ok, broken) = resolved.partition { (_, value) -> JBossStandaloneImport.isResolved(value) }
				vmOptionsField.text = mergeVmOptions(vmOptionsField.text, ok)
				if (broken.isNotEmpty()) {
					showStatus(
						owner,
						"Не удалось подставить переменную окружения, пропущены (значения не менялись):\n" +
							broken.joinToString("\n") { it.first },
					)
				}
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
		var rememberedModulesRoot: String? = null
		val addModuleBtn = Button("Добавить JBoss-модуль по имени…").apply {
			tooltip = Tooltip(
				"Резолвит jar'ы JBoss-модуля прямо по имени (например org.hibernate) через его module.xml, " +
					"рекурсивно по <dependencies> — подтянет и его собственные зависимости (dom4j, antlr и т.п.) " +
					"без ручного угадывания каждой следующей ошибки NoClassDefFound, и без обхода всего дерева modules"
			)
			setOnAction {
				val chooser = DirectoryChooser().apply {
					title = "Выберите папку modules (корень, не подпапку конкретного модуля)"
					rememberedModulesRoot?.let { File(it) }?.takeIf { it.isDirectory }?.let { initialDirectory = it }
				}
				val modulesRoot = chooser.showDialog(owner)?.absolutePath ?: return@setOnAction
				rememberedModulesRoot = modulesRoot

				val inputLines = MultiLineInputDialog.show(
					owner,
					"JBoss-модули",
					"Имена модулей, по одному на строку (и слот через двоеточие, по умолчанию main) — " +
						"можно сразу весь список, собранный для этого приложения:",
					"org.apache.httpcomponents\norg.hibernate\norg.dom4j:main",
				) ?: return@setOnAction
				if (inputLines.isEmpty()) return@setOnAction

				val existing = classpathArea.text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
				val allJars = mutableListOf<String>()
				val allResolved = mutableListOf<String>()
				val allMissing = mutableListOf<String>()
				for (line in inputLines) {
					val parts = line.split(":", limit = 2)
					val moduleName = parts[0].trim()
					val slot = parts.getOrNull(1)?.trim().orEmpty().ifBlank { "main" }
					val tree = JBossModuleResolver.resolveTransitive(modulesRoot, moduleName, slot)
					allJars.addAll(tree.jarPaths)
					allResolved.addAll(tree.resolvedModules)
					allMissing.addAll(tree.missingModules)
				}
				allJars.distinct().filter { it !in existing }.forEach { appendClasspathEntry(it); existing.add(it) }

				val summary = "Добавлено ${allJars.distinct().size} jar из ${allResolved.distinct().size} модулей (вместе с зависимостями): " +
					allResolved.distinct().joinToString(", ")
				if (allMissing.isEmpty()) {
					showStatus(owner, summary)
				} else {
					showStatus(owner, "$summary\n\nНе нашлись (возможно, другой slot или раскладка):\n" + allMissing.distinct().joinToString("\n"))
				}
			}
		}
		val clearDirCacheBtn = Button("Сбросить кэш сканирования папок").apply {
			tooltip = Tooltip(
				"Папки в Classpath (и модули выше) сканируются один раз и запоминаются в AppData насовсем, " +
					"чтобы не пересканировать медленную сеть каждый запуск — нажмите, если содержимое на сервере реально поменялось"
			)
			setOnAction {
				val removed = ConnectorJarRunner.clearDirectoryJarCache()
				showStatus(owner, "Кэш очищен, записей удалено: $removed. Следующий прогон пересканирует папки заново.")
			}
		}
		val findClassBtn = Button("Найти класс в папке…").apply {
			tooltip = Tooltip(
				"Для конечного пользователя: вставьте имя класса (или просто текст ошибки) прямо из " +
					"NoClassDefFoundError/ClassNotFoundException — без угадывания имени JBoss-модуля. " +
					"Папка сканируется один раз (и кэшируется, см. кнопку сброса кэша выше), дальше поиск мгновенный"
			)
			setOnAction {
				val chooser = DirectoryChooser().apply {
					title = "Выберите папку для поиска (например modules)"
					rememberedModulesRoot?.let { File(it) }?.takeIf { it.isDirectory }?.let { initialDirectory = it }
				}
				val searchRoot = chooser.showDialog(owner)?.absolutePath ?: return@setOnAction
				rememberedModulesRoot = searchRoot

				val inputLines = MultiLineInputDialog.show(
					owner,
					"Поиск класса",
					"Вставьте имя класса или текст ошибки (NoClassDefFoundError/ClassNotFoundException), " +
						"можно несколько строк сразу:",
					"org.dom4j.DocumentException",
				) ?: return@setOnAction
				val classNames = inputLines.mapNotNull { JarClassSearch.extractClassName(it) }.distinct()
				if (classNames.isEmpty()) return@setOnAction

				val jarsInRoot = ConnectorJarRunner.expandDirectoriesCached(listOf(searchRoot))
				val existing = classpathArea.text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
				val found = mutableListOf<String>()
				val notFound = mutableListOf<String>()
				for (className in classNames) {
					val jars = JarClassSearch.findClassJars(jarsInRoot, className)
					if (jars.isEmpty()) {
						notFound.add(className)
					} else {
						jars.forEach { if (it !in existing) { appendClasspathEntry(it); existing.add(it); found.add(it) } }
					}
				}

				val summary = if (found.isNotEmpty()) "Добавлено в Classpath:\n" + found.joinToString("\n") else "Ничего нового не добавлено."
				if (notFound.isEmpty()) {
					showStatus(owner, summary)
				} else {
					showStatus(owner, "$summary\n\nНе нашлись в $searchRoot:\n" + notFound.joinToString("\n"))
				}
			}
		}

		val jndiArea = TextArea(current.jndiDataSources.joinToString("\n")).apply {
			prefRowCount = 3
			promptText = "jndiName=driverClassName,jdbcUrl,username,password — по одной на строку"
		}
		val importDatasourcesBtn = Button("Импорт из standalone.xml…").apply {
			tooltip = Tooltip(
				"Берёт driver/URL/логин/пароль из <datasources> того же standalone.xml по jndi-name — " +
					"дописывает недостающие поля руками не придётся, только пароли под Vault (${'$'}{VAULT::...}) надо будет подставить самим"
			)
			setOnAction {
				val file = FileChoosers.create("Выберите standalone.xml", null, "XML Files (*.xml)", "*.xml")
					.showOpenDialog(owner) ?: return@setOnAction
				val dataSources = try {
					JBossDatasourceImport.readDataSources(file)
				} catch (e: Exception) {
					showStatus(owner, "Не удалось разобрать файл:\n${e.message}")
					return@setOnAction
				}
				if (dataSources.isEmpty()) {
					showStatus(owner, "В файле не найден раздел <datasources> с jndi-name — ничего импортировать.")
					return@setOnAction
				}
				jndiArea.text = mergeJndiLines(jndiArea.text, dataSources.map { it.jndiName to JBossDatasourceImport.toConfigLine(it) })
			}
		}
		val findJndiNamesBtn = Button("Найти jndi-name в Spring-контексте коннектора…").apply {
			tooltip = Tooltip(
				"Сканирует META-INF/spring/*.xml на том же classpath, который увидит crif-runner " +
					"(jar коннектора + соседние коннекторы его папки + Classpath ниже), и добавляет " +
					"найденные jndi-name пустыми строками — заполнить можно кнопкой импорта standalone.xml выше"
			)
			setOnAction {
				val classpathEntries = ConnectorJarRunner.expandDirectoriesCached(
					ConnectorJarRunner.resolveClasspathEntries(
						jarPath,
						classpathArea.text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList(),
					)
				)
				val names = SpringJndiLookupScanner.scan(classpathEntries)
				if (names.isEmpty()) {
					showStatus(owner, "В Spring-контексте на classpath не найдено ссылок jndi-name.")
					return@setOnAction
				}
				jndiArea.text = addMissingJndiNames(jndiArea.text, names)
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
					jndiDataSources = jndiArea.text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList(),
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
			HBox(8.0, addJarBtn, addFolderBtn, addModuleBtn, findClassBtn, clearDirCacheBtn),
			Label("Заглушки JNDI DataSource (реальный java:/… JNDI вне сервера недоступен):"),
			jndiArea,
			HBox(8.0, findJndiNamesBtn, importDatasourcesBtn),
			HBox(8.0, okBtn, cancelBtn).apply { alignment = Pos.CENTER_RIGHT },
		).apply {
			padding = Insets(12.0)
			VBox.setVgrow(classpathArea, Priority.ALWAYS)
		}

		dlg.scene = Scene(root, 640.0, 600.0)
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


	/** Добавляет/заменяет строки `jndiName=...` по ключу [jndiName], не трогая остальные. */
	private fun mergeJndiLines(existing: String, newLines: List<Pair<String, String>>): String {
		val lines = existing.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
		for ((jndiName, line) in newLines) {
			val prefix = "$jndiName="
			val index = lines.indexOfFirst { it.startsWith(prefix) }
			if (index >= 0) lines[index] = line else lines.add(line)
		}
		return lines.joinToString("\n")
	}


	/** Дописывает [names] пустыми строками `name=,,,`, не трогая уже существующие (пустые или заполненные). */
	private fun addMissingJndiNames(existing: String, names: List<String>): String {
		val lines = existing.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
		val existingNames = lines.map { it.substringBefore("=") }.toSet()
		for (name in names) {
			if (name !in existingNames) lines.add("$name=,,,")
		}
		return lines.joinToString("\n")
	}
}
