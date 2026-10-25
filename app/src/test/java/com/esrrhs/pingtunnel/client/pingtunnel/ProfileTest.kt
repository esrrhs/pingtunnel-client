package com.esrrhs.pingtunnel.client.pingtunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileTest {

    private fun validConfig() = PingtunnelConfig(
        serverHost = "example.com",
        password = "secret-pass",
    )

    @Test
    fun blankName_isRejected() {
        val profile = Profile(name = "", config = validConfig())
        assertEquals(ValidationError.NAME_REQUIRED, profile.validate())
    }

    @Test
    fun validNameAndConfig_pass() {
        val profile = Profile(name = "home", config = validConfig())
        assertNull(profile.validate())
    }

    @Test
    fun invalidConfig_surfacesConfigError() {
        val profile = Profile(name = "home", config = validConfig().copy(password = "short"))
        assertEquals(ValidationError.PASSWORD_TOO_SHORT, profile.validate())
    }

    @Test
    fun allowedModeWithoutApps_isRejected() {
        val profile = Profile(
            name = "home",
            config = validConfig(),
            perAppMode = PerAppMode.ALLOWED,
        )
        assertEquals(ValidationError.APPS_REQUIRED, profile.validate())
    }

    @Test
    fun newProfile_hasUniqueId() {
        val a = Profile()
        val b = Profile()
        assert(a.id != b.id)
    }
}
