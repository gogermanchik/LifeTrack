package com.lifetrack

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.lifetrack.domain.LabelNutritionMapper
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LabelOcrTest {
    @Test
    fun bundledModelReadsLatinLabelFixture() {
        val b =
            InstrumentationRegistry.getInstrumentation()
                .context
                .assets
                .open("label-latin.png")
                .use { BitmapFactory.decodeStream(it) }
        val ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val text =
                Tasks.await(ocr.process(InputImage.fromBitmap(b, 0)), 30, TimeUnit.SECONDS).text
            val p = LabelNutritionMapper.parse(text)
            assertEquals(200000L, p.calories)
            assertEquals(10500L, p.fat)
            assertEquals(20000L, p.carbs)
            assertEquals(7000L, p.protein)
            assertEquals(100, p.basis)
        } finally {
            ocr.close()
            b.recycle()
        }
    }
}
