package com.ulap.data.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleOAuthTokenVerifierTest {

    @Test
    fun `picker scope allows picker access`() {
        assertTrue(
            scopesAllowPhotosPicker(
                "https://www.googleapis.com/auth/photospicker.mediaitems.readonly",
            ),
        )
    }

    @Test
    fun `picker scope alongside email allows picker access`() {
        assertTrue(
            scopesAllowPhotosPicker(
                "email https://www.googleapis.com/auth/photospicker.mediaitems.readonly openid",
            ),
        )
    }

    @Test
    fun `old library readonly scope does not allow picker access`() {
        assertFalse(
            scopesAllowPhotosPicker(
                "https://www.googleapis.com/auth/photoslibrary.readonly",
            ),
        )
    }

    @Test
    fun `appcreateddata only does not allow picker access`() {
        assertFalse(
            scopesAllowPhotosPicker(
                "https://www.googleapis.com/auth/photoslibrary.readonly.appcreateddata " +
                    "https://www.googleapis.com/auth/photoslibrary.edit.appcreateddata",
            ),
        )
    }

    @Test
    fun `empty scope string does not allow picker access`() {
        assertFalse(scopesAllowPhotosPicker(""))
    }

    @Test
    fun `blank whitespace only does not allow picker access`() {
        assertFalse(scopesAllowPhotosPicker("   "))
    }
}
