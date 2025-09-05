package ru.ravel.xsltsandbox.models.setvalue

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty


@JsonIgnoreProperties(ignoreUnknown = true)
data class SetValues(
	@JacksonXmlElementWrapper(useWrapping = false)
	@JacksonXmlProperty(localName = "SetValue")
	val items: List<SetValue> = emptyList()
)