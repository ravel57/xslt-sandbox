package ru.ravel.xsltsandbox.models.br

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlText

class Constant {
    @JacksonXmlProperty(isAttribute = true, localName = "Type")
    lateinit var type: String

    @JacksonXmlText
    lateinit var value: String
}