package ru.ravel.xsltsandbox.br

import java.io.StringReader
import javax.xml.transform.stream.StreamSource
import kotlin.io.path.exists
import kotlin.io.path.name
import net.sf.saxon.s9api.Processor
import net.sf.saxon.s9api.XPathCompiler
import net.sf.saxon.s9api.XdmNode
import ru.ravel.xsltsandbox.models.bizrule.ChildKind
import ru.ravel.xsltsandbox.models.bizrule.Connective
import ru.ravel.xsltsandbox.models.bizrule.Predicate
import ru.ravel.xsltsandbox.models.bizrule.Quantifier

object BizRuleEvaluator {
	/** Predicate (в т.ч. True/False/TextEquality/TextInequality) */
	fun evaluateBR(xml: String, root: Connective): Boolean {
		val proc = Processor(false)
		val compiler = proc.newXPathCompiler()
		val doc: XdmNode = proc.newDocumentBuilder().build(StreamSource(StringReader(xml)))
		return evalConnective(root, compiler, doc)
	}


	fun kindOf(type: String?): String =
		type?.lowercase()?.split(", ")?.first()?.substringAfterLast(".") ?: ""


	/** Имя типа заглавными словами: `Crif...Logic.IfThenElse, ...` → `IF THEN ELSE` */
	fun displayName(type: String?): String =
		type?.split(", ")?.first()?.substringAfterLast(".").orEmpty()
			.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")
			.uppercase()


	private fun xpathToValues(expr: String, compiler: XPathCompiler, doc: XdmNode): List<String> {
		val selector = compiler.compile(expr).load()
		selector.contextItem = doc
		val result = selector.evaluate()
		return result.map { it.stringValue }
	}


	/** Операнды предиката: переменная и вторая переменная либо константа. null — предикат задан неполно. */
	private fun operands(p: Predicate, vmap: Map<String, String>): Pair<String, String>? {
		val vars = p.variables.map { it.value }
		return when {
			vars.size >= 2 -> (vmap[vars[0]] ?: "") to (vmap[vars[1]] ?: "")
			vars.size == 1 && p.constant != null -> (vmap[vars[0]] ?: "") to (p.constant?.value ?: "")
			else -> null
		}
	}

