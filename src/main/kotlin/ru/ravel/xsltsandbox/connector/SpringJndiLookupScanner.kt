package ru.ravel.xsltsandbox.connector

import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Ищет `jndi-name`, на которые реально ссылается Spring-контекст коннектора — в XML бинов внутри
 * META-INF/spring на том же classpath, который видит crif-runner (jar'ы и папки из [ConnectorJarRunner.resolveClasspathEntries]).
 * Понимает и сокращённую форму (`<jee:jndi-lookup jndi-name="..."/>`), и полную
 * (`JndiObjectFactoryBean` + `<property name="jndiName" value="..."/>`). Закомментированные ссылки
 * DOM-парсер сам игнорирует — это XML-комментарии, а не элементы.
 */
object SpringJndiLookupScanner {

	private val SPRING_XML_IN_JAR = Regex("""META-INF/spring/.*\.xml$""")


	/** @return найденные jndi-name без дублей, в порядке обнаружения. */
	fun scan(classpathEntries: List<String>): List<String> {
		val names = LinkedHashSet<String>()
		for (entry in classpathEntries) {
			val file = File(entry)
			when {
				file.isFile && file.name.endsWith(".jar", ignoreCase = true) -> scanJar(file, names)
				file.isDirectory -> scanDirectory(file, names)
			}
		}
		return names.toList()
	}


	private fun scanDirectory(dir: File, names: MutableSet<String>) {
		dir.walkTopDown().forEach { f ->
			val normalizedPath = f.path.replace('\\', '/')
			when {
				f.isFile && f.name.endsWith(".jar", ignoreCase = true) -> scanJar(f, names)
				f.isFile && f.name.endsWith(".xml", ignoreCase = true) && normalizedPath.contains("META-INF/spring/") ->
					runCatching { names.addAll(parseJndiNames(f.readBytes())) }
			}
		}
	}


	private fun scanJar(jarFile: File, names: MutableSet<String>) {
		runCatching {
			ZipFile(jarFile).use { zip ->
				zip.entries().asSequence()
					.filter { !it.isDirectory && SPRING_XML_IN_JAR.containsMatchIn(it.name) }
					.forEach { entry ->
						runCatching {
							names.addAll(parseJndiNames(zip.getInputStream(entry).readBytes()))
						}
					}
			}
		}
	}


	private fun parseJndiNames(xmlBytes: ByteArray): List<String> {
		val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
		val doc = factory.newDocumentBuilder().parse(xmlBytes.inputStream())
		val found = LinkedHashSet<String>()

		val allElements = doc.getElementsByTagName("*")
		for (i in 0 until allElements.length) {
			val el = allElements.item(i) as Element
			val tag = el.tagName
			if (tag == "jndi-lookup" || tag.endsWith(":jndi-lookup")) {
				el.getAttribute("jndi-name").takeIf { it.isNotBlank() }?.let { found.add(it) }
			}
		}

		val beans = doc.getElementsByTagName("bean")
		for (i in 0 until beans.length) {
			val bean = beans.item(i) as Element
			if (bean.getAttribute("class") != "org.springframework.jndi.JndiObjectFactoryBean") continue
			val props = bean.getElementsByTagName("property")
			for (j in 0 until props.length) {
				val prop = props.item(j) as Element
				if (prop.getAttribute("name") == "jndiName") {
					prop.getAttribute("value").takeIf { it.isNotBlank() }?.let { found.add(it) }
				}
			}
		}

		return found.toList()
	}
}
