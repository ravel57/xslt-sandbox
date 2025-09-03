package ru.ravel.xsltsandbox.models.bizrule

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty


data class DisplayName(
	@JacksonXmlProperty(localName = "MnemonicId")
	val mnemonicId: String
)