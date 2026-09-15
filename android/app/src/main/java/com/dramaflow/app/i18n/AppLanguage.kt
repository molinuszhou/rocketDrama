package com.dramaflow.app.i18n

import android.content.Context
import java.util.Locale

/**
 * 白包 1.6.2 的轻量级应用内语言层。所有服务端内容标题/简介保留其原始内容语言；
 * 导航、广告、奖励、钱包与播放器控制等产品 UI 则按用户选择的界面语言显示。
 */
object AppLanguage {
    private const val PREFERENCES = "joyedrama_language"
    private const val KEY_LANGUAGE = "language_tag"

    data class Option(val tag: String, val label: String)

    val options = listOf(
        Option("en", "English"),
        Option("pt-BR", "Português"),
        Option("es", "Español"),
        Option("id", "Bahasa Indonesia"),
        Option("zh-CN", "简体中文"),
        Option("zh-TW", "繁體中文"),
    )

    fun tag(context: Context): String = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getString(KEY_LANGUAGE, defaultTag()) ?: defaultTag()

    fun save(context: Context, tag: String) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString(KEY_LANGUAGE, tag).apply()
    }

    fun label(context: Context): String = options.firstOrNull { it.tag == tag(context) }?.label ?: "English"

    fun text(context: Context, key: String, vararg values: Any): String {
        val raw = dictionary[key]?.get(tag(context))
            ?: dictionary[key]?.get("en")
            ?: key
        return if (values.isEmpty()) raw else String.format(localeFor(tag(context)), raw, *values)
    }

    private fun localeFor(tag: String): Locale = Locale.forLanguageTag(tag)

    private fun defaultTag(): String = when (Locale.getDefault().toLanguageTag()) {
        "zh-TW", "zh-HK", "zh-MO" -> "zh-TW"
        else -> when (Locale.getDefault().language) {
            "zh" -> "zh-CN"
            "pt" -> "pt-BR"
            "es" -> "es"
            "id" -> "id"
            else -> "en"
        }
    }

    private fun all(en: String, pt: String, es: String, id: String, zhCn: String, zhTw: String) = mapOf(
        "en" to en, "pt-BR" to pt, "es" to es, "id" to id, "zh-CN" to zhCn, "zh-TW" to zhTw,
    )

    private val dictionary = mapOf(
        "home" to all("Home", "Início", "Inicio", "Beranda", "首页", "首頁"),
        "discover" to all("Discover", "Descobrir", "Descubrir", "Jelajahi", "发现", "發現"),
        "rewards" to all("Rewards", "Recompensas", "Recompensas", "Hadiah", "奖励", "獎勵"),
        "profile" to all("Me", "Perfil", "Perfil", "Saya", "我的", "我的"),
        "wallet" to all("Wallet", "Carteira", "Billetera", "Dompet", "我的钱包", "我的錢包"),
        "gems" to all("Gems", "Gemas", "Gemas", "Permata", "宝石", "寶石"),
        "cash_balance" to all("Cash balance", "Saldo em dinheiro", "Saldo de efectivo", "Saldo tunai", "钱包余额", "錢包餘額"),
        "withdraw" to all("Withdraw", "Sacar", "Retirar", "Tarik dana", "提现", "提現"),
        "reward_center" to all("Rewards Center", "Central de recompensas", "Centro de recompensas", "Pusat hadiah", "奖励中心", "獎勵中心"),
        "watch_to_earn" to all("Watch and earn gems", "Assista e ganhe gemas", "Mira y gana gemas", "Tonton dan dapatkan permata", "看剧攒宝石", "看劇攢寶石"),
        "claim_all" to all("Claim all", "Resgatar tudo", "Reclamar todo", "Klaim semua", "全部领取", "全部領取"),
        "keep_watching" to all("Keep watching", "Continuar assistindo", "Seguir viendo", "Lanjut menonton", "继续追剧", "繼續追劇"),
        "welcome_title" to all("Welcome gift", "Presente de boas-vindas", "Regalo de bienvenida", "Hadiah selamat datang", "新人专享奖励", "新人專享獎勵"),
        "welcome_subtitle" to all("Your first reward is ready", "Sua primeira recompensa está pronta", "Tu primera recompensa está lista", "Hadiah pertamamu sudah siap", "首次进入，专属宝石奖励已到账", "首次進入，專屬寶石獎勵已到帳"),
        "watch_ad_claim" to all("Watch ad & claim", "Ver anúncio e resgatar", "Ver anuncio y reclamar", "Tonton iklan & klaim", "看广告立即领取", "看廣告立即領取"),
        "later" to all("Later", "Mais tarde", "Más tarde", "Nanti", "稍后领取", "稍後領取"),
        "chest" to all("Lucky Chest", "Baú da sorte", "Cofre de la suerte", "Peti keberuntungan", "随机宝箱", "隨機寶箱"),
        "open_chest" to all("Watch ad to open", "Ver anúncio para abrir", "Ver anuncio para abrir", "Tonton iklan untuk buka", "看广告开启宝箱", "看廣告開啟寶箱"),
        "no_chest" to all("No chest yet. Watch more episodes to earn one.", "Ainda não há baú. Assista mais episódios para ganhar.", "Aún no hay cofre. Mira más episodios para obtener uno.", "Belum ada peti. Tonton episode lain untuk mendapatkannya.", "暂时没有宝箱，继续看剧即可获得。", "暫時沒有寶箱，繼續看劇即可獲得。"),
        "checkin" to all("Daily check-in", "Check-in diário", "Registro diario", "Check-in harian", "连续签到", "連續簽到"),
        "checked_in" to all("Checked in today", "Check-in feito hoje", "Registrado hoy", "Sudah check-in hari ini", "今日已签到", "今日已簽到"),
        "watch_ad_checkin" to all("Watch ad to check in", "Ver anúncio para fazer check-in", "Ver anuncio para registrarse", "Tonton iklan untuk check-in", "看广告签到", "看廣告簽到"),
        "episodes" to all("Episodes", "Episódios", "Episodios", "Episode", "选集", "選集"),
        "speed" to all("Speed", "Velocidade", "Velocidad", "Kecepatan", "倍速", "倍速"),
        "more" to all("More", "Mais", "Más", "Lainnya", "更多", "更多"),
        "favorite" to all("Favorite", "Favorito", "Favorito", "Favorit", "收藏", "收藏"),
        "hide" to all("Hide", "Ocultar", "Ocultar", "Sembunyikan", "隐藏", "隱藏"),
        "language" to all("Language", "Idioma", "Idioma", "Bahasa", "语言", "語言"),
        "continue_watching" to all("Continue watching", "Continuar assistindo", "Seguir viendo", "Lanjut menonton", "继续观看", "繼續觀看"),
        "ad_loading_failed" to all("Rewarded ad is unavailable. An interstitial will be shown instead.", "Anúncio recompensado indisponível. Um intersticial será exibido.", "El anuncio recompensado no está disponible. Se mostrará un intersticial.", "Iklan berhadiah tidak tersedia. Iklan sela akan ditampilkan.", "激励广告暂不可用，将为你播放一次插屏广告。", "激勵廣告暫不可用，將為你播放一次插屏廣告。"),
        "withdrawal_details" to all("Withdrawal details", "Detalhes do saque", "Detalles del retiro", "Rincian penarikan", "提现说明", "提現說明"),
        "reward_center_subtitle" to all("Earn gems through eligible viewing and claim them when ready.", "Ganhe gemas assistindo e resgate quando quiser.", "Gana gemas viendo y reclámalas cuando quieras.", "Dapatkan permata dari tontonan dan klaim saat siap.", "有效追剧先积累宝石，领取后记入余额。", "有效追劇先累積寶石，領取後記入餘額。"),
        "profile_subtitle" to all("Your viewing progress and reward details", "Seu progresso e detalhes das recompensas", "Tu progreso y detalles de recompensas", "Progres tontonan dan rincian hadiah", "追剧进度与奖励明细", "追劇進度與獎勵明細"),
        "show_controls" to all("Controls", "Controles", "Controles", "Kontrol", "显示控制", "顯示控制"),
    )
}
