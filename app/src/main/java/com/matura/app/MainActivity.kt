package com.matura.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.matura.app.data.Repository
import com.matura.app.ui.MaturaApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = Repository(applicationContext)
        setContent {
            MaturaApp(repo = repo, onExitApp = { finish() })
        }
    }
}
