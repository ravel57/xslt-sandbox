package ru.ravel.xsltsandbox.br

import com.fasterxml.jackson.databind.ObjectMapper
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import ru.ravel.xsltsandbox.models.bizrule.ChildKind
import ru.ravel.xsltsandbox.models.bizrule.Connective
import ru.ravel.xsltsandbox.models.bizrule.Quantifier
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Разбор XML бизнес-правила ([Quantifier] или [Connective]).
 *
 * Jackson раскладывает дочерние элементы связки по спискам (`predicates`, `quantifiers`, `connectives`) и теряет
 * их общий порядок, а у `IfThenElse` он решает всё: первый потомок — условие, второй — «то», третий — «иначе».
 * Поэтому порядок восстанавливается отдельным проходом по тому же XML.
 */
object RuleParser {

	fun parse(mapper: ObjectMapper, xml: String): Any {
		val root: Any = if (xml.trim().startsWith("<Quantifier")) {
			mapper.readValue(xml, Quantifier::class.java)
		} else {
			mapper.readValue(xml, Connective::class.java)
		}
		runCatching {
			val doc = DocumentBuilderFactory.newInstance().apply {
				setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
			}.newDocumentBuilder().parse(InputSource(StringReader(xml)))
			restoreOrder(root, doc.documentElement)
		}
		return root
	}

	private fun children(element: Element): List<Element> =
		(0 until element.childNodes.length).map { element.childNodes.item(it) }
			.filter { it.nodeType == Node.ELEMENT_NODE }
			.map { it as Element }

	private fun restoreOrder(node: Any, element: Element) {
		var quantifier = 0
		var connective = 0
		val order = mutableListOf<ChildKind>()
		for (child in children(element)) {
			when (child.tagName) {
				"Predicate" -> order += ChildKind.PREDICATE
				"Quantifier" -> {
					order += ChildKind.QUANTIFIER
					val model = when (node) {
						is Connective -> node.quantifiers
						is Quantifier -> node.quantifiers
						else -> null
					}
					model?.getOrNull(quantifier)?.let { restoreOrder(it, child) }
					quantifier++
				}

				"Connective" -> {
					order += ChildKind.CONNECTIVE
					val model = when (node) {
						is Connective -> node.connectives
						is Quantifier -> node.connectives
						else -> null
					}
					model?.getOrNull(connective)?.let { restoreOrder(it, child) }
					connective++
				}
			}
		}
		if (node is Connective) node.childOrder = order
	}
}
