package ru.ravel.xsltsandbox

import com.fasterxml.jackson.databind.ObjectMapper
import javafx.application.Application
import javafx.application.Platform
import javafx.event.EventHandler
import javafx.geometry.Insets
import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.canvas.Canvas
import javafx.scene.control.*
import javafx.scene.input.KeyCode
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.Pane
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.scene.shape.Circle
import javafx.scene.shape.Line
import javafx.stage.FileChooser
import javafx.stage.Stage
import javafx.util.Callback
import ru.ravel.xsltsandbox.model.BlockType
import ru.ravel.xsltsandbox.model.BlocksData
import ru.ravel.xsltsandbox.model.InputFormatType
import java.io.File
import java.util.*
import javafx.stage.DirectoryChooser
import javafx.scene.control.MenuBar
import javafx.scene.control.Menu
import javafx.scene.control.MenuItem
import javafx.scene.input.KeyCombination
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.hypot


class LayoutEditor : Application() {
	private var currentProjectFile: File? = null
	private val blocks = mutableListOf<BlockNode>()
	val connections = mutableListOf<OrthogonalConnection>()
	private var draggingLine: Line? = null
	private var draggingFromBlock: BlockNode? = null
	private var draggingFromOutputIndex: Int? = null
	private var selectedBlock: BlockNode? = null
	private var selectedConnection: OrthogonalConnection? = null
	private var activeContextMenu: ContextMenu? = null
	private val windowW = 800.0
	private val windowH = 600.0
	private val gridCanvas = Canvas(windowW, windowH)
	private val workspaceGroup = Group()
	private val contentPane = Pane().apply {
		children.setAll(gridCanvas, workspaceGroup)
		prefWidth = windowW * 2
		prefHeight = windowH * 2
		padding = Insets(10.0)
		isFocusTraversable = true
	}
	private val scrollPane = ScrollPane(contentPane).apply {
		isPannable = false
		hbarPolicy = ScrollPane.ScrollBarPolicy.ALWAYS
		vbarPolicy = ScrollPane.ScrollBarPolicy.ALWAYS
	}
	private var lastEnsureVisible = 0L
	private val fileTreeView = TreeView<File>().apply {
		isShowRoot = false
		prefWidth = 250.0
		cellFactory = Callback { _: TreeView<File> ->
			object : TreeCell<File>() {
				override fun updateItem(item: File?, empty: Boolean) {
					super.updateItem(item, empty)
					text = if (empty || item == null) "" else item.name
				}
			}
		}
		selectionModel.selectedItemProperty().addListener { _, _, newItem ->
			val folder = newItem?.value
			if (folder != null && folder.isDirectory) {
				folder.listFiles { f -> f.isFile && f.name == "Layout.xml" }
					?.firstOrNull()
					?.let { xmlFile ->
						importLayoutFromXml(xmlFile)
					}
			}
		}
	}


