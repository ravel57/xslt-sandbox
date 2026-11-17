package ru.ravel.xsltsandbox.connector

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * Резолвит jar'ы JBoss-модуля напрямую по его имени — без обхода (listing) всего дерева `modules`,
 * который на медленной сетевой папке и давал те самые минуты на прогон. Имя модуля
 * (`org.apache.httpcomponents`) однозначно задаёт путь к его `module.xml` по стандартной раскладке
 * (`modules/system/layers/base/<org/apache/httpcomponents>/<slot>/module.xml`, с фолбэком на более
 * старую плоскую раскладку без `system/layers/base`) — остаётся прочитать один небольшой XML-файл.
 *
 * [resolveTransitive] идёт дальше: у каждого `module.xml` есть секция `<dependencies>` — остальные
 * модули, нужные этому. Рекурсивно обходя её, можно одним вызовом собрать все jar'ы транзитивного
 * дерева зависимостей (например, Hibernate тянет dom4j, antlr, javassist и т.п. сам, без ручного
 * угадывания каждого следующего `NoClassDefFoundError`).
 */
object JBossModuleResolver {

	data class ResolvedModule(val moduleXmlPath: String, val jarPaths: List<String>)

	data class ResolvedTree(
		/** Все jar'ы всего транзитивного дерева, без дублей. */
		val jarPaths: List<String>,
		/** Модули (`name:slot`), для которых `module.xml` нашёлся и был разобран. */
		val resolvedModules: List<String>,
		/** Модули (`name:slot`) из чьих-то `<dependencies>`, для которых `module.xml` не нашёлся. */
		val missingModules: List<String>,
	)


	/** @return `null`, если `module.xml` не нашёлся ни по одному из опробованных соглашений раскладки. */
	fun resolve(modulesRoot: String, moduleName: String, slot: String = "main"): ResolvedModule? {
		val moduleXml = findModuleXml(modulesRoot, moduleName, slot) ?: return null
		val doc = parseXml(moduleXml)
		return ResolvedModule(moduleXml.absolutePath, resourceRootJars(doc, moduleXml.parentFile))
	}


	/** Как [resolve], но рекурсивно обходит `<dependencies>` и собирает jar'ы всего дерева. */
	fun resolveTransitive(modulesRoot: String, moduleName: String, slot: String = "main"): ResolvedTree {
		val visited = LinkedHashSet<String>()
		val missing = mutableListOf<String>()
		val jars = LinkedHashSet<String>()

		fun visit(name: String, moduleSlot: String) {
			val key = "$name:$moduleSlot"
			if (key in visited || key in missing) return

			val moduleXml = findModuleXml(modulesRoot, name, moduleSlot)
			if (moduleXml == null) {
				missing.add(key)
				return
			}
			visited.add(key)

			val doc = parseXml(moduleXml)
			jars.addAll(resourceRootJars(doc, moduleXml.parentFile))

			val dependencies = doc.getElementsByTagName("dependencies")
			for (i in 0 until dependencies.length) {
				val depsEl = dependencies.item(i) as Element
				var child = depsEl.firstChild
				while (child != null) {
					if (child.nodeType == Node.ELEMENT_NODE && child.nodeName == "module") {
						val depEl = child as Element
						val depName = depEl.getAttribute("name").takeIf { it.isNotBlank() }
						if (depName != null) {
							val depSlot = depEl.getAttribute("slot").takeIf { it.isNotBlank() } ?: "main"
							visit(depName, depSlot)
						}
					}
					child = child.nextSibling
				}
			}
		}

		visit(moduleName, slot)
		return ResolvedTree(jars.toList(), visited.toList(), missing)
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


	private fun findModuleXml(modulesRoot: String, moduleName: String, slot: String): File? {
		val namePath = moduleName.trim().replace('.', '/').replace('/', File.separatorChar)
		val slotPath = slot.trim().ifEmpty { "main" }
		val candidates = listOf(
			File(modulesRoot, "system${File.separatorChar}layers${File.separatorChar}base${File.separatorChar}$namePath${File.separatorChar}$slotPath${File.separatorChar}module.xml"),
			File(modulesRoot, "$namePath${File.separatorChar}$slotPath${File.separatorChar}module.xml"),
		)
		return candidates.firstOrNull { it.isFile }
	}


	private fun parseXml(file: File): Document {
		val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
		return factory.newDocumentBuilder().parse(file)
	}


	private fun resourceRootJars(doc: Document, moduleDir: File?): List<String> {
		val resourceRoots = doc.getElementsByTagName("resource-root")
		val result = mutableListOf<String>()
		for (i in 0 until resourceRoots.length) {
			val el = resourceRoots.item(i) as Element
			val path = el.getAttribute("path").takeIf { it.isNotBlank() } ?: continue
			result.add(File(moduleDir, path).absolutePath)
		}
		return result
	}
}
