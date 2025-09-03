package ru.ravel.xsltsandbox.models.procedure

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty


@JsonIgnoreProperties(ignoreUnknown = true)
data class MnemonicContainer(
	@JacksonXmlProperty(localName = "MnemonicId")
	val mnemonicId: String? = null
)