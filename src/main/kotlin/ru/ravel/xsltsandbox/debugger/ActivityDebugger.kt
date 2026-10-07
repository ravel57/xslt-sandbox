package ru.ravel.xsltsandbox.debugger

import com.fasterxml.jackson.dataformat.xml.XmlMapper
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.Properties
import java.util.Stack
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.application.Platform
import javafx.concurrent.Task
import javafx.util.Duration
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.name
import ru.ravel.xsltsandbox.AppContext
import ru.ravel.xsltsandbox.debug.DebugStep
import ru.ravel.xsltsandbox.datadocs.DataDocsProcessor.inputDocuments
import ru.ravel.xsltsandbox.datadocs.DataDocsProcessor
import ru.ravel.xsltsandbox.datadocs.DataDocsProcessor.getDataDocsInOut
import ru.ravel.xsltsandbox.datadocs.DataDocsProcessor.mergeMockWithDataDocs
import ru.ravel.xsltsandbox.datadocs.DataDocsProcessor.replaceDataDocsInString
import ru.ravel.xsltsandbox.datadocs.MockXmlEditor
import ru.ravel.xsltsandbox.diagram.FlowGraphExtractor
import ru.ravel.xsltsandbox.editor.DataDocsViewer
import ru.ravel.xsltsandbox.files.SessionFiles
import ru.ravel.xsltsandbox.models.ActivityType
import ru.ravel.xsltsandbox.models.DocSession
import ru.ravel.xsltsandbox.models.TabState
import ru.ravel.xsltsandbox.models.TransformMode
import ru.ravel.xsltsandbox.models.layout.DiagramLayout
import ru.ravel.xsltsandbox.models.procedure.ProcedureCall
import ru.ravel.xsltsandbox.transform.Transformer
import ru.ravel.xsltsandbox.ui.Dialogs.showStatus
import ru.ravel.xsltsandbox.utils.LayoutUtil
import ru.ravel.xsltsandbox.log.AppLog

/**
 * Отладчик активностей: пошаговый проход по Layout.xml и прогон по всем маршрутам.
 */
