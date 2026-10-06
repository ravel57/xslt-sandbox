package ru.ravel.xsltsandbox.models.bizrule

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty


data class ReferredDocuments(
	@JacksonXmlElementWrapper(useWrapping = false)
	@JacksonXmlProperty(localName = "ReferredDocument")
	// пустой <ReferredDocuments/> приходит как null: Jackson строит такой объект без аргументов
	val documents: List<ReferredDocument>? = null
)