package cz.preclikos.tvhstream.player

import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cz.preclikos.tvhstream.htsp.HtspService
import cz.preclikos.tvhstream.settings.PlayerSettingsStore
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerSessionTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun newSession() = PlayerSession(
        htsp = HtspService(ioDispatcher = Dispatchers.IO),
        playerSettingsStore = PlayerSettingsStore(context),
    )

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    /** Crashlytics: "lateinit property dataSourceFactory has not been initialized". */
    @Test
    fun stopAndRelease_beforeAnyPlayback_doNotCrash() {
        val session = newSession()
        onMain {
            session.stop()
            session.release()
        }
        onMain {
            session.getOrCreatePlayer(context)
            session.stop()
            session.release()
        }
        instrumentation.waitForIdleSync()
    }

    @Test
    fun player_isExposedThroughMediaSession_withPlayPauseCommands() {
        val session = newSession()
        onMain {
            session.getOrCreatePlayer(context)
            val sessionPlayer = session.sessionPlayer
            assertNotNull(sessionPlayer)
            sessionPlayer!!
            assertTrue(sessionPlayer.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
            assertTrue(sessionPlayer.isCommandAvailable(Player.COMMAND_STOP))
            session.release()
            assertNull(session.sessionPlayer)
        }
    }

    /** A second session with the same id would throw if release() didn't free it. */
    @Test
    fun releaseThenRecreate_buildsNewSession() {
        val session = newSession()
        onMain {
            session.getOrCreatePlayer(context)
            session.release()
            session.getOrCreatePlayer(context)
            assertNotNull(session.sessionPlayer)
            session.release()
        }
    }

    @Test
    fun pauseThroughSession_pausesPlayer() {
        val session = newSession()
        onMain {
            val exo = session.getOrCreatePlayer(context)
            val sessionPlayer = session.sessionPlayer!!
            sessionPlayer.play()
            assertTrue(exo.playWhenReady)
            sessionPlayer.pause()
            assertEquals(false, exo.playWhenReady)
            // Short pause: resumes from the buffer, no re-tune.
            sessionPlayer.play()
            assertTrue(exo.playWhenReady)
            session.release()
        }
    }

    @Test
    fun nowPlayingMetadata_isNotSetWithoutMediaItem() {
        val session = newSession()
        onMain {
            val exo = session.getOrCreatePlayer(context)
            session.updateNowPlaying("ČT1 HD", "Události")
            assertEquals(MediaMetadata.EMPTY, exo.mediaMetadata)
            session.release()
        }
    }
}