class ActivityDebugger(
	private val ctx: AppContext,
	private val transformer: Transformer,
	private val files: SessionFiles,
	private val viewer: DataDocsViewer,
	private val mockEditor: MockXmlEditor,
) {
	private val activitiesDebugProcedureStack = mutableMapOf<DocSession, Stack<Path>>()



	fun setInitialCallStack(session: DocSession, callSites: List<Path>) {
		activitiesDebugProcedureStack[session] = Stack<Path>().apply { callSites.forEach(::push) }
	}


	fun runDebugAllPathsBackground() {
		val session = ctx.currentSession

		if (session.dataDocs.isNullOrBlank()) {
			showStatus(ctx.stage, "DataDocs пустые — вставьте DataDocs")
			return
		}

		val task = object : javafx.concurrent.Task<String>() {
			override fun call(): String {
				val report = StringBuilder()
				return report.toString()
			}
		}

		task.setOnSucceeded {
			val report = task.value ?: ""
			viewer.show(ctx.stage, "Debug report", report)
		}

		task.setOnFailed {
			val ex = task.exception
			showStatus(ctx.stage, "Run debug error: ${ex?.message ?: "unknown"}")
		}

		Thread(task, "debug-runner").apply { isDaemon = true }.start()
	}


	fun runDebugAllPaths() {
		val dataDocs = ctx.currentSession.dataDocs
		if (dataDocs.isNullOrBlank()) {
			showStatus(ctx.stage, "Paste DataDocs first (Activities debugger -> Open DataDocs -> Input text).")
			return
		}

		val mode = ctx.currentSession.mode
		val selectedActivityPath = when (mode) {
			TransformMode.XSLT -> ctx.currentSession.xsltPath
			TransformMode.BR -> ctx.currentSession.brPath
			TransformMode.ST,
			TransformMode.SV,
			TransformMode.PR,
			TransformMode.PROCEDURE_RETURN,
			TransformMode.WA,
			TransformMode.FM,
			TransformMode.OTHER -> ctx.currentSession.otherActivityPath
		} ?: run {
			showStatus(ctx.stage, "No activity selected.")
			return
		}

		val flowDir = selectedActivityPath.parent?.parent ?: run {
			showStatus(ctx.stage, "Cannot resolve flow directory for:\n$selectedActivityPath")
			return
		}

		val layoutFile = flowDir.resolve("Layout.xml")
		if (!Files.exists(layoutFile)) {
			showStatus(ctx.stage, "Layout.xml not found:\n$layoutFile")
			return
		}

		val layout = try {
			XmlMapper().readValue(layoutFile.toFile(), DiagramLayout::class.java)
		} catch (e: Exception) {
			showStatus(ctx.stage, "Failed to parse Layout.xml:\n${e.message}")
			return
		}

		val elements = layout.elements?.diagramElements.orEmpty()
		val uidToName: Map<String, String> = elements
			.mapNotNull { e ->
				val uid = e.uid
				if (uid.isNullOrBlank()) null else uid to (e.reference ?: uid)
			}
			.toMap()

		val nameToUid = LinkedHashMap<String, String>().apply {
			uidToName.forEach { (uid, name) -> if (!containsKey(name)) this[name] = uid }
		}

		val startName = selectedActivityPath.parent?.fileName?.toString()
		val startUid = startName?.let { nameToUid[it] } ?: run {
			showStatus(ctx.stage, "Cannot map start activity to Layout.xml element: $startName")
			return
		}

		val edges = FlowGraphExtractor.extractDirectedEdges(layout)
		val adj = LinkedHashMap<String, MutableList<String>>()


		fun edgeFromUid(e: Any): String? {
			val c = e.javaClass
			val getterNames = listOf("getFromUid", "getFrom", "getSourceUid", "getSrcUid", "getA", "getLeft")
			for (g in getterNames) {
				val m = c.methods.firstOrNull { it.name == g && it.parameterCount == 0 } ?: continue
				val v = runCatching { m.invoke(e) }.getOrNull() ?: continue
				if (v is String && v.isNotBlank()) return v
			}
			return null
		}

		fun edgeToUid(e: Any): String? {
			val c = e.javaClass
			val getterNames = listOf("getToUid", "getTo", "getTargetUid", "getDstUid", "getB", "getRight")
			for (g in getterNames) {
				val m = c.methods.firstOrNull { it.name == g && it.parameterCount == 0 } ?: continue
				val v = runCatching { m.invoke(e) }.getOrNull() ?: continue
				if (v is String && v.isNotBlank()) return v
			}
			return null
		}

		edges.forEach { e ->
			val a = edgeFromUid(e) ?: return@forEach
			val b = edgeToUid(e) ?: return@forEach
			adj.computeIfAbsent(a) { mutableListOf() }.add(b)
			adj.computeIfAbsent(b) { mutableListOf() }
		}

		// Синки: вершины без исходящих дуг
		val sinks = adj.filter { it.value.isEmpty() }.keys.toList()
		if (sinks.isEmpty()) {
			showStatus(ctx.stage, "No terminal nodes (sinks) found in Layout.xml graph.")
			return
		}

		// Все простые пути start -> sinks (без повторов вершин). Циклы обрезаем.
		val maxPaths = 2000
		val maxDepth = 200
		val allRoutes = ArrayList<List<String>>()

		fun dfs(u: String, path: MutableList<String>, used: MutableSet<String>) {
			if (allRoutes.size >= maxPaths) return
			if (path.size > maxDepth) return
			if (u in sinks) {
				allRoutes.add(path.toList())
				return
			}
			val nexts = adj[u].orEmpty()
			nexts.forEach { v ->
				if (v in used) return@forEach
				used.add(v)
				path.add(v)
				dfs(v, path, used)
				path.removeAt(path.lastIndex)
				used.remove(v)
			}
		}

		dfs(startUid, mutableListOf(startUid), mutableSetOf(startUid))

		if (allRoutes.isEmpty()) {
			showStatus(ctx.stage, "No routes found from $startName.")
			return
		}


		// Подготовить последовательность шагов (routeIndex, stepIndex, propsPath)
		data class Step(val routeIdx: Int, val stepIdx: Int, val totalSteps: Int, val props: Path?)

		val steps = ArrayList<Step>()
		allRoutes.forEachIndexed { rIdx, route ->
			val total = route.size
			route.forEachIndexed { sIdx, uid ->
				val name = uidToName[uid]
				val props = name?.let { flowDir.resolve(it).resolve("Properties.xml") }
				steps.add(Step(rIdx + 1, sIdx + 1, total, props))
			}
		}

		// Анимация пробежки по всем путям
		val timeline = Timeline()
		timeline.cycleCount = steps.size
		val frameMs = 180.0

		var i = 0
		timeline.keyFrames.add(KeyFrame(Duration.millis(frameMs), javafx.event.EventHandler {
			val st = steps[i]
			ctx.currentSession.debugLastExitName.set("route ${st.routeIdx}/${allRoutes.size}, step ${st.stepIdx}/${st.totalSteps}")
			ctx.currentSession.debugCurrentActivityProps.set(st.props)
			i++
		}))

		timeline.setOnFinished {
			showStatus(ctx.stage, "Run debug finished. Routes: ${allRoutes.size}")
		}

		timeline.playFromStart()
	}


	fun goToNextActivity() {
		setNextActivity(null)
	}


	/**
	 * Работает с [ctx.currentSession]
	 */
	private fun setNextActivity(incomeResult: String?) {
		val selectedActivityPath = when (ctx.currentSession.mode) {
			TransformMode.XSLT -> ctx.currentSession.xsltPath
			TransformMode.BR -> ctx.currentSession.brPath
			TransformMode.ST,
			TransformMode.SV,
			TransformMode.PR,
			TransformMode.PROCEDURE_RETURN,
			TransformMode.WA,
			TransformMode.FM,
			TransformMode.OTHER,
				-> ctx.currentSession.otherActivityPath
		} ?: return

		val docsIn = ctx.currentSession.xmlArea.text
		val result = transformer.doTransform(ctx.stage)

		val exitName = when (ctx.currentSession.mode) {
			in arrayOf(TransformMode.BR, TransformMode.ST, TransformMode.WA, TransformMode.FM) -> result
			in arrayOf(TransformMode.PR, TransformMode.PROCEDURE_RETURN) -> incomeResult ?: "Completed"
			else -> null
		}

		val nextActivityName = LayoutUtil(ctx.currentSession)
			.getNextActivity(selectedActivityPath, ctx.currentSession.mode, exitName)

		reportStep(selectedActivityPath, exitName, nextActivityName, docsIn, result)

		if (nextActivityName == null) {
			return
		}

		val nextActivityDir = selectedActivityPath.parent?.parent?.resolve(nextActivityName)
		AppLog.info("отладка: $selectedActivityPath [${ctx.currentSession.mode}] выход=$exitName → $nextActivityName")
		val nextActivityPropertiesPath = nextActivityDir?.resolve("Properties.xml")
			?: return

		val nextActivityType = LayoutUtil.getActivityType(nextActivityPropertiesPath.toFile())

		if (ctx.currentSession.mode == TransformMode.XSLT) {
			val dataDocsOutputs = DataDocsProcessor.outputDocs(
				ctx.currentSession.xsltPath,
				ctx.currentSession.mappingPropertyFile,
			)

			if (dataDocsOutputs.isNotEmpty()) {
				val before = ctx.currentSession.dataDocs!!
				try {
					ctx.currentSession.dataDocs = replaceDataDocsInString(before, result, dataDocsOutputs)
					val returned = DataDocsProcessor.topLevelNameList(result)
					val missing = dataDocsOutputs.filterNot { it in returned }
					AppLog.info(
						buildString {
							append("выход ${selectedActivityPath.parent?.fileName} → датадоки $dataDocsOutputs: ")
							append("${before.length} → ${ctx.currentSession.dataDocs!!.length}")
							append("симв.; в результате XSLT: ${DataDocsProcessor.topLevelNames(result)}")
						},
					)
					if (missing.isNotEmpty()) {
						AppLog.warn("результат ${selectedActivityPath.parent?.fileName} не содержит $missing — прежние значения оставлены")
					}
				} catch (e: Exception) {
					AppLog.error("Не удалось подставить выход ${selectedActivityPath.parent?.fileName} в датадоки", e)
				}
			}
		}

		processNextActivity(nextActivityPropertiesPath, nextActivityType, nextActivityDir)
	}


	/** Сообщает process viewer о выполненной активности; без `--debug-port` ничего не делает. */
	private fun reportStep(
		activityPath: Path,
		exitName: String?,
		nextActivityName: String?,
		docsIn: String?,
		result: String?,
	) {
		val bridge = ctx.debugBridge ?: return
		val activityDir = activityPath.parent ?: return
		val mode = ctx.currentSession.mode
		bridge.sendStep(
			DebugStep(
				procedure = activityDir.parent?.fileName?.toString() ?: return,
				activity = activityDir.fileName.toString(),
				mode = mode.name,
				exit = exitName,
				next = nextActivityName,
				docsIn = docsIn,
				docsOut = if (mode == TransformMode.XSLT) result else null,
			),
		)
	}


	private fun processProcedure(path: Path) {
		val file = path.resolve("Properties.xml").toFile()
		val procedureToCall = ctx.xmlMapper.readValue(file, ProcedureCall::class.java).procedureToCall
		val procedureDir = if (path.parent?.name == "MainFlow") {
			procedureToCall?.let { path.parent?.parent?.resolve("Procedures")?.resolve(it) }
		} else {
			procedureToCall?.let { path.parent?.parent?.parent?.resolve("Procedures")?.resolve(it) }
		} ?: return
		val procedureLayout = procedureDir.resolve("Layout.xml")
		val layout = ctx.xmlMapper.readValue(procedureLayout.toFile(), DiagramLayout::class.java)
		val elementRef = layout.connections?.diagramConnections
			?.first { conn -> conn.endPoints?.points?.any { it.exitPointRef == "Start" } == true }
			?.endPoints?.points
			?.first { it.exitPointRef != "Start" }
			?.elementRef
			?: return
		val firstActivityName = layout.elements?.diagramElements
			?.first { el -> el.uid == elementRef }
			?.reference
			?: ""
		val firstActivityProperties = procedureDir.resolve(firstActivityName).resolve("Properties.xml")
		val activityType = LayoutUtil.getActivityType(firstActivityProperties.toFile())

		processNextActivity(firstActivityProperties, activityType, procedureDir.resolve(firstActivityName))
	}


	private fun processNextActivity(
		nextActivityPropertiesPath: Path,
		nextActivityType: ActivityType,
		nextActivityDir: Path,
	) {
		// ST получает все дата-документы: её правилам нужны разные
		val neededDataDocs = inputDocuments(nextActivityPropertiesPath.toFile(), ctx.currentSession.dataDocs!!)
		ctx.currentSession.xmlArea.replaceText(neededDataDocs)

		if (nextActivityPropertiesPath.exists()) {
			when (nextActivityType) {
				ActivityType.BIZ_RULE -> {
					ctx.currentSession.mode = TransformMode.BR
					val state = TabState(
						xmlPath = null,
						xsltPath = null,
						brPath = nextActivityPropertiesPath.absolutePathString(),
						process = null
					)
					files.loadTabStateIntoSession(ctx.currentSession, state)
					ctx.currentSession.xsltPath = null
					ctx.currentSession.updateTabTitle()
					ctx.brRadio.isSelected = true
				}

				ActivityType.DATA_MAPPING -> {
					ctx.currentSession.mode = TransformMode.XSLT
					val xsltFile = nextActivityDir.resolve("Mapping.xslt")
					val state = TabState(
						xmlPath = null,
						xsltPath = xsltFile.absolutePathString(),
						brPath = null,
						process = null
					)
					files.loadTabStateIntoSession(ctx.currentSession, state)
					ctx.currentSession.brPath = null
					ctx.currentSession.updateTabTitle()
					ctx.xsltRadio.isSelected = true
				}

				ActivityType.DATA_SOURCE -> {
					ctx.currentSession.mode = TransformMode.XSLT
					val xsltFile = nextActivityDir.resolve("MappingOutput.xslt")
					val mockPath = mockEditor.ensureMockXml(nextActivityDir)
						?: return
					if (mockPath.exists()) {
						val mockText = mockPath.toFile().readText()
						val wantedDocs = getDataDocsInOut(nextActivityPropertiesPath.toFile())
							.filter { it.access in arrayOf("InOut", "Input", "Output") }
							.map { it.referenceName }
							.distinct()
						val sourceDocsXml = ctx.currentSession.xmlArea.text
							.takeIf { it.isNotBlank() }
							?: (ctx.currentSession.dataDocs ?: "")
						val merged = mergeMockWithDataDocs(mockText, sourceDocsXml, wantedDocs)

						val state = TabState(
							xmlPath = null,
							xsltPath = xsltFile.absolutePathString(),
							brPath = null,
							process = null
						)
						files.loadTabStateIntoSession(ctx.currentSession, state)
						ctx.currentSession.xmlArea.replaceText(merged)
						ctx.currentSession.xmlPath = null
						ctx.currentSession.updateTabTitle()
						ctx.xsltRadio.isSelected = true
					} else {
						Platform.runLater {
							showStatus(ctx.stage, "Mock.xml not found in directory:\n${nextActivityDir}")
						}
					}
				}

				ActivityType.SEGMENTATION_TREE -> {
					ctx.currentSession.mode = TransformMode.ST
					ctx.currentSession.otherActivityPath = nextActivityPropertiesPath
					setNextActivity(null)
					return
				}

				ActivityType.SET_VALUE -> {
					ctx.currentSession.mode = TransformMode.SV
					ctx.currentSession.otherActivityPath = nextActivityPropertiesPath
					setNextActivity(null)
					return
				}

				ActivityType.PROCEDURE_CALL -> {
					if (activitiesDebugProcedureStack.containsKey(ctx.currentSession)) {
						activitiesDebugProcedureStack[ctx.currentSession]?.push(nextActivityPropertiesPath)
					} else {
						activitiesDebugProcedureStack[ctx.currentSession] =
							Stack<Path>().apply { push(nextActivityPropertiesPath) }
					}
					processProcedure(nextActivityDir)
					return
				}

				ActivityType.PROCEDURE_RETURN -> {
					if (activitiesDebugProcedureStack[ctx.currentSession]?.isNotEmpty() == true) {
						ctx.currentSession.mode = TransformMode.PR
						ctx.currentSession.otherActivityPath = nextActivityPropertiesPath
						val result = transformer.doTransform(ctx.stage)
						ctx.currentSession.otherActivityPath = activitiesDebugProcedureStack[ctx.currentSession]?.pop()
						ctx.currentSession.mode = TransformMode.PROCEDURE_RETURN
						setNextActivity(result)
						return
					}
				}

				ActivityType.END_PROCEDURE -> {
					Platform.runLater { showStatus(ctx.stage, "Procedure finished") }
					return
				}

				ActivityType.FORM -> {
					ctx.currentSession.mode = TransformMode.FM
					ctx.currentSession.xsltPath = nextActivityPropertiesPath
					ctx.currentSession.otherActivityPath = nextActivityPropertiesPath
					setNextActivity(null)
					return
				}

				ActivityType.WAIT -> {
					ctx.currentSession.mode = TransformMode.WA
					ctx.currentSession.xsltPath = nextActivityPropertiesPath
					ctx.currentSession.otherActivityPath = nextActivityPropertiesPath
					setNextActivity(null)
					return
				}

				else -> {
					ctx.currentSession.mode = TransformMode.PROCEDURE_RETURN
					ctx.currentSession.otherActivityPath = nextActivityPropertiesPath
					setNextActivity(null)
					return
				}
			}
		}
	}
}
