package ru.ravel.xsltsandbox.models.setvalue

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty


@JsonIgnoreProperties(ignoreUnknown = true)
data class SetValue(
	@JacksonXmlProperty(localName = "XPath")
	val xPath: String? = null,

	@JacksonXmlProperty(localName = "OtherwiseValue")
	val otherwiseValue: OtherwiseValue? = null,

	@JacksonXmlProperty(localName = "RemoveAttributeWhenEmpty")
	val removeAttributeWhenEmpty: Boolean? = null
)