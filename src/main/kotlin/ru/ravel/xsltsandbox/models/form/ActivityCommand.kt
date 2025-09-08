package ru.ravel.xsltsandbox.models.form

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty


@JsonIgnoreProperties(ignoreUnknown = true)
data class ActivityCommand(
	@JacksonXmlProperty(isAttribute = true, localName = "Class")
	val clazz: String? = null,

	@JacksonXmlProperty(isAttribute = true, localName = "PredefinedCommand")
	val predefinedCommand: String? = null,

	@JacksonXmlProperty(isAttribute = true, localName = "Value")
	val value: String? = null,

	@JacksonXmlProperty(isAttribute = true, localName = "Action")
	val action: String? = null,

	@JacksonXmlProperty(isAttribute = true, localName = "AuthorizationType")
	val authorizationType: String? = null
)