package ru.ravel.xsltsandbox.models.datasource

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement
import ru.ravel.xsltsandbox.models.datasource.Header
import ru.ravel.xsltsandbox.models.datasource.ReferredDocuments


@JsonIgnoreProperties(ignoreUnknown = true)
@JacksonXmlRootElement(localName = "DataSourceActivityDefinition")
data class DataSource(
	@JacksonXmlProperty(isAttribute = true, localName = "ReferenceName")
	val referenceName: String,

	@JacksonXmlProperty(localName = "Header")
	val header: Header? = null,

	@JacksonXmlProperty(localName = "ReferredDocuments")
	val referredDocuments: ReferredDocuments? = null,

	@JacksonXmlProperty(localName = "ConnectorName")
	val connectorName: String? = null
)