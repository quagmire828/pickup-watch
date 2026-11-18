package com.pickupwatch.scanner

/**
 * 取件码解析器 — 移植自取件码管家 (pain505/1) extract.mjs
 *
 * 支持码形：
 *   连字符：8-3-2015 / 5-5-9-13 / H-01485 / D4-7048 / 109-6-4006 / 2-3-05025
 *   字母数字：A88123
 *   纯数字：470812 / 72778
 *
 * 已验证：105条真实语料 + 22条负样本
 */
object PickupParser {

    // 关键词权重表（CONTRACT 3.2）
    private val KEYWORD_WEIGHTS = mapOf(
        "取件码" to 100, "取货码" to 100, "提货码" to 100,
        "取货号" to 100, "取件号" to 100, "取件编码" to 100, "取包裹码" to 100,
        "取件密码" to 90, "开门码" to 90, "开柜码" to 90,
        "包裹码" to 80, "快件码" to 80,
        "验证码" to 50
    )

    private val ALL_KEYWORDS = KEYWORD_WEIGHTS.keys.sortedByDescending { it.length }

    // 验证码只有当同文出现这些词之一时才算取件关键词
    private val VERIFY_HINTS = Regex("快递柜|驿站|取件|包裹")

    // 强取件特征（出现即可豁免消息级黑名单）
    private val STRONG_FEATURES = Regex("取件码|取货码|提货码|取件号|凭码|货架号|柜号|驿站|快递柜|丰巢|菜鸟|兔喜|妈妈驿站")

    // 消息级黑名单
    private val BLACKLIST_GENERIC = Regex("余额|话费|流量|积分|账单|消费|扣费|充值|还款|信用卡|贷款|中奖|领取优惠券|点击链接|退订|外卖|已送达|送达时间|骑手|点餐")
    private val BLACKLIST_CARRIER = Regex("955\\d{2}|10086|10010|10000|106\\d{8}")

    // 保留号（快递公司客服号）
    private val RESERVED_NUMBERS = setOf("11183", "11185", "95554", "95338", "4001", "10086", "10010", "10000", "12305", "95311")

    // 货架号类正向前缀
    private val SHELF_PREFIX = Regex("货架号|货架|柜号|柜子号|箱号|编号|格口")

    // 动作词（兜底评分用）
    private val ACTION_WORDS = Regex("领取|取出|凭|取件|取货|及时|尽快|速取|出示")

    // 快递公司识别
    private val COURIER_TABLE = listOf(
        Regex("丰巢|蜂巢") to "丰巢",
        Regex("菜鸟驿站|菜鸟") to "菜鸟驿站",
        Regex("速递易|中邮速递") to "速递易",
        Regex("京东|京喜") to "京东",
        Regex("顺丰|丰巢速运") to "顺丰",
        Regex("中通") to "中通",
        Regex("圆通") to "圆通",
        Regex("申通") to "申通",
        Regex("韵达") to "韵达",
        Regex("邮政|EMS|中邮") to "邮政",
        Regex("极兔") to "极兔",
        Regex("德邦") to "德邦",
        Regex("兔喜") to "兔喜",
        Regex("妈妈驿站") to "妈妈驿站",
        Regex("邻里驿站") to "邻里驿站"
    )

    private const val MAX_CODES_PER_TEXT = 6

    // ===== 数据模型 =====

    data class ParseResult(
        val code: String,
        val courier: String?,
        val station: String?,
        val note: String?,
        val rawText: String,
        val eventTime: Long,
        val expireAt: Long?
    )

    // ===== 主入口 =====

