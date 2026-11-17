package ru.ravel.xsltsandbox.connector

/**
 * Настройки реального прогона DataSource-коннекторов через crif-runner (вместо Mock.xml).
 *
 * @param runnerJarPath путь к crif-runner.jar (сам дебагер — CLI-раннер из crif-connectors-debuger/runner-java7),
 *   больше не зашит в ресурсы приложения, задаётся пользователем через настройки
 * @param javaExecutablePath путь к `java`/`java.exe`, которым запускать crif-runner.jar; пусто — берётся
 *   JVM, в которой работает сам sandbox (`java.home`)
 * @param classpathEntries jar-файлы и/или папки с классами проверяемых коннекторов (и Spring, если используется)
 * @param vmOptions доп. опции JVM процесса crif-runner (`-Dxxx=yyy`, `-Xmx...` и т.п.), через пробел/перенос строки
 * @param connectorProps свойства (`connProps`), передаваемые коннектору в execute(...); изначально могут
 *   прийти из аргумента запуска `--input-connector-props-path`, затем редактируются и сохраняются здесь же
 *
 * Имя Java-класса коннектора сюда не входит — оно не настройка приложения, а параметр конкретного
 * прогона: передаётся аргументом запуска sandbox `--input-connector-class-name`.
 *
 * Spring context сюда тоже не входит: crif-runner сам находит XML-файлы бинов в META-INF/spring
 * на classpath коннектора (в т.ч. внутри его jar) и при наличии подгружает — настраивать путь
 * вручную не нужно.
 */
data class ConnectorsConfig(
	val runnerJarPath: String = "",
	val javaExecutablePath: String = "",
	val classpathEntries: List<String> = emptyList(),
	val vmOptions: String = "",
	val connectorProps: Map<String, String> = emptyMap(),
)
