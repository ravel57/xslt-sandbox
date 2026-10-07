package ru.ravel.xsltsandbox.debugger

import java.nio.file.Path
import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.Properties
import java.util.Stack
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


	private class AutoRun {
		var cancelled = false
		var steps = 0

		var stoppedAt: Path? = null

		var endReason: String? = null
		val visits = HashMap<String, Int>()
	}

	private var autoRun: AutoRun? = null

	val isAutoRunning: Boolean get() = autoRun != null

	/** Подпись кнопки «Run debug» меняется на «Stop», пока идёт автопрогон. */
	var onAutoRunStateChanged: (Boolean) -> Unit = {}

	fun stopAutoRun() {
		autoRun?.cancelled = true
	}


	fun runUntilFormOrWait() {
		if (autoRun != null) return
		val session = ctx.currentSession
		if (session.dataDocs.isNullOrBlank()) {
			showStatus(ctx.stage, "DataDocs пустые — загрузите DataDocs (Activities debugger → Open DataDocs).")
			return
		}
		if (currentActivityKey() == null) {
			showStatus(ctx.stage, "Не открыта активность, с которой начинать.")
			return
		}
		val run = AutoRun()
		autoRun = run
		ctx.autoRunning = true
		onAutoRunStateChanged(true)
		AppLog.info("автопрогон: старт с ${currentActivityKey()}")
		autoStep(run)
	}


	private fun autoStep(run: AutoRun) {
		if (run.cancelled) return finishAutoRun(run, "Остановлено пользователем.")
		if (run.steps >= AUTO_RUN_MAX_STEPS) return finishAutoRun(run, "Достигнут лимит шагов ($AUTO_RUN_MAX_STEPS).")

		whenLoaded { loaded ->
			if (!loaded) return@whenLoaded finishAutoRun(run, "Файл следующей активности не загрузился за отведённое время.")

			val session = ctx.currentSession
			val before = currentActivityKey() ?: return@whenLoaded finishAutoRun(run, "Не открыта активность.")
			val visits = (run.visits[before] ?: 0) + 1
			run.visits[before] = visits
			if (visits > AUTO_RUN_MAX_VISITS) {
				return@whenLoaded finishAutoRun(run, "Активность выполняется по кругу (больше $AUTO_RUN_MAX_VISITS раз): ${activityName(before)}.")
			}

			val executedXslt = session.mode == TransformMode.XSLT
			run.steps++
			try {
				setNextActivity(null)
			} catch (e: Exception) {
				AppLog.error("автопрогон: ошибка на ${activityName(before)}", e)
				return@whenLoaded finishAutoRun(run, "Ошибка на активности ${activityName(before)}:\n${e.message}")
			}

			run.stoppedAt?.let { return@whenLoaded finishAutoRun(run, null) }
			run.endReason?.let { return@whenLoaded finishAutoRun(run, "$it (шаг, начатый с ${activityName(before)}).") }
			if (executedXslt && session.xsltSyntaxErrorRanges.isNotEmpty()) {
				return@whenLoaded finishAutoRun(run, "В XSLT активности ${activityName(before)} есть ошибки — прогон остановлен.")
			}
			whenLoaded { nextLoaded ->
				if (!nextLoaded) return@whenLoaded finishAutoRun(
					run,
					"Файл активности после ${activityName(before)} не загрузился за отведённое время."
				)
				if (currentActivityKey() == before) {
					return@whenLoaded finishAutoRun(
						run,
						"Дальше пути нет: шаг после ${activityName(before)} не выполнен (отменён Mock.xml или выбор выхода)."
					)
				}
				Platform.runLater { autoStep(run) }
			}
		}
	}


	private fun finishAutoRun(run: AutoRun, problem: String?) {
		autoRun = null
		ctx.autoRunning = false
		onAutoRunStateChanged(false)
		val message = when {
			run.stoppedAt != null ->
				"Дошли до ${kindOf(run.stoppedAt!!)} ${run.stoppedAt!!.parent?.fileName}. Шагов: ${run.steps}.\n" +
						"Нажмите Next, чтобы выбрать действие."

			else -> "$problem\nШагов: ${run.steps}."
		}
		AppLog.info("автопрогон завершён: ${message.replace('\n', ' ')}")
		if (!run.cancelled) showStatus(ctx.stage, message)
	}


	/** Ждёт, пока файлы только что загруженной активности прочитаются в фоне (не дольше [AUTO_RUN_LOAD_TIMEOUT_MS]). */
	private fun whenLoaded(deadline: Long = System.currentTimeMillis() + AUTO_RUN_LOAD_TIMEOUT_MS, action: (Boolean) -> Unit) {
		when {
			ctx.pendingLoads <= 0 -> action(true)
			System.currentTimeMillis() > deadline -> action(false)
			else -> javafx.animation.PauseTransition(Duration.millis(30.0)).apply {
				setOnFinished { whenLoaded(deadline, action) }
			}.play()
		}
	}


	/** Идентификатор текущего шага: режим и путь выбранной активности; null — активность не открыта. */
	private fun currentActivityKey(): String? {
		val session = ctx.currentSession
		val path = when (session.mode) {
			TransformMode.XSLT -> session.xsltPath
			TransformMode.BR -> session.brPath
			else -> session.otherActivityPath
		}
			?: return null
		return "${session.mode}|$path"
	}

	private fun activityName(key: String): String = runCatching {
		Path.of(key.substringAfter('|')).let { it.parent?.fileName ?: it.fileName }
	}
		.getOrNull()
		?.toString()
		?: key

	private fun kindOf(propertiesPath: Path): String = if (LayoutUtil.getActivityType(propertiesPath.toFile()) == ActivityType.FORM) {
		"формы"
	} else {
		"вейта"
	}


	fun goToNextActivity() {
		if (autoRun != null) return
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
			autoRun?.endReason = "Нет перехода по выходу «${exitName ?: "Completed"}» у ${selectedActivityPath.parent?.fileName}"
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
					if (activitiesDebugProcedureStack[ctx.currentSession]?.isNotEmpty() != true) {
						autoRun?.endReason = "Дошли до возврата из процедуры, а вызывающий блок неизвестен (стек вызовов пуст)"
					}
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
					autoRun?.endReason = "Процесс завершён (EndProcess)"
					if (autoRun == null) {
						Platform.runLater { showStatus(ctx.stage, "Procedure finished") }
					}
					return
				}

				ActivityType.FORM -> {
					ctx.currentSession.mode = TransformMode.FM
					ctx.currentSession.xsltPath = nextActivityPropertiesPath
					ctx.currentSession.otherActivityPath = nextActivityPropertiesPath
					// Автопрогон останавливается на форме: окно выбора действия откроет следующий Next
					autoRun?.let {
						it.stoppedAt = nextActivityPropertiesPath
						return
					}
					setNextActivity(null)
					return
				}

				ActivityType.WAIT -> {
					ctx.currentSession.mode = TransformMode.WA
					ctx.currentSession.xsltPath = nextActivityPropertiesPath
					ctx.currentSession.otherActivityPath = nextActivityPropertiesPath
					autoRun?.let {
						it.stoppedAt = nextActivityPropertiesPath
						return
					}
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

	private companion object {
		const val AUTO_RUN_MAX_STEPS = 1000
		const val AUTO_RUN_MAX_VISITS = 50
		const val AUTO_RUN_LOAD_TIMEOUT_MS = 15_000L
	}
}
