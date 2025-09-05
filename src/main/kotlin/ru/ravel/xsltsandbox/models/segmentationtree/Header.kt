package ru.ravel.xsltsandbox.models.segmentationtree

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
	val skipTracing: Boolean = false,

	@JacksonXmlProperty(localName = "AuditBusinessData")
	val auditBusinessData: Boolean = false
)