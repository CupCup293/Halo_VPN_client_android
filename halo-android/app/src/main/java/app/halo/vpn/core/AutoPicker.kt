package app.halo.vpn.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Выбор основного сервера для «Авто · лучший» — как в Halo для Windows 1.0.
 * «Авто» не дёргается из-за мелочей: быстрее до 10 мс — остаёмся; на 10–30 мс — переходим, только если
 * через новый сервер открываются те же сервисы (YouTube, ChatGPT, Claude); больше 30 мс — переходим.
 * Выбранный сервер закрепляется в url-test: ядро уйдёт с него само, только если он перестанет отвечать.
 */
object AutoPicker {
    const val HOLD_MS = 10
    const val CHECK_MS = 30

    enum class Decision { Keep, Check, Switch }

    fun decision(current: Int?, candidate: Int): Decision {
        if (current == null || current <= 0) return Decision.Switch // текущий не отвечает
        val gain = current - candidate
        return when {
            gain <= HOLD_MS -> Decision.Keep
            gain <= CHECK_MS -> Decision.Check
            else -> Decision.Switch
        }
    }

    /**
     * Сервисы, которые проверяем через сервер перед переходом. API, а не сайты: страницы за Cloudflare отвечают
     * проверке 403 даже там, где сервис работает. Из заблокированной страны API отвечает 403, из разрешённой — 401/405.
     */
    val SERVICES = listOf(
        "YouTube" to "https://www.youtube.com/generate_204",
        "ChatGPT" to "https://api.openai.com/v1/models",
        "Claude" to "https://api.anthropic.com/v1/models",
    )
    const val SERVICE_OK_STATUS = "200-402/404-499" // всё, кроме 403 (регион заблокирован) и 5xx

    private val RU_EXIT = Regex("(?i)🇷🇺|росси|(^|[^а-яё])рф([^а-яё]|$)|russia|moscow|москв")
    fun isRuExit(name: String) = RU_EXIT.containsMatchIn(name)

    /** Сервисы, которые через [current] открываются, а через [candidate] — нет. */
    suspend fun servicesLost(api: Controller, current: String, candidate: String): List<String> = coroutineScope {
        SERVICES.map { (name, url) ->
            async {
                val a = async { api.serviceOk(current, url) }
                val b = async { api.serviceOk(candidate, url) }
                if (a.await() && !b.await()) name else null
            }
        }.awaitAll().filterNotNull()
    }

    /**
     * После проверки всех серверов: оставить текущий или перейти на лучший. Только когда выбран «Авто».
     * Возвращает сервер, закреплённый в «Авто», или null, если ничего не делали.
     */
    suspend fun rank(api: Controller, delays: Map<String, Int>): String? {
        val list = api.servers()
        if (list.selected != ConfigBuilder.AUTO) return null
        val best = delays.filter { it.value > 0 && !isRuExit(it.key) && list.servers.any { s -> s.name == it.key } }
            .minByOrNull { it.value } ?: return null
        val cur = list.autoNow
        var pick = best.key
        if (cur != null && cur != best.key && (delays[cur] ?: 0) > 0) {
            val curDelay = delays[cur]!!
            val gain = curDelay - best.value
            when (decision(curDelay, best.value)) {
                Decision.Keep -> {
                    pick = cur
                    HaloLog.i("авто", "«${Countries.clean(best.key)}» быстрее всего на $gain мс — остаюсь на «${Countries.clean(cur)}»")
                }
                Decision.Check -> {
                    val lost = servicesLost(api, cur, best.key)
                    if (lost.isNotEmpty()) {
                        pick = cur
                        HaloLog.i("авто", "«${Countries.clean(best.key)}» быстрее на $gain мс, но через него не открывается: ${lost.joinToString()} — остаюсь на «${Countries.clean(cur)}»")
                    }
                }
                Decision.Switch -> Unit
            }
        }
        if (pick != cur) HaloLog.i("авто", "основной сервер: «${Countries.clean(pick)}» (${delays[pick]} мс)")
        runCatching { api.fixAuto(pick) }
        return pick
    }
}
