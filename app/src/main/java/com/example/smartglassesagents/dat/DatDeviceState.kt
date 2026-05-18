package com.example.smartglassesagents.dat

data class DatDeviceState(
    val adapterName: String = "Mock DAT adapter",
    val registrationStatus: DatRegistrationStatus = DatRegistrationStatus.NotRegistered,
    val devices: List<DatDevice> = emptyList(),
    val activeDeviceId: String? = null,
    val cameraPermissionStatus: DatPermissionStatus = DatPermissionStatus.Unknown,
    val sessionStatus: DatSessionStatus = DatSessionStatus.Stopped,
    val deviceCount: Int = devices.size,
    val deviceSessionState: String = "Unknown",
    val streamState: String = "Unknown",
    val streamError: String? = null,
    val supportsRealSdk: Boolean = false,
    val recentError: String? = null
) {
    val hasDevice: Boolean = devices.isNotEmpty()
    val activeDevice: DatDevice? = devices.firstOrNull { it.id == activeDeviceId }
    val isReadyForCapture: Boolean =
        registrationStatus == DatRegistrationStatus.Registered &&
            activeDevice != null &&
            cameraPermissionStatus == DatPermissionStatus.Granted &&
            sessionStatus == DatSessionStatus.Running
}

data class DatDevice(
    val id: String,
    val name: String,
    val kind: DatDeviceKind,
    val compatibility: DatDeviceCompatibility = DatDeviceCompatibility.Compatible
)

enum class DatRegistrationStatus(val label: String) {
    NotRegistered("Not registered"),
    Registering("Registering"),
    Registered("Registered"),
    Unavailable("Unavailable")
}

enum class DatPermissionStatus(val label: String) {
    Unknown("Unknown"),
    Granted("Granted"),
    Denied("Denied")
}

enum class DatSessionStatus(val label: String) {
    Stopped("Stopped"),
    Discovering("Discovering"),
    Running("Running"),
    Paused("Paused"),
    Error("Error")
}

enum class DatDeviceKind(val label: String) {
    RayBanMeta("Ray-Ban Meta"),
    MockRayBanMeta("Mock Ray-Ban Meta")
}

enum class DatDeviceCompatibility(val label: String) {
    Compatible("Compatible"),
    UpdateRequired("Update required"),
    Unknown("Unknown")
}
