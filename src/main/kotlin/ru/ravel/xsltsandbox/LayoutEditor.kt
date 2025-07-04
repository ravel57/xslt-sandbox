package ru.ravel.xsltsandbox

import com.fasterxml.jackson.databind.ObjectMapper
import javafx.application.Application
import javafx.application.Platform
import javafx.event.EventHandler
import javafx.geometry.Insets
import javafx.scene.Scene
import javafx.scene.canvas.Canvas
import javafx.scene.control.*
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCombination
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.Pane
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.scene.shape.Circle
import javafx.scene.shape.Polyline
import javafx.stage.DirectoryChooser
import javafx.stage.FileChooser
import javafx.stage.Stage
import javafx.util.Callback
import org.w3c.dom.Element
import ru.ravel.xsltsandbox.model.BlockType
import ru.ravel.xsltsandbox.model.BlocksData
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory


class LayoutEditor : Application() {
	private var currentProjectFile: File? = null
	private val blocks = mutableListOf<BlockNode>()
	val connections = mutableListOf<Connection>()
	private var draggingLine: Polyline? = null
	private var draggingFromBlock: BlockNode? = null
	private var draggingFromOutputIndex: Int? = null
	private var selectedBlock: BlockNode? = null
	private var selectedConnection: Connection? = null
	private var activeContextMenu: ContextMenu? = null
	private val windowW = 800.0
	private val windowH = 600.0
	private val gridCanvas = Canvas(windowW, windowH)
	private val workspaceGroup = Pane()
	private val contentPane = Pane().apply {
		children.addAll(gridCanvas, workspaceGroup)
		padding = Insets(10.0)
		isFocusTraversable = true
	}
	private val scrollPane = ScrollPane(contentPane).apply {
		isPannable = true
		hbarPolicy = ScrollPane.ScrollBarPolicy.ALWAYS
		vbarPolicy = ScrollPane.ScrollBarPolicy.ALWAYS
	}
	private var lastEnsureVisible = 0L

