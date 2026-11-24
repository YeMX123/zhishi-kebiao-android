package com.zhishi.kebiao

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
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

    companion object {
        fun updateAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, ZhishiWidgetProvider::class.java))
            for (id in ids) updateWidget(context, mgr, id)
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

            val rv = RemoteViews(context.packageName, R.layout.widget_zhishi)
            rv.setInt(R.id.wRoot, "setBackgroundResource", if (isDark) R.drawable.widget_bg_dark else R.drawable.widget_bg_light)
            rv.setTextColor(R.id.wHeader, Color.parseColor(if (isDark) "#FFFFFFFF" else "#FF0F172A"))
            rv.setTextColor(R.id.wBadge, Color.parseColor(if (isDark) "#FF7DD3FC" else "#FF2563EB"))
            rv.setTextColor(R.id.wBody, Color.parseColor(if (isDark) "#FFF1F5F9" else "#FF1E293B"))

            // Custom cropped widget background image (supports base64 & file/content URI)
            val bgRaw = prefs.getString("zs_widget_bg_cfg_v2", "") ?: ""
            var hasCustomImg = false
            if (bgRaw.isNotEmpty()) {
                runCatching {
                    val src = JSONObject(bgRaw).optString("src", "")
                    val bmp = when {
                        src.contains("base64,") -> {
                            val bytes = Base64.decode(src.substringAfter("base64,"), Base64.DEFAULT)
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        }
                        src.startsWith("file://") -> BitmapFactory.decodeFile(Uri.parse(src).path)
                        src.startsWith("content://") -> context.contentResolver.openInputStream(Uri.parse(src))?.use { BitmapFactory.decodeStream(it) }
                        else -> null
                    }
                    if (bmp != null) {
                        rv.setImageViewBitmap(R.id.wBgImg, bmp)
                        rv.setViewVisibility(R.id.wBgImg, View.VISIBLE)
                        hasCustomImg = true
                    }
                }
            }
            if (!hasCustomImg) rv.setViewVisibility(R.id.wBgImg, View.GONE)

            val cal = Calendar.getInstance()
            val todayStr = SimpleDateFormat("M月d日 E", Locale.CHINESE).format(cal.time)
            rv.setTextViewText(R.id.wHeader, todayStr)

            val raw = prefs.getString("course_data", "") ?: ""
            val lines = mutableListOf<String>()
            var weekTag = "知时课表"
            if (raw.isNotEmpty()) {
                runCatching {
                    val root = JSONObject(raw)
                    val dataObj = root.optJSONObject("data")
                    val wkNum = dataObj?.optString("cur_week_num", "") ?: ""
                    if (wkNum.isNotEmpty()) weekTag = "第${wkNum}周"
                    val arr = dataObj?.optJSONArray("list") ?: root.optJSONArray("course_list")
                    val dayStart = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    }.timeInMillis / 1000
                    val dayEnd = dayStart + 86400
                    if (arr != null) {
                        val items = mutableListOf<Pair<Long, String>>()
                        for (i in 0 until arr.length()) {
                            val c = arr.getJSONObject(i)
                            val name = c.optString("course_name", "")
                            val groups = c.optJSONArray("groups") ?: continue
                            for (g in 0 until groups.length()) {
                                val grp = groups.getJSONObject(g)
                                val loc = grp.optString("loc", "")
                                val times = grp.optJSONArray("times") ?: continue
                                for (t in 0 until times.length()) {
                                    val tup = times.optJSONArray(t) ?: continue
                                    val sec = tup.optLong(2, 0L)
                                    if (sec in dayStart until dayEnd) {
                                        val hm = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(sec * 1000))
                                        items.add(sec to "$hm  $name" + if (loc.isNotEmpty()) " · $loc" else "")
                                    }
                                }
                            }
                        }
                        items.sortBy { it.first }
                        for ((_, txt) in items.take(4)) lines.add(txt)
                    }
                }
            }
            rv.setTextViewText(R.id.wBadge, weekTag)
            rv.setTextViewText(R.id.wBody, if (lines.isNotEmpty()) lines.joinToString("\n") else "今日暂无课程 · 享受轻松时光")

            val pi = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            rv.setOnClickPendingIntent(R.id.wRoot, pi)
            mgr.updateAppWidget(id, rv)
        }
    }
}
