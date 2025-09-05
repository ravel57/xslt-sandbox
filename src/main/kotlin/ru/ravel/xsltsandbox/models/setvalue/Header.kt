package ru.ravel.xsltsandbox.models.setvalue

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty


@JsonIgnoreProperties(ignoreUnknown = true)
data class Header(
	@JacksonXmlProperty(localName = "DisplayName")
	val displayName: DisplayName? = null,

	@JacksonXmlProperty(localName = "Description")
	val description: Description? = null,

	@JacksonXmlProperty(localName = "Documentation")
	val documentation: String? = null,

	@JacksonXmlProperty(localName = "SkipTracing")
	val skipTracing: Boolean? = null,

	@JacksonXmlProperty(localName = "AuditBusinessData")
	val auditBusinessData: Boolean? = null
)