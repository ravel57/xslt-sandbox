package ru.ravel.xsltsandbox.connector

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import ru.ravel.xsltsandbox.config.AppDirs

/**
 * Хранение [ConnectorsConfig] в connectors.json в каталоге настроек пользователя.
 * Отдельный файл от config.json (вкладки/сессия), чтобы не завязываться на [ru.ravel.xsltsandbox.config.ConfigStore.saveFrom],
 * который пересобирает AppConfig с нуля при каждом сохранении.
 */
class ConnectorConfigStore(
	val path: Path = AppDirs.configDir().resolve("connectors.json"),
) {


	fun load(): ConnectorsConfig = runCatching {
		jacksonObjectMapper().readValue(path.toFile(), ConnectorsConfig::class.java)
	}.getOrElse { ConnectorsConfig() }


	fun save(config: ConnectorsConfig) {
		try {
			Files.createDirectories(path.parent)
			Files.writeString(
				path,
				jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(config),
				StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE
			)
		} catch (ex: Exception) {
			System.err.println("Не удалось сохранить connectors.json -> ${ex.message}")
		}
	}
}
