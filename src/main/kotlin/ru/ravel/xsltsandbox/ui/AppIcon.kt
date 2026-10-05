package ru.ravel.xsltsandbox.ui

import javafx.scene.image.Image
import javafx.stage.Stage

object AppIcon {
	fun apply(stage: Stage) {
		val resource = listOf(
			"/icons/XSLTSandbox.png",
			"/icons/app.png",
			"/icons/icon.png",
		).firstNotNullOfOrNull { path ->
			AppIcon::class.java.getResource(path)
		}

		if (resource == null) {
			System.err.println("Application icon not found. Put XSLTSandbox.png into src/main/resources/icons/")
			return
		}

		runCatching {
			stage.icons.setAll(Image(resource.toExternalForm()))
		}.onFailure { ex ->
			System.err.println("Failed to load application icon: ${ex.message}")
		}
	}
}
