package com.lifetrack

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lifetrack.ui.LifeTheme

class HealthPrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LifeTheme(false) {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.padding(24.dp).safeDrawingPadding(),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            "Ваше здоровье — ваши данные",
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Text(
                            "LifeTrack читает только выбранные вами категории Health Connect. Записи сохраняются на устройстве для истории и прогресса. Они не отправляются в сервис распознавания еды и не передаются рекламным сервисам. Вы можете отозвать доступ в Health Connect и удалить локальные записи в разделе Подключения. Резервная копия включает ваши данные, только когда вы сами запускаете экспорт."
                        )
                        Text(
                            "Samsung Health может делиться доступными категориями через Health Connect. Garmin требует отдельного подключения и одобрения сервиса. Индикатор восстановления помогает самонаблюдению и не является медицинским заключением."
                        )
                        Button({ finish() }) { Text("Понятно") }
                    }
                }
            }
        }
    }
}
