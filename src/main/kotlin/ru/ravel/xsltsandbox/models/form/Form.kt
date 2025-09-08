package ru.ravel.xsltsandbox.models.form

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement

@JsonIgnoreProperties(ignoreUnknown = true)
@JacksonXmlRootElement(localName = "FormActivityDefinition")
data class Form(
	@JacksonXmlProperty(isAttribute = true, localName = "ReferenceName")
	val referenceName: String,

	@JacksonXmlProperty(localName = "Header")
	val header: Header? = null,

	@JacksonXmlProperty(localName = "Commands")
	val commands: Commands? = null,

	@JacksonXmlProperty(localName = "ReferredDocuments")
	val referredDocuments: ReferredDocuments? = null,

	@JacksonXmlElementWrapper(useWrapping = false)
	@JacksonXmlProperty(localName = "ExitTimeout")
	val exitTimeouts: List<ExitTimeout>? = emptyList(),

	@JacksonXmlProperty(localName = "UITemplate")
	val uiTemplate: String? = null,

	@JacksonXmlProperty(localName = "OnlyOneExitTimeOut")
	val onlyOneExitTimeOut: Int? = null
)