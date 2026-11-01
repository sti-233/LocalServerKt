package localserver.utils

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 本机内网地址探测。
 *
 * 背景：`serverIp` 原先在 `Server.kt` 与 `resources/config.js` 里各写死一份，换网络
 * （家里 / 公司 / 手机热点）就得手工改两处，忘了就出现 `/vnc` 跳到旧地址、AI 分享链接打不开。
 *
 * 探测思路：枚举所有网卡的 IPv4，按「像不像局域网地址」打分取最高分。
 * 单靠 [NetworkInterface.isVirtual] 不够 —— Windows 上 Hyper-V 网卡报 `false`，
 * 代理软件的 TUN 网卡（Mihomo / Clash）也报 `false`，所以名字与网段要一起判。
 */
object LocalIp {
    /** 覆盖手段：`-Dlocalserver.ip=192.168.3.32` 或环境变量 `LOCALSERVER_IP`（自动探测不准时用） */
    private const val OVERRIDE_PROP = "localserver.ip"
    private const val OVERRIDE_ENV = "LOCALSERVER_IP"

    /** 网卡名里出现这些词就当虚拟网卡重罚（Windows 下 Java 的 isVirtual() 靠不住） */
    private val VIRTUAL_HINTS = listOf(
        "hyper-v", "vethernet", "wsl", "virtual", "vmware", "virtualbox", "docker",
        "tailscale", "zerotier", "tunnel", "mihomo", "clash", "wintun", "tap-", "tun",
    )

    /**
     * 结果缓存 30 秒：DHCP 换了地址能自己跟上，又不必每次请求都扫一遍网卡
     * （本机有 50+ 个接口，扫一次十几毫秒）。
     */
    private const val CACHE_MS = 30_000L

    private var cachedIp: String = ""
    private var cachedAt: Long = 0L

    /** 当前对外宣告的内网地址：先看覆盖配置，再取缓存，最后重新探测 */
    fun current(): String {
        val override = System.getProperty(OVERRIDE_PROP)?.trim().orEmpty()
            .ifEmpty { System.getenv(OVERRIDE_ENV)?.trim().orEmpty() }
        if (override.isNotEmpty()) return override

        val now = System.currentTimeMillis()
        if (cachedIp.isNotEmpty() && now - cachedAt < CACHE_MS) return cachedIp
        val ip = detect()
        cachedIp = ip
        cachedAt = now
        return ip
    }

    /** 立即探测一次（不读缓存、不写缓存） */
    fun detect(): String = pick(scan())

    /** 候选地址：地址本身 + 所属网卡名（名字参与打分，用来识别虚拟网卡） */
    internal data class Candidate(val ip: String, val interfaceName: String)

    /** 枚举本机所有可用的 IPv4 候选地址 */
    internal fun scan(): List<Candidate> {
        val out = mutableListOf<Candidate>()
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()
        } catch (_: Exception) {
            return out
        }
        while (interfaces.hasMoreElements()) {
            val ni = interfaces.nextElement()
            try {
                if (!ni.isUp || ni.isLoopback) continue
                // WAN Miniport / VPN 的点对点网卡拿不到可路由的局域网地址
                if (ni.isPointToPoint) continue
                val name = listOfNotNull(ni.displayName, ni.name).joinToString(" ")
                for (ia in ni.interfaceAddresses) {
                    val addr = ia.address as? Inet4Address ?: continue
                    if (addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isMulticastAddress) continue
                    val ip = addr.hostAddress ?: continue
                    out += Candidate(ip, name)
                }
            } catch (_: Exception) {
                // 个别虚拟网卡在枚举地址时会抛（驱动异常），跳过它继续
            }
        }
        return out
    }

    /** 打分取最高的一个；一个都没有时退回回环地址 */
    internal fun pick(candidates: List<Candidate>): String {
        if (candidates.isEmpty()) return "127.0.0.1"
        return candidates.maxByOrNull { score(it) }!!.ip
    }

    internal fun score(c: Candidate): Int {
        val ip = c.ip
        var s = 0
        // 内网地址优先；公网地址多半是代理 / VPN 出口，不适合当局域网访问地址
        s += if (isPrivate(ip)) 100 else -60
        s += when {
            ip.startsWith("192.168.") -> 30   // 家用 / 小办公最常见
            ip.startsWith("10.") -> 15
            ip.startsWith("172.") -> -20      // 172.16/12 常被 WSL、Docker 占用
            else -> 0
        }
        // RFC2544 基准测试段：代理软件（Mihomo / Clash）的 TUN 网卡爱用，绝不能当服务器地址
        if (ip.startsWith("198.18.") || ip.startsWith("198.19.")) s -= 200
        val name = c.interfaceName.lowercase()
        if (VIRTUAL_HINTS.any { name.contains(it) }) s -= 200
        return s
    }

    /** 是否 RFC1918 私有地址（按字符串判，便于单独测试） */
    internal fun isPrivate(ip: String): Boolean {
        if (ip.startsWith("10.") || ip.startsWith("192.168.")) return true
        if (!ip.startsWith("172.")) return false
        val second = ip.split('.').getOrNull(1)?.toIntOrNull() ?: return false
        return second in 16..31
    }
}
