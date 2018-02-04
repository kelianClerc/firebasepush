package com.github.tokou.firebasepush

import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleListProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import javafx.geometry.Orientation
import javafx.scene.control.CheckBox
import javafx.scene.control.TextField
import javafx.scene.text.FontWeight
import javafx.util.StringConverter
import tornadofx.*

data class Payload(
    private val registrationIds: List<String>,
    private val notification: Notification?,
    private val data: Data?
) : JsonModel {
    override fun toJSON(json: JsonBuilder) { with(json) {
        add("registration_ids", registrationIds)
        add("notification", notification)
        add("data", data)
    } }
}

data class Notification(
    private val title: String?,
    private val body: String?
) : JsonModel {
    override fun toJSON(json: JsonBuilder) { with(json) {
        add("title", title)
        add("body", body)
    } }
}

data class Data(
    private val values: Map<String, String>
) : JsonModel {
    override fun toJSON(json: JsonBuilder) { with(json) {
        values.forEach { k, v -> add(k, v) }
    } }
}

class KeyValueModel(key: String, value: String) {
    val keyProperty = SimpleStringProperty(key)
    var key by keyProperty
    val valueProperty = SimpleStringProperty(value)
    var value by valueProperty

    override fun equals(other: Any?): Boolean = when (other) {
        is KeyValueModel -> if (key == null) false else other.key == key
        else -> false
    }

    override fun hashCode(): Int {
        return keyProperty.hashCode()
    }
}

class PayloadViewModel : ItemViewModel<Payload>() {
    val tokens = SimpleListProperty<String>()
    val title = SimpleStringProperty()
    val body = SimpleStringProperty()
    val notification = SimpleBooleanProperty()
    val data = SimpleBooleanProperty()
    val values = SimpleListProperty<KeyValueModel>(FXCollections.observableArrayList())
    val selected = SimpleObjectProperty<KeyValueModel>(KeyValueModel("", ""))

    override fun onCommit() {
        val payloadNotification = if (notification.value) Notification(title.get(), body.get()) else null
        val payloadData = if (data.value) Data(convertValues()) else null
        val registrationIds = tokens.get() ?: emptyList<String>()
        item = Payload(registrationIds, payloadNotification, payloadData)
    }

    private fun convertValues() = values.map { it.key to it.value }.toMap()
}

class MainView : View("Firebase Push") {

    val api: Rest by inject()

    lateinit var initialServerKey: String

    init {
        with(api) {
            baseURI = "https://fcm.googleapis.com/fcm/"
            with(engine) {
                requestInterceptor = {
                log.info("--> ${it.method} ${it.uri}\n${it.entity}")
                }
                responseInterceptor = {
                    log.info("<-- ${it.statusCode}\n${it.text()}")
                }
            }
        }
        preferences {
            initialServerKey = get("server_key", "")
        }
    }

    val model: PayloadViewModel by inject()

    var dataField: CheckBox by singleAssign()
    var notificationField: CheckBox by singleAssign()
    var serverKeyField: TextField by singleAssign()

    val statusProperty = SimpleStringProperty("")
    var status by statusProperty

    val converter = object : StringConverter<ObservableList<String>>() {
        override fun toString(`object`: ObservableList<String>?): String =
            `object`?.joinToString("\n") ?: ""

        override fun fromString(string: String?): ObservableList<String> =
            (string?.split("\n")?: emptyList()).observable()
    }

    override val root = form {
        paddingAll = 10
        spacing = 10.0
        fieldset("Config", labelPosition = Orientation.VERTICAL) {
            spacing = 10.0
            field("Server Key") {
                textfield(initialServerKey) {
                    serverKeyField = this
                }
            }
            field("Tokens") {
                textarea(model.tokens, converter) {
                    tooltip("One token per line")
                    prefRowCount = 4
                    isWrapText = false
                }
            }
        }
        fieldset("Payload") {
            spacing = 10.0
            borderpane {
                left = vbox {
                    spacing = 10.0
                    paddingRight = 10.0
                    field {
                        checkbox("Notification", model.notification) {
                            notificationField = this
                        }
                    }
                    field {
                        checkbox("Data", model.data) {
                            dataField = this
                        }
                    }
                }
                center = vbox {
                    spacing = 10.0
                    fieldset {
                        spacing = 4.0
                        visibleWhen { notificationField.selectedProperty() }
                        field("Title") {
                            textfield(model.title)
                        }
                        field("Body") {
                            textfield(model.body)
                        }
                    }
                    tableview<KeyValueModel>(model.values) {
                        tooltip("Edit values by double clicking")
                        visibleWhen { dataField.selectedProperty() }
                        isEditable = true
                        maxHeight = 170.0
                        bindSelected(model.selected)
                        columnResizePolicy = SmartResize.POLICY
                        column("Key", KeyValueModel::keyProperty).makeEditable().weightedWidth(1.0)
                        column("Value", KeyValueModel::valueProperty).makeEditable().weightedWidth(2.0)
                    }
                    hbox {
                        visibleWhen { dataField.selectedProperty() }
                        button("+") {
                            minWidth = 50.0
                            action {
                                model.values.add(KeyValueModel("key", "value"))
                            }
                        }
                        button("-") {
                            minWidth = 50.0
                            action {
                                model.values.remove(model.selected.get())
                            }
                        }
                    }
                }
            }
        }
        button("Send") {
            action {
                runLater { status = "" }
                model.commit {
                    runAsyncWithProgress {
                        runLater {
                            preferences {
                                put("server_key", serverKeyField.text)
                            }
                        }
                        api.post("send", model.item) {
                            it.addHeader("Content-Type", "application/json")
                            it.addHeader("Authorization", "key=${serverKeyField.text}")
                        }
                    } ui {
                        status = "${it.statusCode}: ${it.text()}"
                    }
                }
            }
        }
        label(statusProperty)
    }
}

class Style : Stylesheet() {
    init {
        legend {
            fontWeight = FontWeight.BOLD
            fontSize = 20.px
        }
    }
}

class FirebasePushApp : App(MainView::class, Style::class)