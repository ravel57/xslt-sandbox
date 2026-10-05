package ru.ravel.xsltsandbox.ui

import java.nio.file.Files
import java.nio.file.Path
import javafx.stage.FileChooser

object FileChoosers {
	/**
	 * Создаёт кнопку «Open …».
	 */
	fun create(
		title: String,
		lastPath: Path?,
		description: String,
		vararg masks: String,
	): FileChooser = FileChooser().apply {
		this.title = title
		extensionFilters.add(FileChooser.ExtensionFilter(description, *masks))
		lastPath?.parent
			?.takeIf { Files.isDirectory(it) }
			?.let { initialDirectory = it.toFile() }
	}
}
