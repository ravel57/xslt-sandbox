package ru.ravel.xsltsandbox.editor


internal object XmlMarkupScanner {


	fun scan(text: String, visit: (start: Int, end: Int, name: String?, closing: Boolean, selfClosing: Boolean) -> Unit) {
		var i = text.indexOf('<')
		while (i >= 0) {
			val special = specialEnd(text, i)
			if (special > 0) {
				visit(i, special, null, false, false)
				i = text.indexOf('<', special)
				continue
			}
			val end = tagAt(text, i, visit)
			i = text.indexOf('<', if (end > 0) end else i + 1)
		}
	}


	fun openTagName(fragment: String): String? {
		if (!fragment.startsWith("<")) return null
		var name: String? = null
		var selfClosing = false
		val end = tagAt(fragment, 0) { _, _, tagName, closing, self ->
			name = tagName.takeUnless { closing }
			selfClosing = self
		}
		return name.takeIf { end == fragment.length && !selfClosing }
	}


	private fun specialEnd(text: String, i: Int): Int {
		if (text.startsWith("<!--", i)) {
			text
				.indexOf("-->", i + 4)
				.let {
					if (it >= 0) {
						return it + 3
					}
				}
		}
		if (text.startsWith("<![CDATA[", i)) {
			text
				.indexOf("]]>", i + 9)
				.let {
					if (it >= 0) {
						return it + 3
					}
				}
		}
		if (text.startsWith("<?", i)) {
			text
				.indexOf("?>", i + 2)
				.let {
					if (it >= 0) {
						return it + 2
					}
				}
		}
		if (text.startsWith("<!", i)) {
			text
				.indexOf('>', i + 2)
				.let {
					if (it >= 0) {
						return it + 1
					}
				}
		}
		return -1
	}


	private fun tagAt(
		text: String,
		i: Int,
		visit: (start: Int, end: Int, name: String?, closing: Boolean, selfClosing: Boolean) -> Unit,
	): Int {
		val n = text.length
		var p = i + 1
		val closing = p < n && text[p] == '/'
		if (closing) p++
		if (p >= n || !isNameStart(text[p])) return -1
		val nameStart = p
		p++
		while (p < n && isNameChar(text[p])) p++
		val name = text.substring(nameStart, p)

		while (p < n) {
			when (text[p]) {
				'>' -> {
					visit(i, p + 1, name, closing, false)
					return p + 1
				}

				'"', '\'' -> {
					val close = text.indexOf(text[p], p + 1)
					if (close < 0) return -1
					p = close + 1
				}

				'/' -> {
					if (p + 1 < n && text[p + 1] == '>') {
						visit(i, p + 2, name, closing, true)
						return p + 2
					}
					p++
				}

				else -> p++
			}
		}
		return -1
	}

	private fun isNameStart(c: Char) = c in 'A'..'Z' || c in 'a'..'z' || c == '_'

	private fun isNameChar(c: Char) = isNameStart(c) || c in '0'..'9' || c == ':' || c == '.' || c == '-'
}
