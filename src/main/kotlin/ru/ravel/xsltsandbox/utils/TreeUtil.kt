package ru.ravel.xsltsandbox.utils

import javafx.scene.control.TreeItem
import javafx.scene.layout.HBox
import javafx.scene.text.Text
import ru.ravel.xsltsandbox.br.BizRuleEvaluator.displayName
import ru.ravel.xsltsandbox.br.BizRuleEvaluator.kindOf
import ru.ravel.xsltsandbox.models.bizrule.Connective
import ru.ravel.xsltsandbox.models.bizrule.Predicate
import ru.ravel.xsltsandbox.models.bizrule.Quantifier
import ru.ravel.xsltsandbox.models.bizrule.VariableDefinition

object TreeUtil {
	/** Фрагмент подписи узла BR: текст и CSS-класс для цвета (`null` — без раскраски) */
	private data class Seg(val text: String, val style: String? = null)

	// ───────────────── BR → TreeView ─────────────────
	fun toTreeItem(node: Any): TreeItem<String> = when (node) {
		is Connective -> {
			item(listOf(Seg(displayName(node.type), "br-connective"))).apply {
				node.predicates?.forEach { children.add(toTreeItem(it)) }
				node.quantifiers?.forEach { children.add(toTreeItem(it)) }
				node.connectives?.forEach { children.add(toTreeItem(it)) }
			}
		}

		is Quantifier -> {
			val vd = node.variableDefinition
			val head = Seg(displayName(node.type), "br-quantifier")
			item(if (vd == null) listOf(head) else listOf(head, Seg(" "), *definition(vd))).apply {
				node.predicates?.forEach { children.add(toTreeItem(it)) }
				node.quantifiers?.forEach { children.add(toTreeItem(it)) }
				node.connectives?.forEach { children.add(toTreeItem(it)) }
			}
		}

		is Predicate -> item(predicateSegs(node))

		is VariableDefinition -> item(definition(node).toList())

		else -> TreeItem(node.toString())
	}


	/** Узел дерева: значение — обычный текст (для копирования), графика — тот же текст с цветами */
	private fun item(segs: List<Seg>): TreeItem<String> =
		TreeItem(segs.joinToString("") { it.text }).apply {
			// HBox вместо TextFlow: длинные условия не переносятся, а прокручиваются
			graphic = HBox(*segs.map { seg ->
				Text(seg.text).apply { styleClass.addAll("br-text", *listOfNotNull(seg.style).toTypedArray()) }
			}.toTypedArray())
		}


	/** `имя = xpath` */
	private fun definition(vd: VariableDefinition): Array<Seg> {
		val xpath = vd.xpath?.value ?: return arrayOf(Seg(vd.name, "br-variable"))
		return arrayOf(Seg(vd.name, "br-variable"), Seg(" "), Seg("=", "br-operator"), Seg(" "), Seg(xpath, "br-xpath"))
	}


	/** Знаки сравнения по окончанию имени типа предиката (`textequality`, `numbergreaterthan` и т.п.) */
	private val OPERATORS = listOf(
		"inequality" to "!=",
		"equality" to "==",
		"greaterthanorequal" to ">=",
		"lessthanorequal" to "<=",
		"greaterthan" to ">",
		"lessthan" to "<",
	)


	/**
	 * Читаемая запись условия: `левое == правое`, где операнды — переменные или константа
	 * (текстовые константы в кавычках). Для предикатов без сравнения (`true`, `false`) — их имя.
	 */
	fun describePredicate(p: Predicate): String = predicateSegs(p).joinToString("") { it.text }


	private fun predicateSegs(p: Predicate): List<Seg> {
		val kind = kindOf(p.type)
		val operands = p.variables.map { Seg(it.value, "br-variable") }.toMutableList()
		p.constant?.let { c ->
			val v = c.value ?: ""
			operands += if (kind.startsWith("number")) Seg(v, "br-number") else Seg("\"$v\"", "br-string")
		}
		if (operands.isEmpty()) {
			return listOf(Seg(kind, "br-keyword"))
		}
		val op = OPERATORS.firstOrNull { kind.endsWith(it.first) }?.second
		val sep = Seg(" ")
		return when {
			operands.size == 2 -> {
				listOf(operands[0], sep, Seg(op ?: kind, "br-operator"), sep, operands[1])
			}

			op != null -> {
				listOf(Seg("$kind: ", "br-keyword")) + operands.flatMapIndexed { i, o ->
					if (i == 0) {
						listOf(o)
					} else {
						listOf(sep, Seg(op, "br-operator"), sep, o)
					}
				}
			}

			else -> {
				listOf(
					Seg(kind, "br-keyword"), Seg("(")
				) + operands.flatMapIndexed { i, o ->
					if (i == 0) {
						listOf(o)
					} else {
						listOf(Seg(", "), o)
					} + Seg(")")
				}
			}
		}
	}


	fun expandAll(item: TreeItem<*>) {
		item.isExpanded = true
		item.children.forEach { expandAll(it) }
	}
}