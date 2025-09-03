package ru.ravel.xsltsandbox.models.bizrule

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlText

@JsonIgnoreProperties(ignoreUnknown = true)
class Constant {
	@JacksonXmlProperty(isAttribute = true, localName = "Type")
	var type: String? = null

	@JacksonXmlText
	var value: String? = null
}