	override fun start(primaryStage: Stage) {
		drawGrid(gridCanvas, 10.0)
		gridCanvas.widthProperty().bind(contentPane.widthProperty())
		gridCanvas.heightProperty().bind(contentPane.heightProperty())

		gridCanvas.widthProperty().addListener { _, _, _ -> drawGrid(gridCanvas) }
		gridCanvas.heightProperty().addListener { _, _, _ -> drawGrid(gridCanvas) }

		primaryStage.userData = this

		// ПАНОРАМИРОВАНИЕ мышью
		var panLastX = 0.0
		var panLastY = 0.0
		var panning = false
		contentPane.onMousePressed = EventHandler { event ->
			if (event.button == MouseButton.MIDDLE) {
				panLastX = event.screenX
				panLastY = event.screenY
				panning = true
				contentPane.cursor = javafx.scene.Cursor.CLOSED_HAND
				event.consume()
			}
		}
		contentPane.onMouseDragged = EventHandler { event ->
			if (panning && event.button == MouseButton.MIDDLE) {
				val dx = panLastX - event.screenX
				val dy = panLastY - event.screenY
				scrollPane.hvalue = (scrollPane.hvalue * (contentPane.width - scrollPane.viewportBounds.width) + dx)
					.coerceIn(
						0.0,
						contentPane.width - scrollPane.viewportBounds.width
					) / (contentPane.width - scrollPane.viewportBounds.width)
				scrollPane.vvalue = (scrollPane.vvalue * (contentPane.height - scrollPane.viewportBounds.height) + dy)
					.coerceIn(
						0.0,
						contentPane.height - scrollPane.viewportBounds.height
					) / (contentPane.height - scrollPane.viewportBounds.height)
				panLastX = event.screenX
				panLastY = event.screenY
				event.consume()
			}
		}
		contentPane.onMouseReleased = EventHandler { event ->
			if (panning && event.button == MouseButton.MIDDLE) {
				panning = false
				contentPane.cursor = javafx.scene.Cursor.DEFAULT
				event.consume()
			}
		}

		// Контекстное меню для создания блока
		contentPane.onMouseClicked = EventHandler { event ->
			if (event.button == MouseButton.SECONDARY && event.target === contentPane) {
				val n = event.pickResult.intersectedNode
				if (n is BlockNode || n is Circle) return@EventHandler // не показываем по блокам и кружкам
				event.consume()
				val contextMenu = ContextMenu()
				BlockType.entries.forEach { type ->
					val item = MenuItem(type.displayName)
					item.setOnAction {
						addBlock(contentPane, event.x, event.y, type.displayName, type)
					}
					contextMenu.items.add(item)
				}
				contextMenu.show(contentPane, event.screenX, event.screenY)
				activeContextMenu = contextMenu
				event.consume()
			}
			if (event.button == MouseButton.PRIMARY) {
				if (event.target === contentPane) {
					selectBlock(null)
					selectConnection(null)
				}
			}
			contentPane.requestFocus()
		}

		val openProjectButton = Button("Открыть проект").apply {
			setOnAction {
				val fileChooser = FileChooser()
				fileChooser.title = "Открыть проект"
				fileChooser.extensionFilters.addAll(
					FileChooser.ExtensionFilter("JSON", "*.json"),
				)
				val file = fileChooser.showOpenDialog(primaryStage)
				if (file != null) {
					importBlocksFromFile(file)
					currentProjectFile = file
					primaryStage.title = currentProjectFile?.name ?: "Low code processes executor"
				}
			}
		}
		val splitPane = SplitPane(fileTreeView, scrollPane).apply {
			setDividerPositions(0.15)
		}
		val menuBar = buildMenuBar(primaryStage)
		val root = VBox(menuBar, splitPane)
		// Горячие клавиши
		val scene = Scene(root, windowW, windowH).apply {
			setOnKeyPressed { event ->
				if (event.code in arrayOf(KeyCode.DELETE, KeyCode.BACK_SPACE)) {
					selectedBlock?.let { block ->
						deleteBlockRequest(block)    //FIXME
					}
					selectedConnection?.let { conn ->
						connections.remove(conn)
						conn.from.connectedLines.remove(conn)
						conn.to.connectedLines.remove(conn)
						(conn.line.parent as? Pane)?.children?.remove(conn.line)
						selectedConnection = null
					}
				}
				// Ctrl+S для сохранения
				if (event.isControlDown && event.code == KeyCode.S) {
					if (currentProjectFile != null) {
						saveToFile(currentProjectFile!!)
					} else {
						val fileChooser = FileChooser().apply {
							title = "Сохранить проект"
							extensionFilters.add(FileChooser.ExtensionFilter("JSON Files", "*.json"))
						}
						val file = fileChooser.showSaveDialog(primaryStage)
						if (file != null) {
							saveToFile(file)
							currentProjectFile = file
						}
					}
					event.consume()
				}
				// Ctrl+O для открытия
				if (event.isControlDown && event.code == KeyCode.O) {
					openProject(primaryStage)
					event.consume()
				}
			}
		}
		primaryStage.scene = scene.apply {
			addEventFilter(MouseEvent.MOUSE_PRESSED) { _ ->
				activeContextMenu?.let { menu ->
					if (menu.isShowing) {
						menu.hide()
						activeContextMenu = null
					}
				}
			}
		}
		primaryStage.title = currentProjectFile?.name ?: "rCRIF B)"
		primaryStage.show()
		setupContextMenu()
		contentPane.requestFocus()
	}


