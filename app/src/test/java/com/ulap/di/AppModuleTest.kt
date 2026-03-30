package com.ulap.di

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.ContinuationInterceptor

@OptIn(ExperimentalCoroutinesApi::class)
class AppModuleTest {

    @Test
    fun `provideApplicationScope returns scope whose context includes Dispatchers IO`() {
        val scope = AppModule().provideApplicationScope()
        try {
            val interceptor = scope.coroutineContext[ContinuationInterceptor.Key]
            assertTrue(
                "Expected scope context to contain Dispatchers.IO but was: $interceptor",
                interceptor === Dispatchers.IO
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `child coroutine failure does not cancel sibling coroutine`() = runTest {
        val scope = AppModule().provideApplicationScope()
        try {
            var siblingCompleted = false
            val exceptionCaught = java.util.concurrent.atomic.AtomicBoolean(false)
            val handler = CoroutineExceptionHandler { _, _ -> exceptionCaught.set(true) }

            val failing = scope.launch(handler) {
                throw RuntimeException("deliberate child failure")
            }

            val sibling = scope.launch {
                delay(100)
                siblingCompleted = true
            }

            failing.join()
            sibling.join()

            assertTrue("Failing child should have thrown", exceptionCaught.get())
            assertTrue(
                "Sibling coroutine should have completed despite the failing child, " +
                    "which requires SupervisorJob in the scope",
                siblingCompleted
            )

            assertTrue(
                "Parent scope should still be active after one child fails",
                scope.isActive
            )
        } finally {
            scope.cancel()
        }
    }
}
