package ru.ravel.xsltsandbox.models.segmentationtree

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty

data class Description(
	@JacksonXmlProperty(localName = "MnemonicId")
	val mnemonicId: String? = null
)