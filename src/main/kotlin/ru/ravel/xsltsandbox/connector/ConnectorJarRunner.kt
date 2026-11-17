package ru.ravel.xsltsandbox.connector

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.TimeUnit
import javafx.stage.Stage
import ru.ravel.xsltsandbox.log.AppLog
import ru.ravel.xsltsandbox.ui.Dialogs.showStatus

/**
 * Запускает реальный коннектор через crif-runner CLI (см. crif-connectors-debuger/runner-java7):
 * тот же JAR, который вызывает IntelliJ-плагин, только CLI-режимом — ничего из IDE не используется.
 */
class ConnectorJarRunner(
	private val configStore: ConnectorConfigStore = ConnectorConfigStore(),
) {
	data class RunResult(val success: Boolean, val connectorOutputXml: String? = null, val errorMessage: String? = null)

	/** [jarPath] — jar проверяемого коннектора (`--input-connector-jar-path`), отдельно от [config]'овского classpath зависимостей. */
	data class ResolvedConnector(val className: String, val config: ConnectorsConfig, val jarPath: String?)


	companion object {
		/**
		 * Тот же classpath, что увидит crif-runner: папка проверяемого jar'а целиком (соседние
		 * коннекторы того же бандла деплоя) плюс настроенные зависимости. Используется и при запуске,
		 * и при поиске `jndi-name`/Spring context на этом же classpath ([SpringJndiLookupScanner]).
		 */
		fun resolveClasspathEntries(jarPath: String?, classpathEntries: List<String>): List<String> =
			jarPath?.let {
				val jarDir = File(it).parentFile?.absolutePath
				listOfNotNull(jarDir, it) + classpathEntries
			} ?: classpathEntries
	}


	/**
	 * Разрешает classpath/раннер для прогона; при необходимости открывает диалог настроек — вызывать
	 * только из FX-потока. [className] — имя класса коннектора, [jarPath] — путь к его jar-файлу,
	 * оба задаются аргументами запуска sandbox (`--input-connector-class-name`, `--input-connector-jar-path`)
	 * и настройками приложения не являются. `null` — пользователь отменил диалог или имя класса не задано.
	 */
	fun resolveConnector(owner: Stage, className: String?, jarPath: String?): ResolvedConnector? {
		if (className.isNullOrBlank()) {
			showStatus(owner, "Имя класса коннектора не задано — передайте его аргументом запуска sandbox --input-connector-class-name.")
			return null
		}
		var config = configStore.load()
		if (config.runnerJarPath.isBlank() || (config.classpathEntries.isEmpty() && jarPath.isNullOrBlank())) {
			config = ConnectorSettingsDialog.show(owner, config, jarPath) ?: return null
			configStore.save(config)
		}
		if (config.runnerJarPath.isBlank() || !Files.isRegularFile(Path.of(config.runnerJarPath))) {
			showStatus(owner, "Путь к crif-runner.jar не задан или файл не найден:\n${config.runnerJarPath}")
			return null
		}
		return ResolvedConnector(className, config, jarPath)
	}


	/**
	 * Прогоняет [connectorInputXml] (результат MappingInput.xslt, `<ConnectorInput>...</ConnectorInput>`)
	 * через реальный класс коннектора [connectorName], разрешённый заранее через [resolveConnector].
	 * Только I/O и запуск внешнего процесса — безопасно звать с фонового потока (не блокировать FX).
	 */
	fun execute(connectorName: String, resolved: ResolvedConnector, connectorInputXml: String): RunResult {
		val className = resolved.className
		val config = resolved.config

		val workDir = Files.createTempDirectory("xslt-sandbox-connector-run")
		val inputFile = workDir.resolve("input.xml")
		val outputFile = workDir.resolve("output.xml")
		val resultFile = workDir.resolve("result.json")
		val configFile = workDir.resolve("runner-config.properties")
		val connPropsFile = workDir.resolve("conn-props.properties")

		val runnerJar = File(config.runnerJarPath)
		if (!runnerJar.isFile) {
			return RunResult(false, errorMessage = "crif-runner.jar не найден:\n${config.runnerJarPath}")
		}

		Files.writeString(inputFile, connectorInputXml, Charsets.UTF_8)
		if (config.connectorProps.isNotEmpty()) {
			val props = Properties()
			config.connectorProps.forEach { (k, v) -> props.setProperty(k, v) }
			Files.newOutputStream(connPropsFile).use { props.store(it, "xslt-sandbox: connProps коннектора") }
		}
		writeRunnerConfig(
			configFile, connectorName, className, resolved.jarPath, config,
			inputFile, outputFile, resultFile,
			connPropsFile.takeIf { config.connectorProps.isNotEmpty() },
		)

		val javaBin = javaExecutable(config)
		val vmOptions = config.vmOptions.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
		val command = listOf(javaBin) + vmOptions + listOf("-jar", runnerJar.absolutePath, configFile.toString())

		AppLog.info("коннектор $connectorName ($className): запуск ${command.joinToString(" ")}")
		val process = try {
			ProcessBuilder(command)
				.redirectErrorStream(true)
				.start()
		} catch (e: Exception) {
			return RunResult(false, errorMessage = "Не удалось запустить crif-runner: ${e.message}")
		}

		val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
		val finished = process.waitFor(2, TimeUnit.MINUTES)
		output.lineSequence().filter { it.isNotBlank() }.forEach { AppLog.info("[crif-runner] $it") }

		if (!finished) {
			process.destroyForcibly()
			return RunResult(false, errorMessage = "crif-runner не завершился за 2 минуты — процесс остановлен принудительно.")
		}

		if (!Files.exists(resultFile)) {
			return RunResult(
				false,
				errorMessage = "crif-runner не создал result.json (код завершения ${process.exitValue()}):\n$output",
			)
		}

		@Suppress("UNCHECKED_CAST")
		val resultJson = jacksonObjectMapper().readValue(resultFile.toFile(), Map::class.java) as Map<String, Any?>
		val success = resultJson["success"] == true
		if (!success) {
			val message = resultJson["errorMessage"] as? String
			val stackTrace = resultJson["errorStackTrace"] as? String
			return RunResult(false, errorMessage = listOfNotNull(message, stackTrace).joinToString("\n\n").ifBlank { resultJson.toString() })
		}

		val connectorOutputXml = Files.readString(outputFile, Charsets.UTF_8)
		return RunResult(true, connectorOutputXml = connectorOutputXml)
	}


	private fun writeRunnerConfig(
		configFile: Path,
		connectorName: String,
		className: String,
		jarPath: String?,
		config: ConnectorsConfig,
		inputFile: Path,
		outputFile: Path,
		resultFile: Path,
		connPropsFile: Path?,
	) {
		val classpathEntries = resolveClasspathEntries(jarPath, config.classpathEntries)

		val props = Properties()
		props.setProperty("connector.className", className)
		if (jarPath != null) {
			props.setProperty("connector.jarPath", jarPath)
		}
		props.setProperty("classpath.entries", classpathEntries.joinToString("\n"))
		// по соглашению CRIF имя Spring-бина совпадает с ConnectorName активности; crif-runner сам
		// решает, нужен ли вообще Spring-контекст (ищет META-INF/spring/*.xml на classpath коннектора)
		props.setProperty("spring.beanName", connectorName)
		if (config.jndiDataSources.isNotEmpty()) {
			props.setProperty("jndi.dataSources", config.jndiDataSources.joinToString("\n"))
		}
		if (connPropsFile != null) {
			props.setProperty("connProps.file", connPropsFile.toString())
		}
		props.setProperty("input.xml.file", inputFile.toString())
		props.setProperty("output.xml.file", outputFile.toString())
		props.setProperty("result.file", resultFile.toString())
		Files.newOutputStream(configFile).use { props.store(it, "xslt-sandbox: сгенерированная конфигурация crif-runner") }
	}


	/** Настроенный путь к `java`/`java.exe`, если задан; иначе — JVM, в которой работает сам sandbox. */
	private fun javaExecutable(config: ConnectorsConfig): String {
		config.javaExecutablePath.trim().takeIf { it.isNotEmpty() }?.let { return it }
		val isWindows = System.getProperty("os.name").lowercase().contains("win")
		val bin = Path.of(System.getProperty("java.home"), "bin", if (isWindows) "java.exe" else "java")
		return bin.toString()
	}
}
