package ru.ravel.xsltsandbox.models.br

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty

data class ReferredDocument(
	@JacksonXmlProperty(isAttribute = true, localName = "ReferenceName")
	val referenceName: String,

	@JacksonXmlProperty(isAttribute = true, localName = "Access")
	val access: String
)