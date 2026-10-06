package ru.ravel.xsltsandbox.br

import java.io.StringReader
import javax.xml.transform.stream.StreamSource
import kotlin.io.path.exists
import kotlin.io.path.name
import net.sf.saxon.s9api.Processor
import net.sf.saxon.s9api.XPathCompiler
import net.sf.saxon.s9api.XdmNode
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


	private fun evalPredicate(
		p: Predicate,
		compiler: XPathCompiler,
		doc: XdmNode,
		vmap: Map<String, String>,
	): Boolean {
		return when (kindOf(p.type)) {
			"true" -> {
				true
			}

			"false" -> {
				false
			}

			"textequality" -> {
				val vars = p.variables.map { it.value }
				val constVal = p.constant?.value ?: ""

				when {
					vars.size == 1 && constVal.isNotEmpty() -> {
						(vmap[vars[0]] ?: "") == constVal
					}

					vars.size == 2 -> {
						(vmap[vars[0]] ?: "") == (vmap[vars[1]] ?: "")
					}

					else -> {
						false
					}
				}
			}

			"textinequality" -> {
				val vars = p.variables.map { it.value }
				val constVal = p.constant?.value ?: ""
				when (vars.size) {
					1 -> {
						(vmap[vars[0]] ?: "").trim() != constVal.trim()
					}

					2 -> {
						(vmap[vars[0]] ?: "").trim() != (vmap[vars[1]] ?: "").trim()
					}

					else -> {
						false
					}
				}
			}

			"numberequality" -> {
				val vars = p.variables.map { it.value }
				val constVal = p.constant?.value?.toDoubleOrNull()
				if (vars.size == 1 && constVal != null) {
					(vmap[vars[0]] ?: "").toDoubleOrNull() == constVal
				} else {
					false
				}
			}

			"numberinequality" -> {
				val vars = p.variables.map { it.value }
				val constVal = p.constant?.value?.toDoubleOrNull()
				if (vars.size == 1 && constVal != null) {
					(vmap[vars[0]] ?: "").toDoubleOrNull() != constVal
				} else {
					false
				}
			}

			"greaterthan" -> {
				val vars = p.variables.map { it.value }
				val constVal = p.constant?.value?.toDoubleOrNull()
				if (vars.size == 1 && constVal != null) {
					(vmap[vars[0]] ?: "").toDoubleOrNull()?.let { it > constVal } ?: false
				} else {
					false
				}
			}

			"lessthan" -> {
				val vars = p.variables.map { it.value }
				val constVal = p.constant?.value?.toDoubleOrNull()
				if (vars.size == 1 && constVal != null) {
					(vmap[vars[0]] ?: "").toDoubleOrNull()?.let { it < constVal } ?: false
				} else {
					false
				}
			}

			"textgreaterthanorequal" -> {
				val vars = p.variables.map { it.value }
				val constVal = p.constant?.value ?: ""

				fun cmp(a: String, b: String): Int =
					a.trim().compareTo(b.trim())

				when (vars.size) {
					1 -> cmp(vmap[vars[0]] ?: "", constVal) >= 0
					2 -> cmp(vmap[vars[0]] ?: "", vmap[vars[1]] ?: "") >= 0
					else -> false
				}
			}

			"numbergreaterthan" -> {
				val vars = p.variables.map { it.value }
				val constVal = p.constant?.value ?: ""

				fun toNum(s: String): Double? =
					s.trim().replace(',', '.').toDoubleOrNull()

				fun gt(a: String, b: String): Boolean {
					val na = toNum(a)
					val nb = toNum(b)
					return na != null && nb != null && na > nb
				}

				when (vars.size) {
					1 -> gt(vmap[vars[0]] ?: "", constVal)
					2 -> gt(vmap[vars[0]] ?: "", vmap[vars[1]] ?: "")
					else -> false
				}
			}

			"numbergreaterthan" -> {
				val vars = p.variables.map { it.value }

				fun toNum(s: String): Double? =
					s.trim().replace(',', '.').toDoubleOrNull()

				if (vars.size < 2) return false

				val left = toNum(vmap[vars[0]] ?: "") ?: return false
				val right = toNum(vmap[vars[1]] ?: "") ?: return false

				left > right
			}

			else -> {
				false
			}
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


	private fun evalConnective(
		c: Connective,
		compiler: XPathCompiler,
		doc: XdmNode,
		vmap: Map<String, String> = emptyMap(),
	): Boolean {
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
		"true", "false", "textequality", "textinequality", "numberequality", "numberinequality",
		"greaterthan", "lessthan", "textgreaterthanorequal", "numbergreaterthan",
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
