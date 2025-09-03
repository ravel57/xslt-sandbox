module ru.ravel.xsltsandbox {
	requires javafx.controls;
	requires javafx.fxml;
	requires kotlin.stdlib;
	requires java.xml;
	requires org.fxmisc.richtext;
	requires org.fxmisc.flowless;
	requires com.fasterxml.jackson.kotlin;
	requires com.fasterxml.jackson.databind;
	requires com.fasterxml.jackson.dataformat.xml;
	requires Saxon.HE;
	requires tagsoup;
	requires reactfx;
	requires org.kordamp.ikonli.javafx;
	requires org.kordamp.ikonli.fontawesome5;
	requires org.kordamp.ikonli.core;
	requires org.apache.commons.text;

	opens ru.ravel.xsltsandbox.models to com.fasterxml.jackson.databind, com.fasterxml.jackson.module.kotlin, kotlin.reflect;
	opens ru.ravel.xsltsandbox.models.bizrule to com.fasterxml.jackson.databind, com.fasterxml.jackson.module.kotlin, kotlin.reflect;
	opens ru.ravel.xsltsandbox.models.procedure to com.fasterxml.jackson.databind, com.fasterxml.jackson.module.kotlin, kotlin.reflect;
	opens ru.ravel.xsltsandbox.models.layout to com.fasterxml.jackson.databind, com.fasterxml.jackson.module.kotlin, kotlin.reflect;
	opens ru.ravel.xsltsandbox.models.datamapping to com.fasterxml.jackson.databind, com.fasterxml.jackson.module.kotlin, kotlin.reflect;
	opens ru.ravel.xsltsandbox.models.datasource to com.fasterxml.jackson.databind, com.fasterxml.jackson.module.kotlin, kotlin.reflect;
	exports ru.ravel.xsltsandbox;
}