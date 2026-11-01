package localserver.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 内网地址探测的打分逻辑。
 *
 * 不打桩真实网卡：本机装了 WSL / Hyper-V / 代理 TUN 时，网卡清单因机器而异，
 * 断言"必须选哪个 IP"会在别的机器上失败。这里只喂固定的候选列表，验证排序规则。
 */
class LocalIpTest {

    private fun cand(ip: String, name: String = "Intel Wi-Fi") = LocalIp.Candidate(ip, name)

    @Test
    fun `home lan wins over wsl and tun ranges`() {
        val ip = LocalIp.pick(
            listOf(
                cand("172.29.112.1", "Hyper-V Virtual Ethernet Adapter"),
                cand("198.18.0.1", "Meta Tunnel"),
                cand("192.168.3.32", "Intel(R) Wi-Fi 6E AX211 160MHz"),
                cand("169.254.181.205", "以太网"),
            )
        )
        assertEquals("192.168.3.32", ip)
    }

    @Test
    fun `virtual interface name is punished even when range looks fine`() {
        // 虚拟网卡也可能拿到 192.168.x，名字参与打分才能压下去
        val ip = LocalIp.pick(
            listOf(
                cand("192.168.56.1", "VirtualBox Host-Only Ethernet Adapter"),
                cand("192.168.3.32", "Intel(R) Wi-Fi 6E AX211 160MHz"),
            )
        )
        assertEquals("192.168.3.32", ip)
    }

    @Test
    fun `tun range loses to 10 dot network`() {
        val ip = LocalIp.pick(listOf(cand("198.18.0.1", "Mihomo"), cand("10.8.0.5", "eth0")))
        assertEquals("10.8.0.5", ip)
    }

    @Test
    fun `public address loses to private one`() {
        val ip = LocalIp.pick(listOf(cand("203.0.113.9", "WLAN"), cand("192.168.1.7", "WLAN")))
        assertEquals("192.168.1.7", ip)
    }

    @Test
    fun `empty candidates fall back to loopback`() {
        assertEquals("127.0.0.1", LocalIp.pick(emptyList()))
    }

    @Test
    fun `isPrivate understands rfc1918 boundaries`() {
        assertTrue(LocalIp.isPrivate("10.0.0.1"))
        assertTrue(LocalIp.isPrivate("192.168.3.32"))
        assertTrue(LocalIp.isPrivate("172.16.0.1"))
        assertTrue(LocalIp.isPrivate("172.31.255.254"))
        assertFalse(LocalIp.isPrivate("172.15.0.1"))
        assertFalse(LocalIp.isPrivate("172.32.0.1"))
        assertFalse(LocalIp.isPrivate("198.18.0.1"))
        assertFalse(LocalIp.isPrivate("8.8.8.8"))
        assertFalse(LocalIp.isPrivate("not-an-ip"))
    }

    @Test
    fun `current returns something usable`() {
        // 真机环境下至少能给出一个非空地址（没网卡时是 127.0.0.1）
        val ip = LocalIp.current()
        assertTrue(ip.isNotEmpty())
        assertTrue(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(ip), "unexpected ip: $ip")
    }

    @Test
    fun `system property overrides detection`() {
        val key = "localserver.ip"
        val old = System.getProperty(key)
        try {
            System.setProperty(key, "192.168.99.99")
            assertEquals("192.168.99.99", LocalIp.current())
        } finally {
            if (old == null) System.clearProperty(key) else System.setProperty(key, old)
        }
    }
}
