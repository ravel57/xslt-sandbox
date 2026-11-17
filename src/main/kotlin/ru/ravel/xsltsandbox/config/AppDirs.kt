package ru.ravel.xsltsandbox.config

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Папка настроек приложения, своя для каждой ОС (общая для config.json и connectors.json).
 */
object AppDirs {
	fun configDir(): Path {
		val os = System.getProperty("os.name").lowercase()
		return when {
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
	}
}
