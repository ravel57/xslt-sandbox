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
	data class ResolvedConnector(val className: String, val config: ConnectorsConfig)


	/**
	 * Разрешает classpath/раннер для прогона; при необходимости открывает диалог настроек — вызывать
	 * только из FX-потока. [className] — имя класса коннектора, задаётся аргументом запуска sandbox
	 * `--input-connector-class-name`, настройками приложения не является. `null` — пользователь
	 * отменил диалог или имя класса не задано.
	 */
	fun resolveConnector(owner: Stage, className: String?): ResolvedConnector? {
		if (className.isNullOrBlank()) {
			showStatus(owner, "Имя класса коннектора не задано — передайте его аргументом запуска sandbox --input-connector-class-name.")
			return null
		}
		var config = configStore.load()
		if (config.runnerJarPath.isBlank() || config.classpathEntries.isEmpty()) {
			config = ConnectorSettingsDialog.show(owner, config) ?: return null
			configStore.save(config)
		}
		if (config.runnerJarPath.isBlank() || !Files.isRegularFile(Path.of(config.runnerJarPath))) {
			showStatus(owner, "Путь к crif-runner.jar не задан или файл не найден:\n${config.runnerJarPath}")
			return null
		}
		return ResolvedConnector(className, config)
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

		val runnerJar = File(config.runnerJarPath)
		if (!runnerJar.isFile) {
			return RunResult(false, errorMessage = "crif-runner.jar не найден:\n${config.runnerJarPath}")
		}

		Files.writeString(inputFile, connectorInputXml, Charsets.UTF_8)
		writeRunnerConfig(configFile, connectorName, className, config, inputFile, outputFile, resultFile)

		val javaBin = javaExecutable(config)

		AppLog.info("коннектор $connectorName ($className): запуск $javaBin -jar $runnerJar $configFile")
		val process = try {
			ProcessBuilder(javaBin, "-jar", runnerJar.absolutePath, configFile.toString())
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
		config: ConnectorsConfig,
		inputFile: Path,
		outputFile: Path,
		resultFile: Path,
	) {
		val props = Properties()
		props.setProperty("connector.className", className)
		props.setProperty("classpath.entries", config.classpathEntries.joinToString("\n"))
		if (config.springContextFiles.isNotEmpty()) {
			props.setProperty("spring.contextFiles", config.springContextFiles.joinToString(", "))
			// по соглашению CRIF имя Spring-бина совпадает с ConnectorName активности
			props.setProperty("spring.beanName", connectorName)
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
