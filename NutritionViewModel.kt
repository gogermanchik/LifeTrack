package com.lifetrack.domain

import com.lifetrack.data.FoodLog
import kotlinx.coroutines.delay

data class RecognitionImage(val jpeg: ByteArray, val mimeType: String = "image/jpeg")

data class RecognizedFood(val food: FoodLog, val confidence: String)

data class FoodRecognitionResult(
    val items: List<RecognizedFood>,
    val demonstration: Boolean = false,
)

sealed interface RecognitionOutcome {
    data class Success(val result: FoodRecognitionResult) : RecognitionOutcome

    data class LowConfidence(val result: FoodRecognitionResult) : RecognitionOutcome

    data class Error(val message: String) : RecognitionOutcome

    data class Offline(val message: String) : RecognitionOutcome
}

interface FoodRecognitionService {
    val sendsImageToServer: Boolean

    suspend fun recognizeFood(image: RecognitionImage): RecognitionOutcome
}

class UnconfiguredFoodRecognitionService : FoodRecognitionService {
    override val sendsImageToServer = false

    override suspend fun recognizeFood(image: RecognitionImage): RecognitionOutcome =
        RecognitionOutcome.Offline(
            "Внешний сервис распознавания не подключён. Фото никуда не отправлено. Можно заполнить данные вручную."
        )
}

// Only selected by the explicit development/demo action. Never classifies the image.
class DemoFoodRecognitionService : FoodRecognitionService {
    override val sendsImageToServer = false

    override suspend fun recognizeFood(image: RecognitionImage): RecognitionOutcome {
        if (image.jpeg.isEmpty()) return RecognitionOutcome.Error("Фото недоступно")
        delay(700)
        fun item(n: String, a: Long, k: Long, p: Long, f: Long, c: Long, confidence: String) =
            RecognizedFood(
                FoodLog(
                    customName = n,
                    mealType = "LUNCH",
                    amount = a,
                    calories = k,
                    protein = p,
                    fat = f,
                    carbs = c,
                    source = "PHOTO",
                    recognitionDemo = true,
                ),
                confidence,
            )
        return RecognitionOutcome.LowConfidence(
            FoodRecognitionResult(
                listOf(
                    item("Куриная грудка", 180000, 297000, 45000, 8000, 0, "HIGH"),
                    item("Рис", 150000, 195000, 4500, 1500, 45000, "MEDIUM"),
                    item("Овощи", 100000, 40000, 2500, 1000, 7000, "MEDIUM"),
                    item("Соус", 30000, 88000, 2000, 5500, 15000, "LOW"),
                ),
                demonstration = true,
            )
        )
    }
}