	private fun buildMenuBar(primaryStage: Stage): MenuBar {
		val fileMenu = Menu("Файл")
		val openItem = MenuItem("Открыть проект…").apply {
			accelerator = KeyCombination.keyCombination("Ctrl+O")
			setOnAction { openProject(primaryStage) }
		}
		val saveItem = MenuItem("Сохранить").apply {
			accelerator = KeyCombination.keyCombination("Ctrl+S")
			setOnAction { saveProject(primaryStage) }
		}
		val exitItem = MenuItem("Выход").apply {
			accelerator = KeyCombination.keyCombination("Ctrl+Q")
			setOnAction { Platform.exit() }
		}
		fileMenu.items.addAll(
			openItem,
			saveItem,
			SeparatorMenuItem(),
			exitItem
		)
		return MenuBar().apply { menus.add(fileMenu) }
	}


	private fun openProject(primaryStage: Stage) {
		val dirChooser = DirectoryChooser().apply { title = "Выбрать папку проекта" }
		val projectDir = dirChooser.showDialog(primaryStage) ?: return
		val proceduresDir = File(projectDir, "Procedures")
		val mainFlowDir = File(projectDir, "MainFlow")
		val rootItem = TreeItem(projectDir).apply { isExpanded = true }
		if (proceduresDir.exists() && proceduresDir.isDirectory) {
			rootItem.children.add(
				createNode(proceduresDir).apply { isExpanded = true }
			)
		}
		if (mainFlowDir.exists() && mainFlowDir.isDirectory) {
			rootItem.children.add(
				createNode(mainFlowDir).apply { isExpanded = true }
			)
		}
		fileTreeView.root = rootItem
		fileTreeView.isShowRoot = false
		currentProjectFile = projectDir
		primaryStage.title = projectDir.name
	}


	private fun saveProject(stage: Stage) {
		if (currentProjectFile != null && currentProjectFile!!.extension.equals("json", true)) {
			saveToFile(currentProjectFile!!)
		}
	}


	private fun createNode(file: File): TreeItem<File> {
		val node = TreeItem(file)
		if (file.isDirectory) {
			file.listFiles()?.filter { it.isDirectory }?.forEach { child ->
				node.children.add(createNode(child))
			}
		}
		return node
	}


	private fun importLayoutFromXml(file: File) {
		// 1. очищаем сцену
		blocks.clear()
		connections.clear()
		workspaceGroup.children.clear()

		// 2. читаем XML
		val doc = DocumentBuilderFactory.newInstance()
			.newDocumentBuilder()
			.parse(file)
			.also { it.documentElement.normalize() }

		// 3. создаём блоки
		val uidToBlock = mutableMapOf<String, BlockNode>()
		val elements = doc.getElementsByTagName("DiagramElement")
		for (i in 0 until elements.length) {
			val el = elements.item(i) as Element
			val uid  = el.getAttribute("UID")
			val x    = el.getSingleChild("X").textContent.toDouble()
			val y    = el.getSingleChild("Y").textContent.toDouble()
			val ref  = el.getSingleChild("Reference").textContent
			val w    = el.getSingleChild("Width").textContent.toDouble()
			val h    = el.getSingleChild("Height").textContent.toDouble()

			val type = BlockType.entries.firstOrNull { ref.startsWith(it.name, true) }
				?: BlockType.MAPPING

			val block = BlockNode(x, y, ref, type).apply {
				prefWidth  = w
				prefHeight = h
			}
			blocks.add(block)
			workspaceGroup.children.add(block)
			uidToBlock[uid] = block
		}

		// 4. создаём соединения
		val connNodes = doc.getElementsByTagName("DiagramConnection")
		for (i in 0 until connNodes.length) {
			val connEl = connNodes.item(i) as Element
			val ends = connEl.getElementsByTagName("DiagramEndPoint")
			if (ends.length != 2) continue

			val from = uidToBlock[(ends.item(0) as Element).getAttribute("ElementRef")] ?: continue
			val to   = uidToBlock[(ends.item(1) as Element).getAttribute("ElementRef")] ?: continue

			// --- читаем все DiagramSplit ---
			val splitPts = buildList<Pair<Double, Double>> {
				val splits = connEl.getElementsByTagName("DiagramSplit")
				for (j in 0 until splits.length) {
					val s = splits.item(j) as Element
					val x = s.getSingleChild("X").textContent.toDouble()
					val y = s.getSingleChild("Y").textContent.toDouble()
					add(x to y)
				}
			}

			val conn = OrthogonalConnection(from, to, contentPane, 0, 0, splitPts)
			from.connectedLines.add(conn); to.connectedLines.add(conn); connections.add(conn)
		}


		// 5. расширяем рабочую область и фокусируем её
		updateWorkspaceSize()
		contentPane.requestFocus()
	}


