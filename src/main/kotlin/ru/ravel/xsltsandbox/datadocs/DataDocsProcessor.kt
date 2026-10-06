package ru.ravel.xsltsandbox.datadocs

import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.ByteArrayInputStream
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory
import kotlin.io.path.name
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node as DomNode
import org.xml.sax.InputSource
import ru.ravel.xsltsandbox.models.ActivityType
import ru.ravel.xsltsandbox.models.ReferredDocument
import ru.ravel.xsltsandbox.models.bizrule.BizRule
import ru.ravel.xsltsandbox.models.datamapping.DataMapping
import ru.ravel.xsltsandbox.models.datasource.DataSource
import ru.ravel.xsltsandbox.models.form.Form
import ru.ravel.xsltsandbox.models.setvalue.SetValueActivity
import ru.ravel.xsltsandbox.models.wait.Wait
import ru.ravel.xsltsandbox.utils.LayoutUtil

object DataDocsProcessor {
	private val xmlMapper = XmlMapper().registerKotlinModule()

	fun getDataDocsInOut(propertyFile: File): List<ReferredDocument> {
		val type = LayoutUtil.getActivityType(propertyFile)
		when (type) {
			ActivityType.BIZ_RULE -> {
				return xmlMapper.readValue(propertyFile, BizRule::class.java).referredDocuments.documents
					.map { ReferredDocument(it.referenceName, it.access) }
			}

			ActivityType.DATA_MAPPING -> {
				return xmlMapper.readValue(propertyFile, DataMapping::class.java).referredDocuments?.items
					?.map { ReferredDocument(it.referenceName, it.access) }
					?: emptyList()
			}

			ActivityType.DATA_SOURCE -> {
				return xmlMapper.readValue(propertyFile, DataSource::class.java).referredDocuments?.referredDocuments
					?.map { ReferredDocument(it.referenceName!!, it.access!!) }
					?: emptyList()
			}

			ActivityType.SET_VALUE -> {
				return xmlMapper.readValue(propertyFile, SetValueActivity::class.java).referredDocuments?.documents
					?.map { ReferredDocument(it.referenceName!!, it.access!!) }
					?: emptyList()
			}

			ActivityType.FORM -> {
				return xmlMapper.readValue(propertyFile, Form::class.java).referredDocuments?.documents
					?.map { ReferredDocument(it.referenceName, it.access) }
					?: emptyList()
			}

			ActivityType.WAIT -> {
				return xmlMapper.readValue(propertyFile, Wait::class.java).referredDocuments?.items
					?.mapNotNull { doc ->
						val name = doc.referenceName ?: return@mapNotNull null
						val access = doc.access ?: return@mapNotNull null
						ReferredDocument(name, access)
					}
					?: emptyList()
			}

			else -> {
				return emptyList()
			}
		}
	}


	fun mergeMockWithDataDocs(mockXml: String, dataDocsXml: String, wanted: List<String>): String {
		val dbf = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
		val builder = dbf.newDocumentBuilder()

		fun parse(xml: String) = builder.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

		val mockDoc = parse(mockXml)
		val docsDoc = parse(dataDocsXml)

		val outDoc = builder.newDocument()
		val outRoot = outDoc.createElement("Data")
		outDoc.appendChild(outRoot)

		// 1) целиком корень Mock.xml как ребёнок <Data>
		val mockRoot = mockDoc.documentElement
		outRoot.appendChild(outDoc.importNode(mockRoot, true))

		// 2) источником датадоков считаем:
		//    - если dataDocsXml уже <Data>...</Data> -> берём ЕГО детей
		//    - иначе берём детей корня как есть
		val docsRoot = docsDoc.documentElement
		val docsContainer: Element = if ((docsRoot.localName ?: docsRoot.nodeName) == "Data") docsRoot else docsRoot

		fun findDirectChildByName(root: Element, name: String): Element? {
			val nodes = root.childNodes
			for (i in 0 until nodes.length) {
				val n = nodes.item(i)
				if (n is Element) {
					val ln = n.localName ?: n.nodeName
					if (ln == name) return n
				}
			}
			return null
		}

		// 3) добавляем нужные датадоки на том же уровне, в порядке wanted
		for (docName in wanted.distinct()) {
			val found = findDirectChildByName(docsContainer, docName)
			outRoot.appendChild(
				outDoc.importNode(found ?: outDoc.createElement(docName), true)
			)
		}

		val tf = TransformerFactory.newInstance().newTransformer().apply {
			setOutputProperty(OutputKeys.INDENT, "yes")
			setOutputProperty(OutputKeys.ENCODING, "UTF-8")
		}
		return StringWriter().use { w ->
			tf.transform(DOMSource(outDoc), StreamResult(w))
			w.toString()
		}
	}


