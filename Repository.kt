package com.lifetrack

import android.os.Bundle
import android.content.Intent
import androidx.compose.runtime.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lifetrack.ui.*

class MainActivity : ComponentActivity() {
    private var request by mutableStateOf<Pair<String?,Long>>(null to 0L)
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);request=intent.getStringExtra("lifetrack_action") to System.nanoTime()}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        request=intent.getStringExtra("lifetrack_action") to System.nanoTime()
        setContent {
            val vm: LifeViewModel = viewModel()
            LifeApp(vm,request.first,request.second)
        }
    }
}
