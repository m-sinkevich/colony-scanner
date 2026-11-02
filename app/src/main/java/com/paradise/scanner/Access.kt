package com.paradise.scanner

import android.content.Context
import kotlin.math.ceil

/**
 * Доступ к объекту контента — по тем же правилам, что на терминале модуля:
 * модули (по модулю приписки персонажа), персонажи, «один раз», условие разблокировки.
 */
object Access {
    sealed class Gate {
        object Ok : Gate()
        data class Denied(val title: String, val message: String) : Gate()
        /** Платный объект: нужно внести кристаллы (один раз на персонажа). */
        data class Pay(val price: Int, val variant: String) : Gate()
    }

    fun check(ctx: Context, item: ContentItem, p: Person): Gate {
        if (item.type == "event") return Gate.Denied("АНАЛИЗ НЕВОЗМОЖЕН", "Этот объект не содержит данных для сканера.")
        val mods = item.moduleScope
        val chars = item.charScope
        if (chars.isNotEmpty() && p.id !in chars)
            return Gate.Denied("ДОСТУП ОГРАНИЧЕН", "Данные зашифрованы для другого получателя. Ваш допуск не подходит.")
        if (mods.isNotEmpty() && (p.moduleId == null || p.moduleId !in mods))
            return Gate.Denied("ДОСТУП ОГРАНИЧЕН", "Эти данные недоступны оператору вашего модуля.")
        val prog = Store.progress(ctx, p.id, item.id)
        if (item.playback == "once" && prog.count > 0)
            return Gate.Denied("ДОСТУП ЗАКРЫТ", "Эта запись уже была считана ранее и доступна только один раз. Повторный доступ закрыт.")
        when (item.unlockType) {
            "after_timer" -> {
                Store.attempt(ctx, p, item)
                val first = Store.progress(ctx, p.id, item.id).firstAt.takeIf { it > 0 } ?: System.currentTimeMillis()
                val elapsed = (System.currentTimeMillis() - first) / 60000.0
                if (elapsed < item.timerMinutes) {
                    val remain = ceil(item.timerMinutes - elapsed).toInt()
                    return Gate.Denied("ДОСТУП ОГРАНИЧЕН", "Доступ будет открыт примерно через $remain мин. Таймер запущен при первой попытке сканирования этого объекта.")
                }
            }
            "master_event" -> return Gate.Denied("ДОСТУП ОГРАНИЧЕН", "Этот объект разблокируется событием от мастера.")
            "silence_timeout" -> return Gate.Denied("ДОСТУП ОГРАНИЧЕН", "Этот объект активируется автоматически, не по сканированию.")
        }
        val (variant, price) = energy(item, p)
        if (price > 0 && !Crystals.paid(ctx, p.id, item.id, variant)) return Gate.Pay(price, variant)
        return Gate.Ok
    }

    /** Энергия кристаллов: у основной страницы ("") и у каждого варианта (его id) своя цена. */
    fun energy(item: ContentItem, p: Person): Pair<String, Int> {
        val v = variantFor(item, p)
        return if (v != null) v.id to v.price else "" to item.price
    }

    /** Вариант страницы: сначала для персонажа, затем для его модуля. */
    fun variantFor(item: ContentItem, p: Person?): Variant? {
        if (p == null) return null
        val ok = { v: Variant -> v.blocks.isNotEmpty() || v.body.isNotBlank() }
        return item.variants.firstOrNull { ok(it) && p.id in it.chars }
            ?: item.variants.firstOrNull { ok(it) && p.moduleId != null && p.moduleId in it.mods }
    }
}
