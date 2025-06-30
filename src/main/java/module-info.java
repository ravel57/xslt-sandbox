module ru.ravel.xsltsandbox {
	requires javafx.controls;
	requires javafx.fxml;
	requires kotlin.stdlib;
	requires java.xml;
	requires org.fxmisc.richtext;
	requires org.fxmisc.flowless;


	opens ru.ravel.xsltsandbox to javafx.fxml;
	exports ru.ravel.xsltsandbox;
}