package com.custodysim.app.ui.chat

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatTimeTest {
    @Test fun followsDeviceTimeZoneChangesWithoutRestartingTheProcess() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            assertEquals("2026-10-04 12:00", formatChatTime("2026-10-04T12:00:00Z"))
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            assertEquals("2026-10-04 20:00", formatChatTime("2026-10-04T12:00:00Z"))
        } finally { TimeZone.setDefault(original) }
    }
}
