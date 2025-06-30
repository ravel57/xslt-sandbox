module ru.ravel.xsltsandbox {
	requires javafx.controls;
	requires javafx.fxml;
	requires kotlin.stdlib;


	opens ru.ravel.xsltsandbox to javafx.fxml;
	exports ru.ravel.xsltsandbox;
}