    fun parse(text: String, eventTime: Long = System.currentTimeMillis()): List<ParseResult> {
        val raw = stripWrappingQuotes(text)
        val full = toHalfWidth(raw)

        // 消息级黑名单
        if (isBlacklistedMessage(full)) return emptyList()

        val hits = findKeywordHits(full)
        val codes = findCodes(full, hits)

        if (codes.isEmpty()) {
            if (hits.isEmpty()) return emptyList()
            return emptyList() // 有取件码关键词但没提取到码 → 不生成条目，避免垃圾
        }

        val courier = detectCourier(full)
        val station = extractStation(full, courier)
        val note = extractNote(full)
        val expireAt = extractExpireAt(full, eventTime)

        return codes.map { code ->
            ParseResult(
                code = code,
                courier = courier,
                station = station,
                note = note,
                rawText = raw.take(300),
                eventTime = eventTime,
                expireAt = expireAt
            )
        }
    }

    // ===== 3.1 全角转半角 =====

    private fun toHalfWidth(s: String): String {
        return buildString(s.length) {
            for (ch in s) {
                val c = ch.code
                when {
                    c in 0xFF01..0xFF5E -> append((c - 0xFEE0).toChar())
                    c == 0x3000 -> append(' ')
                    else -> append(ch)
                }
            }
        }
    }

    private fun stripWrappingQuotes(s: String): String {
        var t = s.trim()
        var changed = true
        while (changed && t.length >= 2) {
            changed = false
            for ((open, close) in listOf('"' to '"', '\u201C' to '\u201D', '\u300C' to '\u300D')) {
                if (t.first() == open && t.last() == close) {
                    t = t.substring(1, t.length - 1).trim()
                    changed = true
                }
            }
        }
        return t
    }

    private fun normalize(s: String): String {
        return toHalfWidth(s)
            .replace(Regex("\\s+"), "")
            .lowercase()
            .replace(Regex("[()（）\\s.]"), "")
        // 注意：不去掉连字符 - ，保留取件码原始格式如 87-5-4195
    }

    // ===== 3.4.3 消息级黑名单 =====

    private fun isBlacklistedMessage(full: String): Boolean {
        if (STRONG_FEATURES.containsMatchIn(full)) return false
        if (Regex("验证码").containsMatchIn(full) && !Regex("取件").containsMatchIn(full)) return true
        if (BLACKLIST_GENERIC.containsMatchIn(full)) return true
        if (BLACKLIST_CARRIER.containsMatchIn(full)) return true
        return false
    }

    // ===== 关键词命中 =====

    private data class KeywordHit(val keyword: String, val weight: Int, val start: Int, val end: Int)

    private fun findKeywordHits(full: String): List<KeywordHit> {
        val verifyAllowed = VERIFY_HINTS.containsMatchIn(full)
        val hits = mutableListOf<KeywordHit>()

        for (kw in ALL_KEYWORDS) {
            if (kw == "验证码" && !verifyAllowed) continue
            var from = 0
            while (true) {
                val i = full.indexOf(kw, from)
                if (i < 0) break
                hits.add(KeywordHit(kw, KEYWORD_WEIGHTS[kw] ?: 0, i, i + kw.length))
                from = i + 1
            }
        }

        hits.sortBy { it.start }
        val out = mutableListOf<KeywordHit>()
        for (h in hits) {
            if (out.any { h.start < it.end && it.start < h.end }) continue
            out.add(h)
        }
        return out
    }

    // ===== 码形扫描 =====

    private data class Candidate(val value: String, val index: Int) {
        val end: Int get() = index + value.length
    }

    private fun isHyphenCode(token: String): Boolean {
        if (!token.contains('-')) return false
        val parts = token.split('-')
        if (parts.size < 2 || parts.size > 4) return false
        if (!parts.all { Regex("^[A-Za-z0-9]{1,6}$").matches(it) }) return false
        // 排除日期形状：2024-06-15
        if (parts.size == 3 && Regex("^\\d{4}$").matches(parts[0]) &&
            Regex("^\\d{1,2}$").matches(parts[1]) && Regex("^\\d{1,2}$").matches(parts[2])) return false
        return true
    }

    private fun isPlainCode(token: String): Boolean {
        if (!Regex("^[A-Za-z]?\\d{3,9}$").matches(token)) return false
        return token.replace(Regex("[^0-9]"), "").length <= 9
    }

