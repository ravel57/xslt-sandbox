package ru.ravel.xsltsandbox.models.segmentationtree

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement


@JsonIgnoreProperties(ignoreUnknown = true)
@JacksonXmlRootElement(localName = "BusinessRule")
data class BusinessRule(
	@JacksonXmlProperty(localName = "BusinessRuleID")
	val businessRuleID: String?,

	@JacksonXmlProperty(localName = "Description")
	val description: String? = null,

	@JacksonXmlProperty(localName = "ReferredDocuments")
	val referredDocuments: ReferredDocuments? = null,

	@JacksonXmlProperty(localName = "XmlRule")
	val xmlRule: String? = null
)