package ru.ravel.xsltsandbox.diagram

import com.fasterxml.jackson.dataformat.xml.XmlMapper
import javafx.collections.FXCollections
import javafx.collections.transformation.FilteredList
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Pane
import javafx.scene.layout.VBox
import javafx.stage.Stage
import ru.ravel.xsltsandbox.XmlXsltValidatorApp.Companion.showStatus
import ru.ravel.xsltsandbox.models.DocSession
import ru.ravel.xsltsandbox.models.TransformMode
import java.nio.file.Path
import java.util.function.Predicate
import kotlin.io.path.exists

class RouteFinder(
	private val currentStage: Stage,
	private val currentSession: DocSession,
	private val sceneWidth: Double = 1200.0,
	private val sceneHeight: Double = 800.0,
) {
	private var diagramStage: Stage? = null
	private var cyclesStage: Stage? = null

	fun openFlowDiagramWindow() {
		val selectedActivityPath = when (currentSession.mode) {
			TransformMode.XSLT -> currentSession.xsltPath
			TransformMode.BR -> currentSession.brPath
			TransformMode.ST,
			TransformMode.SV,
			TransformMode.PR,
			TransformMode.PROCEDURE_RETURN,
			TransformMode.WA,
			TransformMode.FM,
			TransformMode.OTHER,
				-> currentSession.otherActivityPath
		} ?: run {
			showStatus(currentStage, "No activity selected.")
			return
		}

		val flowDir = selectedActivityPath.parent?.parent ?: run {
			showStatus(currentStage, "Cannot resolve flow directory for:\n$selectedActivityPath")
			return
		}

		val layoutFile = flowDir.resolve("Layout.xml")
		if (!layoutFile.exists()) {
			showStatus(currentStage, "Layout.xml not found:\n$layoutFile")
			return
		}

		val layout = try {
			XmlMapper().readValue(layoutFile.toFile(), ru.ravel.xsltsandbox.models.layout.DiagramLayout::class.java)
		} catch (e: Exception) {
			showStatus(currentStage, "Failed to parse Layout.xml:\n${e.message}")
			return
		}

		val currentActivityName = selectedActivityPath.parent?.fileName?.toString()

		// отдельное окно (Stage), не трогаем currentStage.scene
		val stage = (diagramStage ?: Stage().also {
			it.initOwner(currentStage)
			it.title = "Flow diagram"
			diagramStage = it
			it.setOnCloseRequest { diagramStage = null }
		}).apply {
			title = "Flow diagram: ${flowDir.fileName}"
		}

		stage.scene = Scene(
			buildDiagramRoot(layout, currentActivityName),
			sceneWidth,
			sceneHeight
		)
		stage.show()
		stage.toFront()
	}

	private fun openCyclesWindow(
		titlePrefix: String,
		cycles: List<List<String>>,
		uidToName: Map<String, String>
	) {
		val stage = (cyclesStage ?: Stage().also {
			it.initOwner(diagramStage ?: currentStage)
			it.title = "Cycles"
			cyclesStage = it
			it.setOnCloseRequest { cyclesStage = null }
		}).apply {
			title = "$titlePrefix (${cycles.size})"
		}

		val body = VBox(6.0).apply { padding = Insets(10.0) }

		if (cycles.isEmpty()) {
			body.children.add(Label("No cycles found."))
		} else {
			cycles.take(300).forEachIndexed { idx, cycle ->
				val names = cycle.map { uidToName[it] ?: it }
				body.children.add(Label("${idx + 1}) " + names.joinToString(" → ")))
			}
			if (cycles.size > 300) body.children.add(Label("… ещё ${cycles.size - 300}"))
		}

		stage.scene = Scene(ScrollPane(body).apply { isFitToWidth = true }, 900.0, 600.0)
		stage.show()
		stage.toFront()
	}

	private fun findCycles(graph: FlowGraph, maxCycles: Int = 200, maxLen: Int = 60): List<List<String>> {
		// Быстрый поиск циклов по back-edge в DFS. Не перечисляет ВСЕ простые циклы (это экспоненциально),
		// но стабильно находит реальные циклы и достаточно для "проверки/подсветки/списка".
		val nodes = linkedSetOf<String>().apply {
			graph.edges.forEach { e ->
				add(e.fromUid)
				add(e.toUid)
			}
		}

		val visited = HashSet<String>(nodes.size)
		val onStack = HashSet<String>(nodes.size)
		val stack = ArrayList<String>()
		val cycles = ArrayList<List<String>>()
		val seenKeys = HashSet<String>()

		fun canonicalKey(cycle: List<String>): String {
			// cycle приходит как [a,b,c,a] или [a,a]
			val base = if (cycle.size >= 2 && cycle.first() == cycle.last()) cycle.dropLast(1) else cycle
			if (base.isEmpty()) return ""
			// вращение к минимальному элементу для дедупа
			val minIdx = base.indices.minByOrNull { base[it] } ?: 0
			val rotated = (base.drop(minIdx) + base.take(minIdx))
			return rotated.joinToString("->")
		}

		fun addCycle(cycle: List<String>) {
			if (cycles.size >= maxCycles) return
			val base = if (cycle.size >= 2 && cycle.first() == cycle.last()) cycle.dropLast(1) else cycle
			if (base.size > maxLen) return
			val key = canonicalKey(cycle)
			if (key.isNotEmpty() && seenKeys.add(key)) {
				// для отображения добавляем "замыкание" обратно в старт
				val withClose = if (base.isNotEmpty()) base + base.first() else base
				cycles.add(withClose)
			}
		}

		fun dfs(u: String) {
			if (cycles.size >= maxCycles) return
			visited.add(u)
			onStack.add(u)
			stack.add(u)

			for (e in graph.outs[u].orEmpty()) {
				if (cycles.size >= maxCycles) break
				val v = e.toUid

				// self-loop
				if (v == u) {
					addCycle(listOf(u, u))
					continue
				}

				if (v !in visited) {
					dfs(v)
				} else if (v in onStack) {
					val idx = stack.indexOf(v)
					if (idx >= 0) {
						val cyc = stack.subList(idx, stack.size).toList() + v
						addCycle(cyc)
					}
				}
			}

			onStack.remove(u)
			stack.removeAt(stack.lastIndex)
		}

		for (n in nodes) {
			if (n !in visited) dfs(n)
			if (cycles.size >= maxCycles) break
		}

		return cycles
	}

	fun buildDiagramRoot(
		layout: ru.ravel.xsltsandbox.models.layout.DiagramLayout,
		currentActivityName: String?
	): BorderPane {
		val elements = layout.elements?.diagramElements.orEmpty()

		// имя для показа: reference, иначе uid
		val uidToName: Map<String, String> = elements
			.mapNotNull { e ->
				val uid = e.uid ?: return@mapNotNull null
				val name = (e.reference ?: uid).trim()
				uid to name
			}
			.toMap()

		// обратная мапа (если есть дубликаты имён — берём первое)
		val nameToUid = LinkedHashMap<String, String>().apply {
			uidToName.forEach { (uid, name) -> putIfAbsent(name, uid) }
		}

		val allNames = uidToName.values.distinct().sorted()

		val dirEdges = FlowGraphExtractor.extractDirectedEdges(layout)
		val graph = FlowGraph(dirEdges)
		val routeFinder = GraphRouteFinder(graph)

		val cycles = findCycles(graph)
		val cyclesNodes = cycles.flatten().toSet()

		val titleLabel = Label("Маршруты").apply { styleClass += "diagram-title" }
		val status = Label("")

		val startCb = ComboBox<String>().apply { promptText = "Start" }
		val endCb = ComboBox<String>().apply { promptText = "End" }

		fun makeFilterable(cb: ComboBox<String>, base: List<String>) {
			cb.isEditable = true
			val source = FXCollections.observableArrayList(base)
			val filtered = FilteredList(source) { true }
			cb.items = filtered

			var internal = false

			cb.editor.textProperty().addListener { _, _, nv ->
				if (internal) return@addListener
				val q = (nv ?: "").trim()

				filtered.predicate = Predicate { item ->
					if (q.isEmpty()) true else item.contains(q, ignoreCase = true)
				}

				// если текущее значение вылетело из фильтра — не держим selection индекс
				val v = cb.value
				if (v != null && v !in filtered) {
					internal = true
					try {
						cb.value = null
					} finally {
						internal = false
					}
				}

				// показываем дропдаун только если он уже открыт
				if (cb.isShowing) cb.show()
			}

			// при выборе из списка синхронизируем editor.text без рекурсии
			cb.valueProperty().addListener { _, _, v ->
				if (internal) return@addListener
				internal = true
				try {
					if (v != null) cb.editor.text = v
				} finally {
					internal = false
				}
			}

			// Enter в редакторе: если введено точное совпадение — выбираем
			cb.editor.setOnAction {
				val t = cb.editor.text?.trim().orEmpty()
				if (t.isNotEmpty() && t in source) cb.value = t
			}
		}

		makeFilterable(startCb, allNames)
		makeFilterable(endCb, allNames)

		val buildBtn = Button("Build")

		val workspace = Pane().apply {
			prefWidth = 1400.0
			prefHeight = 900.0
		}

		val linesLayer = Group()
		val blocksLayer = Group()
		val overlayLayer = Group()
		workspace.children.addAll(linesLayer, blocksLayer, overlayLayer)

		val scroll = ScrollPane(workspace).apply {
			isPannable = true
			hbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
			vbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
			fitToHeightProperty().set(false)
			fitToWidthProperty().set(false)
		}

		val blocks = mutableMapOf<String, DiagramBlockView>()
		val connections = mutableListOf<DiagramConnView>()

		fun clearDiagram() {
			linesLayer.children.clear()
			blocksLayer.children.clear()
			overlayLayer.children.clear()
			blocks.clear()
			connections.clear()
		}

		fun renderBranchingRoutes(startUid: String, endUid: String, routes: List<Route>) {
			clearDiagram()
			if (routes.isEmpty()) return

			val layoutInfo = BranchingLayout.fromRoutes(routes)

			val padX = 20.0
			val padY = 20.0
			val dx = 260.0
			val dy = 150.0
			val maxLevel = layoutInfo.nodesByLevel.keys.maxOrNull() ?: 0
			val maxPerLevel = layoutInfo.nodesByLevel.values.maxOfOrNull { it.size } ?: 1
			workspace.prefWidth = padX * 2 + (maxLevel + 1) * dx + 260
			workspace.prefHeight = padY * 2 + maxPerLevel * dy + 120

			layoutInfo.nodesByLevel.forEach { (lvl, uidsAtLvl) ->
				uidsAtLvl.forEachIndexed { idx, uid ->
					val name = uidToName[uid] ?: uid
					val x = padX + lvl * dx
					val y = padY + idx * dy

					val block = DiagramBlockView(uid = uid, name = name).apply {
						layoutX = x
						layoutY = y
						isStart = uid == startUid
						isEnd = uid == endUid
						highlight = (currentActivityName != null && name == currentActivityName)
						// простая подсказка: узел участвует в цикле в исходном графе
						if (uid in cyclesNodes) {
							style = (style ?: "") + "; -fx-effect: dropshadow(gaussian, rgba(255,0,0,0.35), 18, 0.0, 0, 0);"
						}
					}
					block.onMoved = { connections.forEach { it.update() } }
					block.onPicked = { picked ->
						if (startCb.isFocused || startCb.value == null) startCb.value = picked
						else endCb.value = picked
					}

					blocks[uid] = block
					blocksLayer.children.add(block)
				}
			}

			layoutInfo.edges.forEach { e ->
				val from = blocks[e.fromUid] ?: return@forEach
				val to = blocks[e.toUid] ?: return@forEach
				val conn = DiagramConnView(from = from, to = to, exitName = e.exitName)
				connections.add(conn)
				linesLayer.children.addAll(conn.line, conn.arrow1, conn.arrow2, conn.label)
				conn.update()
			}
		}

		fun rebuild() {
			val startName = startCb.value
			val endName = endCb.value

			val startUid = startName?.let { nameToUid[it] }
			val endUid = endName?.let { nameToUid[it] }

			if (startUid == null || endUid == null) {
				titleLabel.text = "Маршруты"
				status.text = "Select start/end activities. Cycles: ${cycles.size}"
				clearDiagram()
				return
			}

			val routes = routeFinder.findAllShortestRoutes(
				fromUid = startUid,
				toUid = endUid,
				firstExit = null,
				maxRoutes = 200
			)

			if (routes.isEmpty()) {
				titleLabel.text = "Маршрут: не найден"
				status.text = "No routes found. Cycles: ${cycles.size}"
				clearDiagram()
				return
			}

			val len = routes.first().edges.size
			titleLabel.text = "Маршрут: $startName → $endName"
			status.text = "Shortest length: $len, routes: ${routes.size}. Cycles: ${cycles.size}"
			renderBranchingRoutes(startUid = startUid, endUid = endUid, routes = routes)
		}

		// первоначальная подсветка текущей активности
		if (currentActivityName != null) {
			val uid = nameToUid[currentActivityName]
			if (uid != null) {
				val block = DiagramBlockView(uid = uid, name = currentActivityName).apply {
					layoutX = 20.0
					layoutY = 20.0
					highlight = true
					if (uid in cyclesNodes) {
						style = (style ?: "") + "; -fx-effect: dropshadow(gaussian, rgba(255,0,0,0.35), 18, 0.0, 0, 0);"
					}
				}
				blocks[uid] = block
				blocksLayer.children.add(block)
			}
		}

		buildBtn.setOnAction { rebuild() }

		val refreshBtn = Button("Refresh").apply {
			setOnAction {
				// переоткрываем окно, чтобы перечитать Layout.xml и пересобрать UI
				openFlowDiagramWindow()
			}
		}

		val cyclesBtn = Button("Cycles (${cycles.size})").apply {
			isDisable = cycles.isEmpty()
			setOnAction {
				openCyclesWindow("Cycles", cycles, uidToName)
			}
		}

		val topBar = HBox(
			10.0,
			Label("Start:"), startCb,
			Label("End:"), endCb,
			buildBtn,
			cyclesBtn,
			refreshBtn
		).apply {
			padding = Insets(10.0)
			alignment = Pos.CENTER_LEFT
		}

		val header = VBox(4.0, titleLabel, status).apply { padding = Insets(10.0) }

		// сразу показываем, что цикл(ы) есть/нет
		status.text = "Cycles: ${cycles.size}"

		return BorderPane().apply {
			top = VBox(topBar, header)
			center = scroll
		}
	}
}