    private fun scanCandidates(full: String): List<Candidate> {
        val out = mutableListOf<Candidate>()
        val tokenRe = Regex("(?<![0-9A-Za-z-])([A-Za-z0-9]{1,6}(?:-[A-Za-z0-9]{1,6}){1,3})|(?<![0-9A-Za-z])([A-Za-z]?\\d{3,9})")
        var lastEnd = -1

        for (match in tokenRe.findAll(full)) {
            val hyphen = match.groups[1]?.value
            val plain = match.groups[2]?.value
            val value = hyphen ?: plain ?: continue
            if (match.range.first < lastEnd) continue

            val ok = if (hyphen != null) isHyphenCode(value) else isPlainCode(value)
            if (!ok) continue

            out.add(Candidate(value, match.range.first))
            lastEnd = match.range.first + value.length
        }
        return out
    }

    // ===== 3.4 拒绝判定 =====

    private fun afterShelfPrefix(full: String, start: Int): Boolean {
        val before = full.substring(maxOf(0, start - 10), start)
        return Regex("(?:${SHELF_PREFIX.pattern})[：:]*$").containsMatchIn(before)
    }

    private fun isRejectedValue(value: String, start: Int, full: String): Boolean {
        val end = start + value.length
        val digits = value.replace(Regex("[^0-9]"), "")

        // 手机号形态
        val phoneRe = Regex("1[3-9]\\d{9}")
        for (m in phoneRe.findAll(full)) {
            if (start >= m.range.first && end <= m.range.first + m.value.length) return true
        }
        val maskedRe = Regex("1[3-9]\\d[*xX]{2,}\\d{2,4}")
        for (m in maskedRe.findAll(full)) {
            if (start >= m.range.first && end <= m.range.first + m.value.length) return true
        }
        if (Regex("1[3-9]$").containsMatchIn(full.substring(maxOf(0, start - 2), start))) return true

        // 与左右数字拼接是11位以上 → 手机号/长单号
        var runStart = start
        while (runStart > 0 && full[runStart - 1].isDigit()) runStart--
        var runEnd = end
        while (runEnd < full.length && full[runEnd].isDigit()) runEnd++
        if (runEnd - runStart >= 11) return true

        // 年份
        if (Regex("^20\\d{2}$").matches(digits) && end < full.length && Regex("^[年月日]").matches(full[end].toString())) return true

        // 敏感词周围
        val around = full.substring(maxOf(0, start - 3), minOf(full.length, end + 3))
        if (Regex("元|分钟|小时|天|kg|克|电话|手机|订单|运单|编号").containsMatchIn(around)) {
            if (!afterShelfPrefix(full, start)) return true
        }

        // 4位年份
        if (digits.length == 4 && Regex("^(19|20)").matches(digits)) return true

        // 保留号
        if (digits in RESERVED_NUMBERS) return true

        // 运单号
        if (distanceTo(full, start, end, listOf("运单号", "订单号", "快递单号", "快件号")) <= 6) return true

        // 太长
        if (Regex("^\\d{11,}$").matches(digits)) return true

        // 后接"号"
        if (end < full.length && full[end] == '号') return true

        // 前接*
        if (start > 0 && full[start - 1] == '*') return true

        // 时间格式 12:30
        if (end < full.length && full[end] == ':' && Regex("^\\d{1,2}$").matches(digits)) return true

        // ±8字内含"尾号"
        val win8 = full.substring(maxOf(0, start - 8), minOf(full.length, end + 8))
        if (Regex("尾号").containsMatchIn(win8)) return true

        // 格口/区号
        if (end < full.length && full[end] in listOf('格', '区')) return true
        if (start > 0 && full[start - 1] in listOf('格', '区')) return true

        return false
    }

    private fun distanceTo(full: String, index: Int, end: Int, words: List<String>): Int {
        var best = Int.MAX_VALUE
        for (w in words) {
            var from = 0
            while (true) {
                val i = full.indexOf(w, from)
                if (i < 0) break
                val d = if (i + w.length <= index) index - (i + w.length) else i - end
                if (d < best) best = d
                from = i + 1
            }
        }
        return best
    }

