package ru.ravel.xsltsandbox.models.form

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty

data class DisplayName(
	@JacksonXmlProperty(localName = "MnemonicId")
	val mnemonicId: String? = null
)