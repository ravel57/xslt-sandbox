package ru.ravel.xsltsandbox.diagram

import javafx.scene.Parent
import javafx.scene.Scene
import javafx.stage.Modality
import javafx.stage.Stage
import javafx.stage.StageStyle
import ru.ravel.xsltsandbox.models.DocSession

class FlowDiagramWindow(
	private val ownerStage: Stage,
	private val currentSession: DocSession, // <-- замените на ваш реальный тип currentSession
) {
	private var diagramStage: Stage? = null

	fun open() {
		val stage = diagramStage
			?: Stage(StageStyle.DECORATED).also { diagramStage = it }

		val root: Parent = buildRoot()  // перенесите сюда ваш buildDiagramRoot(...)
		val scene = Scene(root)

		// наследуем стили от основного окна (если нужно)
		ownerStage.scene?.stylesheets?.let { scene.stylesheets.setAll(it) }

		stage.initOwner(ownerStage)
		stage.initModality(Modality.NONE)
		stage.title = "Flow diagram"
		stage.scene = scene

		// позиционирование около owner (по желанию)
		stage.x = ownerStage.x + 40
		stage.y = ownerStage.y + 40

		stage.show()
		stage.toFront()
	}

	private fun buildRoot(): Parent {
		// сюда перенесите содержимое вашего buildDiagramRoot(...)
		// и используйте session/ownerStage при необходимости
		TODO("move your UI code here")
	}
}
