package ru.ravel.xsltsandbox.models.wait

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty

data class ReferredDocument(
	@JacksonXmlProperty(isAttribute = true, localName = "ReferenceName")
	val referenceName: String? = null,

	@JacksonXmlProperty(isAttribute = true, localName = "Access")
	val access: String? = null
)