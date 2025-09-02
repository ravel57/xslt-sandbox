package ru.ravel.xsltsandbox.models.br

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlText


class XmlRule {
	@JacksonXmlText
	lateinit var value: String
}