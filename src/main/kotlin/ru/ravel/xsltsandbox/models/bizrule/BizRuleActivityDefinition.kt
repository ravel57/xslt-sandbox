package ru.ravel.xsltsandbox.models.bizrule

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty

data class BizRuleActivityDefinition(
	@JacksonXmlProperty(isAttribute = true, localName = "ReferenceName")
	val referenceName: String,

	@JacksonXmlProperty(localName = "Header")
	val header: Header,

	@JacksonXmlProperty(localName = "ReferredDocuments")
	val referredDocuments: ReferredDocuments,

	@JacksonXmlProperty(localName = "XmlRule")
	val xmlRule: XmlRule
)