package com.lifetrack.data

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.*
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.room.withTransaction
import androidx.work.*
import java.time.*
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

class HealthConnectSource(
    private val context: Context,
    private val db: LifeDatabase = LifeStore.get(context),
) : HealthDataProvider {
    override val sourceId = "HC"

    override suspend fun disconnect() {
        if (status == HealthConnectClient.SDK_AVAILABLE)
            client().permissionController.revokeAllPermissions()
    }

    val status: Int
        get() = HealthConnectClient.getSdkStatus(context)

    private fun client() = HealthConnectClient.getOrCreate(context)

    companion object {
        val groups =
            linkedMapOf(
                "Активность" to
                    setOf(
                        StepsRecord::class,
                        DistanceRecord::class,
                        ActiveCaloriesBurnedRecord::class,
                        TotalCaloriesBurnedRecord::class,
                        ExerciseSessionRecord::class,
                    ),
                "Сон" to setOf(SleepSessionRecord::class),
                "Сердце" to
                    setOf(
                        HeartRateRecord::class,
                        RestingHeartRateRecord::class,
                        OxygenSaturationRecord::class,
                        Vo2MaxRecord::class,
                    ),
                "Тело" to setOf(WeightRecord::class, HeightRecord::class, BodyFatRecord::class),
                "Питание и вода" to setOf(NutritionRecord::class, HydrationRecord::class),
            )

        fun permissions(group: String) =
            groups.getValue(group).map { HealthPermission.getReadPermission(it) }.toSet()
    }

    suspend fun granted(): Set<String> =
        if (status == HealthConnectClient.SDK_AVAILABLE)
            client().permissionController.getGrantedPermissions()
        else emptySet()

    override suspend fun sync(): Int {
        if (status != HealthConnectClient.SDK_AVAILABLE) return 0
        val client = client()
        val granted = granted()
        val now = Instant.now()
        val start = LocalDate.now().minusDays(27).atStartOfDay(ZoneId.systemDefault()).toInstant()
        val rows = mutableListOf<HealthEntry>()
        val kinds = mutableSetOf<String>()
        fun can(type: KClass<out Record>) = HealthPermission.getReadPermission(type) in granted
        suspend fun <T : Record> read(type: KClass<T>): List<T> {
            val result = mutableListOf<T>()
            var token: String? = null
            do {
                val r =
                    client.readRecords(
                        ReadRecordsRequest(
                            type,
                            TimeRangeFilter.between(start, now),
                            pageSize = 500,
                            pageToken = token,
                        )
                    )
                result.addAll(r.records)
                token = r.pageToken?.takeIf { it.isNotBlank() }
                require(result.size < 100_000) { "Слишком много записей за период" }
            } while (token != null)
            return result
        }
        fun add(
            r: Record,
            kind: String,
            s: Instant,
            e: Instant,
            value: Long,
            unit: String,
            detail: String = "",
        ) {
            val m = r.metadata
            rows +=
                HealthEntry(
                    "HC:$kind:${m.id}",
                    "HC",
                    m.dataOrigin.packageName,
                    listOfNotNull(m.device?.manufacturer, m.device?.model).joinToString(" "),
                    m.id,
                    kind,
                    s.toEpochMilli(),
                    e.toEpochMilli(),
                    value,
                    unit,
                    detail,
                )
        }
        // Daily cumulative aggregates use the system's priority/overlap logic, not raw sums.
        for (offset in 27L downTo 0L) {
            val date = LocalDate.now().minusDays(offset)
            val a = date.atStartOfDay(ZoneId.systemDefault()).toInstant()
            val b = minOf(date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant(), now)
            if (a >= b) continue
            suspend fun aggregate(
                kind: String,
                block:
                    suspend () -> Pair<
                            Long?,
                            Set<androidx.health.connect.client.records.metadata.DataOrigin>,
                        >,
            ) {
                val (v, origins) = block()
                kinds += kind
                // The platform can synthesize total energy from a default BMR with no source.
                // Import only aggregates backed by actual source records.
                if (v != null && origins.isNotEmpty())
                    rows +=
                        HealthEntry(
                            "HC:$kind:$date",
                            "HC",
                            origins.joinToString(",") { it.packageName },
                            "",
                            date.toString(),
                            kind,
                            a.toEpochMilli(),
                            b.toEpochMilli(),
                            v,
                            "",
                            "",
                        )
            }
            if (can(SleepSessionRecord::class))
                aggregate("SLEEP") {
                    val r =
                        client.aggregate(
                            AggregateRequest(
                                setOf(SleepSessionRecord.SLEEP_DURATION_TOTAL),
                                TimeRangeFilter.between(
                                    a.minusSeconds(43200),
                                    minOf(b.minusSeconds(43200), now),
                                ),
                            )
                        )
                    r[SleepSessionRecord.SLEEP_DURATION_TOTAL]?.toMillis() to r.dataOrigins
                }
            if (can(StepsRecord::class))
                aggregate("STEPS") {
                    val r =
                        client.aggregate(
                            AggregateRequest(
                                setOf(StepsRecord.COUNT_TOTAL),
                                TimeRangeFilter.between(a, b),
                            )
                        )
                    r[StepsRecord.COUNT_TOTAL] to r.dataOrigins
                }
            if (can(DistanceRecord::class))
                aggregate("DISTANCE") {
                    val r =
                        client.aggregate(
                            AggregateRequest(
                                setOf(DistanceRecord.DISTANCE_TOTAL),
                                TimeRangeFilter.between(a, b),
                            )
                        )
                    r[DistanceRecord.DISTANCE_TOTAL]?.inMeters?.times(1000)?.toLong() to
                        r.dataOrigins
                }
            if (can(ActiveCaloriesBurnedRecord::class))
                aggregate("ACTIVE_CALORIES") {
                    val r =
                        client.aggregate(
                            AggregateRequest(
                                setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL),
                                TimeRangeFilter.between(a, b),
                            )
                        )
                    r[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]
                        ?.inKilocalories
                        ?.times(1000)
                        ?.toLong() to r.dataOrigins
                }
            if (can(TotalCaloriesBurnedRecord::class))
                aggregate("TOTAL_CALORIES") {
                    val r =
                        client.aggregate(
                            AggregateRequest(
                                setOf(TotalCaloriesBurnedRecord.ENERGY_TOTAL),
                                TimeRangeFilter.between(a, b),
                            )
                        )
                    r[TotalCaloriesBurnedRecord.ENERGY_TOTAL]
                        ?.inKilocalories
                        ?.times(1000)
                        ?.toLong() to r.dataOrigins
                }
        }
        if (can(SleepSessionRecord::class)) {
            kinds += "SLEEP_SESSION"
            read(SleepSessionRecord::class).forEach { r ->
                val duration =
                    if (r.stages.isEmpty()) Duration.between(r.startTime, r.endTime).toMillis()
                    else
                        r.stages
                            .filter {
                                it.stage in
                                    setOf(
                                        SleepSessionRecord.STAGE_TYPE_SLEEPING,
                                        SleepSessionRecord.STAGE_TYPE_LIGHT,
                                        SleepSessionRecord.STAGE_TYPE_DEEP,
                                        SleepSessionRecord.STAGE_TYPE_REM,
                                    )
                            }
                            .sumOf { Duration.between(it.startTime, it.endTime).toMillis() }
                add(
                    r,
                    "SLEEP_SESSION",
                    r.startTime,
                    r.endTime,
                    duration,
                    "мс",
                    r.stages.joinToString(";") {
                        "${it.stage},${it.startTime.toEpochMilli()},${it.endTime.toEpochMilli()}"
                    },
                )
            }
        }
        if (can(WeightRecord::class)) {
            kinds += "WEIGHT"
            read(WeightRecord::class).forEach {
                add(it, "WEIGHT", it.time, it.time, (it.weight.inKilograms * 1000).toLong(), "кг")
            }
        }
        if (can(BodyFatRecord::class)) {
            kinds += "BODY_FAT"
            read(BodyFatRecord::class).forEach {
                add(it, "BODY_FAT", it.time, it.time, (it.percentage.value * 1000).toLong(), "%")
            }
        }
        if (can(HeightRecord::class)) {
            kinds += "HEIGHT"
            read(HeightRecord::class).forEach {
                add(it, "HEIGHT", it.time, it.time, (it.height.inMeters * 100_000).toLong(), "см")
            }
        }
        if (can(RestingHeartRateRecord::class)) {
            kinds += "RESTING_HR"
            read(RestingHeartRateRecord::class).forEach {
                add(it, "RESTING_HR", it.time, it.time, it.beatsPerMinute * 1000, "уд/мин")
            }
        }
        if (can(HeartRateRecord::class)) {
            kinds += "HEART_RATE"
            read(HeartRateRecord::class).forEach { r ->
                if (r.samples.isNotEmpty())
                    add(
                        r,
                        "HEART_RATE",
                        r.startTime,
                        r.endTime,
                        (r.samples.map { it.beatsPerMinute }.average() * 1000).toLong(),
                        "уд/мин",
                        r.samples.joinToString(";") {
                            "${it.time.toEpochMilli()},${it.beatsPerMinute}"
                        },
                    )
            }
        }
        if (can(OxygenSaturationRecord::class)) {
            kinds += "SPO2"
            read(OxygenSaturationRecord::class).forEach {
                add(it, "SPO2", it.time, it.time, (it.percentage.value * 1000).toLong(), "%")
            }
        }
        if (can(Vo2MaxRecord::class)) {
            kinds += "VO2MAX"
            read(Vo2MaxRecord::class).forEach {
                add(
                    it,
                    "VO2MAX",
                    it.time,
                    it.time,
                    (it.vo2MillilitersPerMinuteKilogram * 1000).toLong(),
                    "мл/кг/мин",
                )
            }
        }
        if (can(ExerciseSessionRecord::class)) {
            kinds += "WORKOUT"
            read(ExerciseSessionRecord::class).forEach {
                add(
                    it,
                    "WORKOUT",
                    it.startTime,
                    it.endTime,
                    Duration.between(it.startTime, it.endTime).toMinutes(),
                    "мин",
                    it.title ?: "Тренировка · ${it.exerciseType}",
                )
            }
        }
        if (can(HydrationRecord::class)) {
            kinds += "HYDRATION"
            read(HydrationRecord::class).forEach {
                add(
                    it,
                    "HYDRATION",
                    it.startTime,
                    it.endTime,
                    (it.volume.inLiters * 1000).toLong(),
                    "мл",
                )
            }
        }
        if (can(NutritionRecord::class)) {
            kinds += "NUTRITION"
            read(NutritionRecord::class)
                .filter { it.energy != null }
                .forEach {
                    add(
                        it,
                        "NUTRITION",
                        it.startTime,
                        it.endTime,
                        (it.energy?.inKilocalories?.times(1000))?.toLong() ?: 0,
                        "ккал",
                        it.name.orEmpty(),
                    )
                }
        }
        db.withTransaction {
            for (kind in kinds) db.productDao()
                .clearRange("HC", kind, start.toEpochMilli(), now.toEpochMilli())
            rows.forEach { db.productDao().put(it) }
            db.productDao()
                .put(ProductPreference("health_last_sync", now.toEpochMilli().toString()))
        }
        return rows.size
    }
}

class HealthSyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            val s = HealthConnectSource(applicationContext)
            if ("android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND" !in s.granted())
                return Result.success()
            s.sync()
            AutoProgressUpdater(LifeStore.get(applicationContext)).update()
            Result.success()
        } catch (e: SecurityException) {
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    "health_sync",
                    ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<HealthSyncWorker>(6, TimeUnit.HOURS)
                        .setConstraints(
                            Constraints.Builder().setRequiresBatteryNotLow(true).build()
                        )
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                        .build(),
                )
        }
    }
}
