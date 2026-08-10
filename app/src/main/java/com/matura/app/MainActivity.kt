package com.matura.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.matura.app.data.Repository
import com.matura.app.ui.MaturaApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Android 15 wymusza rysowanie od krawedzi do krawedzi. Wlaczamy to jawnie,
        // zeby na starszych systemach zachowanie bylo takie samo — inaczej odstepy
        // pod pasek nawigacji dzialalyby tylko na czesci telefonow.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val repo = Repository(applicationContext)
        setContent {
            MaturaApp(repo = repo, onExitApp = { finish() })
        }
    }
}
