package ru.ravel.xsltsandbox.xslt


object XsltErrorLocator {
	/** Старт и конец (исключительно) строки lineIdx (0-based) в тексте */
	private fun lineBounds(text: String, lineIdx: Int): IntRange {
		var start = 0
		repeat(lineIdx) {
			val nl = text.indexOf('\n', start)
			if (nl < 0) return (text.length..text.length)
			start = nl + 1
		}
		val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
		return start until end
	}


	/** Смещает в абсолютный offset; colIdx может быть 0 при loc.columnNumber=0 */
	private fun offsetFor(text: String, lineIdx: Int, colIdx: Int): Int {
		val lb = lineBounds(text, lineIdx)
		val base = lb.first
		// защищаемся от выхода за границы строки
		return (base + colIdx).coerceIn(lb.first, lb.last)
	}


	/** Возвращает диапазон символов, который стоит подчеркнуть для ошибки Saxon */
	fun computeXsltErrorRange(xslt: String, lineNo: Int, colNo: Int): IntRange {
		val lineIdx = (lineNo - 1).coerceAtLeast(0)
		val colIdx = (colNo - 1).coerceAtLeast(0)

		// Если колонка известна (>0) — хотя бы 1 символ там
		if (colNo > 0) {
			val pos = offsetFor(xslt, lineIdx, colIdx)
			return pos..(pos + 1)
		}

		// col==0 → эвристика: подсветить значение match|select|test|use-when в этой строке
		val lb = lineBounds(xslt, lineIdx)
		val line = xslt.substring(lb.first, lb.last)
		val m = Regex("""\b(match|select|test|use-when)\s*=\s*(["'])(.*?)\2""")
			.find(line)
		if (m != null) {
			val valRangeInLine = m.groups[3]!!.range // только содержимое в кавычках
			val start = lb.first + valRangeInLine.first
			val endEx = lb.first + valRangeInLine.last + 1
			return start until endEx
		}

		// запасной вариант — первый символ тега в строке
		val lt = line.indexOf('<')
		val pos = if (lt >= 0) lb.first + lt else lb.first
		return pos..(pos + 1)
	}
}