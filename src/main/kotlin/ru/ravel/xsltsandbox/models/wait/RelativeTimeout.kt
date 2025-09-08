package ru.ravel.xsltsandbox.models.wait

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty


@JsonIgnoreProperties(ignoreUnknown = true)
data class RelativeTimeout(
	@JacksonXmlProperty(localName = "CalculationStart")
	val calculationStart: String? = null,

	@JacksonXmlProperty(localName = "TimeSpanFull")
	val timeSpanFull: TimeSpanFull? = null,

	@JacksonXmlProperty(localName = "DataDocumentTimeSpan")
	val dataDocumentTimeSpan: DataDocumentTimeSpan? = null
)