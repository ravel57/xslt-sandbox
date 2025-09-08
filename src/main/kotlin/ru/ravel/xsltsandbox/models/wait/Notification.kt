package ru.ravel.xsltsandbox.models.wait

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty

data class Notification(
	@JacksonXmlProperty(localName = "ActionID")
	val actionId: String? = null,

	@JacksonXmlProperty(localName = "NotificationMessage")
	val notificationMessage: MnemonicWrapper? = null,

	@JacksonXmlProperty(localName = "DispatchRuleID")
	val dispatchRuleId: String? = null,

	@JacksonXmlProperty(localName = "NotificationSubject")
	val notificationSubject: MnemonicWrapper? = null,

	@JacksonXmlProperty(localName = "NotificationMethod")
	val notificationMethod: String? = null
)