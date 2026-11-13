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
        instrumentation.waitForIdleSync()
        Thread.sleep(1_000) // let the home screen settle and take focus
        // ONE press must exit (Compose's focus-exit used to swallow the first one).
        Espresso.pressBackUnconditionally()
        instrumentation.waitForIdleSync()
        Thread.sleep(500)
        // Still running → the process survived; the activity is gone.
        assertEquals(Lifecycle.State.DESTROYED, scenario.state)
    }
}
