package com.example.hello

data class AccData(
    var idx: Int = 0,
    var x: Float = 0.0f,
    var y: Float = 0.0f,
    var z: Float = 0.0f,
    var timestamp: Long = 0
)

data class UploadBody (
    var acc: List<AccData>,
    var startAt: Long = 0,
    var endAt: Long = 0,
    var sensorDelayTime: String = "",
    var createDateTime: Long,
    var deviceId: String = "",
    var collectId: Long = 0,
)

data class UploadResponse (
    val result: String? = null
)