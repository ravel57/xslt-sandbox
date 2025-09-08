package ru.ravel.xsltsandbox.models.form

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty

data class AbsoluteTimeout(
	@JacksonXmlProperty(localName = "DataDocumentDateTime")
	val dataDocumentDateTime: DataDocumentDateTime? = null,
)