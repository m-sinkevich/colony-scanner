package com.paradise.scanner

import android.content.Context
import org.json.JSONObject

/**
 * Кристаллы — игровая валюта. Оплата идёт только онлайн: кристалл сразу списывается в базе
 * (функция spend_crystal), потраченный больше не принимается. Оплаченный объект открывается персонажу без оплаты.
 */
object Crystals {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("crystals", Context.MODE_PRIVATE)

    fun paid(ctx: Context, charId: String, contentId: String): Boolean =
        "$charId|$contentId" in Repo.get(ctx).paid || sp(ctx).getBoolean("$charId|$contentId", false)

    fun markPaid(ctx: Context, charId: String, contentId: String) {
        sp(ctx).edit().putBoolean("$charId|$contentId", true).apply()
    }

    /** После загрузки базы с сервера локальные отметки не нужны: мастер мог сбросить энергию объекта. */
    fun clearLocal(ctx: Context) { sp(ctx).edit().clear().apply() }

    fun isCrystal(ctx: Context, code: String): Boolean = code.trim().uppercase() in Repo.get(ctx).crystals

    data class Result(val result: String, val paid: Int, val price: Int, val completed: Boolean)

    /** Списать кристалл за объект. Нет сети — исключение. Вызывать не из главного потока. */
    fun spend(ctx: Context, code: String, p: Person, item: ContentItem): Result {
        val body = JSONObject().put("p_code", code.trim().uppercase()).put("p_character", p.id).put("p_content", item.id)
            .put("p_module", p.moduleId ?: JSONObject.NULL).put("p_source", "scanner")
        val o = JSONObject(Store.rpc(ctx, "spend_crystal", body))
        val r = Result(o.optString("result"), o.optInt("paid"), o.optInt("price", item.price), o.optBoolean("completed"))
        if (r.completed || r.result == "already_paid") markPaid(ctx, p.id, item.id)
        Store.log(ctx, p, if (r.result == "ok") "crystal_spend" else "crystal_reject",
            JSONObject().put("code", code.trim().uppercase()).put("contentId", item.id).put("result", r.result).put("paid", r.paid).put("price", r.price))
        Store.pushLater(ctx)
        return r
    }
}
