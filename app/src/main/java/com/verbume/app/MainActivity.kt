package com.verbume.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.verbume.app.data.Repository
import com.verbume.app.ui.VerbumeApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = Repository(applicationContext)
        setContent {
            VerbumeApp(repo = repo, onExitApp = { finish() })
        }
    }
}
