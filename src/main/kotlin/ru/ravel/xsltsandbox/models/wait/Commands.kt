package ru.ravel.xsltsandbox.models.wait

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty

data class Commands(
	@JacksonXmlElementWrapper(useWrapping = false)
	@JacksonXmlProperty(localName = "ActivityCommand")
	val items: List<ActivityCommand>? = emptyList()
)