package ru.ravel.xsltsandbox.models.br

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty

data class Predicate(
	@field:JacksonXmlProperty(isAttribute = true, localName = "Type")
	val type: String,

	@field:JacksonXmlProperty(localName = "Variable")
	val variable: Variable? = null,

	@field:JacksonXmlProperty(localName = "Constant")
	val constant: Constant? = null
)