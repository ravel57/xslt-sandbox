package ru.ravel.xsltsandbox

import javafx.beans.binding.Bindings
import javafx.event.EventHandler
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.HBox
import javafx.scene.layout.Pane
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.scene.shape.Circle
import javafx.scene.shape.Rectangle
import javafx.scene.text.Font
import javafx.scene.text.Text
import javafx.stage.Modality
import javafx.stage.Stage
import ru.ravel.xsltsandbox.model.BlockSerialized
import ru.ravel.xsltsandbox.model.BlockType
import ru.ravel.xsltsandbox.model.InputFormatType
import java.nio.file.Path
import java.util.*
import kotlin.math.roundToInt


class BlockNode(
	var x: Double,
	var y: Double,
	var name: String = "",
	var blockType: BlockType = BlockType.MAPPING,
	var code: String = "",
	var inputCount: Int = 1,
	var outputCount: Int = 1,
	var serializedId: UUID = UUID.randomUUID(),
	var inputFormat: InputFormatType = InputFormatType.XML,
	var dataDocs: String = "",
	var otherInfo: String = "",
	var inputNames: MutableList<String> = MutableList(inputCount) { "in${it}" },
	var outputNames: MutableList<String> = MutableList(outputCount) { "out${it}" },
	var outputsData: MutableList<MutableMap<String, Any>> = mutableListOf(),
	var packagesNames: MutableList<String> = mutableListOf(),
	private val xsltPath: Path?,
) : Pane() {


	companion object {
		const val BLOCK_W = 100.0
		const val BLOCK_H = 100.0
		const val CIRCLE_R = 9.0
		const val LABEL_MARGIN = 4.0
	}

	private val rect = Rectangle().apply {
		widthProperty().bind(prefWidthProperty())
		heightProperty().bind(prefHeightProperty())
		arcWidth = 14.0
		arcHeight = 14.0
		fill = blockType.color
		stroke = Color.DARKGRAY
		strokeWidth = 4.0
	}
	private val label = Text(name).apply {
		font = Font(14.0)
		layoutXProperty().bind(
			Bindings.createDoubleBinding(
				{ (rect.width - layoutBounds.width) / 2 },
				rect.widthProperty(),
				layoutBoundsProperty()
			)
		)
		layoutYProperty().bind(
			Bindings.createDoubleBinding(
				{ rect.height + 5.0 + layoutBounds.height },
				rect.heightProperty(),
				layoutBoundsProperty()
			)
		)
	}
	val inputCircles = mutableListOf<Circle>()
	val outputCircles = mutableListOf<Circle>()
	val connectedLines = mutableListOf<Connection>()
	private var dragOffsetX = 0.0
	private var dragOffsetY = 0.0
	var onMove: (() -> Unit)? = null
	var selected: Boolean = false
		set(value) {
			field = value
			rect.stroke = if (value) Color.LIGHTGREEN else Color.DARKGRAY
			rect.strokeWidth = if (value) 4.0 else 2.0
		}
	var executing: Boolean = false
		set(value) {
			field = value
			rect.stroke = if (value) Color.RED else Color.DARKGRAY
			rect.strokeWidth = if (value) 4.0 else 2.0
		}


	init {
		setOnMouseDragged(::onBlockDragged)
		layoutX = x
		layoutY = y
		rect.stroke = Color.BLACK
		rect.fill = blockType.color

		// стилизуем прямоугольник
		rect.apply {
			arcWidth = 14.0
			arcHeight = 14.0
			fill = blockType.color
			stroke = Color.DARKGRAY
			strokeWidth = 4.0
		}

		// настраиваем текст
		label.apply {
			font = Font(14.0)
			text = name
		}

		// создаём кружки ввода/вывода
		children.add(rect)
		children.add(label)

		createIOCircles()

		// Drag & Drop обработчики на rect (и зеркально на label)
		rect.onMousePressed = EventHandler { event -> onBlockPressed(event) }
		rect.onMouseDragged = EventHandler { event -> onBlockDragged(event) }
		rect.onMouseReleased = EventHandler { event ->
			if (event.button == MouseButton.PRIMARY) {
				snapToGrid()
				updateConnectedLines()
				event.consume()
			}
		}
		label.onMouseReleased = rect.onMouseReleased

		rect.onMousePressed = EventHandler { event -> onBlockPressed(event) }
		rect.onMouseDragged = EventHandler { event -> onBlockDragged(event) }
		label.onMousePressed = rect.onMousePressed
		label.onMouseDragged = rect.onMouseDragged

		this.onMouseClicked = EventHandler { event ->
			if (event.button == MouseButton.PRIMARY) {
				val app = scene?.window?.userData as? LayoutEditor
				app?.selectBlock(this)
				if (event.clickCount == 2) {
					(scene?.window?.userData as? LayoutEditor)?.selectBlock(this)
					val validatorStage = Stage()
					XsltValidatorApp(
						isOpenedInEditorMode = false,
						xsltPath = xsltPath,
					).start(validatorStage)
					event.consume()
				}
			}
		}

		// Контекстное меню по ПКМ на блоке
		this.onMousePressed = EventHandler { event ->
			if (event.button == MouseButton.SECONDARY) {
				val contextMenu = ContextMenu()

				val deleteItem = MenuItem("Удалить")
				deleteItem.setOnAction {
					(scene?.window?.userData as? LayoutEditor)?.deleteBlockRequest(this)
				}

				val prevBlocksItem = MenuItem("Предыдущие связанные блоки")
				prevBlocksItem.setOnAction {
					val prevBlocks = connectedLines
						.filter { it.to == this }
						.map { it.from.name }
						.joinToString("\n")
					showAlert("Предыдущие блоки", if (prevBlocks.isNotBlank()) prevBlocks else "Нет связанных")
				}

				val nextBlocksItem = MenuItem("Следующие связанные блоки")
				nextBlocksItem.setOnAction {
					val nextBlocks = connectedLines
						.filter { it.from == this }
						.map { it.to.name }
						.joinToString("\n")
					showAlert("Следующие блоки", if (nextBlocks.isNotBlank()) nextBlocks else "Нет связанных")
				}

				contextMenu.items.addAll(deleteItem, prevBlocksItem, nextBlocksItem)
				contextMenu.show(this, event.screenX, event.screenY)
				event.consume()
			}
		}
	}


	private fun onBlockPressed(event: MouseEvent) {
		if (event.button == MouseButton.PRIMARY) {
			val parentPane = parent as Node
			val mouseInPane = parentPane.sceneToLocal(event.sceneX, event.sceneY)
			dragOffsetX = mouseInPane.x - layoutX
			dragOffsetY = mouseInPane.y - layoutY
			event.consume()
		}
	}

	private fun onBlockDragged(event: MouseEvent) {
		if (event.button == MouseButton.PRIMARY) {
			val parentPane = parent as Pane
			val mouseInPane = parentPane.sceneToLocal(event.sceneX, event.sceneY)
			layoutX = mouseInPane.x - dragOffsetX
			layoutY = mouseInPane.y - dragOffsetY
			updateConnectedLines()
			parentPane.apply {
				val b = layoutBounds
				minWidth = b.maxX
				minHeight = b.maxY
			}
			onMove?.invoke()
			event.consume()
		}
	}

	private fun showAlert(title: String, message: String) {
		val alert = Alert(Alert.AlertType.INFORMATION)
		alert.title = title
		alert.headerText = null
		alert.contentText = message
		alert.showAndWait()
	}

	private fun showCodeEditor() {
		val dialog = Stage()
		dialog.title = "Редактор блока \"$name\""

		val titleTextArea = TextArea().apply {
			prefWidth = 400.0
			prefHeight = 40.0
			text = name
		}

		if (blockType in arrayOf(BlockType.INPUT_DATA, BlockType.START)) {
			// Форматы
			val formats = InputFormatType.entries
			val toggleGroup = ToggleGroup()
			val radioButtons = formats.map { format ->
				RadioButton(format.name).apply {
					this.toggleGroup = toggleGroup
					isSelected = (format == inputFormat)
				}
			}
			val radiosBox = VBox(10.0, *radioButtons.toTypedArray()).apply {
				padding = Insets(5.0)
			}
			val radioTab = Tab("Формат", radiosBox).apply { isClosable = false }

			// Код
			val codeTextArea = TextArea().apply {
				prefWidth = 400.0
				prefHeight = 250.0
				text = code
			}
			val codeTab = Tab("Код", codeTextArea).apply { isClosable = false }
			val tabPane = TabPane(codeTab, radioTab)
			val saveButton = Button("Сохранить").apply {
				setOnAction {
					name = titleTextArea.text
					label.text = name
					inputFormat = formats[radioButtons.indexOfFirst { it.isSelected }]
					code = codeTextArea.text
					recreateIOCircles()
					dialog.close()
				}
			}

			val vbox = VBox(10.0, titleTextArea, tabPane, saveButton).apply {
				padding = Insets(15.0)
			}
			dialog.scene = Scene(vbox)
			dialog.initModality(Modality.APPLICATION_MODAL)
			dialog.showAndWait()
		} else {
			// --- Для других типов (оставить старый редактор) ---
			val codeTextArea = TextArea().apply {
				prefWidth = 400.0
				prefHeight = 250.0
				text = code
			}
			val dataDocsTextArea = TextArea().apply {
				prefWidth = 400.0
				prefHeight = 250.0
				text = dataDocs
			}
			// Новая вкладка
			val editableInputsBox = buildEditableInputsBox()
			val editableOutputsBox = buildEditableOutputsBox()
			val configBox = VBox(10.0, editableInputsBox, editableOutputsBox).apply {
				padding = Insets(5.0)
			}
			val configTab = Tab("Конфигурация входов и выходов", configBox).apply { isClosable = false }

			val codeTab = Tab("Код", codeTextArea).apply { isClosable = false }
			val docsTab = Tab("DataDocs", dataDocsTextArea).apply { isClosable = false }
			val tabPane = TabPane(codeTab, docsTab, configTab)
			var pipPackagesBox: VBox? = null
//			if (blockType == BlockType.MAPPING_PYTHON) {
//				pipPackagesBox = buildEditablePipBox()
//				val pipTab = Tab("pip", pipPackagesBox).apply { isClosable = false }
//				tabPane.tabs.add(pipTab)
//			}
			val saveButton = Button("Сохранить").apply {
				setOnAction {
//					if (blockType == BlockType.MAPPING_PYTHON && pipPackagesBox != null) {
//						val scrollPane = pipPackagesBox.children[1] as ScrollPane
//						val rowsBox = scrollPane.content as VBox
//						val newPackages = mutableListOf<String>()
//						for (row in rowsBox.children) {
//							val box = row as HBox
//							val tf = box.children[0] as TextField
//							val value = tf.text.trim()
//							if (value.isNotBlank()) newPackages.add(value)
//						}
//						packagesNames = newPackages
//					}
					val newInputNames = mutableListOf<String>()
					val scrollPane = editableInputsBox.children[1] as ScrollPane
					val rowsBox = scrollPane.content as VBox
					for (row in rowsBox.children) {
						val box = row as HBox
						val tf = box.children[0] as TextField
						newInputNames.add(tf.text)
					}
					inputNames = newInputNames
					inputCount = newInputNames.size
					val newOutputNames = mutableListOf<String>()
					val outputsScrollPane = editableOutputsBox.children[1] as ScrollPane
					val outputsRowsBox = outputsScrollPane.content as VBox
					for (row in outputsRowsBox.children) {
						val box = row as HBox
						val tf = box.children[0] as TextField
						newOutputNames.add(tf.text)
					}
					outputNames = newOutputNames
					outputCount = newOutputNames.size
					code = codeTextArea.text
					dataDocs = dataDocsTextArea.text
					name = titleTextArea.text
					label.text = name
					recreateIOCircles()
					dialog.close()
				}
			}
			val vbox = VBox(10.0, titleTextArea, tabPane, saveButton).apply {
				padding = Insets(15.0)
			}
			dialog.scene = Scene(vbox)
			dialog.initModality(Modality.APPLICATION_MODAL)
			dialog.showAndWait()
		}
	}

	private fun buildEditableInputsBox(): VBox {
		val inputsBox = VBox(4.0)
		val scrollContent = VBox(4.0)
		val scrollPane = ScrollPane(scrollContent).apply {
			prefHeight = 180.0
			isFitToWidth = true
			vbarPolicy = ScrollPane.ScrollBarPolicy.ALWAYS
		}

		val addBtn = Button("+").apply {
			setOnAction {
				addInputRow(scrollContent)
			}
		}
		val header = HBox(6.0, Label("Входы:"), addBtn)
		inputsBox.children.add(header)
		inputsBox.children.add(scrollPane)
		inputNames.forEach { name ->
			addInputRow(scrollContent, name)
		}
		if (scrollContent.children.isEmpty()) {
			addInputRow(scrollContent)
		}
		return inputsBox
	}

	private fun addInputRow(container: VBox, initialText: String = "") {
		val defaultName = if (initialText.isEmpty()) "in${container.children.size}" else initialText
		val tf = TextField(defaultName)
		lateinit var box: HBox
		val delBtn = Button("–").apply {
			setOnAction {
				if (container.children.size > 1) {
					container.children.remove(box)
				}
			}
		}
		box = HBox(6.0, tf, delBtn)
		box.alignment = Pos.CENTER_LEFT
		container.children.add(box)
	}

	private fun buildEditableOutputsBox(): VBox {
		val outputsBox = VBox(4.0)
		val scrollContent = VBox(4.0)
		val scrollPane = ScrollPane(scrollContent).apply {
			prefHeight = 180.0
			isFitToWidth = true
			vbarPolicy = ScrollPane.ScrollBarPolicy.ALWAYS
		}
		val addBtn = Button("+").apply {
			setOnAction {
				addOutputRow(scrollContent)
			}
		}
		val header = HBox(6.0, Label("Выходы:"), addBtn)
		outputsBox.children.add(header)
		outputsBox.children.add(scrollPane)
		outputNames.forEach { name ->
			addOutputRow(scrollContent, name)
		}
		if (scrollContent.children.isEmpty()) {
			addOutputRow(scrollContent)
		}
		return outputsBox
	}

	private fun addOutputRow(container: VBox, initialText: String = "") {
		val defaultName = if (initialText.isEmpty()) "out${container.children.size}" else initialText
		val tf = TextField(defaultName)
		lateinit var box: HBox
		val delBtn = Button("–").apply {
			setOnAction {
				if (container.children.size > 1) {
					container.children.remove(box)
				}
			}
		}
		box = HBox(6.0, tf, delBtn)
		box.alignment = Pos.CENTER_LEFT
		container.children.add(box)
	}


	private fun recreateIOCircles() {
		// Удалить старые кружки
		children.removeAll(inputCircles)
		children.removeAll(outputCircles)
		inputCircles.clear()
		outputCircles.clear()
		createIOCircles()

		// 1. Удалить соединения с недопустимыми индексами (например, если выходов стало меньше)
		val invalidConnections = connectedLines.filter {
			it.from == this && it.fromPort >= outputCircles.size ||
					it.to == this && it.toPort >= inputCircles.size
		}
		invalidConnections.forEach { conn ->
			(scene?.window?.userData as? LayoutEditor)?.let { app ->
				app.connections.remove(conn)
				conn.from.connectedLines.remove(conn)
				conn.to.connectedLines.remove(conn)
				(conn.line.parent as? Pane)?.children?.remove(conn.line)
			}
		}
		connectedLines.removeAll(invalidConnections)

		// 2. Обновить линии (перепривязать к новым кружкам)
		updateConnectedLines()

		// 3. Переназначить обработчики для новых кружков
		(scene?.window?.userData as? LayoutEditor)?.let { app ->
			rebuildCirclesHandlers { outIndex, outCircle ->
				outCircle.onMousePressed = javafx.event.EventHandler { event ->
					if (event.button == MouseButton.PRIMARY) {
						app.startConnectionFromBlock(this, outIndex)
						event.consume()
					}
				}
				outCircle.onMouseDragged = javafx.event.EventHandler { event ->
					if (event.button == MouseButton.PRIMARY) {
						app.continueConnectionDrag(event)
						event.consume()
					}
				}
				outCircle.onMouseReleased = javafx.event.EventHandler { event ->
					if (event.button == MouseButton.PRIMARY) {
						event.consume()
					}
				}
			}
		}
		(scene?.window?.userData as? LayoutEditor)?.setupHandlersForBlock(this)
	}

	private fun createIOCircles() {
		children.removeAll(inputCircles)
		children.removeAll(outputCircles)
		inputCircles.clear()
		outputCircles.clear()
		val r = CIRCLE_R
		if (blockType != BlockType.START && blockType != BlockType.INPUT_DATA) {
			repeat(inputCount) { i ->
				val circle = Circle(r).apply {
					centerX = 0.0
					centerYProperty().bind(
						rect.heightProperty()
							.multiply(i + 1.0)
							.divide(inputCount + 1.0)
					)
					stroke = Color.DARKBLUE
					strokeWidth = 1.6
					fill = Color.LIGHTSKYBLUE
					Tooltip.install(this, Tooltip(inputNames.getOrNull(i) ?: ""))
				}
				inputCircles += circle
				children += circle
			}
		}
		if (blockType != BlockType.EXIT) {
			repeat(outputCount) { i ->
				val circle = Circle(r).apply {
					centerXProperty().bind(rect.widthProperty())
					centerYProperty().bind(
						rect.heightProperty()
							.multiply(i + 1.0)
							.divide(outputCount + 1.0)
					)
					stroke = Color.DARKRED
					strokeWidth = 1.6
					fill = Color.ORANGE
					Tooltip.install(this, Tooltip(outputNames.getOrNull(i) ?: ""))
				}
				outputCircles += circle
				children += circle
			}
		}
	}

	fun inputPoint(index: Int): Pair<Double, Double> =
		Pair(
			layoutX + inputCircles[index].centerX,
			layoutY + inputCircles[index].centerY
		)

	fun outputPoint(index: Int): Pair<Double, Double> =
		Pair(
			layoutX + outputCircles[index].centerX,
			layoutY + outputCircles[index].centerY
		)

	fun updateConnectedLines() {
		connectedLines.forEach { it.updateLine() }
	}

	fun rebuildCirclesHandlers(handler: (outIndex: Int, outCircle: Circle) -> Unit) {
		outputCircles.forEachIndexed { outIndex, outCircle ->
			handler(outIndex, outCircle)
		}
	}

	fun toSerialized(): BlockSerialized = BlockSerialized(
		id = this.serializedId,
		x = this.layoutX,
		y = this.layoutY,
		name = this.name,
		blockType = this.blockType.name,
		inputFormat = this.inputFormat,
		code = this.code,
		dataDocs = this.dataDocs,
		otherInfo = this.otherInfo,
		inputCount = this.inputCount,
		outputCount = this.outputCount,
		inputNames = this.inputNames.toList(),
		outputNames = this.outputNames.toList(),
		outputsData = outputsData,
		packagesNames = packagesNames,
	)

	fun updateOutputs() {
		// Удаляем старые выходы из children
		children.removeAll(outputCircles)
		outputCircles.clear()
		for (i in 0 until outputCount) {
			val circle = Circle(width - 10.0, 15.0 + i * 20, 9.0, Color.ORANGE).apply {
				stroke = Color.DARKRED
				strokeWidth = 4.0
			}
			outputCircles.add(circle)
			children.add(circle)

			// Обработчики:
			circle.onMousePressed = EventHandler { event ->
				if (event.button == MouseButton.PRIMARY) {
					(scene?.window?.userData as? LayoutEditor)?.let { app ->
						app.startConnectionFromBlock(this@BlockNode, i)
					}
					event.consume()
				}
			}
			circle.onMouseDragged = EventHandler { event ->
				if (event.button == MouseButton.PRIMARY) {
					(scene?.window?.userData as? LayoutEditor)?.continueConnectionDrag(event)
					event.consume()
				}
			}
			circle.onMouseReleased = EventHandler { event ->
				if (event.button == MouseButton.PRIMARY) {
					event.consume()
				}
			}
		}
	}


	private fun addPipRow(container: VBox, initialText: String = "") {
		val tf = TextField(initialText)
		lateinit var box: HBox
		val delBtn = Button("–").apply {
			setOnAction {
				container.children.remove(box)
			}
		}
		box = HBox(6.0, tf, delBtn)
		box.alignment = Pos.CENTER_LEFT
		container.children.add(box)
	}

	private fun snapToGrid(gridSize: Double = 10.0) {
		layoutX = (layoutX / gridSize).roundToInt() * gridSize
		layoutY = (layoutY / gridSize).roundToInt() * gridSize
	}

}
