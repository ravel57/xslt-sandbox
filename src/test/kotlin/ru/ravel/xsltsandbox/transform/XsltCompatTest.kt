package ru.ravel.xsltsandbox.transform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.StringReader
import java.io.StringWriter
import javax.xml.transform.TransformerException
import javax.xml.transform.TransformerFactory
import javax.xml.transform.stream.StreamResult
import javax.xml.transform.stream.StreamSource

class XsltCompatTest {

	private val strictSheet = """<?xml version="1.0" encoding="utf-16"?>
		|<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform" version="2.0">
		|  <xsl:output method="text"/>
		|  <xsl:template match="/Data">
		|    <xsl:value-of select="max((1, 2)) + 1*(@a!='') + 1*(@b!='')"/>
		|  </xsl:template>
		|</xsl:stylesheet>""".trimMargin()

	private fun factory(): TransformerFactory =
		TransformerFactory.newInstance("net.sf.saxon.TransformerFactoryImpl", XsltCompatTest::class.java.classLoader)

	private fun run(sheet: String, xml: String): String {
		val out = StringWriter()
		factory().newTemplates(StreamSource(StringReader(sheet))).newTransformer()
			.transform(StreamSource(StringReader(xml)), StreamResult(out))
		return out.toString()
	}

	@Test
	fun `version 2 is switched to 1 and everything else stays in place`() {
		val compat = XsltCompat.asBackwardsCompatible(strictSheet)!!

		assertEquals(strictSheet.length, compat.length)
		assertEquals(strictSheet.replaceFirst("version=\"2.0\"", "version=\"1.0\""), compat)
	}

	@Test
	fun `version 1 or no version needs no switch`() {
		assertNull(XsltCompat.asBackwardsCompatible("<xsl:stylesheet xmlns:xsl='http://www.w3.org/1999/XSL/Transform' version='1.0'/>"))
		assertNull(XsltCompat.asBackwardsCompatible("<root/>"))
		assertNull(XsltCompat.asBackwardsCompatible("<xsl:stylesheet xmlns:xsl='http://www.w3.org/1999/XSL/Transform'/>"))
	}

	@Test
	fun `version in a commented out stylesheet or other elements is not touched`() {
		val text = "<?xml version=\"1.0\"?><!-- <xsl:stylesheet version=\"2.0\"> --><xsl:transform xmlns:xsl='x' version='3.0'><a version=\"2.0\"/></xsl:transform>"

		assertEquals(text.replace("version='3.0'", "version='1.0'"), XsltCompat.asBackwardsCompatible(text))
	}

	@Test
	fun `crif style boolean arithmetic fails in strict mode and works in the compat mode`() {
		val xml = "<Data a='x' b=''/>"

		assertThrows(TransformerException::class.java) { run(strictSheet, xml) }
		// max() из XPath 2.0 остаётся доступным, булевы значения считаются как 1/0: 2 + 1 + 0
		assertEquals("3", run(XsltCompat.asBackwardsCompatible(strictSheet)!!, xml))
		assertTrue(XsltCompat.asBackwardsCompatible(strictSheet) != strictSheet)
	}
}
