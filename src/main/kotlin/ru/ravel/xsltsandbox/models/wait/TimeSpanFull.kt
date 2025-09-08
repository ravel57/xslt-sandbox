package ru.ravel.xsltsandbox.models.wait

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty

data class TimeSpanFull(
	@JacksonXmlProperty(localName = "Days")
	val days: Int? = null,

	@JacksonXmlProperty(localName = "Time")
	val time: TimeDef? = null
)