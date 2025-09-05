package ru.ravel.xsltsandbox.models.segmentationtree

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty


data class ReferredDocuments(
	@JacksonXmlElementWrapper(useWrapping = false)
	@JacksonXmlProperty(localName = "Document")
	val documents: List<String>? = null
)