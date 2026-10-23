package com.esrrhs.pingtunnel.client.util

import com.esrrhs.pingtunnel.client.pingtunnel.PerAppMode
import com.esrrhs.pingtunnel.client.pingtunnel.Profile
import com.esrrhs.pingtunnel.client.pingtunnel.PingtunnelConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileShareTest {

    private fun sample() = Profile(
        id = "id-1",
        name = "home",
        config = PingtunnelConfig(
            serverHost = "example.com",
            key = 123456L,
            encryptMode = "aes256",
            encryptKey = "passphrase",
            enableIpv6 = false,
        ),
        perAppMode = PerAppMode.DISALLOWED,
        perAppPackages = listOf("com.example.app"),
        bypassLan = true,
        pingMs = 42,
    )

    @Test
    fun roundTrip_preservesAllFields() {
        val encoded = ProfileShare.encode(sample())
        val decoded = ProfileShare.decode(encoded)
        assertEquals(sample(), decoded)
    }

    @Test
    fun encoded_hasSchemePrefix() {
        assert(ProfileShare.encode(sample()).startsWith(ProfileShare.SCHEME))
    }

    @Test
    fun decode_withoutScheme_returnsNull() {
        assertNull(ProfileShare.decode("not-scheme-data"))
    }

    @Test
    fun decode_corruptedBase64_returnsNull() {
        assertNull(ProfileShare.decode(ProfileShare.SCHEME + "!!!not-base64!!"))
    }
}
