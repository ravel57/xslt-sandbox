package ru.ravel.xsltsandbox.models.br

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlText


@JsonIgnoreProperties(ignoreUnknown = true)
class XPath {

	@JacksonXmlText
	var value: String? = null

	@JacksonXmlProperty(isAttribute = true, localName = "ParentName")
	var parentName: String? = null

	companion object {
		@JvmStatic
		@JsonCreator
		fun fromString(text: String): XPath {
			val x = XPath()
			x.value = text
			return x
		}
	}
}