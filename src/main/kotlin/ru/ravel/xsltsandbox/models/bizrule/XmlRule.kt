package ru.ravel.xsltsandbox.models.bizrule

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlText


class XmlRule {
	@JacksonXmlText
	lateinit var value: String
}