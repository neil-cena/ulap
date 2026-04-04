package com.ulap.ui.gallery

import com.ulap.data.local.entity.ChunkMetadataEntity
import com.ulap.data.local.entity.ChunkStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger

/**
 * RED contract: [tdd/task_1_red_spec.md] — at most one concurrent entry into [ChunkPrefetchEngine]'s
 * urlResolver (xb-brt-forge).
 */
class ChunkPrefetchEngineUrlResolverSerializationTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun chunk(index: Int) = ChunkMetadataEntity(
        id = index.toLong(),
        mediaItemId = "media-1",
        chunkIndex = index,
        telegramFileId = "tg-file-$index",
        telegramMessageId = 1000L + index,
        byteOffset = index.toLong() * 1024,
        byteLength = 1024,
        status = ChunkStatus.UPLOADED,
    )

    private fun meta(count: Int) = (0 until count).map { chunk(it) }

    private fun successClient(): OkHttpClient {
        val client = mock<OkHttpClient>()
        val call = mock<Call>()
        val response = mock<Response>()
        val body = mock<ResponseBody>()
        whenever(client.newCall(any())).thenReturn(call)
        whenever(call.execute()).thenReturn(response)
        whenever(response.isSuccessful).thenReturn(true)
        whenever(response.code).thenReturn(200)
        whenever(response.body).thenReturn(body)
        whenever(body.bytes()).thenReturn(byteArrayOf())
        whenever(body.byteStream()).thenReturn(byteArrayOf().inputStream())
        whenever(body.contentLength()).thenReturn(0L)
        whenever(body.source()).thenReturn(Buffer())
        return client
    }

    @Test(timeout = 60_000)
    fun urlResolver_neverExceedsSingleConcurrentInvocation_xb_brt_forge_task_1() = runBlocking {
        val depth = AtomicInteger(0)
        val maxDepth = AtomicInteger(0)
        val engine = ChunkPrefetchEngine(
            chunkDir = temporaryFolder.root,
            chunkMeta = meta(5),
            urlResolver = { index ->
                val d = depth.incrementAndGet()
                maxDepth.updateAndGet { old -> maxOf(old, d) }
                delay(30)
                depth.decrementAndGet()
                "https://cdn4.cdn-telegram.org/file/chunk-$index.dat"
            },
            okHttpClient = successClient(),
            concurrency = 5,
            windowSize = 5,
        )
        engine.setPrefetchOrigin(0)
        for (i in 0 until 5) {
            try {
                engine.waitForChunk(i)
            } catch (_: Exception) {
            }
        }
        engine.release()
        assertTrue(
            "xb-brt-forge tdd/task_1_red_spec.md: max concurrent urlResolver depth was ${maxDepth.get()}",
            maxDepth.get() <= 1,
        )
    }
}
