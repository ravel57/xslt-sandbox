module ru.ravel.xsltsandbox {
	requires javafx.controls;
	requires javafx.fxml;
	requires kotlin.stdlib;
	requires java.xml;
	requires org.fxmisc.richtext;
	requires org.fxmisc.flowless;
	requires com.fasterxml.jackson.kotlin;
	requires com.fasterxml.jackson.databind;
	requires Saxon.HE;
	requires tagsoup;
	requires reactfx;
	requires org.kordamp.ikonli.javafx;
	requires org.kordamp.ikonli.fontawesome5;
	requires org.kordamp.ikonli.core;


	opens ru.ravel.xsltsandbox to javafx.fxml, com.fasterxml.jackson.databind;
	opens ru.ravel.xsltsandbox.models to com.fasterxml.jackson.databind, com.fasterxml.jackson.module.kotlin, kotlin.reflect;
	exports ru.ravel.xsltsandbox;
}