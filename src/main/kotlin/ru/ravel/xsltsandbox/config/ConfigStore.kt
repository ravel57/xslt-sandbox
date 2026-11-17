package ru.ravel.xsltsandbox.config

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import ru.ravel.xsltsandbox.AppContext
import ru.ravel.xsltsandbox.models.AppConfig
import ru.ravel.xsltsandbox.models.TabState

/**
 * Хранение [AppConfig] в config.json в каталоге настроек пользователя.
 */
class ConfigStore {
	val path: Path = AppDirs.configDir().resolve("config.json")


	fun load(): AppConfig = runCatching {
		jacksonObjectMapper().readValue(path.toFile(), AppConfig::class.java)
	}.getOrElse { AppConfig() }


	/** Сохраняет открытые вкладки и активную вкладку из [ctx] */
	fun saveFrom(ctx: AppContext) {
		val workTabs = ctx.tabPane.tabs.filter { it != ctx.plusTab }
		val tabStates = workTabs.map { tab ->
			val s = ctx.sessions[tab]!!
			TabState(
				xmlPath = s.xmlPath?.toString(),
				xsltPath = s.xsltPath?.toString(),
				brPath = s.brPath?.toString(),
				process = ctx.processPath?.toString(),
			)
		}
		val activeIndex = workTabs.indexOf(ctx.tabPane.selectionModel.selectedItem).coerceAtLeast(0)
		save(AppConfig(tabStates, activeIndex))
	}


	fun save(config: AppConfig) {
		try {
			Files.createDirectories(path.parent)
			Files.writeString(
				path,
				jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(config),
				StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE
			)
		} catch (ex: Exception) {
			System.err.println("Не удалось сохранить config.json -> ${ex.message}")
		}
	}
}
