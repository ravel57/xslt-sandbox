package ru.ravel.xsltsandbox.connector

import java.io.File
import java.util.zip.ZipFile

/**
 * Ищет, в каком из уже известных (просканированных/закэшированных) jar лежит конкретный класс —
 * чтобы конечному пользователю при `NoClassDefFoundError`/`ClassNotFoundException` достаточно было
 * вставить имя класса прямо из текста ошибки, а не угадывать имя JBoss-модуля по соглашениям
 * раскладки (которые, как выяснилось, не всегда совпадают с пакетом класса — `org.hibernate.javax.persistence`
 * для `javax.persistence.*`, `org.springframework.spring` для `org.springframework.*` и т.п.).
 */
object JarClassSearch {

	/** [className] в любом виде: через точки или слэши, с `.class` на конце или без. */
	fun findClassJars(jarPaths: List<String>, className: String): List<String> {
		val entryName = className.trim().replace('.', '/').removeSuffix(".class") + ".class"
		return jarPaths.filter { jarPath ->
			runCatching {
				ZipFile(File(jarPath)).use { zip -> zip.getEntry(entryName) != null }
			}.getOrDefault(false)
		}
	}


	/** Вытаскивает имя класса из типичного текста исключения (NoClassDefFoundError/ClassNotFoundException). */
	fun extractClassName(text: String): String? {
		val patterns = listOf(
			Regex("""ClassNotFoundException:\s*([\w.$]+)"""),
			Regex("""NoClassDefFoundError:\s*([\w/$]+)"""),
		)
		for (pattern in patterns) {
			pattern.find(text)?.groupValues?.get(1)?.let { return it }
		}
		return text.trim().takeIf { it.isNotEmpty() && (it.contains('.') || it.contains('/')) }
	}
}
