package ru.ravel.xsltsandbox.config

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import javafx.application.Application
import kotlin.io.path.name
import ru.ravel.xsltsandbox.AppContext
import ru.ravel.xsltsandbox.models.AppConfig
import ru.ravel.xsltsandbox.models.TabState

/**
 * Хранение [AppConfig] в config.json в каталоге настроек пользователя.
 */
class ConfigStore {
	val path: Path = run {
		val os = System.getProperty("os.name").lowercase()
		val configDir = when {
			os.contains("win") -> Paths.get(
				System.getenv("APPDATA"),
				"xslt-sandbox"
			)

			os.contains("mac") -> Paths.get(
				System.getProperty("user.home"),
				"Library",
				"Application Support",
				"xslt-sandbox"
			)

			else -> Paths.get(
				System.getenv("XDG_CONFIG_HOME")
					?: Paths.get(System.getProperty("user.home"), ".config").toString(),
				"xslt-sandbox"
			)
		}
		configDir.resolve("config.json")
	}


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
