package ru.ravel.xsltsandbox.models.layout

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty


data class DiagramEndPoint(
	@JacksonXmlProperty(isAttribute = true, localName = "ElementRef")
	val elementRef: String? = null,
	@JacksonXmlProperty(isAttribute = true, localName = "ExitPointRef")
	val exitPointRef: String? = null
)