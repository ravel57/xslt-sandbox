package ru.ravel.xsltsandbox.models.br

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty


data class VariableDefinition(
	@field:JacksonXmlProperty(isAttribute = true, localName = "Type")
	val type: String,

	@field:JacksonXmlProperty(isAttribute = true, localName = "Name")
	val name: String,

	@field:JacksonXmlProperty(localName = "XPath")
	val xpath: XPath? = null
)