	private fun Element.getSingleChild(tag: String): Element =
		getElementsByTagName(tag).item(0) as Element


	private fun updateWorkspaceSize(margin: Double = 200.0) {
		if (blocks.isEmpty()) return
		var maxX = 0.0
		var maxY = 0.0
		blocks.forEach { b ->
			val w = b.layoutBounds.width
			val h = b.layoutBounds.height
			maxX = maxOf(maxX, b.layoutX + w)
			maxY = maxOf(maxY, b.layoutY + h)
		}
		contentPane.prefWidth = maxX + margin
		contentPane.prefHeight = maxY + margin
	}


	fun selectBlock(block: BlockNode?) {
		blocks.forEach { it.selected = false }
		connections.forEach { it.selected = false }
		selectedBlock = block
		block?.selected = true
		selectedConnection = null
	}


	fun selectConnection(conn: OrthogonalConnection?) {
		connections.forEach { it.selected = false }
		blocks.forEach { it.selected = false }
		selectedConnection = conn
		conn?.selected = true
		selectedBlock = null
	}


	fun deleteBlockRequest(block: BlockNode) {
		val toRemove = connections.filter { it.from == block || it.to == block }
		toRemove.forEach { conn ->
			(conn.line.parent as? Pane)?.children?.remove(conn.line)
			conn.from.connectedLines.remove(conn)
			conn.to.connectedLines.remove(conn)
		}
		connections.removeAll(toRemove)
		(block.parent as? Pane)?.children?.remove(block)
		blocks.remove(block)
		selectedBlock = null
	}


	private fun addBlock(parent: Pane, x: Double, y: Double, name: String, blockType: BlockType) {
		val block = BlockNode(x, y, name, blockType)
		blocks.add(block)
		block.onMove = { ensureBlockVisible(block) }
		parent.children.add(block)
		setupHandlersForBlock(block)
	}


	// --- Сериализация и загрузка ---
	private fun saveToFile(file: File) {
		currentProjectFile = file
		val blocksData = BlocksData(blocks.map { it.toSerialized() }, connections.map { it.toSerialized() })
		file.writeText(ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(blocksData))
	}

