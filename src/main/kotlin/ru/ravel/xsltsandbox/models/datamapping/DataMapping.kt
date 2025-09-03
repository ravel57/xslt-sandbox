package ru.ravel.xsltsandbox.models.datamapping

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement


@JacksonXmlRootElement(localName = "MappingActivityDefinition")
@JsonIgnoreProperties(ignoreUnknown = true)
data class DataMapping @JsonCreator constructor(
	@JacksonXmlProperty(isAttribute = true, localName = "ReferenceName")
	@JsonProperty("ReferenceName")
	val referenceName: String,

	@JacksonXmlProperty(localName = "Header")
	@JsonProperty("Header")
	val header: Header? = null,

	@JacksonXmlProperty(localName = "ReferredDocuments")
	@JsonProperty("ReferredDocuments")
	val referredDocuments: ReferredDocuments? = null,

	@JacksonXmlProperty(localName = "XsltDataMapping")
	@JsonProperty("XsltDataMapping")
	val xsltDataMapping: XsltDataMapping? = null
)