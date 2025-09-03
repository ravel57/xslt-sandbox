package ru.ravel.xsltsandbox.models.bizrule

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlText


class Variable {
	@JacksonXmlText
	lateinit var value: String
}