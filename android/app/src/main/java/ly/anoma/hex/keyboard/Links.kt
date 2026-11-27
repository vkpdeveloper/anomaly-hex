package ly.anoma.hex.keyboard

/** Web links in copied text: where they point ([host]) and the same links without their tracking
 * ([clean]). Pure, so it is tested off the device. */
internal object Links {
    private val LINK = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    /** Closing punctuation that ends a sentence rather than the link. */
    private const val TRAILING = ".,;:!?)]}'\""

    /** Tracking parameters any site may carry: ad and campaign tags, click IDs, mail tracking. */
    private val ANYWHERE = setOf(
        "fbclid", "gclid", "gclsrc", "dclid", "gbraid", "wbraid", "msclkid", "yclid", "twclid", "ttclid",
        "li_fat_id", "mc_cid", "mc_eid", "igshid", "igsh", "_hsenc", "_hsmi", "mkt_tok", "vero_id", "oly_enc_id",
        "oly_anon_id", "rb_clickid", "s_cid", "wickedid", "srsltid", "_gl", "ref_src", "ref_url",
    )
    private val ANYWHERE_PREFIXES = listOf("utm_", "pk_", "mtm_", "hsa_")

    /** Parameters that only track on these sites, where elsewhere they may mean something. */
    private val BY_SITE = listOf(
        setOf("youtube.com", "youtu.be", "music.youtube.com") to setOf("si", "pp", "feature"),
        setOf("spotify.com", "open.spotify.com") to setOf("si", "context", "nd"),
        setOf("x.com", "twitter.com") to setOf("s", "t"),
        setOf("instagram.com", "threads.net", "threads.com") to setOf("igsh", "igshid", "img_index"),
        setOf("tiktok.com") to setOf("_r", "_t", "is_from_webapp", "sender_device", "is_copy_url", "u_code",
            "share_app_id", "share_item_id", "share_link_id", "social_share_type", "tt_from"),
        setOf("linkedin.com") to setOf("trk", "trackingId", "lipi", "midToken", "midSig", "trkEmail", "eid", "rcm"),
        setOf("reddit.com") to setOf("share_id", "rdt"),
        setOf("facebook.com") to setOf("mibextid", "rdid", "sfnsn"),
    )
    /** Amazon's referral tags, in the query and as a `/ref=` path segment. */
    private val AMAZON = setOf("ref", "ref_", "pd_rd_w", "pd_rd_wg", "pd_rd_r", "pd_rd_i", "pf_rd_p", "pf_rd_r",
        "pf_rd_s", "pf_rd_t", "pf_rd_i", "pf_rd_m", "content-id", "linkCode", "linkId", "tag", "psc", "_encoding",
        "crid", "sprefix", "dib", "dib_tag", "sp_csd", "smid", "spLa")

    /** The links in [text], with sentence punctuation after them left out. */
    fun find(text: String): List<String> = LINK.findAll(text).map { it.value.trimEnd { c -> c in TRAILING } }
        .filter { host(it) != null }.toList()

    /** The single link [text] is, besides spaces around it; null when it is anything else. */
    fun only(text: String): String? = text.trim().takeIf { LINK.matchEntire(it) != null }?.let(::find)?.singleOrNull()

    /** How a link reads on a chip or card: from its site's name, since the scheme says nothing. */
    fun display(link: String) = link.substringAfter("://").removePrefix("www.")

    /** The link's host, lowercased and without `www.`, or null when it has none. */
    fun host(link: String): String? {
        val rest = link.substringAfter("://", "")
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
        val host = authority.substringBefore(':').lowercase().removePrefix("www.").removePrefix("m.")
        return host.takeIf { it.contains('.') && it.all { c -> c.isLetterOrDigit() || c == '.' || c == '-' } }
    }

    /** [text] with every link in it cleaned: everything else is left as it was. */
    fun clean(text: String): String {
        var out = text
        for (link in find(text).distinct()) {
            val cleaned = cleanLink(link)
            if (cleaned != link) out = out.replace(link, cleaned)
        }
        return out
    }

    /** One link without its tracking parameters, the rest of its query untouched and in order. */
    fun cleanLink(link: String): String {
        val host = host(link) ?: return link
        val hash = link.indexOf('#')
        val fragment = if (hash >= 0) link.substring(hash) else ""
        val beforeFragment = if (hash >= 0) link.substring(0, hash) else link
        var path = beforeFragment.substringBefore('?')
        val query = beforeFragment.substringAfter('?', "")
        val amazon = isSite(host, "amazon") || host == "amzn.to"
        val local = BY_SITE.filter { (sites, _) -> sites.any { isOn(host, it) } }.flatMap { it.second }.toSet()
        if (amazon) path = path.replace(Regex("/ref=[^/?#]*$"), "")
        val kept = query.split('&').filter { it.isNotEmpty() }.filterNot { pair ->
            val key = pair.substringBefore('=')
            key in ANYWHERE || ANYWHERE_PREFIXES.any { key.startsWith(it, ignoreCase = true) } ||
                key in local || (amazon && key in AMAZON)
        }
        return path + (if (kept.isEmpty()) "" else "?" + kept.joinToString("&")) + fragment
    }

    private fun isOn(host: String, site: String) = host == site || host.endsWith(".$site")
    /** Any of a brand's country domains: amazon.com, amazon.co.uk, smile.amazon.de. */
    private fun isSite(host: String, brand: String) = host.split('.').contains(brand)
}
