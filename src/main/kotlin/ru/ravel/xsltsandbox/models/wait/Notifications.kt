package ru.ravel.xsltsandbox.models.wait

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty

data class Notifications(
	@JacksonXmlElementWrapper(useWrapping = false)
	@JacksonXmlProperty(localName = "Notification")
	val items: List<Notification>? = emptyList()
)