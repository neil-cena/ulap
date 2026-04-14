package com.ulap.sync

import android.content.Context
import android.content.Intent
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Bug Reproduction Test — BUG-004b: MediaObserverService.start() crashes on Android 12+
 * when the app restarts into a background state (e.g. after an OOM kill).
 *
 * ## Defect
 *
 * Context.startService() throws BackgroundServiceStartNotAllowedException (a subclass of
 * IllegalStateException) on Android 12+ when the caller is in background state. This propagates
 * as a RuntimeException that wraps in "Unable to start activity ComponentInfo{…}", crashing
 * the app a second time on relaunch.
 *
 * ## Required contract
 *
 * MediaObserverService.start() must silently swallow any Exception from startService() and
 * log a warning instead of propagating the crash to the caller.
 *
 * Deterministic: no real Android runtime required — uses a plain Mockito mock.
 */
class MediaObserverServiceStartGuardTest {

    @Test
    fun start_contextThrowsOnStartService_doesNotCrash() {
        val context = mock<Context>()
        whenever(context.startService(any<Intent>()))
            .thenThrow(IllegalStateException("Not allowed to start service: app is in background"))

        // Must NOT throw. Before the fix, the IllegalStateException propagates uncaught,
        // crashing the activity. After the fix, it is caught and logged.
        MediaObserverService.start(context)
    }
}
