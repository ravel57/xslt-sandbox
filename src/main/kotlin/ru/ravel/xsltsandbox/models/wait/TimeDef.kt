package ru.ravel.xsltsandbox.models.wait

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty

data class TimeDef(
	@JacksonXmlProperty(localName = "Hour")
	val hour: Int? = null,

	@JacksonXmlProperty(localName = "Minutes")
	val minutes: Int? = null
)