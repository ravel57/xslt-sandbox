package ru.ravel.xsltsandbox.editor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random
import java.util.regex.Pattern

class XmlMarkupScannerTest {

	/** Прежняя регулярка (итерация на каждый символ тега) — эталон поведения на небольших текстах. */
	private val old: Pattern = Pattern.compile(
		"<!--[\\s\\S]*?-->|<!\\[CDATA\\[[\\s\\S]*?]]>|<\\?[\\s\\S]*?\\?>|<![^>]*>" +
			"|<(/?)([A-Za-z_][\\w:.-]*)(?:\"[^\"]*\"|'[^']*'|[^>\"'])*?(/?)>",
	)

	private data class Token(val start: Int, val end: Int, val name: String?, val closing: Boolean, val selfClosing: Boolean)

	private fun viaRegex(text: String): List<Token> {
		val m = old.matcher(text)
		val result = mutableListOf<Token>()
		while (m.find()) {
			val name = m.group(2)
			result += Token(m.start(), m.end(), name, name != null && m.group(1).isNotEmpty(), name != null && m.group(3).isNotEmpty())
		}
		return result
	}

	private fun viaScanner(text: String): List<Token> {
		val result = mutableListOf<Token>()
		XmlMarkupScanner.scan(text) { s, e, name, closing, self -> result += Token(s, e, name, closing, self) }
		return result
	}

	@Test
	fun `scanner finds exactly what the old regex found`() {
		val samples = listOf(
			"<a><b x=\"1\" y='2'/><c>text</c></a>",
			"<a href=\"/p/q\" b=/c>x</a><br/><br />",
			"<!-- <a> --><![CDATA[ <b/> ]]><?pi x?><!DOCTYPE d><r a=\"x>y\"/>",
			"<ApplicationData ApplicationID=\"1\" Process_Key=\"CRDS\"><Info a='/'/></ApplicationData>",
			"<x:y z:w=\"1\"\n   q=\"2\"\n/>",
			"<!---->< a><a/ ><a b=\"unterminated><c>",
		)
		for (sample in samples) assertEquals(viaRegex(sample), viaScanner(sample), sample)
	}

	@Test
	fun `scanner agrees with the old regex on random markup-like text`() {
		val pieces = listOf("<", ">", "/", "\"", "'", "a", "b1", " ", "=", "!", "-", "--", "?", "[", "]", "CDATA", "\n", "<!--", "-->", "<![CDATA[", "]]>", "<?", "?>", "</", "/>", ":", ".", "_")
		val random = Random(20261007)
		repeat(30_000) {
			val text = buildString { repeat(1 + random.nextInt(18)) { append(pieces[random.nextInt(pieces.size)]) } }
			assertEquals(viaRegex(text), viaScanner(text), "text=«$text»")
		}
	}

	@Test
	fun `a tag with hundreds of thousands of attributes does not overflow the stack`() {
		val attributes = (1..200_000).joinToString(" ") { "attr$it=\"v$it\"" }
		val text = "<Data><Big $attributes>body</Big><Self $attributes/></Data>"

		var failure: Throwable? = null
		var tags: List<Token> = emptyList()
		val thread = Thread(null, {
			try {
				tags = viaScanner(text)
			} catch (t: Throwable) {
				failure = t
			}
		}, "scan", 256 * 1024)
		thread.start()
		thread.join()

		assertTrue(failure == null, "разбор длинного тега упал: $failure")
		assertEquals(listOf("Data", "Big", "Big", "Self", "Data"), tags.map { it.name })
		assertTrue(tags[3].selfClosing && !tags[1].selfClosing)
	}

	@Test
	fun `open tag name is taken only from a whole opening tag`() {
		assertEquals("Info", XmlMarkupScanner.openTagName("<Info a=\"/p\" b='1'>"))
		assertNull(XmlMarkupScanner.openTagName("<Info a=\"1\"/>"))
		assertNull(XmlMarkupScanner.openTagName("</Info>"))
		assertNull(XmlMarkupScanner.openTagName("<Info>tail"))
		assertNull(XmlMarkupScanner.openTagName("text <Info>"))
		val long = "<Big " + (1..100_000).joinToString(" ") { "a$it=\"/v$it\"" } + ">"
		assertEquals("Big", XmlMarkupScanner.openTagName(long))
	}
}
