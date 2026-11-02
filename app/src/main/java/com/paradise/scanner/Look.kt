package com.paradise.scanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import java.util.Locale
import kotlin.random.Random

/** Тип сканера: у каждого свой цвет, форма панелей и кнопок, экран сканирования и надписи. */
enum class Kind { MED, TECH, SCI }

object Look {
    @Volatile
    var kind: Kind = Kind.TECH

    fun of(modeId: String?): Kind = when (modeId) {
        "medic" -> Kind.MED
        "sci" -> Kind.SCI
        else -> Kind.TECH
    }

    /** Тип берётся из профиля, выставленного при входе персонажа. */
    fun apply(ctx: Context) {
        kind = of(Prefs(ctx).role)
    }

    val accent: Int
        get() = when (kind) {
            Kind.MED -> Color.parseColor("#F4F8FB")
            Kind.TECH -> Color.parseColor("#3DB8FF")
            Kind.SCI -> Color.parseColor("#46F08C")
        }

    /** Второй цвет для мелких акцентов. */
    val accent2: Int
        get() = when (kind) {
            Kind.MED -> Color.parseColor("#FF5A6E")
            Kind.TECH -> Color.parseColor("#FFB84D")
            Kind.SCI -> Color.parseColor("#B9F94D")
        }

    val kicker: String
        get() = when (kind) {
            Kind.MED -> "✚ БИОМЕДИЦИНСКИЙ СКАНЕР"
            Kind.TECH -> "◈ ТЕХНИЧЕСКИЙ АНАЛИЗАТОР"
            Kind.SCI -> "⬡ СПЕКТРАЛЬНЫЙ АНАЛИЗАТОР"
        }

    val title: String
        get() = when (kind) {
            Kind.MED -> "БИОМОНИТОР"
            Kind.TECH -> "ДИАГНОСТИКА"
            Kind.SCI -> "СПЕКТРОАНАЛИЗ"
        }

    val startLabel: String
        get() = when (kind) {
            Kind.MED -> "✚  НАЧАТЬ ОБСЛЕДОВАНИЕ"
            Kind.TECH -> "▶  НАЧАТЬ СКАНИРОВАНИЕ"
            Kind.SCI -> "⬡  НАЧАТЬ АНАЛИЗ"
        }

    val cancelLabel: String
        get() = when (kind) {
            Kind.MED -> "■  ПРЕРВАТЬ ОБСЛЕДОВАНИЕ"
            Kind.TECH -> "■  ОТМЕНИТЬ СКАНИРОВАНИЕ"
            Kind.SCI -> "■  ОСТАНОВИТЬ АНАЛИЗ"
        }

    val againLabel: String
        get() = when (kind) {
            Kind.MED -> "НОВОЕ ОБСЛЕДОВАНИЕ"
            Kind.TECH -> "НОВОЕ СКАНИРОВАНИЕ"
            Kind.SCI -> "НОВЫЙ АНАЛИЗ"
        }

    val opticLabel: String
        get() = when (kind) {
            Kind.MED -> "◎  ОПТИЧЕСКИЙ СКАН"
            Kind.TECH -> "◎  ОПТИЧЕСКИЙ СКАН"
            Kind.SCI -> "◎  ОПТИЧЕСКИЙ СКАН"
        }

    val searching: String
        get() = when (kind) {
            Kind.MED -> "ПОИСК БИОСИГНАТУР…"
            Kind.TECH -> "ПОИСК СИГНАТУР…"
            Kind.SCI -> "ПОИСК СПЕКТРАЛЬНЫХ СИГНАТУР…"
        }

    val idleHint: String
        get() = when (kind) {
            Kind.MED -> "Нажмите «Начать обследование»\nи поднесите сканер к пациенту или объекту."
            Kind.TECH -> "Нажмите «Начать сканирование»\nи поднесите сканер к объекту."
            Kind.SCI -> "Нажмите «Начать анализ»\nи поднесите сканер к образцу."
        }

    val phrases: List<String>
        get() = when (kind) {
            Kind.MED -> listOf(
                "Калибровка биосенсоров…", "Снятие кардиограммы…", "Анализ состава крови…",
                "Измерение сатурации…", "Проверка нейроактивности…", "Поиск патогенов…", "Сверка с медкартой колонии…"
            )
            Kind.TECH -> listOf(
                "Калибровка сенсорной матрицы…", "Прогрев квантового детектора…", "Фильтрация фонового излучения…",
                "Поиск сигнатур в радиусе 0.1 м…", "Подстройка фазы резонатора…", "Сканирование спектра 13.56 МГц…", "Компенсация магнитных помех…"
            )
            Kind.SCI -> listOf(
                "Настройка спектрометра…", "Разложение спектра поглощения…", "Секвенирование фрагментов…",
                "Изотопный анализ…", "Поиск органических соединений…", "Сравнение с ксенобиологическим каталогом…", "Калибровка по эталону Сола…"
            )
        }

