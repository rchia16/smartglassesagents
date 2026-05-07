package com.example.smartglassesagents.experiment

enum class TaskType(val wireName: String, val label: String, val defaultPrompt: String) {
    BoardText(
        wireName = "board_text",
        label = "Board text",
        defaultPrompt = "Read any visible text on the board. Report unreadable regions and uncertainty."
    ),
    TabletopItems(
        wireName = "tabletop_items",
        label = "Tabletop items",
        defaultPrompt = "Identify small items on the table and describe their approximate positions."
    ),
    Faces(
        wireName = "faces",
        label = "Faces",
        defaultPrompt = "Count visible faces and describe approximate positions. Do not identify people."
    ),
    GeneralQuery(
        wireName = "general_query",
        label = "General query",
        defaultPrompt = "Answer the user's visual question concisely and state uncertainty."
    );

    companion object {
        fun fromWireName(value: String): TaskType =
            entries.firstOrNull { it.wireName == value } ?: GeneralQuery
    }
}

enum class CaptureSource(val wireName: String, val label: String) {
    PhoneCamera("phone_camera", "Phone camera"),
    RayBanMetaDat("rayban_meta_dat", "Ray-Ban Meta DAT"),
    Mock("mock", "Mock")
}

data class HostConfig(
    val baseUrl: String = "http://10.0.2.2:8765",
    val pairingToken: String = ""
)

sealed interface ExperimentStatus {
    data object Idle : ExperimentStatus
    data object CheckingHost : ExperimentStatus
    data object CapturingImage : ExperimentStatus
    data object SendingImage : ExperimentStatus
    data class Ready(val message: String) : ExperimentStatus
    data class Error(val message: String) : ExperimentStatus
}
