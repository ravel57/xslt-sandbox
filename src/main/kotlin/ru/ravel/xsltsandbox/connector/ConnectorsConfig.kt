package ru.ravel.xsltsandbox.connector

/**
 * Настройки реального прогона DataSource-коннекторов через crif-runner (вместо Mock.xml).
 *
 * @param runnerJarPath путь к crif-runner.jar (сам дебагер — CLI-раннер из crif-connectors-debuger/runner-java7),
 *   больше не зашит в ресурсы приложения, задаётся пользователем через настройки
 * @param javaExecutablePath путь к `java`/`java.exe`, которым запускать crif-runner.jar; пусто — берётся
 *   JVM, в которой работает сам sandbox (`java.home`)
 * @param classpathEntries jar-файлы и/или папки с классами проверяемых коннекторов (и Spring, если используется)
 * @param springContextFiles необязательные Spring XML context-файлы; если заданы, бин ищется по имени
 *   коннектора (`ConnectorName` из Properties.xml используется как имя Spring-бина)
 *
 * Имя Java-класса коннектора сюда не входит — оно не настройка приложения, а параметр конкретного
 * прогона: передаётся аргументом запуска sandbox `--input-connector-class-name`.
 */
data class ConnectorsConfig(
	val runnerJarPath: String = "",
	val javaExecutablePath: String = "",
	val classpathEntries: List<String> = emptyList(),
	val springContextFiles: List<String> = emptyList(),
)
