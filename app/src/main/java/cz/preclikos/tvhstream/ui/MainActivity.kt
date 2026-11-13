package cz.preclikos.tvhstream.ui

import androidx.activity.enableEdgeToEdge
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Lets Compose observe WindowInsets (IME visibility for TV text fields).
        // TVs have no system bars, so the layout is unchanged.
        enableEdgeToEdge()
        setContent {
            TVHStreamTheme {
                AppRoot()
            }
        }
    }
}
