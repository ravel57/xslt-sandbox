package ru.ravel.xsltsandbox.transform


internal object XsltCompat {

	private val COMMENTS = Regex("<!--[\\s\\S]*?-->")
	private val ROOT_VERSION = Regex("(<(?:[\\w.-]+:)?(?:stylesheet|transform)\\b[^>]*?\\bversion\\s*=\\s*)([\"'])([^\"']*)\\2")


	fun asBackwardsCompatible(xslt: String): String? {
		val masked = COMMENTS.replace(xslt) { m -> m.value.map { if (it == '\n' || it == '\r') it else ' ' }.joinToString("") }
		val match = ROOT_VERSION.find(masked) ?: return null
		val declared = match.groupValues[3].trim().toDoubleOrNull() ?: return null
		if (declared < 2.0) return null
		val range = match.groups[3]!!.range
		return xslt.replaceRange(range, "1.0")
	}
}
