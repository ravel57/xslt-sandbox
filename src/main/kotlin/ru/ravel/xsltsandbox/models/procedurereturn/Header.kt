package ru.ravel.xsltsandbox.models.procedurereturn

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty

data class Header(
	@JacksonXmlProperty(localName = "DisplayName")
	val displayName: MnemonicWrapper? = null,

	@JacksonXmlProperty(localName = "Description")
	val description: MnemonicWrapper? = null,

	@JacksonXmlProperty(localName = "Documentation")
	val documentation: String? = null,

	@JacksonXmlProperty(localName = "PhaseID")
	val phaseID: String? = null,

	@JacksonXmlProperty(localName = "SkipTracing")
	val skipTracing: Boolean? = null,

	@JacksonXmlProperty(localName = "AuditBusinessData")
	val auditBusinessData: Boolean? = null
)