	private fun importBlocksFromFile(file: File) {
		currentProjectFile = file
		updateBlocks()
		val data: BlocksData = ObjectMapper().readValue(file, BlocksData::class.java)

		// Очистка
		blocks.clear()
		connections.clear()
		(scrollPane.content as? Pane)?.children?.removeIf { it is BlockNode || it is Line }

		val idToBlock = mutableMapOf<UUID, BlockNode>()
		data.blocks.forEach { b ->
			val blockType = try {
				BlockType.valueOf(b.blockType)
			} catch (_: Exception) {
				BlockType.MAPPING
			}
			val defaultInputCount = if (blockType in arrayOf(BlockType.START, BlockType.INPUT_DATA)) {
				0
			} else {
				b.inputCount.coerceAtLeast(1)
			}
			val defaultOutputCount = if (blockType == BlockType.EXIT) {
				0
			} else {
				b.outputCount.coerceAtLeast(1)
			}
			val block = BlockNode(
				x = b.x,
				y = b.y,
				name = b.name,
				blockType = blockType,
				inputCount = defaultInputCount,
				outputCount = defaultOutputCount,
				serializedId = b.id,
				inputFormat = b.inputFormat ?: InputFormatType.XML,
				code = b.code ?: "",
				dataDocs = b.dataDocs ?: "",
				otherInfo = b.otherInfo ?: "",
				inputNames = b.inputNames?.toMutableList() ?: mutableListOf(),
				outputNames = b.outputNames?.toMutableList() ?: mutableListOf(),
				outputsData = b.outputsData ?: mutableListOf(),
				packagesNames = b.packagesNames ?: mutableListOf(),
			)
			block.onMove = { ensureBlockVisible(block) }
			blocks.add(block)
			idToBlock[b.id] = block
			(scrollPane.content as? Pane)?.children?.add(block)
			block.rebuildCirclesHandlers { outIndex, outCircle ->
				outCircle.onMousePressed = EventHandler { event ->
					if (event.button == MouseButton.PRIMARY) {
						selectBlock(block)
						(scrollPane.content as? Pane)?.requestFocus()
						val (startX, startY) = block.outputPoint(outIndex)
						val line = Line(startX, startY, startX, startY).apply {
							stroke = Color.BLUE
							strokeWidth = 2.0
						}
						(scrollPane.content as? Pane)?.children?.add(line)
						draggingLine = line
						draggingFromBlock = block
						draggingFromOutputIndex = outIndex
						event.consume()
					}
				}
				outCircle.onMouseDragged = EventHandler { event ->
					if (event.button == MouseButton.PRIMARY && draggingLine != null) {
						val paneCoords = (scrollPane.content as? Pane)?.sceneToLocal(event.sceneX, event.sceneY)
						if (paneCoords != null) {
							draggingLine!!.endX = paneCoords.x
							draggingLine!!.endY = paneCoords.y
						}
						event.consume()
					}
				}
				outCircle.onMouseReleased = EventHandler { event ->
					if (event.button == MouseButton.PRIMARY && draggingLine != null) {
						val paneCoords = contentPane.sceneToLocal(event.sceneX, event.sceneY)
						val toBlockPair = blocks.asSequence()
							.flatMap { other ->
								other.inputCircles.mapIndexed { inputIdx, inputCircle ->
									Triple(
										other, inputCircle, inputIdx
									)
								}
							}.find { (other, inputCircle, _) ->
								if (other == draggingFromBlock) return@find false
								val p = inputCircle.localToScene(inputCircle.centerX, inputCircle.centerY)
								val panePoint = contentPane.sceneToLocal(p.x, p.y)
								if (panePoint == null || paneCoords == null) {
									return@find false
								}
								val dx = panePoint.x - paneCoords.x
								val dy = panePoint.y - paneCoords.y
								Math.hypot(dx, dy) <= inputCircle.radius + 4
							}
						if (toBlockPair != null && paneCoords != null) {
							val (toBlock, _, inputIdx) = toBlockPair

							// убираем временную линию предпросмотра
							contentPane.children?.remove(draggingLine)

							// создаём ортогональное соединение: host = contentPane
							val conn = OrthogonalConnection(
								draggingFromBlock!!,        // from-блок
								toBlock,                    // to-блок
								contentPane,                // <-- правильный Pane-хост
								draggingFromOutputIndex!!,  // номер выходного порта
								inputIdx                    // номер входного порта
							)

							connections.add(conn)
							draggingFromBlock!!.connectedLines.add(conn)
							toBlock.connectedLines.add(conn)

							// обработчик клика по линии (оставляем без изменений)
							conn.line.onMouseClicked = EventHandler { onMouseEvent ->
								if (onMouseEvent.button == MouseButton.PRIMARY) {
									selectConnection(conn)
									(conn.line.parent as? Pane)?.requestFocus()
									onMouseEvent.consume()
								}
							}

							draggingLine = null
							draggingFromOutputIndex = null
						} else {
							// к цели не попали — удаляем временную линию
							contentPane.children?.remove(draggingLine)
							draggingLine = null
							draggingFromOutputIndex = null
						}
						event.consume()
					}
				}
			}
		}

		/// ───────── восстановление соединений ─────────
		data.connections.forEach { c ->
			val fromBlock = idToBlock[c.fromId] ?: return@forEach
			val toBlock   = idToBlock[c.toId]   ?: return@forEach
			val outIdx    = c.fromOutputIndex
			val inIdx     = c.toInputIndex

			// создаём ортогональное соединение
			val conn = OrthogonalConnection(
				fromBlock,
				toBlock,
				contentPane,
				outIdx,
				inIdx
			)

			connections.add(conn)
			fromBlock.connectedLines.add(conn)
			toBlock.connectedLines.add(conn)

			// обработчик клика вешаем на всю группу
			conn.setOnMouseClicked { e ->
				if (e.button == MouseButton.PRIMARY) {
					selectConnection(conn)
					contentPane.requestFocus()
					e.consume()
				}
			}
		}

	}


