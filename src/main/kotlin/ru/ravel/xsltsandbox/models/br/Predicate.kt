package ru.ravel.xsltsandbox.models.br

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty

data class Predicate(
	@field:JacksonXmlProperty(isAttribute = true, localName = "Type")
	val type: String,

	@field:JacksonXmlElementWrapper(useWrapping = false)
	@field:JacksonXmlProperty(localName = "Variable")
	val variables: List<Variable> = emptyList(),

	@field:JacksonXmlProperty(localName = "Constant")
	val constant: Constant? = null
)