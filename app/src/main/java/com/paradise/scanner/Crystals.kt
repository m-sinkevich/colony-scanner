package com.paradise.scanner

import android.content.Context
import org.json.JSONObject

/**
 * Кристаллы — игровая валюта. Есть сеть — кристалл сразу списывается в базе (функция spend_crystal).
 * Нет сети — оплата проверяется по скачанной базе (коды кристаллов, потраченные, внесённая энергия),
 * кристалл помечается потраченным на этом телефоне, а оплата уходит в очередь и досылается
 * через spend_crystal_offline, когда появится сеть. Если тот же кристалл тем временем потратили
 * на другом устройстве, оплата всё равно засчитывается, а мастер видит в журнале crystal_conflict.
 */
object Crystals {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("crystals", Context.MODE_PRIVATE)

    fun paid(ctx: Context, charId: String, contentId: String, variant: String): Boolean =
        "$charId|$contentId|$variant" in Repo.get(ctx).paid || sp(ctx).getBoolean("$charId|$contentId|$variant", false)

    fun markPaid(ctx: Context, charId: String, contentId: String, variant: String) {
        sp(ctx).edit().putBoolean("$charId|$contentId|$variant", true).apply()
    }

    /** После загрузки базы с сервера локальные отметки не нужны: мастер мог сбросить энергию объекта.
     *  Пока есть недосланные оплаты без сети — отметки сохраняем. */
    fun clearLocal(ctx: Context) { if (!Store.pendingSpends(ctx)) sp(ctx).edit().clear().apply() }

    fun isCrystal(ctx: Context, code: String): Boolean = code.trim().uppercase() in Repo.get(ctx).crystals

    data class Result(val result: String, val paid: Int, val price: Int, val completed: Boolean, val offline: Boolean = false)

    /** Списать кристалл за объект. Нет сети — оплата по скачанной базе с досылкой позже. Вызывать не из главного потока. */
    fun spend(ctx: Context, code: String, p: Person, item: ContentItem, variant: String, price: Int): Result {
        val c = code.trim().uppercase()
        val body = JSONObject().put("p_code", c).put("p_character", p.id).put("p_content", item.id)
            .put("p_module", p.moduleId ?: JSONObject.NULL).put("p_source", "scanner").put("p_variant", variant)
        val r = try {
            val o = JSONObject(Store.rpc(ctx, "spend_crystal", body))
            Result(o.optString("result"), o.optInt("paid"), o.optInt("price", price), o.optBoolean("completed"))
        } catch (e: Store.HttpFail) {
            throw e
        } catch (e: java.io.IOException) {
            spendLocal(ctx, c, p, item, variant, price)
        }
        if (r.completed || r.result == "already_paid") markPaid(ctx, p.id, item.id, variant)
        if (r.result == "ok" && !r.offline) sp(ctx).edit().putBoolean("spent:$c", true).apply()
        Store.log(ctx, p, if (r.result == "ok") "crystal_spend" else "crystal_reject",
            JSONObject().put("code", c).put("contentId", item.id).put("variant", variant).put("result", r.result).put("paid", r.paid).put("price", r.price)
                .also { if (r.offline) it.put("offline", true) })
        Store.pushLater(ctx)
        return r
    }

    /** Оплата без сети: по скачанной базе и отметкам этого телефона. */
    private fun spendLocal(ctx: Context, c: String, p: Person, item: ContentItem, variant: String, price: Int): Result {
        val db = Repo.get(ctx)
        val key = "${p.id}|${item.id}|$variant"
        val have = (db.payCounts[key] ?: 0) + sp(ctx).getInt("cnt:$key", 0)
        if (paid(ctx, p.id, item.id, variant) || price <= 0) return Result("already_paid", have, price, true, true)
        if (c !in db.crystals) return Result("unknown", have, price, false, true)
        if (c in db.spentCrystals || sp(ctx).getBoolean("spent:$c", false)) return Result("spent", have, price, false, true)
        val now = have + 1
        sp(ctx).edit().putBoolean("spent:$c", true).putInt("cnt:$key", sp(ctx).getInt("cnt:$key", 0) + 1).apply()
        Store.spendOffline(ctx, p, item, variant, c)
        return Result("ok", now, price, now >= price, true)
    }
}