	fun startConnectionFromBlock(block: BlockNode, outputIndex: Int) {
		draggingFromBlock = block
		draggingFromOutputIndex = outputIndex
		val (startX, startY) = block.outputPoint(outputIndex)
		val line = Line(startX, startY, startX, startY).apply {
			stroke = Color.BLUE
			strokeWidth = 2.0
		}
		(scrollPane.content as Pane).children.add(line)
		draggingLine = line
	}

	fun continueConnectionDrag(event: MouseEvent) {
		draggingLine?.let { line ->
			val paneCoords = (scrollPane.content as Pane).sceneToLocal(event.sceneX, event.sceneY)
			line.endX = paneCoords.x
			line.endY = paneCoords.y
		}
	}


	private fun updateBlocks() {
		// 1. Сохраняем положение камеры
		val hValue = scrollPane.hvalue
		val vValue = scrollPane.vvalue

		// 2. Меняем контент (или делаем что угодно с блоками)
		val grid = gridCanvas
		contentPane.children?.setAll(grid)
		blocks.forEach {
			contentPane.children?.add(it)
		}

		Platform.runLater {
			scrollPane.hvalue = hValue
			scrollPane.vvalue = vValue
		}
	}


	private fun drawGrid(canvas: Canvas, gridSize: Double = 10.0, boldStep: Int = 5) {
		val gc = canvas.graphicsContext2D
		gc.clearRect(0.0, 0.0, canvas.width, canvas.height)
		val w = canvas.width
		val h = canvas.height

		// Сетка 10×10 — полупрозрачная
		gc.stroke = Color.rgb(180, 180, 180, 0.25)
		gc.lineWidth = 1.0
		var x = 0.0
		while (x <= w) {
			gc.strokeLine(x, 0.0, x, h)
			x += gridSize
		}
		var y = 0.0
		while (y <= h) {
			gc.strokeLine(0.0, y, w, y)
			y += gridSize
		}

		// Сетка 50×50 — более видимая (каждая 5-я линия)
		gc.stroke = Color.rgb(120, 120, 120, 0.5)
		gc.lineWidth = 2.0
		x = 0.0
		while (x <= w) {
			if ((x / gridSize) % boldStep == 0.0) {
				gc.strokeLine(x, 0.0, x, h)
			}
			x += gridSize
		}
		y = 0.0
		while (y <= h) {
			if ((y / gridSize) % boldStep == 0.0) {
				gc.strokeLine(0.0, y, w, y)
			}
			y += gridSize
		}
		gc.lineWidth = 1.0 // возвращаем обратно
	}


	// для gridCanvas и contentPane
	private fun setupContextMenu() {
		val showMenuHandler = EventHandler<MouseEvent> { event ->
			if (event.button == MouseButton.SECONDARY) {
				// Не показывать меню, если клик по блокам (BlockNode или Circle)
				val node = event.pickResult.intersectedNode
				if (node is BlockNode || node is Circle) return@EventHandler
				event.consume()
			}
		}
		contentPane.onMousePressed = showMenuHandler
		gridCanvas.onMousePressed = showMenuHandler
		gridCanvas.isMouseTransparent = true
	}


