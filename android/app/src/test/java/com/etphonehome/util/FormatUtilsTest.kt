package com.etphonehome.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * formatBytes feeds the status screen and the ongoing notification
 * ("To desktop: …", "↓… ↑…").
 */
class FormatUtilsTest {

    @Test
    fun `small counts are plain bytes and units start at 1 KiB`() {
        assertEquals("0 B", FormatUtils.formatBytes(0))
        assertEquals("1023 B", FormatUtils.formatBytes(1023))
        assertEquals("1.00 KB", FormatUtils.formatBytes(1024))
        assertEquals("1.50 KB", FormatUtils.formatBytes(1536))
        assertEquals("5.00 MB", FormatUtils.formatBytes(5L * 1024 * 1024))
    }

    @Test
    fun `a count just under a unit boundary shows the next unit, not 1024`() {
        // Used to print "1024.00 KB" / "1024.00 MB".
        assertEquals("1.00 MB", FormatUtils.formatBytes(1024L * 1024 - 1))
        assertEquals("1.00 GB", FormatUtils.formatBytes(1024L * 1024 * 1024 - 1))
        // Still in KB while it prints below 1024.
        assertEquals("1023.99 KB", FormatUtils.formatBytes(1_048_570)) // 1023.994 KiB
    }

    @Test
    fun `petabyte-and-up counts format instead of throwing`() {
        // The unit table stopped at TB, so these threw
        // ArrayIndexOutOfBoundsException.
        assertEquals("1.00 PB", FormatUtils.formatBytes(1L shl 50))
        assertEquals("1.00 EB", FormatUtils.formatBytes(1L shl 60))
        assertEquals("8.00 EB", FormatUtils.formatBytes(Long.MAX_VALUE))
    }

    @Test
    fun `speed appends per second`() {
        assertEquals("2.00 KB/s", FormatUtils.formatSpeed(2048))
    }
}
