package ru.ravel.xsltsandbox.xslt

import ru.ravel.xsltsandbox.models.SmartOptions
import ru.ravel.xsltsandbox.models.ValueOfWarning

object XsltSelectChecker {
	private fun stripStringLiterals(s: String): String {
		val out = StringBuilder(s.length)
		var i = 0
		while (i < s.length) {
			val ch = s[i]
			if (ch == '\'' || ch == '"') {
				out.append(' ')
				i++
				while (i < s.length) {
					val d = s[i]
					out.append(' ')
					i++
					if (d == ch) break
				}
			} else {
				out.append(ch)
				i++
			}
		}
		return out.toString()
	}


	/**
	 * Проверка «умным» правилом: абсолютен ли путь или «якорен» функцией
	 */
	private fun isOkBySmartRule(expr: String, opt: SmartOptions): Boolean {
		val t = expr.trim()
		if (t.isEmpty()) return true

		val clean = stripStringLiterals(t)

		// Явно абсолютные пути
		if (clean.startsWith("/")) return true         // /... или //...
		// Абсолютный путь как аргумент функции: ищем '/' сразу после начала/скобки/запятой
		if (Regex("(^|[,(])\\s*/").containsMatchIn(clean)) return true

		// root()/..., doc()/..., document()/...
		if (Regex("\\b(?:fn:)?(?:root|doc(?:ument)?)\\s*\\(").containsMatchIn(clean) && clean.contains(")")) {
			return true
		}

		// «Короткий» доступ к атрибуту: @id
		if (opt.allowAttributeShortcut && Regex("^\\s*@[\\w:.-]+\\s*(\\|\\s*@[\\w:.-]+\\s*)*\$").matches(clean)) {
			return true
		}

		// Явные относительные конструкции — считаем нарушением (если не разрешены)
		if (!opt.allowDot && (clean == "." || clean.startsWith("./"))) return false
		if (!opt.allowDotDot && (clean.startsWith(".."))) return false

		// Иначе — относительное выражение
		return false
	}


	/**
	 * Ищет все xsl:value-of/@select, которые НЕ проходят умную проверку.
	 */
	fun collectBadValueOfSelectsSmart(xsltText: String, opt: SmartOptions): List<ValueOfWarning> {
		val selectRe = Regex(
			"""<(?:xsl:)?(?:value-of|copy-of|for-each|apply-templates|sort|attribute|param|with-param)\b[^>]*\bselect\s*=\s*(["'])(.*?)\1""",
			setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
		)
		val testRe = Regex(
			"""<(?:xsl:)?(?:if|when)\b[^>]*\btest\s*=\s*(["'])(.*?)\1""",
			setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
		)
		val useRe = Regex(
			"""<(?:xsl:)?key\b[^>]*\buse\s*=\s*(["'])(.*?)\1""",
			setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
		)
		val valueRe = Regex(
			"""<(?:xsl:)?number\b[^>]*\bvalue\s*=\s*(["'])(.*?)\1""",
			setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
		)
		val matchRe = Regex(
			"""<(?:xsl:)?template\b[^>]*\bmatch\s*=\s*(["'])(.*?)\1""",
			setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
		)
		val useWhenRe = Regex(
			"""\buse-when\s*=\s*(["'])(.*?)\1""",
			RegexOption.IGNORE_CASE
		)
		val patterns = listOf(
			selectRe,   // …/@select
			testRe,     // …/@test
			useRe,      // xsl:key/@use
			valueRe,    // xsl:number/@value
			matchRe,    // xsl:template/@match
			useWhenRe   // …/@use-when (XSLT 3.0)
		)
		val matches = patterns
			.asSequence()
			.flatMap { it.findAll(xsltText) }
			.sortedBy { it.range.first }
		val out = mutableListOf<ValueOfWarning>()
		for (m in matches) {
			val exprGroup = m.groups[2] ?: continue
			val raw = exprGroup.value
			val startOffset = exprGroup.range.first
			if (!isOkBySmartRule(raw, opt)) {
				val before = xsltText.substring(0, startOffset)
				val line = before.count { it == '\n' } + 1
				val lastNl = before.lastIndexOf('\n')
				val col = if (lastNl >= 0) startOffset - lastNl else startOffset + 1
				out.add(ValueOfWarning(exprGroup.range, line, col, raw))
			}
		}
		return out
	}
}