	private val fileTreeView: TreeView<File> = TreeView<File>().apply {
		prefWidth = DEFAULT_FILE_TREE_WIDTH
		isShowRoot = false
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
					?.let { xmlFile -> importLayoutFromXml(xmlFile) }
			}
		}
	}
	private val treeScroll: ScrollPane = ScrollPane(fileTreeView).apply {
		isFitToHeight = true
		isFitToWidth = true
		prefWidth = DEFAULT_FILE_TREE_WIDTH
	}


	override fun start(primaryStage: Stage) {
		drawGrid(gridCanvas, 10.0)
		gridCanvas.widthProperty().unbind()
		gridCanvas.heightProperty().unbind()

		fun resizeGrid() {
			val vpH = scrollPane.viewportBounds.height
			val contentH = contentPane.prefHeight
			gridCanvas.height = maxOf(vpH, contentH)
			drawGrid(gridCanvas)
		}
		resizeGrid()

		scrollPane.viewportBoundsProperty().addListener { _, _, _ -> resizeGrid() }
		contentPane.minWidthProperty().addListener { _, _, _ -> resizeGrid() }
		contentPane.minHeightProperty().addListener { _, _, _ ->
			val vp = scrollPane.viewportBounds
			gridCanvas.height = maxOf(vp.height, contentPane.minHeight)
			drawGrid(gridCanvas)
		}

		primaryStage.userData = this

		contentPane.apply {
			val b = layoutBounds
			gridCanvas.width = b.width
			gridCanvas.height = b.height
			drawGrid(gridCanvas)
		}

		scrollPane.content = contentPane
		contentPane.setOnContextMenuRequested { e ->
			val picked = e.pickResult.intersectedNode
			if (picked is BlockNode || picked is Circle) {
				e.consume()
				return@setOnContextMenuRequested
			}
			val menu = ContextMenu().apply {
				BlockType.entries.forEach { type ->
					items.add(MenuItem(type.displayName).apply {
						setOnAction {
							addBlock(e.x, e.y, type.displayName, type)
						}
					})
				}
			}
			menu.show(contentPane, e.screenX, e.screenY)
			activeContextMenu = menu
			e.consume()
		}
		scrollPane.viewportBoundsProperty().addListener { _, _, vp ->
			gridCanvas.widthProperty().unbind()
			gridCanvas.heightProperty().unbind()
			gridCanvas.width = vp.width
			gridCanvas.height = vp.height
			drawGrid(gridCanvas)
		}

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
		workspaceGroup.setOnContextMenuRequested { e ->
			val picked = e.pickResult.intersectedNode
			if (picked is BlockNode || picked is Circle) return@setOnContextMenuRequested
			val menu = ContextMenu().apply {
				BlockType.entries.forEach { type ->
					items += MenuItem(type.displayName).apply {
						setOnAction {
							addBlock(e.x, e.y, type.displayName, type)
						}
					}
				}
			}
			menu.show(workspaceGroup, e.screenX, e.screenY)
			activeContextMenu = menu
			e.consume()
		}
		val splitPane: SplitPane = SplitPane(treeScroll, scrollPane).apply {
			SplitPane.setResizableWithParent(treeScroll, false)
			Platform.runLater {
				val total = width.takeIf { it > 0 } ?: this.scene.width
				setDividerPositions(DEFAULT_FILE_TREE_WIDTH / total)
			}
		}
		VBox.setVgrow(splitPane, Priority.ALWAYS)
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
		// 1. очистка
		contentPane.children.removeIf { it is BlockNode || it is Polyline }
		workspaceGroup.children.removeIf { it is BlockNode || it is Polyline }
		blocks.clear()
		connections.clear()

		// 2. читаем XML
		val doc = DocumentBuilderFactory.newInstance()
			.newDocumentBuilder()
			.parse(file)
			.also { it.documentElement.normalize() }

		// 3. создаём блоки и карту UID → BlockNode
		val uidToBlock = mutableMapOf<String, BlockNode>()
		val elems = doc.getElementsByTagName("DiagramElement")
		for (i in 0 until elems.length) {
			val el = elems.item(i) as Element
			val uid = el.getAttribute("UID")
			// 3.1 читаем из XML координаты центра и размер
			val xmlX = el.getSingleChild("X").textContent.toDouble()
			val xmlY = el.getSingleChild("Y").textContent.toDouble()
			val w = el.getSingleChild("Width").textContent.toDouble() * 0.8
			val h = el.getSingleChild("Height").textContent.toDouble()
			val ref = el.getSingleChild("Reference").textContent

			// 3.2 выбираем тип блока
			val type = BlockType.entries
				.firstOrNull { ref.startsWith(it.name, ignoreCase = true) }
				?: BlockType.MAPPING

			// 3.3 вычисляем положение левого-верхнего угла,
			//     чтобы (xmlX, xmlY) оказался в центре блока
			val topLeftX = xmlX - w / 2
			val topLeftY = xmlY - h / 2

			// 3.4 создаём и настраиваем блок
			val xsltPath = file.parentFile
				?.listFiles()
				?.firstOrNull { it.name == ref }
				?.listFiles { f -> f.isFile && f.name == "Mapping.xslt" }
				?.firstOrNull()
				?.toPath()
			val block = BlockNode(
				x = topLeftX,
				y = topLeftY,
				name = ref,
				blockType = type,
				xsltPath = xsltPath,
			).apply {
				onMove = this@LayoutEditor::updateContentSize
				prefWidth = w
				prefHeight = h
			}

			// 3.5 регистрируем
			blocks += block
			workspaceGroup.children += block
			setupHandlersForBlock(block)
			uidToBlock[uid] = block
		}

		// 4. создаём соединения через Polyline
		val connElems = doc.getElementsByTagName("DiagramConnection")
		workspaceGroup.applyCss()
		workspaceGroup.layout()
		for (i in 0 until connElems.length) {
			val connEl = connElems.item(i) as Element
			val ends = connEl.getElementsByTagName("DiagramEndPoint")
			if (ends.length != 2) continue
			val fromUid = (ends.item(0) as Element).getAttribute("ElementRef")
			val toUid = (ends.item(1) as Element).getAttribute("ElementRef")
			val from = uidToBlock[fromUid] ?: continue
			val to = uidToBlock[toUid] ?: continue
			val splitPts = buildList {
				val splits = connEl.getElementsByTagName("DiagramSplit")
				for (j in 0 until splits.length) {
					val sp = splits.item(j) as Element
					add(
						sp.getSingleChild("X").textContent.toDouble() to sp.getSingleChild("Y").textContent.toDouble()
					)
				}
			}
			val startCircle = from.outputCircles[0]
			val startScene = startCircle.localToScene(startCircle.centerX, startCircle.centerY)
			val startLocal = workspaceGroup.sceneToLocal(startScene)
			val endCircle = to.inputCircles[0]
			val endScene = endCircle.localToScene(endCircle.centerX, endCircle.centerY)
			val endLocal = workspaceGroup.sceneToLocal(endScene)
			val coords = mutableListOf<Double>().apply {
				add(startLocal.x)
				add(startLocal.y)
				splitPts.forEach { (x, y) ->
					add(x)
					add(y)
				}
				add(endLocal.x); add(endLocal.y)
			}
			val polyline = Polyline().apply {
				points.addAll(coords)
				strokeWidth = 4.0
				stroke = Color.GRAY
				setOnMouseClicked { e ->
					if (e.button == MouseButton.PRIMARY) {
						selectConnection(null)
						val conn = Connection(
							from = from,
							to = to,
							line = this,
							fromPort = 0,
							toPort = 0,
							splitPts = splitPts
						)
						selectConnection(conn)
						e.consume()
					}
				}
			}
			workspaceGroup.children.add(0, polyline)
			val conn = Connection(
				from = from,
				to = to,
				line = polyline,
				fromPort = 0,
				toPort = 0,
				splitPts = splitPts
			)
			from.connectedLines += conn
			to.connectedLines += conn
			connections += conn
		}

		// 5. обновляем область и фокус
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


	fun selectConnection(conn: Connection?) {
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

	private fun addBlock(x: Double, y: Double, name: String, blockType: BlockType) {
		val block = BlockNode(x, y, name, blockType, xsltPath = null).apply {
			layoutX = x
			layoutY = y
			prefWidth = BlockNode.BLOCK_H
			prefHeight = BlockNode.BLOCK_W
			onMove = this@LayoutEditor::updateContentSize
		}
		blocks += block
		workspaceGroup.children += block
		setupHandlersForBlock(block)
		updateContentSize()
	}


	// --- Сериализация и загрузка ---
	private fun saveToFile(file: File) {
		currentProjectFile = file
		val blocksData = BlocksData(blocks.map { it.toSerialized() }, connections.map { it.toSerialized() })
		file.writeText(ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(blocksData))
	}


	fun startConnectionFromBlock(block: BlockNode, outputIndex: Int) {
		draggingFromBlock = block
		draggingFromOutputIndex = outputIndex
		val (startX, startY) = block.outputPoint(outputIndex)
		val line = Polyline().apply {
			stroke = Color.GRAY
			strokeWidth = 4.0
		}
		(scrollPane.content as Pane).children.add(line)
		draggingLine = line
	}

	fun continueConnectionDrag(event: MouseEvent) {
		draggingLine?.let { poly ->
			val paneCoords = (scrollPane.content as Pane).sceneToLocal(event.sceneX, event.sceneY)
			if (poly.points.size >= 4) {
				poly.points[2] = paneCoords.x
				poly.points[3] = paneCoords.y
			}
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
			val vp = scrollPane.viewportBounds
			gridCanvas.width = maxOf(vp.width, contentPane.minWidth)
			gridCanvas.height = maxOf(vp.height, contentPane.minHeight)
			drawGrid(gridCanvas)
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
					val line = Polyline().apply {
						stroke = Color.GRAY
						strokeWidth = 4.0
						points.addAll(startX, startY, startX, startY)
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
						val pts = draggingLine!!.points
						pts[pts.size - 2] = paneCoords.x
						pts[pts.size - 1] = paneCoords.y
					}
					event.consume()
				}
			}
			outCircle.onMouseReleased = EventHandler { event ->
				completeLine(event)
			}
		}
	}


	private fun completeLine(event: MouseEvent) {
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
			if (toBlockPair != null && paneCoords != null) {
				val (toBlock, _, inputIdx) = toBlockPair
				val (startX, startY) = draggingFromBlock!!.outputPoint(draggingFromOutputIndex!!)
				val (endX, endY) = toBlock.inputPoint(inputIdx)
				draggingLine!!.points.apply {
					clear()
					addAll(startX, startY, endX, endY)
				}
				val conn = Connection(
					from = draggingFromBlock!!,
					to = toBlock,
					line = draggingLine!!,
					fromPort = draggingFromOutputIndex!!,
					toPort = inputIdx
				)
				connections.add(conn)
				draggingFromBlock!!.connectedLines.add(conn)
				toBlock.connectedLines.add(conn)

				// 6) по клику на линию — выделять её
				conn.line.setOnMouseClicked { me ->
					if (me.button == MouseButton.PRIMARY) {
						selectConnection(conn)
						(conn.line.parent as? Pane)?.requestFocus()
						me.consume()
					}
				}
			} else {
				contentPane.children?.remove(draggingLine)
				draggingLine = null
				draggingFromOutputIndex = null
			}
			event.consume()
		}
	}


	private fun ensureBlockVisible(
		block: BlockNode,
		margin: Double = 80.0,
		extendStep: Double = 200.0
	) {
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
				val pts = conn.line.points
				for (i in pts.indices step 2) {
					pts[i] = pts[i] + shift
				}
			}
			contentPane.prefWidth = contentPane.width + shift
			changed = true
		}
		if (block.layoutY - margin < 0) {
			val shift = extendStep
			blocks.forEach { it.layoutY += shift }
			connections.forEach { conn ->
				val pts = conn.line.points
				for (i in 1 until pts.size step 2) {
					pts[i] = pts[i] + shift
				}
			}
			contentPane.prefHeight = contentPane.height + shift
			changed = true
		}
		if (changed) {
			gridCanvas.widthProperty().unbind()
			gridCanvas.heightProperty().unbind()
			gridCanvas.width = contentPane.prefWidth
			gridCanvas.height = contentPane.prefHeight
			scrollPane.viewportBoundsProperty().addListener { _, _, vp ->
				gridCanvas.width = vp.width
				gridCanvas.height = vp.height
				drawGrid(gridCanvas)
			}
			drawGrid(gridCanvas)
		}
	}


	private fun updateContentSize() {
		val bounds = workspaceGroup.layoutBounds
		contentPane.prefWidth = bounds.maxX
		contentPane.prefHeight = bounds.maxY
	}


	companion object {
		private const val DEFAULT_FILE_TREE_WIDTH = 150.0

		@JvmStatic
		fun main(args: Array<String>) {
			launch(LayoutEditor::class.java)
		}
	}
}