    // ===== 3.3 关键词锚点抓取 =====

    private val CLUSTER_SEP = Regex("^[,，、;；\\s]+$")

    private fun gapIsSeparatorOnly(full: String, gapStart: Int, gapEnd: Int): Boolean {
        if (gapEnd <= gapStart) return true
        if (gapEnd - gapStart > 4) return false
        val gap = full.substring(gapStart, gapEnd)
        if (Regex("\\d").containsMatchIn(gap)) return false
        return CLUSTER_SEP.containsMatchIn(gap)
    }

    private fun codesFromKeywordHit(full: String, hitStart: Int, hitEnd: Int, cands: List<Candidate>): List<String> {
        val limit = minOf(full.length, hitStart + 60)
        val after = mutableListOf<String>()
        var prevEnd: Int? = null

        for (c in cands) {
            if (c.index < hitEnd || c.index >= limit) continue
            if (prevEnd == null) {
                if (c.index - hitEnd > 3) continue
            } else if (!gapIsSeparatorOnly(full, prevEnd, c.index)) {
                continue
            }
            after.add(c.value)
            prevEnd = c.end
            if (after.size >= MAX_CODES_PER_TEXT) return after
        }
        if (after.isNotEmpty()) return after

        // 回头看关键词左侧紧邻的码
        val before = mutableListOf<String>()
        val winStart = maxOf(0, hitStart - 20)
        for (c in cands) {
            if (c.end > hitStart || c.index < winStart) continue
            if (hitStart - c.end > 2) continue
            before.add(c.value)
            if (before.size >= MAX_CODES_PER_TEXT) break
        }
        return before
    }

    // 3.3.1 "凭"锚点
    private fun codesFromAnchor(full: String, cands: List<Candidate>): List<String> {
        val out = mutableListOf<String>()
        // 请凭取件码 / 凭取件码 + 码形
        val re1 = Regex("请?凭(?:取件码|取货码|取件号|包裹码|快件码|提货码|码)[^0-9A-Za-z]{0,3}([A-Za-z0-9-]{3,40})")
        val re2 = Regex("请凭[^0-9A-Za-z]{0,3}([A-Za-z0-9-]{3,40})")
        val re3 = Regex("凭[^0-9A-Za-z]{0,3}([A-Za-z0-9-]{3,40})")

        for (re in listOf(re1, re2, re3)) {
            for (m in re.findAll(full)) {
                val raw = m.groups[1]?.value ?: continue
                val rawStart = m.range.first + m.value.lastIndexOf(raw)
                for (seg in raw.split(Regex("[,，、;；\\s]+"))) {
                    if (seg.isEmpty()) continue
                    if (!isHyphenCode(seg) && !isPlainCode(seg)) continue
                    val idx = rawStart + raw.indexOf(seg)
                    val cand = Candidate(seg, idx)
                    if (!isRejectedValue(cand.value, cand.index, full)) {
                        out.add(cand.value)
                    }
                }
            }
        }
        return out
    }

    // 3.4.5 兜底评分
    private fun fallbackScored(full: String, cands: List<Candidate>): List<String> {
        data class Scored(val cand: Candidate, val score: Int)

        val scored = mutableListOf<Scored>()
        for (c in cands) {
            if (isRejectedValue(c.value, c.index, full)) continue
            val window = full.substring(maxOf(0, c.index - 30), minOf(full.length, c.end + 30))
            var score = 0
            if (ALL_KEYWORDS.any { it in window }) score += 2
            if (ACTION_WORDS.containsMatchIn(window)) score += 2
            if (c.value.contains('-')) score += 1
            if (Regex("^\\d{6,9}$").matches(c.value)) score += 1
            if (STRONG_FEATURES.containsMatchIn(window)) score += 1
            if (score >= 3) scored.add(Scored(c, score))
        }
        scored.sortWith(compareByDescending<Scored> { it.score }.thenBy { it.cand.index })
        return scored.take(MAX_CODES_PER_TEXT).map { it.cand.value }
    }

