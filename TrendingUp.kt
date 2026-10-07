package com.lifetrack

import androidx.health.connect.client.HealthConnectClient
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lifetrack.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HealthConnectIntegrationTest {
    @Test
    fun actualPlatformSyncNeverImportsEstimatesWithNoDataOrigin() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, LifeDatabase::class.java).build()
        try {
            val source = HealthConnectSource(context, db)
            assumeTrue(source.status == HealthConnectClient.SDK_AVAILABLE)
            assumeTrue(source.granted().containsAll(HealthConnectSource.permissions("Активность")))
            source.sync()
            assertTrue(db.productDao().health().first().all { it.origin.isNotBlank() })
            assertTrue(db.productDao().preferences().first().any { it.key == "health_last_sync" })
        } finally {
            db.close()
        }
    }
}
