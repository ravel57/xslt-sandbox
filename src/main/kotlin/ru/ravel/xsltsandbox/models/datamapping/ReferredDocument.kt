package ru.ravel.xsltsandbox.models.datamapping

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty

data class ReferredDocument(
	@JacksonXmlProperty(isAttribute = true, localName = "ReferenceName")
	@JsonProperty("ReferenceName")
	val referenceName: String,

	@JacksonXmlProperty(isAttribute = true, localName = "Access")
	@JsonProperty("Access")
	val access: String
)