	/**
	 * Собирает <Data> только из указанных датадоков, в порядке wantedDocs.
	 *
	 * @param xmlContent исходный XML как строка
	 * @param wantedDocs список требуемых имен узлов (например ["ApplicationData","DocumentCodebook"])
	 * @param createEmptyIfMissing если true — добавит пустой узел, когда нужный не найден в исходнике
	 */
	fun extractNeededDataDocs(
		xmlContent: String,
		wantedDocs: List<String>,
		createEmptyIfMissing: Boolean = false,
	): String {
		val dbf = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
		val srcDoc = dbf.newDocumentBuilder()
			.parse(ByteArrayInputStream(xmlContent.toByteArray(Charsets.UTF_8)))
		srcDoc.documentElement.normalize()

		val destDoc = dbf.newDocumentBuilder().newDocument()
		val outRoot = destDoc.createElement("Data")
		destDoc.appendChild(outRoot)

		val srcRoot = srcDoc.documentElement
		val children = srcRoot.childNodes

		// сохраняем порядок, заданный списком wantedDocs
		for (wanted in wantedDocs) {
			var found = false
			for (i in 0 until children.length) {
				val n = children.item(i)
				if (n.nodeType == Element.ELEMENT_NODE) {
					val name = (n as Element).localName ?: n.nodeName
					if (name == wanted) {
						outRoot.appendChild(destDoc.importNode(n, true))
						found = true
					}
				}
			}
			if (!found && createEmptyIfMissing) {
				outRoot.appendChild(destDoc.createElement(wanted))
			}
		}

		val tf = TransformerFactory.newInstance().newTransformer().apply {
			setOutputProperty(OutputKeys.INDENT, "yes")
			setOutputProperty(OutputKeys.ENCODING, "UTF-8")
		}
		return StringWriter().use { w ->
			tf.transform(DOMSource(destDoc), StreamResult(w))
			w.toString()
		}
	}


	fun replaceDataDocsInString(
		xmlContent: String,
		newDataDocsXml: String,
		wantedDocs: List<String>,
	): String {
		val dbf = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
		// Исходный документ
		val srcDoc = dbf.newDocumentBuilder()
			.parse(ByteArrayInputStream(xmlContent.toByteArray(Charsets.UTF_8)))
		srcDoc.documentElement.normalize()
		val srcRoot = srcDoc.documentElement
		// Новые датадоки
		val newDoc = dbf.newDocumentBuilder()
			.parse(ByteArrayInputStream(newDataDocsXml.toByteArray(Charsets.UTF_8)))
		newDoc.documentElement.normalize()
		val newRoot = newDoc.documentElement
		// Удаляем в исходнике только те датадоки, которые указаны
		val toRemove = mutableListOf<DomNode>()
		val children = srcRoot.childNodes
		for (i in 0 until children.length) {
			val n = children.item(i)
			if (n.nodeType == DomNode.ELEMENT_NODE && wantedDocs.contains(n.nodeName)) {
				toRemove.add(n)
			}
		}
		toRemove.forEach { srcRoot.removeChild(it) }
		// Из нового документа берём только нужные датадоки и вставляем в исходный
		val newChildren = newRoot.childNodes
		for (i in 0 until newChildren.length) {
			val n = newChildren.item(i)
			if (n.nodeType == DomNode.ELEMENT_NODE && wantedDocs.contains(n.nodeName)) {
				srcRoot.appendChild(srcDoc.importNode(n, true))
			}
		}
		// В строку
		val transformer = TransformerFactory.newInstance().newTransformer().apply {
			setOutputProperty(OutputKeys.INDENT, "yes")
			setOutputProperty(OutputKeys.ENCODING, "UTF-8")
		}
		return StringWriter().use { w ->
			transformer.transform(DOMSource(srcDoc), StreamResult(w))
			w.toString()
		}
	}


	fun applySetValues(propertiesFile: File, dataDocsXml: String): String {
		val xmlMapper = XmlMapper()
		val activity: SetValueActivity = xmlMapper.readValue(propertiesFile, SetValueActivity::class.java)
		// Парсим входной XML-текст
		val dbf = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
		val doc: Document = dbf.newDocumentBuilder()
			.parse(InputSource(StringReader(dataDocsXml)))
		val root = doc.documentElement               // <Data> или иной корень
		val rootName = root.nodeName

		val xPath = XPathFactory.newInstance().newXPath()

		activity.setValues?.items?.forEach { sv ->
			val raw = sv.xPath ?: return@forEach
			val expr = normalizeExpr(raw, rootName)
			// Атрибут?
			val atPos = expr.lastIndexOf("/@")
			if (atPos >= 0) {
				val parentExpr = expr.substring(0, atPos)
				val attrName = expr.substring(atPos + 2) // после "/@"
				// ВАЖНО: вычисляем относительно document (узел-документ) и абсолютным путём:
				val parent = xPath.evaluate(parentExpr, doc, XPathConstants.NODE) as? Element
					?: run {
						// Фолбэк: игнорировать NS — через local-name()
						val local = parentExpr.split('/').filter { it.isNotBlank() }.joinToString("/", prefix = "/") {
							if (it == rootName) it else "*[local-name()='$it']"
						}
						xPath.evaluate(local, doc, XPathConstants.NODE) as? Element
					}
				parent?.setAttribute(attrName, sv.otherwiseValue?.valueConstant ?: return@forEach)
			} else {
				// Узел-элемент
				val node = (xPath.evaluate(expr, doc, XPathConstants.NODE) as? Element)
					?: run {
						val local = expr.split('/').filter { it.isNotBlank() }.joinToString("/", prefix = "/") {
							if (it == rootName) it else "*[local-name()='$it']"
						}
						xPath.evaluate(local, doc, XPathConstants.NODE) as? Element
					}
				if (node != null) {
					node.textContent = sv.otherwiseValue?.valueConstant ?: return@forEach
				}
			}
		}
		// Обратно в строку
		val tf = TransformerFactory.newInstance().newTransformer().apply {
			setOutputProperty(OutputKeys.INDENT, "yes")
			setOutputProperty(OutputKeys.ENCODING, "UTF-8")
		}
		return StringWriter().use { w ->
			tf.transform(DOMSource(doc), StreamResult(w))
			w.toString()
		}
	}


	private fun normalizeExpr(expr: String, rootName: String): String {
		val t = expr.trim().removePrefix("./")
		if (t.startsWith("/")) {
			return t
		}
		return if (t.startsWith("$rootName/")) {
			"/$t"
		} else {
			"/$rootName/$t"
		}
	}
}
