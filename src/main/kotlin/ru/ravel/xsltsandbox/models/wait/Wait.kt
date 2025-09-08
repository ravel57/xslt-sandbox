package ru.ravel.xsltsandbox.models.wait

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement


@JacksonXmlRootElement(localName = "WaitActivityDefinition")
@JsonIgnoreProperties(ignoreUnknown = true)
data class Wait(
	@JacksonXmlProperty(isAttribute = true, localName = "ReferenceName")
	val referenceName: String,

	@JacksonXmlProperty(localName = "Header")
	val header: Header? = null,

	@JacksonXmlProperty(localName = "Commands")
	val commands: Commands? = null,

	@JacksonXmlProperty(localName = "ReferredDocuments")
	val referredDocuments: ReferredDocuments? = null,

	@JacksonXmlProperty(localName = "WaitActivityDefinition")
	val details: WaitDetails? = null
)