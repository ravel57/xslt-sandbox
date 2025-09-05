package ru.ravel.xsltsandbox.models.setvalue

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty


@JsonIgnoreProperties(ignoreUnknown = true)
data class DisplayName(
	@JacksonXmlProperty(localName = "MnemonicId")
	val mnemonicId: String? = null
)