    private fun findCodes(full: String, hits: List<KeywordHit>): List<String> {
        val cands = scanCandidates(full)
        val seen = mutableSetOf<String>()  // normalized, for dedup only
        val result = mutableListOf<String>()  // raw codes

        // 主路径1：关键词锚点
        for (h in hits) {
            for (code in codesFromKeywordHit(full, h.start, h.end, cands)) {
                val n = normalize(code)
                if (n !in seen) {
                    seen.add(n)
                    result.add(code)
                    if (result.size >= MAX_CODES_PER_TEXT) return result
                }
            }
        }

        // 主路径2："凭"锚点
        for (code in codesFromAnchor(full, cands)) {
            val n = normalize(code)
            if (n !in seen) {
                seen.add(n)
                result.add(code)
                if (result.size >= MAX_CODES_PER_TEXT) return result
            }
        }
        if (result.isNotEmpty()) return result

        // 兜底
        for (code in fallbackScored(full, cands)) {
            val n = normalize(code)
            if (n !in seen) {
                seen.add(n)
                result.add(code)
                if (result.size >= MAX_CODES_PER_TEXT) break
            }
        }
        return result
    }

    // ===== 3.7 快递公司 / 网点 =====

    private fun detectCourier(full: String): String? {
        for ((re, name) in COURIER_TABLE) {
            if (re.containsMatchIn(full)) return name
        }
        return null
    }

    private fun extractStation(full: String, courier: String?): String? {
        // 品牌(网点名)
        val m1 = Regex("(菜鸟驿站|丰巢|速递易|京东|顺丰)[(]([^)]{2,20})[)]").find(full)
        if (m1 != null) return trimPunct(m1.groups[2]!!.value)
        // 品牌...设施类型(网点名)
        val m1b = Regex("(菜鸟驿站|丰巢|速递易|京东|顺丰)[^)，,。；;]{0,6}[(]([^)]{2,20})[)]").find(full)
        if (m1b != null) return trimPunct(m1b.groups[2]!!.value)
        // 其他驿站(网点名)
        val m1c = Regex("(?:快递超市|驿站|代收点|自提柜|快递柜|便利店|门卫|物业)[(]([^)]{2,20})[)]").find(full)
        if (m1c != null) return trimPunct(m1c.groups[1]!!.value)
        // 到...取件（不只"件"，也匹配"取"后跟"尾号""包裹"等）
        val m2a = Regex("[到至](.{2,30}?)(?:取件|取尾号|取$|取\\s)").find(full)
        if (m2a != null) return trimPunct(m2a.groups[1]!!.value)
        // 到...取件（原版）
        val m2 = Regex("到(.{2,20}?)取件").find(full)
        if (m2 != null) return trimPunct(m2.groups[1]!!.value)
        // 至...取件
        val m2b = Regex("至(.{2,20}?)取件").find(full)
        if (m2b != null) return trimPunct(m2b.groups[1]!!.value)
        val m3 = Regex("请到(.{2,20}?)领取").find(full)
        if (m3 != null) return trimPunct(m3.groups[1]!!.value)
        return null
    }

    private fun trimPunct(s: String): String? {
        var t = s.replace(Regex("^[，,。、；;：:\\s]+"), "").replace(Regex("[，,。、；;：:\\s]+$"), "")
        if (t.contains(')')) t = t.substringBefore(')')
        else if (t.contains('(')) t = t.substringBefore('(')
        t = t.replace(Regex("[，,。、；;：:\\s]+$"), "")
        return t.ifEmpty { null }
    }

    private fun extractExpireAt(full: String, eventTime: Long): Long? {
        // X小时内取件
        val m1 = Regex("(\\d{1,3})\\s*小时内取件").find(full)
            ?: Regex("(\\d{1,3})小时内").find(full)
        if (m1 != null) {
            return eventTime + (m1.groups[1]!!.value.toInt()) * 3600_000L
        }
        // X月X日 前
        return null
    }

    private fun extractNote(full: String): String? {
        return null // 简化：不提取备注
    }
}