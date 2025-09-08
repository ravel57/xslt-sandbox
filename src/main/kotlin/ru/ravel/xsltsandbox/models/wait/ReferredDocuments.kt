package ru.ravel.xsltsandbox.models.wait

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty

data class ReferredDocuments(
	@JacksonXmlElementWrapper(useWrapping = false)
	@JacksonXmlProperty(localName = "ReferredDocument")
	val items: List<ReferredDocument>? = emptyList()
)