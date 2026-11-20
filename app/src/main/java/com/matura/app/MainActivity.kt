package com.matura.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.matura.app.data.RepositoryHolder
import com.matura.app.ui.MaturaApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Android 15 wymusza rysowanie od krawedzi do krawedzi. Wlaczamy to jawnie,
        // zeby na starszych systemach zachowanie bylo takie samo: inaczej odstepy
        // pod pasek nawigacji dzialalyby tylko na czesci telefonow.
        //
        // Styl paskow jest podany wprost jako CIEMNY (jasne ikony, bez nakladki systemu), bo gra
        // zawsze rysuje ciemne tlo. Wywolanie bez argumentow dobiera ikony do motywu telefonu,
        // wiec przy jasnym motywie zegar i bateria wychodzily ciemne na prawie czarnym tle,
        // a pod przyciskami nawigacji system dokladal jasna polprzezroczysta nakladke.
        val ciemnePaski = SystemBarStyle.dark(Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = ciemnePaski, navigationBarStyle = ciemnePaski)
        super.onCreate(savedInstanceState)

        // Na Androidzie 7 do 9 start z przycisku "Otworz" (sklep, instalator), a potem z ikony
        // na pulpicie, potrafi polozyc DRUGA MainActivity na pierwszej zamiast ja odslonic.
        // Kazda sklada wlasne ekrany z wlasnym obrazem danych, wiec ta odslonieta pozniej
        // pokazuje i zapisuje stary stan. Taka instancja nie jest korzeniem zadania i przyszla
        // zwyklym intentem startowym: konczymy ja, zanim cokolwiek zlozy, a system pokazuje te,
        // ktora juz dziala.
        if (drugaInstancjaZIkony()) {
            finish()
            return
        }

        // Jedno repozytorium na proces, a nie na instancje ekranu: patrz RepositoryHolder.
        val repo = RepositoryHolder.of(applicationContext)
        setContent {
            MaturaApp(repo = repo, onExitApp = { finish() })
        }
    }

    private fun drugaInstancjaZIkony(): Boolean =
        !isTaskRoot &&
            intent?.action == Intent.ACTION_MAIN &&
            intent?.hasCategory(Intent.CATEGORY_LAUNCHER) == true
}
