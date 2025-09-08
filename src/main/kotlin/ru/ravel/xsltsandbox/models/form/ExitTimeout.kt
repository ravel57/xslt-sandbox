package ru.ravel.xsltsandbox.models.form

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty


@JsonIgnoreProperties(ignoreUnknown = true)
data class ExitTimeout(
	@JacksonXmlProperty(localName = "ExitName")
	val exitName: String? = null,

	@JacksonXmlProperty(localName = "ExitBusinessRules")
	val exitBusinessRules: String? = null,

	@JacksonXmlProperty(localName = "AbsoluteTimeout")
	val absoluteTimeout: AbsoluteTimeout? = null,
)