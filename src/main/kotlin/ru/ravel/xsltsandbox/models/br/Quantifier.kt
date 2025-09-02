package ru.ravel.xsltsandbox.models.br

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty

data class Quantifier(
	@field:JacksonXmlProperty(isAttribute = true, localName = "Type")
	val type: String,

	@field:JacksonXmlProperty(localName = "VariableDefinition")
	val variableDefinition: VariableDefinition? = null,

	@field:JacksonXmlElementWrapper(useWrapping = false)
	@field:JacksonXmlProperty(localName = "Predicate")
	val predicates: List<Predicate>? = null,

	@field:JacksonXmlElementWrapper(useWrapping = false)
	@field:JacksonXmlProperty(localName = "Quantifier")
	val quantifiers: List<Quantifier>? = null,

	@field:JacksonXmlElementWrapper(useWrapping = false)
	@field:JacksonXmlProperty(localName = "Connective")
	val connectives: List<Connective>? = null,
)