	private fun number(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()

	/** Числовое сравнение; если число не разобралось, итог — [whenUnparsable] (для «не равно» — true, как раньше). */
	private fun numeric(
		p: Predicate,
		vmap: Map<String, String>,
		whenUnparsable: Boolean = false,
		test: (Double, Double) -> Boolean,
	): Boolean {
		val (left, right) = operands(p, vmap) ?: return false
		val a = number(left)
		val b = number(right)
		return if (a == null || b == null) whenUnparsable else test(a, b)
	}

	/** Сравнение строк (ординально, без пробелов по краям); [test] получает знак compareTo. */
	private fun textOrder(p: Predicate, vmap: Map<String, String>, test: (Int) -> Boolean): Boolean {
		val (left, right) = operands(p, vmap) ?: return false
		return test(left.trim().compareTo(right.trim()))
	}

	private fun strings(p: Predicate, vmap: Map<String, String>, test: (String, String) -> Boolean): Boolean {
		val (left, right) = operands(p, vmap) ?: return false
		return test(left, right)
	}


	private fun evalPredicate(
		p: Predicate,
		compiler: XPathCompiler,
		doc: XdmNode,
		vmap: Map<String, String>,
	): Boolean {
		return when (kindOf(p.type)) {
			"true" -> true
			"false" -> false

			"textequality" -> {
				val vars = p.variables.map { it.value }
				val constVal = p.constant?.value ?: ""
				when {
					vars.size == 1 && constVal.isNotEmpty() -> (vmap[vars[0]] ?: "") == constVal
					vars.size == 2 -> (vmap[vars[0]] ?: "") == (vmap[vars[1]] ?: "")
					else -> false
				}
			}

			"textinequality" -> {
				val vars = p.variables.map { it.value }
				val constVal = p.constant?.value ?: ""
				when (vars.size) {
					1 -> (vmap[vars[0]] ?: "").trim() != constVal.trim()
					2 -> (vmap[vars[0]] ?: "").trim() != (vmap[vars[1]] ?: "").trim()
					else -> false
				}
			}

			"textlessthan" -> textOrder(p, vmap) { it < 0 }
			"textlessthanorequal" -> textOrder(p, vmap) { it <= 0 }
			"textgreaterthan" -> textOrder(p, vmap) { it > 0 }
			"textgreaterthanorequal" -> textOrder(p, vmap) { it >= 0 }

			"numberequality" -> numeric(p, vmap) { a, b -> a == b }
			"numberinequality" -> numeric(p, vmap, whenUnparsable = true) { a, b -> a != b }
			"numbergreaterthan", "greaterthan" -> numeric(p, vmap) { a, b -> a > b }
			"numbergreaterthanorequal" -> numeric(p, vmap) { a, b -> a >= b }
			"numberlessthan", "lessthan" -> numeric(p, vmap) { a, b -> a < b }
			"numberlessthanorequal" -> numeric(p, vmap) { a, b -> a <= b }

			"stringstartswith" -> strings(p, vmap) { a, b -> a.startsWith(b) }
			"stringendswith" -> strings(p, vmap) { a, b -> a.endsWith(b) }
			"stringcontains" -> strings(p, vmap) { a, b -> a.contains(b) }

			else -> false
		}
	}


	fun evalQuantifier(
		q: Quantifier,
		compiler: XPathCompiler,
		doc: XdmNode,
		parentVars: Map<String, String> = emptyMap(),
	): Boolean {
		val vd = q.variableDefinition ?: return false
		val xpath = vd.xpath?.value ?: return false

		val values = xpathToValues(xpath, compiler, doc)
		val preds = q.predicates.orEmpty()
		val quants = q.quantifiers.orEmpty()
		val conns = q.connectives.orEmpty()

		fun okFor(value: String): Boolean {
			val vmap = parentVars + mapOf(vd.name to value)
			val predsOk = preds.all { evalPredicate(it, compiler, doc, vmap) }
			val quantsOk = quants.all { evalQuantifier(it, compiler, doc, vmap) }
			val connsOk = conns.all { evalConnective(it, compiler, doc, vmap) }
			return predsOk && quantsOk && connsOk
		}

		return when (kindOf(q.type)) {
			"some", "exists", "the" -> {
				values.any(::okFor)
			}

			"all", "forall", "each" -> {
				values.isNotEmpty() && values.all(::okFor)
			}

			"no" -> {
				values.none(::okFor)
			}

			"exactlyone" -> {
				values.count(::okFor) == 1
			}

			"morethanone" -> {
				values.count(::okFor) > 1
			}

			else -> false
		}
	}


	/**
	 * `IfThenElse`: первый дочерний элемент — условие, второй — «то», третий — «иначе» (порядок — как в документе).
	 * Без «иначе» связка истинна при ложном условии (импликация).
	 */
	private fun evalIfThenElse(
		c: Connective,
		compiler: XPathCompiler,
		doc: XdmNode,
		vmap: Map<String, String>,
	): Boolean {
		val preds = c.predicates.orEmpty()
		val quants = c.quantifiers.orEmpty()
		val conns = c.connectives.orEmpty()
		val order = c.childOrder.takeIf { it.size == preds.size + quants.size + conns.size }
			?: (preds.map { ChildKind.PREDICATE } + quants.map { ChildKind.QUANTIFIER } + conns.map { ChildKind.CONNECTIVE })
		var p = 0
		var q = 0
		var k = 0
		val parts: List<() -> Boolean> = order.map { kind ->
			when (kind) {
				ChildKind.PREDICATE -> preds[p++].let { x -> { evalPredicate(x, compiler, doc, vmap) } }
				ChildKind.QUANTIFIER -> quants[q++].let { x -> { evalQuantifier(x, compiler, doc, vmap) } }
				ChildKind.CONNECTIVE -> conns[k++].let { x -> { evalConnective(x, compiler, doc, vmap) } }
			}
		}
		return when (parts.size) {
			0 -> true
			1 -> parts[0]()
			2 -> if (parts[0]()) parts[1]() else true
			else -> if (parts[0]()) parts[1]() else parts[2]()
		}
	}


	private fun evalConnective(
		c: Connective,
		compiler: XPathCompiler,
		doc: XdmNode,
		vmap: Map<String, String> = emptyMap(),
	): Boolean {
		if (kindOf(c.type) == "ifthenelse") return evalIfThenElse(c, compiler, doc, vmap)
		val preds = c.predicates.orEmpty()
		val quants = c.quantifiers.orEmpty()
		val conns = c.connectives.orEmpty()

		val predsAll = preds.all { evalPredicate(it, compiler, doc, vmap) }
		val predsAny = preds.any { evalPredicate(it, compiler, doc, vmap) }
		val quantsAll = quants.all { evalQuantifier(it, compiler, doc, vmap) }
		val quantsAny = quants.any { evalQuantifier(it, compiler, doc, vmap) }
		val connsAll = conns.all { evalConnective(it, compiler, doc, vmap) }
		val connsAny = conns.any { evalConnective(it, compiler, doc, vmap) }

		return when (kindOf(c.type)) {
			"and" -> {
				predsAll && quantsAll && connsAll
			}

			"or" -> {
				predsAny || quantsAny || connsAny
			}

			"not", "no" -> {
				!(predsAny || quantsAny || connsAny)
			}

			"some", "exists",
			"the",
				-> {
				predsAny || quantsAny || connsAny
			}

			"all", "forall" -> {
				predsAll && quantsAll && connsAll
			}

			else -> {
				predsAll
			}
		}
	}


	/**
	 * Пояснение вычисления правила для журнала: значения переменных (первые несколько) и результат каждого
	 * предиката, а также неподдерживаемые типы предикатов и переменные связки, которые вычислитель не связывает.
	 * [root] — [Connective] или [Quantifier]; строк не больше [maxLines].
	 */
	fun explain(xml: String, root: Any, maxLines: Int = 60): List<String> {
		val lines = mutableListOf<String>()
		try {
			val proc = Processor(false)
			val doc = proc.newDocumentBuilder().build(StreamSource(StringReader(xml)))
			val explainer = Explainer(proc.newXPathCompiler(), doc, lines, maxLines)
			when (root) {
				is Connective -> explainer.connective(root, emptyMap(), 0)
				is Quantifier -> explainer.quantifier(root, emptyMap(), 0)
			}
		} catch (e: Exception) {
			lines += "не удалось пояснить: ${e.message}"
		}
		return lines
	}


	private val SUPPORTED_PREDICATES = setOf(
		"true", "false", "textequality", "textinequality", "textlessthan", "textlessthanorequal", "textgreaterthan",
		"textgreaterthanorequal", "numberequality", "numberinequality", "numbergreaterthan", "numbergreaterthanorequal",
		"numberlessthan", "numberlessthanorequal", "greaterthan", "lessthan",
		"stringstartswith", "stringendswith", "stringcontains",
	)


	private class Explainer(
		private val compiler: XPathCompiler,
		private val doc: XdmNode,
		private val lines: MutableList<String>,
		private val maxLines: Int,
	) {
		private fun add(depth: Int, text: String) {
			if (lines.size < maxLines) lines += "  ".repeat(depth) + text
			else if (lines.size == maxLines) lines += "…"
		}

		private fun short(value: String) = value.trim().let { if (it.length > 60) it.take(60) + "…" else it }

		fun connective(c: Connective, vmap: Map<String, String>, depth: Int) {
			add(depth, "связка ${displayName(c.type)}")
			c.variableDefinitions.orEmpty().forEach {
				add(depth + 1, "VariableDefinition ${it.name} в связке — вычислитель их не связывает, предикаты увидят пустое значение")
			}
			predicates(c.predicates.orEmpty(), vmap, depth + 1)
			c.quantifiers.orEmpty().forEach { quantifier(it, vmap, depth + 1) }
			c.connectives.orEmpty().forEach { connective(it, vmap, depth + 1) }
		}

		fun quantifier(q: Quantifier, parent: Map<String, String>, depth: Int) {
			val vd = q.variableDefinition
			val xpath = vd?.xpath?.value
			if (vd == null || xpath == null) {
				add(depth, "квантор ${displayName(q.type)}: нет определения переменной")
				return
			}
			val values = xpathToValues(xpath, compiler, doc)
			add(depth, "квантор ${displayName(q.type)} ${vd.name} = $xpath → ${values.size} знач.: ${values.take(3).map(::short)}")
			for (value in values.take(3)) {
				val vmap = parent + mapOf(vd.name to value)
				predicates(q.predicates.orEmpty(), vmap, depth + 1)
				q.quantifiers.orEmpty().forEach { quantifier(it, vmap, depth + 1) }
				q.connectives.orEmpty().forEach { connective(it, vmap, depth + 1) }
			}
		}

		private fun predicates(preds: List<Predicate>, vmap: Map<String, String>, depth: Int) {
			for (p in preds) {
				val vars = p.variables.map { it.value }
				val values = vars.joinToString { "$it='${short(vmap[it] ?: "<не определена>")}'" }
				val constant = p.constant?.value?.let { " const='${short(it)}'" }.orEmpty()
				val note = if (kindOf(p.type) in SUPPORTED_PREDICATES) "" else "  [тип не поддерживается — всегда false]"
				add(depth, "предикат ${p.type.substringBefore(',')}: $values$constant → ${evalPredicate(p, compiler, doc, vmap)}$note")
			}
		}
	}
}