	fun setupHandlersForBlock(block: BlockNode) {
		// Для каждого выходного кружка
		block.outputCircles.forEachIndexed { outputIdx, outCircle ->
			outCircle.onMousePressed = EventHandler { event ->
				if (event.button == MouseButton.PRIMARY) {
					selectBlock(block)
					contentPane.requestFocus()
					val (startX, startY) = block.outputPoint(outputIdx)
					val line = Line(startX, startY, startX, startY).apply {
						stroke = Color.BLUE
						strokeWidth = 2.0
					}
					contentPane.children?.add(line)
					draggingLine = line
					draggingFromBlock = block
					draggingFromOutputIndex = outputIdx
					event.consume()
				}
			}
			outCircle.onMouseDragged = EventHandler { event ->
				if (event.button == MouseButton.PRIMARY && draggingLine != null) {
					val paneCoords = contentPane.sceneToLocal(event.sceneX, event.sceneY)
					if (paneCoords != null) {
						draggingLine!!.endX = paneCoords.x
						draggingLine!!.endY = paneCoords.y
					}
					event.consume()
				}
			}
			outCircle.onMouseReleased = EventHandler { event ->
				if (event.button == MouseButton.PRIMARY && draggingLine != null) {
					val paneCoords = contentPane.sceneToLocal(event.sceneX, event.sceneY)
					// Найти input-кружок под курсором
					val toBlockPair = blocks.asSequence().flatMap { other ->
						other.inputCircles.mapIndexed { inputIdx, inputCircle -> Triple(other, inputCircle, inputIdx) }
					}.find { (other, inputCircle, _) ->
						if (other == draggingFromBlock) return@find false
						val p = inputCircle.localToScene(inputCircle.centerX, inputCircle.centerY)
						val panePoint = contentPane.sceneToLocal(p.x, p.y)
						if (panePoint == null || paneCoords == null) {
							return@find false
						}
						val dx = panePoint.x - paneCoords.x
						val dy = panePoint.y - paneCoords.y
						Math.hypot(dx, dy) <= inputCircle.radius + 4
					}
					// успешное попадание в input-кружок
					if (toBlockPair != null && paneCoords != null) {
						val (toBlock, _, inputIdx) = toBlockPair
						contentPane.children.remove(draggingLine)
						val conn = OrthogonalConnection(
							draggingFromBlock!!,        // исходный блок
							toBlock,                    // целевой блок
							contentPane,                // <-- правильный host-Pane
							draggingFromOutputIndex!!,  // номер выходного порта
							inputIdx                    // номер входного порта
						)
						connections.add(conn)
						draggingFromBlock!!.connectedLines.add(conn)
						toBlock.connectedLines.add(conn)
						conn.setOnMouseClicked { e ->
							if (e.button == MouseButton.PRIMARY) {
								selectConnection(conn)
								contentPane.requestFocus()
								e.consume()
							}
						}
						draggingLine = null
						draggingFromOutputIndex = null
					} else {
						contentPane.children.remove(draggingLine)
						draggingLine = null
						draggingFromOutputIndex = null
					}

					event.consume()
				}
			}
		}
	}


	private fun ensureBlockVisible(block: BlockNode, margin: Double = 80.0, extendStep: Double = 200.0) {
		val now = System.currentTimeMillis()
		if (now - lastEnsureVisible < 180) return
		lastEnsureVisible = now

		val right = block.layoutX + block.width
		val bottom = block.layoutY + block.height
		var changed = false

		if (right + margin > contentPane.width) {
			contentPane.prefWidth = contentPane.width + extendStep
			changed = true
		}
		if (bottom + margin > contentPane.height) {
			contentPane.prefHeight = contentPane.height + extendStep
			changed = true
		}
		if (block.layoutX - margin < 0) {
			val shift = extendStep
			blocks.forEach { it.layoutX += shift }
			connections.forEach { conn ->
				conn.line.startX += shift
				conn.line.endX += shift
			}
			contentPane.prefWidth = contentPane.width + shift
			changed = true
		}
		if (block.layoutY - margin < 0) {
			val shift = extendStep
			blocks.forEach { it.layoutY += shift }
			connections.forEach { conn ->
				conn.line.startY += shift
				conn.line.endY += shift
			}
			contentPane.prefHeight = contentPane.height + shift
			changed = true
		}
		if (changed) {
			gridCanvas.widthProperty().unbind()
			gridCanvas.heightProperty().unbind()
			gridCanvas.width = contentPane.prefWidth
			gridCanvas.height = contentPane.prefHeight
			gridCanvas.widthProperty().bind(contentPane.widthProperty())
			gridCanvas.heightProperty().bind(contentPane.heightProperty())
			drawGrid(gridCanvas)
		}
	}


	companion object {
		@JvmStatic
		fun main(args: Array<String>) {
			launch(LayoutEditor::class.java)
		}
	}
}