package com.dramaflow.app.i18n

import android.content.Context
import java.util.Locale

/**
 * 应用内轻量级语言层。服务端剧名和简介保留原始内容语言；导航、奖励、钱包、播放器与设置
 * 均通过本字典呈现，以避免 UI 改版后出现中英文混排。
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
        val raw = dictionary[key]?.get(tag(context)) ?: dictionary[key]?.get("en") ?: key
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
        "cash_balance" to all("Cash balance", "Saldo", "Saldo", "Saldo", "钱包余额", "錢包餘額"),
        "available_balance" to all("Available balance", "Saldo disponível", "Saldo disponible", "Saldo tersedia", "可提现余额", "可提現餘額"),
        "withdraw" to all("Withdraw", "Sacar", "Retirar", "Tarik dana", "提现", "提現"),
        "reward_center" to all("Rewards Center", "Central de recompensas", "Centro de recompensas", "Pusat hadiah", "奖励中心", "獎勵中心"),
        "watch_to_earn" to all("Watch to earn gems", "Assista para ganhar gemas", "Mira para ganar gemas", "Tonton untuk dapat permata", "看剧攒宝石", "看劇攢寶石"),
        "claim_all" to all("Claim all", "Resgatar tudo", "Reclamar todo", "Klaim semua", "全部领取", "全部領取"),
        "keep_watching" to all("Keep watching", "Continuar assistindo", "Seguir viendo", "Lanjut menonton", "继续追剧", "繼續追劇"),
        "continue_watching" to all("Continue watching", "Continuar assistindo", "Seguir viendo", "Lanjut menonton", "继续观看", "繼續觀看"),
        "watch_now" to all("Watch now", "Assistir", "Ver ahora", "Tonton sekarang", "立即观看", "立即觀看"),
        "go_watch" to all("Go watch", "Ir assistir", "Ir a ver", "Mulai menonton", "去看剧", "去看劇"),
        "welcome_title" to all("Welcome gift", "Presente de boas-vindas", "Regalo de bienvenida", "Hadiah selamat datang", "新人专享奖励", "新人專享獎勵"),
        "welcome_subtitle" to all("Your first reward is ready", "Sua primeira recompensa está pronta", "Tu primera recompensa está lista", "Hadiah pertamamu sudah siap", "首次进入，专属宝石奖励已到账", "首次進入，專屬寶石獎勵已到帳"),
        "watch_ad_claim" to all("Watch ad & claim", "Ver anúncio e resgatar", "Ver anuncio y reclamar", "Tonton iklan & klaim", "看广告立即领取", "看廣告立即領取"),
        "later" to all("Later", "Mais tarde", "Más tarde", "Nanti", "稍后再说", "稍後再說"),
        "chest" to all("Lucky Chest", "Baú da sorte", "Cofre de la suerte", "Peti keberuntungan", "幸运宝箱", "幸運寶箱"),
        "open_chest" to all("Watch ad to open", "Ver anúncio para abrir", "Ver anuncio para abrir", "Tonton iklan untuk buka", "看广告开启", "看廣告開啟"),
        "no_chest" to all("Keep watching to earn a chest.", "Continue assistindo para ganhar um baú.", "Sigue viendo para ganar un cofre.", "Terus menonton untuk mendapat peti.", "继续追剧获得宝箱。", "繼續追劇獲得寶箱。"),
        "checkin" to all("Daily check-in", "Check-in diário", "Registro diario", "Check-in harian", "每日签到", "每日簽到"),
        "checked_in" to all("Checked in today", "Check-in feito hoje", "Registrado hoy", "Sudah check-in", "今日已签到", "今日已簽到"),
        "watch_ad_checkin" to all("Watch ad to check in", "Ver anúncio para fazer check-in", "Ver anuncio para registrarse", "Tonton iklan untuk check-in", "看广告签到", "看廣告簽到"),
        "episodes" to all("Episodes", "Episódios", "Episodios", "Episode", "选集", "選集"),
        "speed" to all("Speed", "Velocidade", "Velocidad", "Kecepatan", "倍速", "倍速"),
        "more" to all("More", "Mais", "Más", "Lainnya", "更多", "更多"),
        "favorite" to all("Favorite", "Favorito", "Favorito", "Favorit", "收藏", "收藏"),
        "hide" to all("Hide", "Ocultar", "Ocultar", "Sembunyikan", "隐藏", "隱藏"),
        "language" to all("Language", "Idioma", "Idioma", "Bahasa", "语言", "語言"),
        "show_controls" to all("Controls", "Controles", "Controles", "Kontrol", "显示控制", "顯示控制"),
        "home_greeting" to all("Find a story for today", "Encontre uma história para hoje", "Encuentra una historia para hoy", "Temukan cerita hari ini", "发现一部好短剧", "發現一部好短劇"),
        "home_subtitle" to all("Pick up where you left off, or find a new favorite.", "Continue de onde parou ou encontre um novo favorito.", "Retoma donde lo dejaste o descubre un favorito.", "Lanjutkan tontonan atau temukan favorit baru.", "继续上次的剧情，或发现新的心动。", "繼續上次的劇情，或發現新的心動。"),
        "search_hint" to all("Search dramas, genres or keywords", "Busque dramas, gêneros ou palavras", "Busca dramas, géneros o palabras", "Cari drama, genre, atau kata kunci", "搜索剧名、题材或关键词", "搜尋劇名、題材或關鍵詞"),
        "featured_this_week" to all("Featured this week", "Destaque da semana", "Destacado de la semana", "Pilihan minggu ini", "本周精选", "本週精選"),
        "recommended_for_you" to all("Recommended for you", "Para você", "Recomendado para ti", "Rekomendasi untukmu", "为你推荐", "為你推薦"),
        "popular_this_week" to all("Popular this week", "Em alta nesta semana", "Popular esta semana", "Populer minggu ini", "本周热播", "本週熱播"),
        "all_dramas" to all("All dramas", "Todos os dramas", "Todos los dramas", "Semua drama", "全部短剧", "全部短劇"),
        "discover_subtitle" to all("Browse by mood and find your next episode.", "Navegue por estilos e encontre seu próximo episódio.", "Explora por estilo y encuentra tu próximo episodio.", "Jelajahi sesuai suasana dan temukan episode berikutnya.", "分类浏览，快速找到想看的剧情。", "分類瀏覽，快速找到想看的劇情。"),
        "filter" to all("Filter", "Filtrar", "Filtrar", "Filter", "筛选", "篩選"),
        "continue_label" to all("Continue", "Continuar", "Continuar", "Lanjutkan", "继续", "繼續"),
        "episodes_count" to all("%1\$d episodes", "%1\$d episódios", "%1\$d episodios", "%1\$d episode", "%1\$d 集", "%1\$d 集"),
        "rewards_subtitle" to all("Watch, earn gems, unlock more stories.", "Assista, ganhe gemas e desbloqueie histórias.", "Mira, gana gemas y desbloquea historias.", "Tonton, dapatkan permata, buka cerita baru.", "看剧赚宝石，解锁更多精彩。", "看劇賺寶石，解鎖更多精彩。"),
        "ledger_note" to all("Rewards are confirmed by the server ledger.", "As recompensas são confirmadas pelo servidor.", "Las recompensas las confirma el servidor.", "Hadiah dikonfirmasi oleh buku besar server.", "奖励以服务端账本为准。", "獎勵以服務端帳本為準。"),
        "watch_tasks" to all("Watch tasks", "Tarefas de exibição", "Tareas de visualización", "Tugas menonton", "追剧任务", "追劇任務"),
        "watch_tasks_subtitle" to all("Complete episodes to unlock gem rewards.", "Conclua episódios para liberar gemas.", "Completa episodios para desbloquear gemas.", "Selesaikan episode untuk membuka hadiah permata.", "完成指定集数，解锁宝石奖励。", "完成指定集數，解鎖寶石獎勵。"),
        "daily_benefits" to all("Daily benefits", "Benefícios diários", "Beneficios diarios", "Manfaat harian", "今日福利", "今日福利"),
        "daily_benefits_subtitle" to all("Check in daily for an extra reward.", "Faça check-in todos os dias para ganhar mais.", "Regístrate a diario para ganar más.", "Check-in setiap hari untuk hadiah tambahan.", "连续签到，领取更多宝石。", "連續簽到，領取更多寶石。"),
        "pending_rewards" to all("Pending rewards", "Recompensas pendentes", "Recompensas pendientes", "Hadiah tertunda", "待领取奖励", "待領取獎勵"),
        "claim_after_ad" to all("Watch a rewarded ad to claim all", "Veja um anúncio para resgatar tudo", "Mira un anuncio para reclamar todo", "Tonton iklan berhadiah untuk klaim semua", "观看激励广告后全部领取", "觀看激勵廣告後全部領取"),
        "today_day_reward" to all("Day %1\$d · +%2\$d gems", "Dia %1\$d · +%2\$d gemas", "Día %1\$d · +%2\$d gemas", "Hari %1\$d · +%2\$d permata", "第 %1\$d 天 · +%2\$d 宝石", "第 %1\$d 天 · +%2\$d 寶石"),
        "profile_subtitle" to all("Your viewing progress and reward details", "Seu progresso e recompensas", "Tu progreso y recompensas", "Progres tontonan dan hadiah", "追剧进度与奖励明细", "追劇進度與獎勵明細"),
        "my_services" to all("My services", "Meus serviços", "Mis servicios", "Layanan saya", "我的服务", "我的服務"),
        "favorites" to all("My favorites", "Meus favoritos", "Mis favoritos", "Favorit saya", "我的收藏", "我的收藏"),
        "reward_ledger" to all("Reward ledger", "Extrato de recompensas", "Historial de recompensas", "Riwayat hadiah", "奖励流水", "獎勵流水"),
        "ad_privacy" to all("Ads & privacy", "Anúncios e privacidade", "Anuncios y privacidad", "Iklan & privasi", "广告与隐私", "廣告與隱私"),
        "developer_services" to all("Development services", "Serviços de desenvolvimento", "Servicios de desarrollo", "Layanan pengembangan", "开发调试服务", "開發偵錯服務"),
        "content_service" to all("Content service", "Serviço de conteúdo", "Servicio de contenido", "Layanan konten", "局域网内容服务", "區域網內容服務"),
        "rewards_service" to all("Rewards service", "Serviço de recompensas", "Servicio de recompensas", "Layanan hadiah", "积分与奖励服务", "積分與獎勵服務"),
        "cash_out" to all("Cash out", "Sacar", "Retirar", "Tarik dana", "提现", "提現"),
        "rules" to all("Rules", "Regras", "Reglas", "Aturan", "规则", "規則"),
        "payment_account" to all("Payment account", "Conta de pagamento", "Cuenta de pago", "Akun pembayaran", "收款账户", "收款帳戶"),
        "enter_cashout_amount" to all("Enter cash-out amount", "Informe o valor do saque", "Introduce el importe", "Masukkan jumlah penarikan", "输入提现金额", "輸入提現金額"),
        "cashout_hint" to all("Enter an amount using 0.50–20.00 format.", "Informe um valor entre 0,50 e 20,00.", "Introduce un importe entre 0,50 y 20,00.", "Masukkan jumlah antara 0,50–20,00.", "请输入 0.50–20.00 之间的金额。", "請輸入 0.50–20.00 之間的金額。"),
        "wallet_balance" to all("Wallet balance", "Saldo da carteira", "Saldo de la cartera", "Saldo dompet", "钱包余额", "錢包餘額"),
        "tomorrow_transfer" to all("Est. transfer tomorrow", "Previsão para amanhã", "Transferencia estimada mañana", "Perkiraan transfer besok", "预计明日转入", "預計明日轉入"),
        "ad_boost" to all("Ad boost", "Aceleração por anúncio", "Aceleración por anuncio", "Percepatan iklan", "广告加速", "廣告加速"),
        "cashout_explainer" to all("How cash-out works", "Como funciona o saque", "Cómo funciona el retiro", "Cara kerja penarikan", "提现说明", "提現說明"),
        "earn_gems" to all("Earn gems", "Ganhe gemas", "Gana gemas", "Dapatkan permata", "赚取宝石", "賺取寶石"),
        "convert_wallet" to all("Convert to wallet", "Converta para a carteira", "Convierte a la cartera", "Tukar ke dompet", "兑换到钱包", "兌換到錢包"),
        "request_cashout" to all("Request cash out", "Solicitar saque", "Solicitar retiro", "Ajukan penarikan", "申请提现", "申請提現"),
        "cashout_rules" to all("Cash-out rules", "Regras de saque", "Reglas de retiro", "Aturan penarikan", "钱包与奖励规则", "錢包與獎勵規則"),
        "ad_loading_failed" to all("Rewarded video is unavailable. Please try again later.", "O vídeo com recompensa não está disponível. Tente mais tarde.", "El vídeo con recompensa no está disponible. Inténtalo más tarde.", "Video berhadiah tidak tersedia. Coba lagi nanti.", "激励广告暂不可用，请稍后重试。", "激勵廣告暫不可用，請稍後重試。"),
        "withdrawal_details" to all("Withdrawal details", "Detalhes do saque", "Detalles del retiro", "Rincian penarikan", "提现说明", "提現說明"),
        "close" to all("Close", "Fechar", "Cerrar", "Tutup", "关闭", "關閉"),
        "cancel" to all("Cancel", "Cancelar", "Cancelar", "Batal", "取消", "取消"),
        "done" to all("Done", "Concluído", "Listo", "Selesai", "知道了", "知道了"),
        "retry" to all("Retry", "Tentar novamente", "Reintentar", "Coba lagi", "重试", "重試"),
        "restore_default" to all("Restore default", "Restaurar padrão", "Restaurar predeterminado", "Pulihkan bawaan", "恢复默认", "恢復預設"),
        "save" to all("Save", "Salvar", "Guardar", "Simpan", "保存", "儲存"),
        "continue_label" to all("Continue", "Continuar", "Continuar", "Lanjutkan", "继续", "繼續"),
        "episode_label" to all("Episode %1\$d", "Episódio %1\$d", "Episodio %1\$d", "Episode %1\$d", "第 %1\$d 集", "第 %1\$d 集"),
        "episode_progress" to all("Episode %1\$d/%2\$d%3\$s", "Episódio %1\$d/%2\$d%3\$s", "Episodio %1\$d/%2\$d%3\$s", "Episode %1\$d/%2\$d%3\$s", "第 %1\$d/%2\$d 集%3\$s", "第 %1\$d/%2\$d 集%3\$s"),
        "portrait_drama" to all("Portrait drama", "Drama vertical", "Drama vertical", "Drama vertikal", "竖屏短剧", "豎屏短劇"),
        "watch_progress" to all("Episode %1\$d · %2\$s", "Episódio %1\$d · %2\$s", "Episodio %1\$d · %2\$s", "Episode %1\$d · %2\$s", "第 %1\$d 集 · %2\$s", "第 %1\$d 集 · %2\$s"),
        "claim_episode_rewards" to all("Claim episode rewards", "Resgatar recompensas", "Reclamar recompensas", "Klaim hadiah episode", "领取全部追剧宝石", "領取全部追劇寶石"),
        "claim_episode_rewards_message" to all("You have %1\$d gems waiting. Watch a rewarded ad to claim all pending gems; boosted episodes are included.", "Você tem %1\$d gemas aguardando. Assista a um anúncio para resgatar todas; episódios com bônus estão incluídos.", "Tienes %1\$d gemas pendientes. Mira un anuncio para reclamarlas todas; se incluyen episodios mejorados.", "Kamu punya %1\$d permata menunggu. Tonton iklan berhadiah untuk mengklaim semuanya; episode bonus termasuk.", "你已累计 %1\$d 宝石。完整观看一条激励广告后将一次性领取全部待领取宝石；已提前开启翻倍的分集会按双倍结算。", "你已累計 %1\$d 寶石。完整觀看一條激勵廣告後將一次性領取全部待領取寶石；已提前開啟翻倍的分集會按雙倍結算。"),
        "not_now" to all("Not now", "Agora não", "Ahora no", "Nanti saja", "暂不", "暫不"),
        "double_episode_reward" to all("Double this episode's reward", "Dobrar recompensa deste episódio", "Duplicar la recompensa del episodio", "Gandakan hadiah episode ini", "本集奖励翻倍", "本集獎勵翻倍"),
        "double_episode_reward_message" to all("Watch a rewarded ad to double the gem reward for this completed episode. Each episode can be doubled once.", "Assista a um anúncio para dobrar a recompensa desta conclusão. Cada episódio só pode dobrar uma vez.", "Mira un anuncio para duplicar la recompensa de este episodio. Cada episodio se duplica una vez.", "Tonton iklan untuk menggandakan hadiah episode ini. Setiap episode hanya sekali.", "完整观看一条广告后，本集完成观看时获得的宝石将自动按双倍累计。每集最多翻倍一次。", "完整觀看一條廣告後，本集完成觀看時獲得的寶石將自動按雙倍累計。每集最多翻倍一次。"),
        "watch_ad_double" to all("Watch ad to double", "Ver anúncio para dobrar", "Ver anuncio para duplicar", "Tonton iklan untuk gandakan", "看广告翻倍", "看廣告翻倍"),
        "reward_doubled" to all("Reward doubled", "Recompensa dobrada", "Recompensa duplicada", "Hadiah digandakan", "本集已翻倍", "本集已翻倍"),
        "checkin_rewards" to all("Check-in rewards", "Recompensas de check-in", "Recompensas de registro", "Hadiah check-in", "连续签到奖励", "連續簽到獎勵"),
        "newcomer_reward" to all("Welcome reward", "Recompensa de boas-vindas", "Recompensa de bienvenida", "Hadiah selamat datang", "新人欢迎奖励", "新人歡迎獎勵"),
        "welcome_reward_message" to all("Claim %1\$d gems after watching a rewarded ad.", "Resgate %1\$d gemas após assistir a um anúncio.", "Reclama %1\$d gemas tras ver un anuncio.", "Klaim %1\$d permata setelah menonton iklan.", "首次加入可领取 %1\$d 宝石。完整观看一条激励广告后即可到账。", "首次加入可領取 %1\$d 寶石。完整觀看一條激勵廣告後即可到帳。"),
        "exchange_gems" to all("Exchange gems", "Trocar gemas", "Canjear gemas", "Tukar permata", "兑换宝石", "兌換寶石"),
        "exchange_gems_message" to all("The exchange enters your wallet and becomes withdrawable under the current rules.", "A troca entra na carteira e fica disponível conforme as regras.", "El canje entra en tu cartera según las reglas vigentes.", "Pertukaran masuk ke dompet sesuai aturan saat ini.", "兑换后记入钱包余额，系统会按当前规则陆续转入可提现余额。", "兌換後記入錢包餘額，系統會按目前規則陸續轉入可提現餘額。"),
        "minimum_gems" to all("At least %1\$d gems", "Pelo menos %1\$d gemas", "Al menos %1\$d gemas", "Minimal %1\$d permata", "至少 %1\$d 宝石", "至少 %1\$d 寶石"),
        "ad_boost_message" to all("Watch a rewarded ad to accelerate the wallet transfer. Estimated extra transfer: $%1\$s.", "Assista a um anúncio para acelerar a transferência. Extra estimado: $%1\$s.", "Mira un anuncio para acelerar la transferencia. Extra estimado: $%1\$s.", "Tonton iklan untuk mempercepat transfer. Tambahan perkiraan: $%1\$s.", "完整观看一条广告后，可加快钱包余额转入可提现余额。预计本次额外转入 $%1\$s。", "完整觀看一條廣告後，可加快錢包餘額轉入可提現餘額。預計本次額外轉入 $%1\$s。"),
        "reward_ledger_message" to all("Rule version: %1\$s. Recent entitlements are recorded in the server ledger.", "Versão da regra: %1\$s. Os direitos recentes ficam no servidor.", "Versión de regla: %1\$s. Los derechos recientes se guardan en el servidor.", "Versi aturan: %1\$s. Hak terbaru disimpan di server.", "当前规则版本：%1\$s。最近权益由服务端账本保存。", "目前規則版本：%1\$s。最近權益由服務端帳本保存。"),
        "resume_playback" to all("Resume", "Retomar", "Reanudar", "Lanjutkan", "继续播放", "繼續播放"),
        "player_gesture_hint" to all("Tap to play or pause · swipe up for next · swipe sideways to switch dramas", "Toque para pausar · deslize para o próximo", "Toca para pausar · desliza para el siguiente", "Ketuk untuk jeda · geser untuk episode berikut", "轻点播放或暂停；上滑下一集，左右切换短剧", "輕點播放或暫停；上滑下一集，左右切換短劇"),
        "seek_to" to all("Drag to seek", "Arraste para avançar", "Arrastra para avanzar", "Geser untuk melompat", "拖动跳转", "拖動跳轉"),
        "playback_speed" to all("Playback speed", "Velocidade", "Velocidad", "Kecepatan", "播放速度", "播放速度"),
        "playback_settings" to all("Playback settings", "Configurações de reprodução", "Ajustes de reproducción", "Pengaturan pemutaran", "播放设置", "播放設定"),
        "collapse" to all("Collapse", "Recolher", "Contraer", "Tutup", "收起", "收起"),
        "free_episode_message" to all("The first %1\$d episodes are free. Later episodes follow server unlock rules.", "Os primeiros %1\$d episódios são gratuitos. Os próximos seguem regras do servidor.", "Los primeros %1\$d episodios son gratis. Los demás siguen reglas del servidor.", "%1\$d episode pertama gratis. Sisanya mengikuti aturan server.", "前 %1\$d 集可免费观看，后续集数按服务端规则解锁。", "前 %1\$d 集可免費觀看，後續集數按服務端規則解鎖。"),
        "free_episodes_used" to all("Free episodes used", "Episódios grátis usados", "Episodios gratis usados", "Episode gratis habis", "免费集数已用完", "免費集數已用完"),
        "unlock_episode_message" to all("Episode %1\$d requires a full rewarded ad to unlock. Playback starts automatically after the ad.", "O episódio %1\$d precisa de um anúncio completo para desbloquear. A reprodução começa depois.", "El episodio %1\$d requiere un anuncio completo. Se reproducirá después.", "Episode %1\$d perlu iklan lengkap untuk dibuka. Pemutaran dimulai sesudahnya.", "第 %1\$d 集需要完整观看一条激励广告解锁。广告结束后会自动开始播放本集。", "第 %1\$d 集需要完整觀看一條激勵廣告解鎖。廣告結束後會自動開始播放本集。"),
        "unlock_with_ad" to all("Watch ad to unlock", "Ver anúncio para desbloquear", "Ver anuncio para desbloquear", "Tonton iklan untuk buka", "看激励广告解锁", "看激勵廣告解鎖"),
        "chest_earned" to all("You earned %1\$d chest(s)!", "Você ganhou %1\$d baú(s)!", "¡Has ganado %1\$d cofre(s)!", "Kamu mendapat %1\$d peti!", "恭喜获得 %1\$d 个宝箱！", "恭喜獲得 %1\$d 個寶箱！"),
        "chest_earned_message" to all("Your chest is in the rewards library. Open one now, or keep watching and return later.", "Seu baú está na biblioteca de recompensas. Abra agora ou volte depois.", "El cofre está en tus recompensas. Ábrelo ahora o vuelve después.", "Peti ada di hadiahmu. Buka sekarang atau lanjutkan menonton.", "宝箱已经放入你的奖励库。现在打开 1 个，或继续追剧后再来领取。", "寶箱已放入你的獎勵庫。現在開啟 1 個，或繼續追劇後再來領取。"),
        "open_now" to all("Open now", "Abrir agora", "Abrir ahora", "Buka sekarang", "立即开启", "立即開啟"),
        "server_unavailable" to all("Content service unavailable", "Serviço de conteúdo indisponível", "Servicio de contenido no disponible", "Layanan konten tidak tersedia", "暂时无法连接内容服务", "暫時無法連線內容服務"),
        "server_hint" to all("Check that the local service is running and enter the computer's local IPv4 address.", "Verifique o serviço local e informe o IPv4 do computador.", "Comprueba el servicio local e introduce la IPv4 del ordenador.", "Periksa layanan lokal dan masukkan IPv4 komputer.", "请确认 Windows 服务已启动，并填入电脑的局域网 IPv4 地址。", "請確認 Windows 服務已啟動，並填入電腦的區域網 IPv4 位址。"),
        "server_address" to all("Server address", "Endereço do servidor", "Dirección del servidor", "Alamat server", "设置服务器地址", "設定伺服器位址"),
        "startup_tagline" to all("Short dramas, ready when you are", "Dramas curtos, prontos para você", "Historias cortas, listas para ti", "Drama pendek, siap kapan saja", "精彩短剧，即刻开追", "精彩短劇，即刻開追"),
    )
}
