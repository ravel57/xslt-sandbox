package ru.ravel.xsltsandbox.diagram

import com.fasterxml.jackson.dataformat.xml.XmlMapper
import javafx.application.Platform
import javafx.collections.FXCollections
import javafx.collections.ObservableList
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
import ru.ravel.xsltsandbox.models.DocSession
import ru.ravel.xsltsandbox.models.TransformMode
import ru.ravel.xsltsandbox.models.layout.DiagramLayout
import ru.ravel.xsltsandbox.ui.Dialogs.showStatus
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

	fun openFlowDiagramWindow() {
		val mode = currentSession.mode
		val selectedActivityPath =
			if (mode == TransformMode.XSLT) currentSession.xsltPath
			else if (mode == TransformMode.BR) currentSession.brPath
			else currentSession.otherActivityPath
				?: run {
					showStatus(currentStage, "No activity selected.")
					return
				}

		val flowDir = selectedActivityPath?.parent?.parent ?: run {
			showStatus(currentStage, "Cannot resolve flow directory for:\n$selectedActivityPath")
			return
		}

		val layoutFile = flowDir.resolve("Layout.xml")
		if (!layoutFile.exists()) {
			showStatus(currentStage, "Layout.xml not found:\n$layoutFile")
			return
		}

		val layout = try {
			XmlMapper().readValue(layoutFile.toFile(), DiagramLayout::class.java)
		} catch (e: Exception) {
			showStatus(currentStage, "Failed to parse Layout.xml:\n${e.message}")
			return
		}

		val currentActivityName = selectedActivityPath.parent?.fileName?.toString()

		val stage = (diagramStage ?: Stage().also {
			it.initOwner(currentStage)
			it.title = "Flow diagram"
			diagramStage = it
			it.setOnCloseRequest { diagramStage = null }
		}).apply { title = "Flow diagram: ${flowDir.fileName}" }

		stage.scene = Scene(buildDiagramRoot(flowDir, layout, currentActivityName, stage), sceneWidth, sceneHeight)
		stage.show()
		stage.toFront()
	}

	private data class CycleInfo(val uids: List<String>, val text: String)

	fun buildDiagramRoot(
		flowDir: Path,
		layout: ru.ravel.xsltsandbox.models.layout.DiagramLayout,
		currentActivityName: String?,
		ownerStage: Stage
	): BorderPane {
		val elements = layout.elements?.diagramElements.orEmpty()

		val uidToName: Map<String, String> = elements
			.mapNotNull { e ->
				val uid = e.uid
				if (uid.isNullOrBlank()) null
				else uid to (e.reference ?: uid)
			}
			.toMap()

		val nameToUid = LinkedHashMap<String, String>().apply {
			uidToName.forEach { (uid, name) ->
				if (!containsKey(name)) this[name] = uid
			}
		}

		val allNames = uidToName.values.distinct().sorted()

		val dirEdges = FlowGraphExtractor.extractDirectedEdges(layout)
		val graph = FlowGraph(dirEdges)
		val routeFinder = GraphRouteFinder(graph)

		// циклические связи в полном графе (SCC), чтобы можно было дорисовывать их линиями
		val cyclesGraph = computeCyclesGraphInfo(dirEdges)

		val titleLabel = Label("Маршруты")
		val namesBase: ObservableList<String> = FXCollections.observableArrayList(allNames)

		fun makeFilterable(cb: ComboBox<String>, base: ObservableList<String>) {
			cb.isEditable = true

			val filtered = FilteredList(base) { true }
			cb.items = filtered

			val editor = cb.editor
			var programmatic = false

			fun applyFilter(raw: String?) {
				val q = (raw ?: "").trim()

				// очистка selection перед сменой predicate — иначе JavaFX иногда пытается выбрать индекс,
				// которого уже нет (IndexOutOfBounds / IllegalArgumentException в TextInputControl)
				programmatic = true
				cb.selectionModel.clearSelection()
				programmatic = false

				filtered.predicate = Predicate { item ->
					q.isEmpty() || item.contains(q, ignoreCase = true)
				}
			}

			fun commitFromEditor() {
				val t = editor.text?.trim().orEmpty()
				val match = base.firstOrNull { it.equals(t, ignoreCase = true) }

				programmatic = true
				cb.value = match
				programmatic = false

				Platform.runLater { cb.hide() }
			}

			editor.textProperty().addListener { _, _, nv ->
				if (programmatic) return@addListener

				applyFilter(nv)

				// открываем список после обработки текущего key event (иначе бывают проблемы с кареткой/selection)
				Platform.runLater {
					if (cb.isFocused && !cb.isShowing) cb.show()
				}
			}

			cb.valueProperty().addListener { _, _, v ->
				if (programmatic) return@addListener
				if (v == null) return@addListener

				programmatic = true
				Platform.runLater {
					try {
						editor.text = v
						editor.positionCaret(v.length)
					} finally {
						programmatic = false
					}
				}
			}

			editor.setOnAction { commitFromEditor() }
			editor.focusedProperty().addListener { _, _, focused ->
				if (!focused) commitFromEditor()
			}

			cb.setOnShowing {
				if (!programmatic) applyFilter(editor.text)
			}
		}

		fun resolveName(cb: ComboBox<String>): String? {
			val v = cb.value?.trim()
			if (!v.isNullOrBlank()) return v
			val t = cb.editor?.text?.trim()
			if (t.isNullOrBlank()) return null
			return namesBase.firstOrNull { it.equals(t, ignoreCase = true) }
		}

		val startCb = ComboBox<String>().apply { promptText = "Start" }
		val endCb = ComboBox<String>().apply { promptText = "End" }

		makeFilterable(startCb, namesBase)
		makeFilterable(endCb, namesBase)

		val buildBtn = Button("Build")

		val stepBtn = Button("Step")
		val runBtn = Button("Run debug").apply {
			setOnAction {
				if (currentSession.dataDocs.isNullOrBlank()) {
					showStatus(currentStage, "DataDocs пустые — вставьте DataDocs и повторите Run debug")
					return@setOnAction
				}
				currentSession.onDebugRun?.invoke()
			}
		}
		val exitDbgLabel = Label().apply {
			textProperty().bind(currentSession.debugLastExitName)
		}

		val workspace = Pane().apply {
			prefWidth = 1600.0
			prefHeight = 1000.0
		}

		val linesLayer = Group()
		val blocksLayer = Group()
		workspace.children.addAll(linesLayer, blocksLayer)

		val scroll = ScrollPane(workspace).apply {
			isPannable = true
			hbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
			vbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
		}

		val blocks = mutableMapOf<String, DiagramBlockView>()
		val connections = mutableListOf<DiagramConnView>()

		var highlightedUid: String? = null

		fun clearHighlight() {
			highlightedUid?.let { uid ->
				blocks[uid]?.highlight = false
			}
			highlightedUid = null
		}

		fun highlightUid(uid: String?) {
			if (uid == null) return
			if (highlightedUid == uid) return
			// снять прошлую подсветку
			clearHighlight()
			// подсветить новую
			blocks[uid]?.highlight = true
			highlightedUid = uid
		}

		fun centerOnUid(uid: String?) {
			val b = uid?.let { blocks[it] } ?: return
			Platform.runLater {
				// гарантируем, что layout уже посчитан
				val bounds = b.boundsInParent
				val vp = scroll.viewportBounds
				val contentW = workspace.prefWidth
				val contentH = workspace.prefHeight
				val targetX = bounds.minX + bounds.width / 2.0 - vp.width / 2.0
				val targetY = bounds.minY + bounds.height / 2.0 - vp.height / 2.0
				val hxDen = (contentW - vp.width).coerceAtLeast(1.0)
				val vyDen = (contentH - vp.height).coerceAtLeast(1.0)
				scroll.hvalue = (targetX / hxDen).coerceIn(0.0, 1.0)
				scroll.vvalue = (targetY / vyDen).coerceIn(0.0, 1.0)
			}
		}

		fun highlightByActivityName(name: String?) {
			val uid = name?.let { nameToUid[it] }
			highlightUid(uid)
			centerOnUid(uid)
		}

		fun clearDiagram() {
			clearHighlight()
			linesLayer.children.clear()
			blocksLayer.children.clear()
			blocks.clear()
			connections.clear()
		}


		fun renderBranchingRoutes(startUid: String, endUid: String, routes: List<Route>, cyclesGraph: CyclesGraphInfo) {
			clearDiagram()
			if (routes.isEmpty()) return

			val layoutInfo = BranchingLayout.fromRoutes(routes)

			val padX = 20.0
			val padY = 20.0
			val dx = 260.0
			val dy = 150.0
			val maxLevel = layoutInfo.nodesByLevel.keys.maxOrNull() ?: 0
			val maxPerLevel = layoutInfo.nodesByLevel.values.maxOfOrNull { it.size } ?: 1
			workspace.prefWidth = padX * 2 + (maxLevel + 1) * dx + 320
			workspace.prefHeight = padY * 2 + maxPerLevel * dy + 160

			layoutInfo.nodesByLevel.forEach { (lvl, uidsAtLvl) ->
				uidsAtLvl.forEachIndexed { idx, uid ->
					val name = uidToName[uid] ?: uid
					val x = padX + lvl * dx
					val y = padY + idx * dy

					val block = DiagramBlockView(uid = uid, name = name)
					block.layoutX = x
					block.layoutY = y
					block.isStart = (uid == startUid)
					block.isEnd = (uid == endUid)

					block.onMoved = { connections.forEach { it.update() } }
					block.onPicked = { picked ->
						if (startCb.value == null) startCb.value = picked else endCb.value = picked
					}

					blocks[uid] = block
					blocksLayer.children.add(block)
				}
			}
			val connMap = LinkedHashMap<ExitKey, DiagramConnView>()
			val allEdgesForPorts = ArrayList<DirEdge>().apply {
				addAll(layoutInfo.edges)
				addAll(cyclesGraph.cycleEdges)
			}

			val portsByFrom: Map<String, List<String>> = allEdgesForPorts
				.groupBy { it.fromUid }
				.mapValues { (_, edges) ->
					edges.map { e ->
						val raw = e.exitLabel()?.trim().orEmpty()
						if (raw.isNotBlank()) raw else "→ " + (uidToName[e.toUid] ?: e.toUid)
					}.distinct()
				}

			blocks.forEach { (uid, block) ->
				val exits = portsByFrom[uid].orEmpty()
				block.setExits(exits)
			}

			fun resolveExitName(fromUid: String, toUid: String, rawExit: String?): String {
				val raw = rawExit?.trim().orEmpty()
				if (raw.isNotBlank()) return raw
				return "→ " + (uidToName[toUid] ?: toUid)
			}

			fun putConn(fromUid: String, toUid: String, rawExitName: String?, isCycle: Boolean) {
				val from = blocks[fromUid] ?: return
				val to = blocks[toUid] ?: return

				val exitName = resolveExitName(fromUid, toUid, rawExitName)
				val ports = portsByFrom[fromUid] ?: listOf(exitName)
				val exitIndex = ports.indexOf(exitName).let { if (it >= 0) it else 0 }
				val exitCount = ports.size.coerceAtLeast(1)

				val key = ExitKey(fromUid, exitName)
				val style = if (isCycle) EdgeStyle.CYCLE else EdgeStyle.ROUTE

				val existing = connMap[key]
				if (existing != null) {
					return
				}
				val conn = DiagramConnView(
					from = from,
					to = to,
					exitName = exitName,
					exitIndex = exitIndex,
					exitCount = exitCount,
					routeYProvider = {
						blocks.values.maxOfOrNull { it.layoutY + it.boundsInParent.maxY }?.plus(60.0) ?: 500.0
					}
				)
				conn.onPicked = { picked ->
					connections.forEach { it.selected = (it === picked) }
				}
				conn.onOpenDataDocs = { picked ->
					val key = DocSession.DebugEdgeKey(
						from = picked.fromName,
						exit = picked.exit?.takeIf { it.isNotBlank() },
						to = picked.toName
					)
					val saved = currentSession.debugDocsByEdge[key]
					val title = buildString {
						append("DataDocs")
						if (!picked.exit.isNullOrBlank()) append(" [").append(picked.exit).append("]")
						append(": ").append(picked.fromName).append(" -> ").append(picked.toName)
					}
					val text = if (saved != null) {
						buildString {
							append("=== IN ===\n")
							append(saved.inDocs)
							append("\n\n=== OUT ===\n")
							append(saved.outDocs)
						}
					} else {
						currentSession.dataDocs ?: ""
					}
					currentSession.onOpenDataDocsViewer?.invoke(ownerStage, title, text)
				}
				linesLayer.children.addAll(conn.path, conn.pick, conn.arrow, conn.label)
				connMap[key] = conn
				connections.add(conn)
				conn.update()
			}

			layoutInfo.edges.forEach { e ->
				putConn(fromUid = e.fromUid, toUid = e.toUid, rawExitName = e.exitLabel(), isCycle = false)
			}

			// дополнительно дорисовываем "замыкающие" связи циклов (если обе активности видимы на диаграмме)
			cyclesGraph.cycleEdges.forEach { e ->
				putConn(fromUid = e.fromUid, toUid = e.toUid, rawExitName = e.exitLabel(), isCycle = true)
			}

			// Подсветка текущей активности (debugger)
			val dbgName = currentSession.debugActivityNameOf(currentSession.debugCurrentActivityProps.get())
			highlightByActivityName(dbgName ?: currentActivityName)
		}


		fun rebuild() {
			val startName = resolveName(startCb)
			val endName = resolveName(endCb)

			val startUid = startName?.let { nameToUid[it] }
			val endUid = endName?.let { nameToUid[it] }

			if (startUid == null || endUid == null) {
				titleLabel.text = "Маршруты"
//				status.text = "Select start/end activities."
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
				clearDiagram()
				return
			}

			val len = routes.first().edges.size
			titleLabel.text = "Маршрут: $startName → $endName"
			renderBranchingRoutes(startUid = startUid, endUid = endUid, routes = routes, cyclesGraph = cyclesGraph)
		}

		buildBtn.setOnAction { rebuild() }
		stepBtn.setOnAction { currentSession.onDebugStep?.invoke() }
		val topBar = HBox(
			10.0,
			Label("Start:"), startCb,
			Label("End:"), endCb,
			buildBtn,
			stepBtn,
			runBtn,
			Label("Exit:"),
			exitDbgLabel
		).apply {
			padding = Insets(10.0)
			alignment = Pos.CENTER_LEFT
		}

		val header = VBox(4.0, titleLabel).apply { padding = Insets(10.0) }

		Platform.runLater {
			highlightByActivityName(currentSession.debugActivityNameOf(currentSession.debugCurrentActivityProps.get()) ?: currentActivityName)
		}

		return BorderPane().apply {
			top = VBox(topBar, header)
			center = scroll
		}
	}

	// ========= cycles detection =========
	private data class CyclesGraphInfo(
		val cycleEdges: List<DirEdge>,
		val cyclicNodeUids: Set<String>,
		val cyclesCount: Int
	)

	private fun computeCyclesGraphInfo(dirEdges: List<DirEdge>): CyclesGraphInfo {
		val nodes = LinkedHashSet<String>()
		val adj = HashMap<String, MutableList<String>>()
		dirEdges.forEach { e ->
			nodes.add(e.fromUid)
			nodes.add(e.toUid)
			adj.computeIfAbsent(e.fromUid) { mutableListOf() }.add(e.toUid)
			adj.computeIfAbsent(e.toUid) { mutableListOf() } // ensure key exists
		}

		var index = 0
		val idx = HashMap<String, Int>()
		val low = HashMap<String, Int>()
		val stack = ArrayDeque<String>()
		val onStack = HashSet<String>()
		val sccs = mutableListOf<List<String>>()

		fun strongConnect(v: String) {
			idx[v] = index
			low[v] = index
			index++

			stack.addLast(v)
			onStack.add(v)

			adj[v]?.forEach { w ->
				if (!idx.containsKey(w)) {
					strongConnect(w)
					low[v] = kotlin.math.min(low[v]!!, low[w]!!)
				} else if (onStack.contains(w)) {
					low[v] = kotlin.math.min(low[v]!!, idx[w]!!)
				}
			}

			if (low[v] == idx[v]) {
				val comp = mutableListOf<String>()
				while (true) {
					val w = stack.removeLast()
					onStack.remove(w)
					comp.add(w)
					if (w == v) break
				}
				sccs.add(comp)
			}
		}

		nodes.forEach { v ->
			if (!idx.containsKey(v)) strongConnect(v)
		}

		val compId = HashMap<String, Int>()
		sccs.forEachIndexed { i, comp -> comp.forEach { compId[it] = i } }

		// SCC считается циклом если > 1 узла, либо одиночный узел с самопетлёй
		val compIsCycle = BooleanArray(sccs.size)
		sccs.forEachIndexed { i, comp ->
			if (comp.size > 1) {
				compIsCycle[i] = true
			} else {
				val v = comp[0]
				compIsCycle[i] = adj[v]?.any { it == v } == true
			}
		}

		val cyclicNodeUids = HashSet<String>()
		sccs.forEachIndexed { i, comp ->
			if (compIsCycle[i]) cyclicNodeUids.addAll(comp)
		}

		val cycleEdges = dirEdges
			.filter { e ->
				val a = compId[e.fromUid] ?: return@filter false
				val b = compId[e.toUid] ?: return@filter false
				a == b && compIsCycle[a]
			}
			.distinctBy { it.fromUid to it.toUid }

		val cyclesCount = compIsCycle.count { it }
		return CyclesGraphInfo(cycleEdges = cycleEdges, cyclicNodeUids = cyclicNodeUids, cyclesCount = cyclesCount)
	}

	private fun computeCycles(edges: List<DirEdge>, uidToName: Map<String, String>): List<CycleInfo> {
		val adj = mutableMapOf<String, MutableList<String>>()
		val nodes = LinkedHashSet<String>()
		edges.forEach { e ->
			nodes.add(e.fromUid)
			nodes.add(e.toUid)
			adj.getOrPut(e.fromUid) { mutableListOf() }.add(e.toUid)
		}

		// Tarjan SCC
		var index = 0
		val stack = ArrayDeque<String>()
		val onStack = HashSet<String>()
		val idx = HashMap<String, Int>()
		val low = HashMap<String, Int>()
		val sccs = mutableListOf<List<String>>()

		fun strongConnect(v: String) {
			idx[v] = index
			low[v] = index
			index += 1
			stack.addLast(v)
			onStack.add(v)

			for (w in adj[v].orEmpty()) {
				if (w !in idx) {
					strongConnect(w)
					low[v] = minOf(low[v]!!, low[w]!!)
				} else if (w in onStack) {
					low[v] = minOf(low[v]!!, idx[w]!!)
				}
			}

			if (low[v] == idx[v]) {
				val comp = mutableListOf<String>()
				while (true) {
					val w = stack.removeLast()
					onStack.remove(w)
					comp.add(w)
					if (w == v) break
				}
				sccs.add(comp)
			}
		}

		nodes.forEach { v -> if (v !in idx) strongConnect(v) }

		val selfLoops = edges.filter { it.fromUid == it.toUid }.map { it.fromUid }.toSet()

		val cycleInfos = mutableListOf<CycleInfo>()
		for (comp in sccs) {
			val isCycle = comp.size > 1 || (comp.size == 1 && comp[0] in selfLoops)
			if (!isCycle) continue

			val cycle = buildOneCycle(comp, adj)
			if (cycle.isEmpty()) continue

			val text =
				cycle.joinToString(" → ") { uidToName[it] ?: it } + " → " + (uidToName[cycle.first()] ?: cycle.first())
			cycleInfos.add(CycleInfo(uids = cycle, text = text))
		}

		return cycleInfos.sortedBy { it.text }
	}

	// build one representative simple cycle inside an SCC
	private fun buildOneCycle(comp: List<String>, adj: Map<String, List<String>>): List<String> {
		val set = comp.toHashSet()
		val start = comp.firstOrNull() ?: return emptyList()

		val parent = HashMap<String, String?>()
		val visited = HashSet<String>()
		var foundBackFrom: String? = null
		var foundBackTo: String? = null

		fun dfs(v: String) {
			if (foundBackFrom != null) return
			visited.add(v)
			for (w in adj[v].orEmpty()) {
				if (w !in set) continue
				if (w !in visited) {
					parent[w] = v
					dfs(w)
					if (foundBackFrom != null) return
				} else {
					// found an edge to an already visited node in this SCC; treat as cycle closure
					foundBackFrom = v
					foundBackTo = w
					return
				}
			}
		}

		parent[start] = null
		dfs(start)

		val a = foundBackFrom ?: return listOf(start) // self-loop fallback; shown as 1-node cycle
		val b = foundBackTo ?: start

		// reconstruct path a -> ... -> b using parent links backwards from a until b
		val pathRev = mutableListOf<String>()
		var cur: String? = a
		while (cur != null) {
			pathRev.add(cur)
			if (cur == b) break
			cur = parent[cur]
		}
		if (pathRev.last() != b) {
			// couldn't reach b via parent chain; fallback to comp order
			return comp
		}
		val cycle = pathRev.asReversed()
		return cycle
	}


	private enum class EdgeStyle {
		NORMAL,
		ROUTE,
		CYCLE
	}

	private data class ExitKey(val fromUid: String, val exit: String)

	private fun DirEdge.exitLabel(): String? {
		val c = this.javaClass
		val getterNames = listOf(
			"getExitName",
			"getExit",
			"getOutcome",
			"getOutcomeName",
			"getLabel",
			"getVia"
		)
		for (g in getterNames) {
			val m = c.methods.firstOrNull { it.name == g && it.parameterCount == 0 } ?: continue
			val v = runCatching { m.invoke(this) }.getOrNull() ?: continue
			when (v) {
				is String -> if (v.isNotBlank()) return v
				is Enum<*> -> return v.name
				else -> {
					val nm = v.javaClass.methods.firstOrNull { it.name == "getName" && it.parameterCount == 0 }
					val s = runCatching { nm?.invoke(v) }.getOrNull()
					if (s is String && s.isNotBlank()) return s
				}
			}
		}
		return null
	}

}