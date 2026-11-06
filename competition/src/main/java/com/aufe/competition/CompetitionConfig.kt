package com.aufe.competition

/**
 * 学科竞赛（创新创业管理平台）接入配置。
 *
 * 该平台部署在校内网 211.86.241.245，校外访问走学校 WebVPN 的域名改写：
 *   原地址 http://211.86.241.245/xsXmMain.aspx
 *   改写后 http://211-86-241-245.vpn2.aufe.edu.cn:8118/xsXmMain.aspx
 *
 * 改动点集中在这里，换校区 / 换端口只改常量。
 */
object CompetitionConfig {

    /** VPN 改写后的主机名（校内直连时也可换成 211.86.241.245） */
    const val HOST: String = "211-86-241-245.vpn2.aufe.edu.cn"

    /** VPN 映射端口 */
    const val PORT: Int = 8118

    /** 入口页面：四个功能标签都在这一个页面里靠 __doPostBack 切换 */
    const val ENTRY_PATH: String = "xsXmMain.aspx"

    /** 下载文件在公共下载目录下的子目录名 */
    const val DOWNLOAD_DIR: String = "学科竞赛"

    /** 入口完整地址 */
    fun entryUrl(): String = "http://$HOST:$PORT/$ENTRY_PATH"

    /** 视为"站内"的域名后缀：保留在 WebView 内，不跳外部浏览器 */
    private val INTERNAL_DOMAINS = listOf("aufe.edu.cn")

    /** 视为"站内"的 IP 前缀（校内直连场景） */
    private val INTERNAL_IP_PREFIXES = listOf("211.86.241.")

    /**
     * 判断是否站内地址。
     *
     * 必须把 vpn.aufe.edu.cn 也算进来，否则 VPN 会话过期时页面会跳到
     * 登录门户，被误判为外链踢到系统浏览器，用户就再也登不进来了。
     */
    fun isInternalHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.lowercase()
        if (INTERNAL_IP_PREFIXES.any { h.startsWith(it) }) return true
        return INTERNAL_DOMAINS.any { h == it || h.endsWith(".$it") }
    }
}
