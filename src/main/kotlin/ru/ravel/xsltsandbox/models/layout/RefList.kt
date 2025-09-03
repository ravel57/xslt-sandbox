package ru.ravel.xsltsandbox.models.layout

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty


data class RefList(
	@JacksonXmlElementWrapper(useWrapping = false)
	@JacksonXmlProperty(localName = "string")
	val refs: List<String>? = emptyList()
)