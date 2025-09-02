package ru.ravel.xsltsandbox.models.br

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement

@JacksonXmlRootElement(localName = "Connective")
data class Connective(
	@field:JacksonXmlProperty(isAttribute = true, localName = "Type")
	val type: String,

	@field:JacksonXmlElementWrapper(useWrapping = false)
	@field:JacksonXmlProperty(localName = "Connective")
	val connectives: List<Connective>? = null,

	@field:JacksonXmlElementWrapper(useWrapping = false)
	@field:JacksonXmlProperty(localName = "Quantifier")
	val quantifiers: List<Quantifier>? = null,

	@field:JacksonXmlElementWrapper(useWrapping = false)
	@field:JacksonXmlProperty(localName = "VariableDefinition")
	val variableDefinitions: List<VariableDefinition>? = null,

	@field:JacksonXmlElementWrapper(useWrapping = false)
	@field:JacksonXmlProperty(localName = "Predicate")
	val predicates: List<Predicate>? = null,
)