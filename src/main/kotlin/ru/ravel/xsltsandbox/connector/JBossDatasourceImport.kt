package ru.ravel.xsltsandbox.connector

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Читает `<datasources>` из JBoss `standalone.xml` — реальные JDBC-подключения (driver/URL/логин/пароль)
 * под каждым `jndi-name`, которые иначе пришлось бы вручную собирать по кускам конфигурации сервера.
 * Готовые строки идут прямо в поле «Заглушки JNDI DataSource» (`jndiName=driverClassName,jdbcUrl,username,password`,
 * см. [ConnectorsConfig.jndiDataSources] и `jndi.dataSources` в runner-java7).
 */
object JBossDatasourceImport {

	data class DataSourceInfo(
		val jndiName: String,
		val driverClassName: String,
		val jdbcUrl: String,
		val username: String,
		val password: String,
	)


	fun readDataSources(file: File): List<DataSourceInfo> {
		val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
		val doc = factory.newDocumentBuilder().parse(file)
		val root = doc.documentElement

		val driverClassByName = elementsByTag(root, "driver")
			.mapNotNull { el ->
				val name = el.getAttribute("name")
				val driverClass = childText(el, "driver-class")
				if (name.isNotBlank() && driverClass != null) name to driverClass else null
			}
			.toMap()

		return elementsByTag(root, "datasource").mapNotNull { ds ->
			val jndiName = ds.getAttribute("jndi-name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
			val jdbcUrl = childText(ds, "connection-url") ?: return@mapNotNull null
			val driverRef = childText(ds, "driver")
			val driverClass = driverRef?.let { driverClassByName[it] } ?: ""
			val security = elementsByTag(ds, "security").firstOrNull()
			val username = security?.let { childText(it, "user-name") } ?: ""
			val password = security?.let { childText(it, "password") } ?: ""
			DataSourceInfo(jndiName, driverClass, jdbcUrl, username, password)
		}
	}


	/** Строка в формате поля «Заглушки JNDI DataSource» (`jndiName=driverClassName,jdbcUrl,username,password`). */
	fun toConfigLine(info: DataSourceInfo): String =
		"${info.jndiName}=${info.driverClassName},${info.jdbcUrl},${info.username},${info.password}"


	private fun elementsByTag(root: Element, tag: String): List<Element> {
		val nodeList = root.getElementsByTagName(tag)
		return (0 until nodeList.length).map { nodeList.item(it) as Element }
	}


	private fun childText(el: Element, tag: String): String? {
		val nodeList = el.getElementsByTagName(tag)
		if (nodeList.length == 0) return null
		return nodeList.item(0).textContent?.trim()?.takeIf { it.isNotEmpty() }
	}
}
