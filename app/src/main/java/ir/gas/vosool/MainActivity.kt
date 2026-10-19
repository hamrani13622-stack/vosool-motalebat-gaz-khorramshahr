
package ir.gas.vosool

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(28), dp(20), dp(20))
            setBackgroundColor(Color.rgb(245, 248, 252))
        }

        val title = TextView(this).apply {
            text = "وصول مطالبات گاز قادر خرمشهر"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(20, 65, 110))
            setPadding(0, dp(20), 0, dp(16))
        }
        root.addView(title)

        val description = TextView(this).apply {
            text = "سامانه اطلاع‌رسانی بدهی مشترکین"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.DKGRAY)
        }
        root.addView(description)

        val status = TextView(this).apply {
            text = "برای شروع، فایل اکسل مشترکین را انتخاب کنید."
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(24))
        }
        root.addView(status)

        val importButton = Button(this).apply {
            text = "انتخاب فایل اکسل"
            isAllCaps = false
        }
        root.addView(importButton)

        importButton.setOnClickListener {
            status.text =
                "مرحله ورود اکسل در نسخه بعدی تکمیل می‌شود."
        }

        val reportButton = Button(this).apply {
            text = "گزارش پیامک‌ها"
            isAllCaps = false
        }
        root.addView(reportButton)

        reportButton.setOnClickListener {
            status.text =
                "گزارش ارسال پس از پیاده‌سازی پایگاه داده نمایش داده می‌شود."
        }

        setContentView(root)
    }
}
