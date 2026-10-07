package com.lifetrack.data

import androidx.room.withTransaction
import kotlinx.serialization.json.*

class GarminAuthManager(private val backend: BackendClient) {
    suspend fun authorize(): String =
        backend
            .request("/garmin/start", JsonObject(emptyMap()))["url"]
            ?.jsonPrimitive
            ?.content
            ?.also { require(it.startsWith("https://connect.garmin.com/oauth2Confirm?")) }
            ?: error("Авторизация Garmin недоступна")
}

class GarminApiClient(private val backend: BackendClient) {
    suspend fun records(): List<HealthEntry> {
        val raw = backend.request("/garmin/records")["records"] ?: error("Пустой ответ Garmin")
        return Json.decodeFromJsonElement<List<HealthEntry>>(raw).map { GarminMapper.map(it) }
    }

    suspend fun disconnect() {
        backend.request("/garmin/disconnect", JsonObject(emptyMap()))
    }
}

object GarminMapper {
    fun map(v: HealthEntry): HealthEntry {
        require(
            v.source == "GARMIN" &&
                v.kind in
                    setOf(
                        "STEPS",
                        "SLEEP",
                        "HEART_RATE",
                        "RESTING_HR",
                        "ACTIVE_CALORIES",
                        "TOTAL_CALORIES",
                        "STRESS",
                        "SPO2",
                        "BODY_BATTERY",
                        "WEIGHT",
                        "BODY_FAT",
                        "RESPIRATION",
                        "BLOOD_PRESSURE",
                        "INTENSITY_MINUTES",
                        "WORKOUT",
                        "HRV",
                    ) &&
                v.start <= v.end &&
                v.value >= 0 &&
                v.externalId.isNotBlank()
        )
        return v.copy(id = "GARMIN:${v.kind}:${v.externalId}")
    }
}

class GarminRepository(private val db: LifeDatabase) {
    suspend fun ingest(rows: List<HealthEntry>) {
        require(rows.size <= 100_000)
        db.withTransaction { rows.forEach { db.productDao().put(GarminMapper.map(it)) } }
    }

    suspend fun clear() {
        db.productDao().disconnect("GARMIN")
    }
}

class GarminSyncManager(private val api: GarminApiClient, private val repo: GarminRepository) :
    HealthDataProvider {
    override val sourceId = "GARMIN"

    override suspend fun sync(): Int {
        val rows = api.records()
        repo.ingest(rows)
        return rows.size
    }

    override suspend fun disconnect() {
        api.disconnect()
        repo.clear()
    }
}
