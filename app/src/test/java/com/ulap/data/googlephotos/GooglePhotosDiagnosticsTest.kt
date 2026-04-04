package com.ulap.data.googlephotos

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class GooglePhotosDiagnosticsTest {

    @Test
    fun `plain exception omits HTTP section`() {
        val s = formatGooglePhotosDiagnostics(IllegalStateException("no account"))
        assertTrue(s.contains("IllegalStateException"))
        assertTrue(s.contains("no account"))
    }

    @Test
    fun `httpStatusCodeOrNull reads nested HttpException`() {
        val body = """{}""".toResponseBody("application/json".toMediaType())
        val http = HttpException(Response.error<String>(401, body))
        val wrapped = RuntimeException("wrap", http)
        assertEquals(401, wrapped.httpStatusCodeOrNull())
    }

    @Test
    fun `HttpException includes status and body snippet`() {
        val body = """{"error":{"code":403,"message":"Photos Library API has not been used"}}"""
            .toResponseBody("application/json; charset=UTF-8".toMediaType())
        val retrofitResponse = Response.error<String>(403, body)
        val ex = HttpException(retrofitResponse)
        val s = formatGooglePhotosDiagnostics(ex)
        assertTrue(s, s.contains("HTTP 403"))
        assertTrue(s, s.contains("Photos Library API"))
    }
}
