package ru.ravel.xsltsandbox.xml

import kotlin.io.path.name
import ru.ravel.xsltsandbox.models.SegMeta
import ru.ravel.xsltsandbox.models.XPathMeta
import ru.ravel.xsltsandbox.models.bizrule.XPath

object XPathBuilder {
	/**
	 * Формирует абсолютный XPath до узла (или атрибута) под курсором
	 * и возвращает метаданные сегментов для GUI-редактора.
	 *
	 * @param xml    полное содержимое XML-документа
	 * @param offset позиция курсора (selection.start) в этом тексте
	 */
	fun buildXPathWithMeta(xml: String, offset: Int): XPathMeta {
		data class Frame(
			val name: String,
			val attrs: Map<String, String>,
			val indexInSiblings: Int,
			val openStart: Int,
			val openEnd: Int,
			val childCounters: MutableMap<String, Int> = hashMapOf(),
		)

		val openRx = Regex("""<([A-Za-z_][\w:.\-]*)([^>]*?)>""")
		val selfRx = Regex("""<([A-Za-z_][\w:.\-]*)([^>]*?)/>""")
		val closeRx = Regex("""</([A-Za-z_][\w:.\-]*)\s*>""")
		val attrRx = Regex("""([\w:-]+)\s*=\s*(['"])(.*?)\2""")

		fun attrsOf(tag: String) =
			attrRx.findAll(tag).associate { it.groupValues[1] to it.groupValues[3] }

		// Предикат по атрибуту, если курсор внутри головы тега
		var attrPred = ""
		run {
			val ts = xml.lastIndexOf('<', offset).coerceAtLeast(0)
			val te = xml.indexOf('>', ts).let { if (it == -1) ts else it }
			if (offset in ts..te) {
				attrRx.findAll(xml.substring(ts, te + 1)).forEach { a ->
					val s = ts + a.range.first
					val e = ts + a.range.last
					if (offset in s..e) attrPred = "/@${a.groupValues[1]}"
				}
			}
		}

		val stack = mutableListOf<Frame>()
		val rootCounters = hashMapOf<String, Int>()

		var i = 0
		var pathAtOffset: List<Frame>? = null

		fun nextIndexFor(parent: Frame?, name: String): Int {
			val counters = parent?.childCounters ?: rootCounters
			val n = (counters[name] ?: 0) + 1
			counters[name] = n
			return n
		}

		while (i < xml.length && pathAtOffset == null) {
			val lt = xml.indexOf('<', i)
			if (lt < 0) {
				break
			}

			// offset в тексте между тегами — путь это текущий стек
			if (offset in i until lt) {
				pathAtOffset = stack.toList()
				break
			}

			// ---- Самозакрывающийся тег ----
			val mSelf = selfRx.matchAt(xml, lt)
			if (mSelf != null) {
				val name = mSelf.groupValues[1]
				val attrs = attrsOf(mSelf.value)
				val idx = nextIndexFor(stack.lastOrNull(), name)
				val leaf = Frame(name, attrs, idx, mSelf.range.first, mSelf.range.last, hashMapOf())
				if (offset in mSelf.range) {
					pathAtOffset = stack + leaf
					break
				}
				i = mSelf.range.last + 1
				continue
			}

			// ---- Открывающий тег ----
			val mOpen = openRx.matchAt(xml, lt)
			if (mOpen != null) {
				val name = mOpen.groupValues[1]
				val attrs = attrsOf(mOpen.value)
				val idx = nextIndexFor(stack.lastOrNull(), name)
				val fr = Frame(name, attrs, idx, mOpen.range.first, mOpen.range.last)
				stack.add(fr)
				if (offset in mOpen.range) {
					pathAtOffset = stack.toList()
					break
				}
				i = mOpen.range.last + 1
				continue
			}

			// ---- Закрывающий тег ----
			val mClose = closeRx.matchAt(xml, lt)
			if (mClose != null) {
				if (offset in mClose.range && stack.isNotEmpty()) {
					pathAtOffset = stack.toList()
					break
				}
				val closeName = mClose.groupValues[1]
				// Поп до совпадающего имени (защита от «битого» XML)
				for (k in stack.indices.reversed()) {
					if (stack[k].name == closeName) {
						while (stack.size > k) stack.removeAt(stack.lastIndex)
						break
					}
				}
				i = mClose.range.last + 1
				continue
			}

			// Не тег — просто сдвигаемся после '<'
			i = lt + 1
		}

		// Если ничего не зафиксировали, но есть открытый контекст — используем его
		if (pathAtOffset == null && stack.isNotEmpty()) {
			pathAtOffset = stack.toList()
		}

		val path = pathAtOffset ?: return XPathMeta("/", emptyList())
		val segs = path.map { SegMeta(it.name, "[${it.indexInSiblings}]", it.attrs) }

		val xpath = buildString {
			segs.forEach { append('/').append(it.name).append(it.predicate) }
			append(attrPred)
		}
		return XPathMeta(xpath, segs)
	}
}
