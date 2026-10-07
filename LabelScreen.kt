package com.lifetrack.data

/** Providers map their native data into HealthEntry; UI consumes the local store. */
interface HealthDataProvider {
    val sourceId: String
    suspend fun sync(): Int
    suspend fun disconnect()
}
