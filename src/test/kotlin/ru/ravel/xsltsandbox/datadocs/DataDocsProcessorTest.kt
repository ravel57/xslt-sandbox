package ru.ravel.xsltsandbox.datadocs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DataDocsProcessorTest {

	@TempDir
	lateinit var dir: Path

	private fun bizRule(referredDocuments: String): java.io.File {
		val file = dir.resolve("Properties.xml")
		Files.writeString(
			file,
			"""<BizRuleActivityDefinition ReferenceName="BR_1">
			|  <Header>
			|    <DisplayName><MnemonicId>BR_1</MnemonicId></DisplayName>
			|    <Description><MnemonicId>d</MnemonicId></Description>
			|    <SkipTracing>false</SkipTracing>
			|    <AuditBusinessData>false</AuditBusinessData>
			|  </Header>
			|  $referredDocuments
			|  <XmlRule>true()</XmlRule>
			|</BizRuleActivityDefinition>""".trimMargin(),
		)
		return file.toFile()
	}

	@Test
	fun `business rule without referred documents has an empty list`() {
		assertEquals(emptyList<Any>(), DataDocsProcessor.getDataDocsInOut(bizRule("<ReferredDocuments/>")))
		assertEquals(emptyList<Any>(), DataDocsProcessor.getDataDocsInOut(bizRule("<ReferredDocuments></ReferredDocuments>")))
		assertEquals(emptyList<Any>(), DataDocsProcessor.getDataDocsInOut(bizRule("")))
	}

	@Test
	fun `business rule lists its referred documents`() {
		val docs = DataDocsProcessor.getDataDocsInOut(
			bizRule("""<ReferredDocuments><ReferredDocument ReferenceName="A" Access="Input"/><ReferredDocument ReferenceName="B" Access="InOut"/></ReferredDocuments>"""),
		)
		assertEquals(listOf("A" to "Input", "B" to "InOut"), docs.map { it.referenceName to it.access })
	}
}
