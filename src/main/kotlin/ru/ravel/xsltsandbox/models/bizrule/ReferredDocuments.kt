package ru.ravel.xsltsandbox.models.bizrule

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty


data class ReferredDocuments(
	@JacksonXmlElementWrapper(useWrapping = false)
	@JacksonXmlProperty(localName = "ReferredDocument")
	val documents: List<ReferredDocument>
)