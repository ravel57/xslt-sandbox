package ru.ravel.xsltsandbox.connector

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Резолвит jar'ы JBoss-модуля напрямую по его имени — без обхода (listing) всего дерева `modules`,
 * который на медленной сетевой папке и давал те самые минуты на прогон. Имя модуля
 * (`org.apache.httpcomponents`) однозначно задаёт путь к его `module.xml` по стандартной раскладке
 * (`modules/system/layers/base/<org/apache/httpcomponents>/<slot>/module.xml`, с фолбэком на более
 * старую плоскую раскладку без `system/layers/base`) — остаётся прочитать один небольшой XML-файл.
 *
 * Не резолвит зависимости модуля (`<dependencies><module name="..."/></dependencies>`) — только его
 * собственные `<resource-root>`; при необходимости зависимые модули добавляются тем же способом ещё раз.
 */
object JBossModuleResolver {

	data class ResolvedModule(val moduleXmlPath: String, val jarPaths: List<String>)


	/** @return `null`, если `module.xml` не нашёлся ни по одному из опробованных соглашений раскладки. */
	fun resolve(modulesRoot: String, moduleName: String, slot: String = "main"): ResolvedModule? {
		val namePath = moduleName.trim().replace('.', '/').replace('/', File.separatorChar)
		val slotPath = slot.trim().ifEmpty { "main" }
		val candidates = listOf(
			File(modulesRoot, "system${File.separatorChar}layers${File.separatorChar}base${File.separatorChar}$namePath${File.separatorChar}$slotPath${File.separatorChar}module.xml"),
			File(modulesRoot, "$namePath${File.separatorChar}$slotPath${File.separatorChar}module.xml"),
		)
		val moduleXml = candidates.firstOrNull { it.isFile } ?: return null
		return ResolvedModule(moduleXml.absolutePath, parseResourceRoots(moduleXml))
	}


	/** Пути, по которым искали `module.xml` — для сообщения пользователю, если ни один не подошёл. */
	fun candidatePaths(modulesRoot: String, moduleName: String, slot: String = "main"): List<String> {
		val namePath = moduleName.trim().replace('.', '/').replace('/', File.separatorChar)
		val slotPath = slot.trim().ifEmpty { "main" }
		return listOf(
			File(modulesRoot, "system${File.separatorChar}layers${File.separatorChar}base${File.separatorChar}$namePath${File.separatorChar}$slotPath${File.separatorChar}module.xml").path,
			File(modulesRoot, "$namePath${File.separatorChar}$slotPath${File.separatorChar}module.xml").path,
		)
	}


	private fun parseResourceRoots(moduleXml: File): List<String> {
		val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
		val doc = factory.newDocumentBuilder().parse(moduleXml)
		val resourceRoots = doc.getElementsByTagName("resource-root")
		val dir = moduleXml.parentFile
		val result = mutableListOf<String>()
		for (i in 0 until resourceRoots.length) {
			val el = resourceRoots.item(i) as Element
			val path = el.getAttribute("path").takeIf { it.isNotBlank() } ?: continue
			result.add(File(dir, path).absolutePath)
		}
		return result
	}
}
