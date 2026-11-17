package ru.ravel.xsltsandbox.connector

/**
 * Настройки реального прогона DataSource-коннекторов через crif-runner (вместо Mock.xml).
 *
 * @param runnerJarPath путь к crif-runner.jar (сам дебагер — CLI-раннер из crif-connectors-debuger/runner-java7),
 *   больше не зашит в ресурсы приложения, задаётся пользователем через настройки
 * @param classpathEntries jar-файлы и/или папки с классами проверяемых коннекторов (и Spring, если используется)
 * @param springContextFiles необязательные Spring XML context-файлы; если заданы, бин ищется по имени
 *   коннектора (`ConnectorName` из Properties.xml используется как имя Spring-бина)
 * @param connectorClassNames `ConnectorName` → полное имя Java-класса, реализующего `execute(...)`
 */
data class ConnectorsConfig(
	val runnerJarPath: String = "",
	val classpathEntries: List<String> = emptyList(),
	val springContextFiles: List<String> = emptyList(),
	val connectorClassNames: Map<String, String> = emptyMap(),
)
