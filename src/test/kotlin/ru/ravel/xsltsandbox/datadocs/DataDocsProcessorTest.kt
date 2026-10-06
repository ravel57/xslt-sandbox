package ru.ravel.xsltsandbox.datadocs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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

	@Test
	fun `missing property file gives no documents instead of failing`() {
		assertEquals(emptyList<Any>(), DataDocsProcessor.getDataDocsInOut(dir.resolve("BusinessRules/Properties.xml").toFile()))
	}

	private val allDocs = "<Data><A><x/></A><B><y/></B><C><z/></C></Data>"

	@Test
	fun `ordinary activity gets only its input documents`() {
		val file = bizRule("""<ReferredDocuments><ReferredDocument ReferenceName="B" Access="Input"/></ReferredDocuments>""")

		val input = DataDocsProcessor.inputDocuments(file, allDocs)

		assertTrue("<B>" in input && "<A>" !in input && "<C>" !in input, input)
	}

	@Test
	fun `segmentation tree and its business rules get all documents`() {
		val rule = dir.resolve("R1.xml")
		Files.writeString(rule, "<BusinessRule><BusinessRuleID>R1</BusinessRuleID><ReferredDocuments><Document>A</Document></ReferredDocuments><XmlRule>x</XmlRule></BusinessRule>")
		val tree = dir.resolve("ST.xml")
		Files.writeString(tree, "<SegmentationTreeActivityDefinition ReferenceName=\"ST\"><Rules/></SegmentationTreeActivityDefinition>")

		assertEquals(allDocs, DataDocsProcessor.inputDocuments(rule.toFile(), allDocs))
		assertEquals(allDocs, DataDocsProcessor.inputDocuments(tree.toFile(), allDocs))
	}

	private val current = "<Data><A><v>old</v></A><B><v>keep</v></B></Data>"

	@Test
	fun `output replaces only the documents it returns`() {
		val merged = DataDocsProcessor.replaceDataDocsInString(current, "<Data><A><v>new</v></A></Data>", listOf("A"))

		assertTrue("<v>new</v>" in merged && "old" !in merged && "keep" in merged, merged)
	}

	@Test
	fun `document missing from the output is kept instead of dropped`() {
		val merged = DataDocsProcessor.replaceDataDocsInString(current, "<Data><Other/></Data>", listOf("A"))

		assertEquals(listOf("A", "B"), DataDocsProcessor.topLevelNameList(merged))
		assertTrue("old" in merged, merged)
	}

	@Test
	fun `output that is the document itself without Data wrapper replaces it`() {
		val merged = DataDocsProcessor.replaceDataDocsInString(current, "<A><v>new</v></A>", listOf("A"))

		assertTrue("<v>new</v>" in merged && "old" !in merged, merged)
		assertEquals(listOf("B", "A"), DataDocsProcessor.topLevelNameList(merged))
	}
}
