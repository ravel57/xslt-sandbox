package ru.ravel.xsltsandbox.models.br

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty

data class Header(
	@JacksonXmlProperty(localName = "DisplayName")
	val displayName: DisplayName,

	@JacksonXmlProperty(localName = "Description")
	val description: Description,

	@JacksonXmlProperty(localName = "Documentation")
	val documentation: String? = null,

	@JacksonXmlProperty(localName = "SkipTracing")
	val skipTracing: Boolean,

	@JacksonXmlProperty(localName = "AuditBusinessData")
	val auditBusinessData: Boolean
)