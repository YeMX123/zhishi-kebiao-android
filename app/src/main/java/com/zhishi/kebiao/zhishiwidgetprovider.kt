package com.zhishi.kebiao

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Base64
import android.view.View
import android.widget.RemoteViews
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class ZhishiWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) updateWidget(context, mgr, id)
    }

    private data class CourseSlot(
        val startSec: Long,
        val endSec: Long,
        val name: String,
        val loc: String,
        val colorHex: String
    )

    companion object {
        private val WEEKDAYS = arrayOf("日", "一", "二", "三", "四", "五", "六")
        private val LEFT_ITEMS = intArrayOf(R.id.left_item_0, R.id.left_item_1)
        private val LEFT_BARS = intArrayOf(R.id.left_bar_0, R.id.left_bar_1)
        private val LEFT_NAMES = intArrayOf(R.id.left_name_0, R.id.left_name_1)
        private val LEFT_TIMES = intArrayOf(R.id.left_time_0, R.id.left_time_1)
        private val LEFT_LOCS = intArrayOf(R.id.left_loc_0, R.id.left_loc_1)

        private val RIGHT_ITEMS = intArrayOf(R.id.right_item_0, R.id.right_item_1, R.id.right_item_2)
        private val RIGHT_BARS = intArrayOf(R.id.right_bar_0, R.id.right_bar_1, R.id.right_bar_2)
        private val RIGHT_NAMES = intArrayOf(R.id.right_name_0, R.id.right_name_1, R.id.right_name_2)
        private val RIGHT_TIMES = intArrayOf(R.id.right_time_0, R.id.right_time_1, R.id.right_time_2)
        private val RIGHT_LOCS = intArrayOf(R.id.right_loc_0, R.id.right_loc_1, R.id.right_loc_2)

        fun updateAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, ZhishiWidgetProvider::class.java))
            for (id in ids) updateWidget(context, mgr, id)
        }

        private fun makeColorBar(colorHex: String): Bitmap {
            val c = runCatching { Color.parseColor(colorHex) }.getOrDefault(Color.parseColor("#FF4AA5F0"))
            val bmp = Bitmap.createBitmap(12, 132, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c }
            canvas.drawRoundRect(RectF(0f, 0f, 12f, 132f), 6f, 6f, paint)
            return bmp
        }

        private fun updateWidget(context: Context, mgr: AppWidgetManager, id: Int) {
            val prefs = context.getSharedPreferences("zhishi_storage", Context.MODE_PRIVATE)
            val theme = prefs.getString("zs_widget_theme_v1", "auto") ?: "auto"
            val sysDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val isDark = when (theme) {
                "dark" -> true
                "light" -> false
                else -> sysDark
            }

            // Smoked frosted-glass Day/Night palette (identical to patched v3.1.0 appearance)
            val textColor = Color.parseColor(if (isDark) "#FFFFFFFF" else "#FF0F172A")
            val subTextColor = Color.parseColor(if (isDark) "#FFB4C2D6" else "#FF475569")
            val dividerColor = Color.parseColor(if (isDark) "#33FFFFFF" else "#330F172A")
            val accentColor = Color.parseColor(if (isDark) "#FF7DD3FC" else "#FF2563EB")

            val rv = RemoteViews(context.packageName, R.layout.widget_zhishi)
            rv.setInt(R.id.wRoot, "setBackgroundResource", if (isDark) R.drawable.widget_bg_dark else R.drawable.widget_bg_light)
            rv.setTextColor(R.id.date_number, textColor)
            rv.setInt(R.id.week_divider_1, "setBackgroundColor", dividerColor)
            rv.setInt(R.id.week_divider_2, "setBackgroundColor", dividerColor)
            rv.setTextColor(R.id.week_number, subTextColor)
            rv.setTextColor(R.id.week_text, subTextColor)
            rv.setTextColor(R.id.day_prefix, accentColor)
            rv.setTextColor(R.id.day_of_week, accentColor)
            rv.setTextColor(R.id.future_date_label, textColor)
            rv.setTextColor(R.id.left_empty, subTextColor)
            rv.setTextColor(R.id.right_empty, subTextColor)
            rv.setTextColor(R.id.additional_courses_text, subTextColor)

            // Optional custom widget background image
            val bgRaw = prefs.getString("zs_widget_bg_cfg_v2", "") ?: ""
            var hasCustomImg = false
            if (bgRaw.isNotEmpty()) {
                runCatching {
                    val src = JSONObject(bgRaw).optString("src", "")
                    val idx = src.indexOf("base64,")
                    if (idx != -1) {
                        val bytes = Base64.decode(src.substring(idx + 7), Base64.DEFAULT)
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null) {
                            rv.setImageViewBitmap(R.id.wBgImg, bmp)
                            rv.setViewVisibility(R.id.wBgImg, View.VISIBLE)
                            hasCustomImg = true
                        }
                    }
                }
            }
            if (!hasCustomImg) rv.setViewVisibility(R.id.wBgImg, View.GONE)

            // Date header
            val nowSec = System.currentTimeMillis() / 1000L
            val cal = Calendar.getInstance()
            rv.setTextViewText(R.id.date_number, cal.get(Calendar.DAY_OF_MONTH).toString())
            rv.setTextViewText(R.id.day_of_week, WEEKDAYS[cal.get(Calendar.DAY_OF_WEEK) - 1])

            val todayStart = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis / 1000L

            var weekStr = "1"
            val allSlots = mutableListOf<CourseSlot>()
            val raw = prefs.getString("course_data", "") ?: ""
            if (raw.isNotEmpty()) {
                runCatching {
                    val root = JSONObject(raw)
                    val dataObj = root.optJSONObject("data")
                    if (dataObj != null) {
                        val firstMon = dataObj.optLong("first_monday_time", 0L)
                        weekStr = if (firstMon > 0L) {
                            val wk = ((todayStart - firstMon) / 604800L).toInt() + 1
                            if (wk > 0) wk.toString() else dataObj.optString("cur_week_num", "1")
                        } else {
                            dataObj.optString("cur_week_num", "1")
                        }
                    }
                    val arr = dataObj?.optJSONArray("list") ?: root.optJSONArray("course_list")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val c = arr.getJSONObject(i)
                            val name = c.optString("course_name", "")
                            val colorHex = c.optString("color", "#4AA5F0")
                            val groups = c.optJSONArray("groups") ?: continue
                            for (g in 0 until groups.length()) {
                                val grp = groups.getJSONObject(g)
                                val loc = grp.optString("loc", "")
                                val times = grp.optJSONArray("times") ?: continue
                                for (t in 0 until times.length()) {
                                    val tup = times.optJSONArray(t) ?: continue
                                    val startSec = tup.optLong(2, 0L)
                                    val durSec = tup.optLong(3, 2700L)
                                    if (startSec > 0L) {
                                        allSlots.add(CourseSlot(startSec, startSec + durSec, name, loc, colorHex))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            rv.setTextViewText(R.id.week_number, weekStr)

            val hmFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
            val mdFmt = SimpleDateFormat("M月d日", Locale.CHINESE)

            // Exact original ScheduleWidgetProvider logic:
            // Today's active/upcoming courses (hide already-ended courses)
            val todayAll = allSlots.filter { it.startSec in todayStart until (todayStart + 86400L) }.sortedBy { it.startSec }
            val todayUpcoming = todayAll.filter { it.endSec > nowSec }

            // Left column: up to 2 courses from todayUpcoming
            if (todayUpcoming.isEmpty()) {
                for (itemId in LEFT_ITEMS) rv.setViewVisibility(itemId, View.GONE)
                rv.setTextViewText(R.id.left_empty, if (todayAll.isEmpty()) "今天没有课程" else "今天已经没有其他课程了")
                rv.setViewVisibility(R.id.left_empty, View.VISIBLE)
            } else {
                rv.setViewVisibility(R.id.left_empty, View.GONE)
                bindSlots(rv, todayUpcoming.take(2), LEFT_ITEMS, LEFT_BARS, LEFT_NAMES, LEFT_TIMES, LEFT_LOCS, textColor, hmFmt)
            }

            // Right column: if today has >2 upcoming courses, overflow today's remaining courses here;
            // otherwise show the next future day with courses ("明天" / "后天" / "M月d日")
            if (todayUpcoming.size > 2) {
                val overflow = todayUpcoming.drop(2)
                rv.setViewVisibility(R.id.future_date_label, View.GONE)
                rv.setViewVisibility(R.id.right_empty, View.GONE)
                bindSlots(rv, overflow.take(2), RIGHT_ITEMS, RIGHT_BARS, RIGHT_NAMES, RIGHT_TIMES, RIGHT_LOCS, textColor, hmFmt)
                rv.setViewVisibility(R.id.additional_courses_text, View.VISIBLE)
                val extra = overflow.size - 2
                rv.setTextViewText(
                    R.id.additional_courses_text,
                    if (extra > 0) "今天还有${extra}节课" else "今天已经没有其他课程了"
                )
            } else {
                var futureLabel = "明天"
                var futureList = emptyList<CourseSlot>()
                for (offset in 1..14) {
                    val dStart = todayStart + offset * 86400L
                    val found = allSlots.filter { it.startSec in dStart until (dStart + 86400L) }.sortedBy { it.startSec }
                    if (found.isNotEmpty()) {
                        futureList = found
                        futureLabel = when (offset) {
                            1 -> "明天"
                            2 -> "后天"
                            else -> mdFmt.format(Date(dStart * 1000L))
                        }
                        break
                    }
                }
                if (futureList.isEmpty()) {
                    rv.setViewVisibility(R.id.future_date_label, View.GONE)
                    for (itemId in RIGHT_ITEMS) rv.setViewVisibility(itemId, View.GONE)
                    rv.setViewVisibility(R.id.right_empty, View.VISIBLE)
                    rv.setTextViewText(R.id.right_empty, "暂无后续课程")
                    rv.setViewVisibility(R.id.additional_courses_text, View.GONE)
                } else {
                    rv.setViewVisibility(R.id.future_date_label, View.VISIBLE)
                    rv.setTextViewText(R.id.future_date_label, futureLabel)
                    rv.setViewVisibility(R.id.right_empty, View.GONE)
                    bindSlots(rv, futureList.take(2), RIGHT_ITEMS, RIGHT_BARS, RIGHT_NAMES, RIGHT_TIMES, RIGHT_LOCS, textColor, hmFmt)
                    rv.setViewVisibility(R.id.additional_courses_text, View.VISIBLE)
                    val extra = futureList.size - 2
                    rv.setTextViewText(
                        R.id.additional_courses_text,
                        if (extra > 0) "其他${extra}节课程" else "暂无其它课程"
                    )
                }
            }

            val pi = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            rv.setOnClickPendingIntent(R.id.wRoot, pi)
            mgr.updateAppWidget(id, rv)
        }

        private fun bindSlots(
            rv: RemoteViews,
            slots: List<CourseSlot>,
            itemIds: IntArray,
            barIds: IntArray,
            nameIds: IntArray,
            timeIds: IntArray,
            locIds: IntArray,
            textColor: Int,
            hmFmt: SimpleDateFormat
        ) {
            for (i in itemIds.indices) {
                if (i < slots.size) {
                    val slot = slots[i]
                    rv.setViewVisibility(itemIds[i], View.VISIBLE)
                    rv.setImageViewBitmap(barIds[i], makeColorBar(slot.colorHex))
                    rv.setTextViewText(nameIds[i], slot.name)
                    rv.setTextColor(nameIds[i], textColor)
                    val timeRange = hmFmt.format(Date(slot.startSec * 1000L)) + "-" + hmFmt.format(Date(slot.endSec * 1000L))
                    rv.setTextViewText(timeIds[i], timeRange)
                    rv.setTextColor(timeIds[i], textColor)
                    rv.setTextViewText(locIds[i], if (slot.loc.isNotEmpty()) slot.loc else "暂未指定地点")
                    rv.setTextColor(locIds[i], textColor)
                } else {
                    rv.setViewVisibility(itemIds[i], View.GONE)
                }
            }
        }
    }
}