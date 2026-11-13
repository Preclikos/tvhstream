package cz.preclikos.tvhstream.ui

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Back from the home screen used to call exitProcess(0), which would also kill this
 * test process. It must now just finish the activity.
 */
@RunWith(AndroidJUnit4::class)
class BackExitTest {
    @Test
    fun backOnHome_finishesActivity_withoutKillingProcess() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Unconfigured app may start on Settings, where Back first returns to Channels.
        repeat(3) {
            if (scenario.state == Lifecycle.State.DESTROYED) return@repeat
            Espresso.pressBackUnconditionally()
            instrumentation.waitForIdleSync()
            Thread.sleep(500)
        }
        // Still running → the process survived; the activity is gone.
        assertEquals(Lifecycle.State.DESTROYED, scenario.state)
    }
}
