package ru.ravel.xsltsandbox.connector

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Читает `<system-properties>` из JBoss `standalone.xml` — тех самых `-D`, которые сервер выставляет
 * при старте и от которых зависят Spring-бины коннекторов (`cfj.prop`, `cfj.log4j2.prop` и т.п.).
 * При прямом запуске через crif-runner их никто не выставляет, поэтому нужно перенести их в VM options.
 */
object JBossStandaloneImport {

	data class ParsedProperty(val name: String, val rawValue: String)

	private val ENV_TOKEN = Regex("""\$\{env\.([A-Za-z0-9_]+)}""")


	/** @return список свойств как есть в файле, со значениями, возможно содержащими `${env.NAME}`. */
	fun readSystemProperties(file: File): List<ParsedProperty> {
		val factory = DocumentBuilderFactory.newInstance().apply {
			isNamespaceAware = false
			setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
		}
		val doc = factory.newDocumentBuilder().parse(file)
		val systemProps = doc.getElementsByTagName("system-properties")
		if (systemProps.length == 0) return emptyList()
		val entries = (systemProps.item(0) as Element).getElementsByTagName("property")
		return (0 until entries.length).map { i ->
			val el = entries.item(i) as Element
			ParsedProperty(el.getAttribute("name"), el.getAttribute("value"))
		}
	}


	/** Имена переменных окружения сервера (`${env.NAME}`), встречающихся в значениях [properties]. */
	fun envVarNames(properties: List<ParsedProperty>): List<String> =
		properties.flatMap { p -> ENV_TOKEN.findAll(p.rawValue).map { it.groupValues[1] } }.distinct()


	/**
	 * Подставляет [envValues] (имя переменной → значение) в сырые значения свойств. Пустое/отсутствующее
	 * значение переменной оставляет `${env.NAME}` как есть — вызывающий код должен проверить
	 * [isResolved] и не подставлять "битые" значения туда, где раньше могло быть правильное.
	 */
	fun resolve(properties: List<ParsedProperty>, envValues: Map<String, String>): List<Pair<String, String>> =
		properties.map { p ->
			p.name to ENV_TOKEN.replace(p.rawValue) { m -> envValues[m.groupValues[1]]?.takeIf { it.isNotBlank() } ?: m.value }
		}


	/** `false`, если в [value] остался хотя бы один неподставленный `${env.NAME}`. */
	fun isResolved(value: String): Boolean = !ENV_TOKEN.containsMatchIn(value)
}
