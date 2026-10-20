package com.etphonehome.pro

import com.etphonehome.session.ClientIdentity
import org.junit.Assert.assertEquals
import org.junit.Test

class PairedDevicesTest {
    @Test
    fun seenAddsUpdatesAndOrdersByLastUse() {
        var l = PairedDevices.seen(emptyList(), ClientIdentity("a", "alpha", false), 100)
        l = PairedDevices.seen(l, ClientIdentity("b", "beta", true), 200)
        l = PairedDevices.seen(l, ClientIdentity("a", "alpha-renamed", false), 300)
        assertEquals(listOf("a", "b"), l.map { it.id })
        assertEquals("alpha-renamed", l[0].name)
        assertEquals(100L, l[0].firstSeenMs)
        assertEquals(300L, l[0].lastSeenMs)
    }

    @Test
    fun encodeDecodeRoundTripKeepsTabsAndNewlinesInNames() {
        val l = listOf(PairedDevice("x\ty", "name\nwith\\stuff", true, 1, 2), PairedDevice("z", "plain", false, 3, 4))
        assertEquals(l, PairedDevices.decode(PairedDevices.encode(l)))
        assertEquals(emptyList<PairedDevice>(), PairedDevices.decode(null))
        assertEquals(emptyList<PairedDevice>(), PairedDevices.decode("garbage\tline"))
    }

    @Test
    fun keepsAtMostMax() {
        var l = emptyList<PairedDevice>()
        for (i in 0 until PairedDevices.MAX + 5) l = PairedDevices.seen(l, ClientIdentity("id$i", "n", false), i.toLong())
        assertEquals(PairedDevices.MAX, l.size)
        assertEquals("id${PairedDevices.MAX + 4}", l.first().id)
    }
}
