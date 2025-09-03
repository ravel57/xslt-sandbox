package ru.ravel.xsltsandbox.models.datamapping

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty

data class XsltDataMapping(
	@JacksonXmlProperty(localName = "Mode")
	val mode: String
)