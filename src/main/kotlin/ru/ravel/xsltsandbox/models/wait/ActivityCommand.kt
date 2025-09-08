package ru.ravel.xsltsandbox.models.wait

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty

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
	val authorizationType: String? = null,

	@JacksonXmlProperty(localName = "Name")
	val name: MnemonicWrapper? = null,

	@JacksonXmlProperty(localName = "Description")
	val description: MnemonicWrapper? = null
)