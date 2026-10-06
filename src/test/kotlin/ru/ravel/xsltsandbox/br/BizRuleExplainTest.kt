package ru.ravel.xsltsandbox.br

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.ravel.xsltsandbox.models.bizrule.Connective
import ru.ravel.xsltsandbox.models.bizrule.Constant
import ru.ravel.xsltsandbox.models.bizrule.Predicate
import ru.ravel.xsltsandbox.models.bizrule.Quantifier
import ru.ravel.xsltsandbox.models.bizrule.Variable
import ru.ravel.xsltsandbox.models.bizrule.VariableDefinition
import ru.ravel.xsltsandbox.models.bizrule.XPath

class BizRuleExplainTest {

	private val xml = "<Data><ApplicationData><ChannelType>CF</ChannelType></ApplicationData></Data>"

	private fun variable(name: String) = Variable().apply { value = name }
	private fun constant(text: String) = Constant().apply { value = text }

	private fun channelIs(expected: String) = Quantifier(
		type = "Crif.Logic.The, Crif",
		variableDefinition = VariableDefinition("Crif.Text, Crif", "Channel_Type", XPath.fromString("/Data/ApplicationData/ChannelType")),
		predicates = listOf(Predicate("Crif.Logic.TextEquality, Crif", listOf(variable("Channel_Type")), constant(expected))),
	)

	@Test
	fun `explanation shows the variable value and the predicate result`() {
		val lines = BizRuleEvaluator.explain(xml, channelIs("OC"))

		assertTrue(lines.any { "Channel_Type" in it && "1 знач." in it && "[CF]" in it }, lines.toString())
		assertTrue(lines.any { "TextEquality" in it && "Channel_Type='CF'" in it && "const='OC'" in it && it.endsWith("false") }, lines.toString())
		assertFalse(BizRuleEvaluator.evaluateBR(xml, Connective("Crif.Logic.And, Crif", quantifiers = listOf(channelIs("OC")))))
		assertTrue(BizRuleEvaluator.evaluateBR(xml, Connective("Crif.Logic.And, Crif", quantifiers = listOf(channelIs("CF")))))
	}

	@Test
	fun `explanation points at unsupported predicate types`() {
		val rule = Connective(
			"Crif.Logic.And, Crif",
			predicates = listOf(Predicate("Crif.Logic.TextContains, Crif", listOf(variable("X")), constant("a"))),
		)

		val lines = BizRuleEvaluator.explain(xml, rule)

		assertTrue(lines.any { "TextContains" in it && "не поддерживается" in it }, lines.toString())
	}

	@Test
	fun `explanation warns about variable definitions inside a connective`() {
		val rule = Connective(
			"Crif.Logic.And, Crif",
			variableDefinitions = listOf(VariableDefinition("Crif.Text, Crif", "V", XPath.fromString("/Data"))),
		)

		assertTrue(BizRuleEvaluator.explain(xml, rule).any { "VariableDefinition V" in it })
		assertEquals(emptyList<String>(), BizRuleEvaluator.explain(xml, "не правило"))
	}
}
