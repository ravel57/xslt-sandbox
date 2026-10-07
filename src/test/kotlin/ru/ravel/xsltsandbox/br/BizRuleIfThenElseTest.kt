package ru.ravel.xsltsandbox.br

import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.ravel.xsltsandbox.models.bizrule.ChildKind
import ru.ravel.xsltsandbox.models.bizrule.Connective

class BizRuleIfThenElseTest {

	private val mapper = XmlMapper().registerKotlinModule()
	private val logic = "Crif.CreditFlow.XmlRules.Logic"

	private fun rule(xml: String): Connective = RuleParser.parse(mapper, xml) as Connective

	private fun evaluate(rule: String, data: String): Boolean = BizRuleEvaluator.evaluateBR(data, rule(rule))

	private fun quantifier(type: String, name: String, xpath: String, predicate: String = "") =
		"<Quantifier Type='$logic.$type'><VariableDefinition Type='$logic.XPath.XmlTextVariable' Name='$name'><XPath>$xpath</XPath></VariableDefinition>$predicate</Quantifier>"

	/** Правило из реального процесса: «есть родительская заявка? то другие активные заявки : любые активные заявки». */
	private val duplicates = "<Connective Type='$logic.IfThenElse'>" +
		quantifier("The", "ApplicationID", "/Data/DocumentInput/Application/ParentAppl/ApplicationID") +
		quantifier(
			"Some", "TechStatus",
			"/Data/SubjectData/Subject/ApplicationsList/Applications[@ProductName='CRDS'][@TechStatus!='End' and @TechStatus!='Cancel']" +
				"[@ApplicationID!=/Data/DocumentInput/Application/ParentAppl/ApplicationID]",
		) +
		quantifier(
			"Some", "TechStatus",
			"/Data/SubjectData/Subject/ApplicationsList/Applications[@ProductName='CRDS'][@TechStatus!='End' and @TechStatus!='Cancel']",
		) +
		"</Connective>"

	private fun applications(vararg apps: Pair<String, String>) =
		apps.joinToString("") { (id, status) -> "<Applications ProductName='CRDS' ApplicationID='$id' TechStatus='$status'/>" }

	private fun data(parent: String?, vararg apps: Pair<String, String>) =
		"<Data><DocumentInput><Application>" +
			(parent?.let { "<ParentAppl><ApplicationID>$it</ApplicationID></ParentAppl>" } ?: "") +
			"</Application></DocumentInput><SubjectData><Subject><ApplicationsList>${applications(*apps)}</ApplicationsList></Subject></SubjectData></Data>"

	@Test
	fun `with a parent application only other active applications count`() {
		// активна только сама родительская заявка: ветка «то» ищет другие — их нет, итог ложь (раньше был true)
		assertFalse(evaluate(duplicates, data("P1", "P1" to "Work")))
		assertTrue(evaluate(duplicates, data("P1", "P1" to "Work", "X2" to "Work")))
		assertFalse(evaluate(duplicates, data("P1", "P1" to "Work", "X2" to "End", "X3" to "Cancel")))
	}

	@Test
	fun `without a parent application any active application counts`() {
		assertTrue(evaluate(duplicates, data(null, "A1" to "Work")))
		assertFalse(evaluate(duplicates, data(null, "A1" to "End")))
		assertFalse(evaluate(duplicates, data(null)))
	}

	@Test
	fun `child order is restored in the document order`() {
		val parsed = rule(
			"<Connective Type='$logic.IfThenElse'>" +
				quantifier("No", "S", "/Data/S") +
				"<Predicate Type='$logic.True' />" +
				quantifier("The", "I", "/Data/I") +
				"</Connective>",
		)

		assertEquals(listOf(ChildKind.QUANTIFIER, ChildKind.PREDICATE, ChildKind.QUANTIFIER), parsed.childOrder)
	}

	@Test
	fun `predicates in condition then and else positions are not regrouped`() {
		fun ite(vararg predicates: String) = "<Connective Type='$logic.IfThenElse'>" +
			predicates.joinToString("") { "<Predicate Type='$logic.$it' />" } + "</Connective>"

		assertFalse(evaluate(ite("True", "False", "True"), "<Data/>")) // условие истинно → «то» = False
		assertTrue(evaluate(ite("False", "False", "True"), "<Data/>")) // условие ложно → «иначе» = True
		assertTrue(evaluate(ite("False", "False"), "<Data/>")) // без «иначе» ложное условие даёт true
	}

	/** Предикат [type] над переменной L и второй переменной R либо константой [constant] (вложен в кванторы). */
	private fun check(type: String, left: String, right: String?, constant: String? = null): Boolean {
		val variables = "<Variable>L</Variable>" + (if (right != null) "<Variable>R</Variable>" else "")
		val const = constant?.let { "<Constant Type='System.String'>$it</Constant>" }.orEmpty()
		val predicate = "<Predicate Type='$logic.$type'>$variables$const</Predicate>"
		val inner = if (right != null) quantifier("The", "R", "/Data/R", predicate) else predicate
		val rule = "<Connective Type='$logic.And'>" + quantifier("The", "L", "/Data/L", inner) + "</Connective>"
		val data = "<Data><L>$left</L>${right?.let { "<R>$it</R>" } ?: ""}</Data>"
		return evaluate(rule, data)
	}

	@Test
	fun `number predicates work with two variables and with a constant`() {
		assertTrue(check("NumberEquality", "3", "3.0"))
		assertFalse(check("NumberEquality", "3", "4"))
		assertTrue(check("NumberGreaterThanOrEqual", "5", "5"))
		assertTrue(check("NumberGreaterThanOrEqual", "6", "5"))
		assertFalse(check("NumberGreaterThanOrEqual", "4", "5"))
		assertTrue(check("NumberGreaterThan", "10", null, "9"))
		assertTrue(check("NumberLessThanOrEqual", "2", null, "2"))
		assertTrue(check("NumberInequality", "2", "3"))
	}

	@Test
	fun `text and string predicates from real processes are supported`() {
		assertTrue(check("TextLessThan", "a", "b"))
		assertFalse(check("TextLessThan", "b", "a"))
		assertTrue(check("TextLessThanOrEqual", "2", null, "2"))
		assertTrue(check("StringStartsWith", "A2A-77", null, "A2A"))
		assertFalse(check("StringStartsWith", "B2A-77", null, "A2A"))
	}
}