    val analysisPhrases: List<String>
        get() = when (kind) {
            Kind.MED -> listOf("Биосигнатура захвачена…", "Расшифровка показателей…", "Сверка с медкартой…", "Постановка диагноза…", "Формирование заключения…")
            Kind.TECH -> listOf("Сигнатура захвачена. Фиксация…", "Декодирование сигнатуры…", "Сверка с архивом колонии…", "Расшифровка показаний…", "Формирование отчёта…")
            Kind.SCI -> listOf("Образец зафиксирован…", "Спектральное разложение…", "Сопоставление с каталогом…", "Интерпретация данных…", "Формирование протокола…")
        }

    fun telemetry(busy: Boolean): String = when (kind) {
        Kind.MED -> String.format(Locale.US, "ЧСС %d ◇ SpO₂ %d%% ◇ T %.1f°C ◇ АД %d/%d",
            if (busy) 90 + Random.nextInt(30) else 62 + Random.nextInt(10), 95 + Random.nextInt(4), 36.4 + Random.nextDouble() * 0.6,
            110 + Random.nextInt(20), 70 + Random.nextInt(12))
        Kind.TECH -> String.format(Locale.US, "SIG %ddB ◇ ФОН %.2f мЗв ◇ T %+d°C ◇ g %.2f",
            if (busy) -40 - Random.nextInt(30) else -85 - Random.nextInt(10), 0.08 + Random.nextDouble() * 0.09, 17 + Random.nextInt(4), 0.91 + Random.nextDouble() * 0.02)
        Kind.SCI -> String.format(Locale.US, "λ %dнм ◇ pH %.1f ◇ C/N %.2f ◇ ИЗОТОП Σ%d",
            380 + Random.nextInt(360), 6.4 + Random.nextDouble() * 1.4, 4 + Random.nextDouble() * 3, 10 + Random.nextInt(80))
    }

    val letter: Float
        get() = when (kind) {
            Kind.MED -> 0.06f
            Kind.TECH -> 0.14f
            Kind.SCI -> 0.1f
        }

    /** Панель/кнопка в стиле типа сканера. */
    fun panel(ctx: Context, stroke: Int, fill: Int, chamferDp: Float = 14f, brackets: Boolean = true): Drawable = when (kind) {
        Kind.TECH -> HudDrawable(ctx, stroke, fill, chamferDp = chamferDp, brackets = brackets)
        Kind.MED -> GradientDrawable().apply {
            setColor(fill)
            cornerRadius = ctx.dpf(chamferDp + 4f)
            setStroke(ctx.dp(2).coerceAtLeast(1), Pal.withAlpha(stroke, 210))
        }
        Kind.SCI -> OctaDrawable(ctx, stroke, fill, chamferDp * 0.7f)
    }
}

/** Восьмиугольная панель с двойным контуром — для научного сканера. */
class OctaDrawable(ctx: Context, private val stroke: Int, fillColor: Int, cutDp: Float) : Drawable() {
    private val cut = ctx.dpf(cutDp)
    private val gap = ctx.dpf(4f)
    private val outer = Path()
    private val inner = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = fillColor }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.dpf(1.4f); color = Pal.withAlpha(stroke, 200) }
    private val thin = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.dpf(0.8f); color = Pal.withAlpha(stroke, 90) }

    private fun octa(p: Path, l: Float, t: Float, r: Float, b: Float, c: Float) {
        val k = c.coerceAtMost((b - t) / 2.2f).coerceAtMost((r - l) / 2.2f)
        p.reset()
        p.moveTo(l + k, t); p.lineTo(r - k, t); p.lineTo(r, t + k); p.lineTo(r, b - k)
        p.lineTo(r - k, b); p.lineTo(l + k, b); p.lineTo(l, b - k); p.lineTo(l, t + k); p.close()
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val s = line.strokeWidth
        octa(outer, bounds.left + s, bounds.top + s, bounds.right - s, bounds.bottom - s, cut)
        octa(inner, bounds.left + gap, bounds.top + gap, bounds.right - gap, bounds.bottom - gap, cut * 0.75f)
    }

    override fun draw(canvas: Canvas) {
        canvas.drawPath(outer, fill)
        canvas.drawPath(outer, line)
        if (bounds.height() > gap * 5) canvas.drawPath(inner, thin)
    }

    override fun setAlpha(alpha: Int) { fill.alpha = alpha; line.alpha = alpha; thin.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { fill.colorFilter = colorFilter; line.colorFilter = colorFilter; thin.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
