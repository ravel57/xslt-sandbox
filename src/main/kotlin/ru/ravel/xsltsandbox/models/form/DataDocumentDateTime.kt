package ru.ravel.xsltsandbox.models.form

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty

data class DataDocumentDateTime(
	@JacksonXmlProperty(localName = "DocumentID")
	val documentId: String? = null,

	@JacksonXmlProperty(localName = "XPath")
	val xPath: String? = null,

	@JacksonXmlProperty(localName = "Alias")
	val alias: String? = null
)