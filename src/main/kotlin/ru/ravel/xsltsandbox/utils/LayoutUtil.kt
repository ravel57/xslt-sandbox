package ru.ravel.xsltsandbox.utils

import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import ru.ravel.xsltsandbox.models.ActivityDirection
import ru.ravel.xsltsandbox.models.ActivityType
import ru.ravel.xsltsandbox.models.DocSession
import ru.ravel.xsltsandbox.models.TransformMode
import ru.ravel.xsltsandbox.models.layout.DiagramLayout
import java.io.File
import java.nio.file.Path
import java.util.*
import kotlin.io.path.name


class LayoutUtil(
	private val session: DocSession,
) {

	init {
		if (!stack.containsKey(session)) {
			stack[session] = Stack<String?>()
		}
	}

	fun getActivityByDirection(
		selectedActivity: Path,
		activityDirection: ActivityDirection,
		mode: TransformMode,
		exitName: String?,
	): String? {
		val mapper = XmlMapper().registerKotlinModule()

		val layoutFile = File(selectedActivity.parent.parent.toFile(), "Layout.xml")
		val diagramLayout = mapper.readValue(layoutFile, DiagramLayout::class.java)

		val currentActivityName = selectedActivity.parent.name
		val currentActivityUid = diagramLayout.elements?.diagramElements
			?.firstOrNull { el -> el.reference == currentActivityName }
			?.uid

		when (activityDirection) {
			ActivityDirection.NEXT -> {
				val nextActivityUid = diagramLayout.connections?.diagramConnections
					?.firstOrNull { conn ->
						when (mode) {
							TransformMode.XSLT, TransformMode.SV -> {
								conn.endPoints?.points?.any {
									it.elementRef == currentActivityUid && it.exitPointRef == "Completed"
								} == true
							}

							TransformMode.BR -> {
								conn.endPoints?.points?.any {
									it.elementRef == currentActivityUid && it.exitPointRef?.lowercase() == exitName
								} == true
							}

							TransformMode.ST -> {
								conn.endPoints?.points?.any {
									it.elementRef == currentActivityUid && it.exitPointRef == exitName
								} == true
							}

							TransformMode.OTHER -> {
								TODO()
							}
						}
					}
					?.endPoints?.points
					?.firstOrNull { it.exitPointRef == "Enter" }
					?.elementRef
				val nextActivityName = diagramLayout.elements?.diagramElements
					?.firstOrNull { el -> el.uid == nextActivityUid }
					?.reference

				stack[session]?.push(currentActivityName)
				return nextActivityName
			}

			ActivityDirection.PREVIOUS -> {
				val previousActivityUids = diagramLayout.connections?.diagramConnections
					?.filter { conn -> conn.endPoints?.points?.any { it.elementRef == currentActivityUid && it.exitPointRef == "Enter" } == true }
				if (previousActivityUids?.size?.let { it > 1 } == true) {
					return stack[session]?.pop()
				} else {
					val previousActivityUid = previousActivityUids
						?.firstOrNull { conn -> conn.endPoints?.points?.any { it.elementRef == currentActivityUid && it.exitPointRef == "Enter" } == true }
						?.endPoints?.points
						?.firstOrNull { it.elementRef != currentActivityUid }
						?.elementRef
					val previousActivity = diagramLayout.elements?.diagramElements
						?.firstOrNull { el -> el.uid == previousActivityUid }
						?.reference
					if (stack[session]?.isNotEmpty() == true) {
						stack[session]?.pop()
					}
					return previousActivity
				}
			}

			else -> {
				throw RuntimeException("Unknown activity direction $activityDirection")
			}
		}
	}


	companion object {
		private val stack = mutableMapOf<DocSession, Stack<String?>>()


		fun getActivityType(file: File): ActivityType {
			val header = XmlUtil.readXmlSafe(file).lines().first().replace("\uFEFF", "")
			return when {
				header.startsWith("<BizRuleActivityDefinition") -> ActivityType.BIZ_RULE
				header.startsWith("<DataSourceActivityDefinition") -> ActivityType.DATA_SOURCE
				header.startsWith("<DispatchActivityDefinition") -> ActivityType.DISPATCH
				header.startsWith("<FormActivityDefinition") -> ActivityType.FORM
				header.startsWith("<MappingActivityDefinition") -> ActivityType.DATA_MAPPING
				header.startsWith("<ProcedureCallActivityDefinition") -> ActivityType.PROCEDURE_CALL
				header.startsWith("<SegmentationTreeActivityDefinition") -> ActivityType.SEGMENTATION_TREE
				header.startsWith("<SetValueActivityDefinition") -> ActivityType.SET_VALUE
				header.startsWith("<WaitActivityDefinition") -> ActivityType.WAIT
				header.startsWith("<ProcedureReturnActivityDefinition") -> ActivityType.PROCEDURE_RETURN
				header.startsWith("<EndProcessActivityDefinition") -> ActivityType.END_PROCEDURE
				header.startsWith("<SendEMailActivityDefinition") -> ActivityType.SEND_EMAIL
				header.startsWith("<PhaseActivityDefinition") -> ActivityType.SET_PHASE
				header.startsWith("<BusinessRule") -> ActivityType.BUSINESS_RULE
				else -> ActivityType.UNKNOWN
			}
		}

	}

}