package ir.gas.vosool

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

private lateinit var statusText: TextView

private val excelPicker =
    registerForActivityResultCompatPlaceholder()

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val layout = LinearLayout(this)
    layout.orientation = LinearLayout.VERTICAL
    layout.setPadding(32, 40, 32, 32)
    layout.gravity = Gravity.CENTER_HORIZONTAL

    val title = TextView(this)
    title.text = "وصول مطالبات گاز قادر خرمشهر"
    title.textSize = 23f
    title.typeface = Typeface.DEFAULT_BOLD
    title.gravity = Gravity.CENTER
    layout.addView(title)

    val subtitle = TextView(this)
    subtitle.text = "سامانه مدیریت مطالبات و پیامک مشترکان"
    subtitle.textSize = 16f
    subtitle.gravity = Gravity.CENTER
    subtitle.setPadding(0, 20, 0, 32)
    layout.addView(subtitle)

    val selectButton = Button(this)
    selectButton.text = "انتخاب فایل اکسل"
    layout.addView(
        selectButton,
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    )

    statusText = TextView(this)
    statusText.text = "هنوز فایلی انتخاب نشده است."
    statusText.textSize = 16f
    statusText.setPadding(0, 24, 0, 24)
    layout.addView(statusText)

    val reportButton = Button(this)
    reportButton.text = "گزارش پیامک‌ها"
    layout.addView(
        reportButton,
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    )

    selectButton.setOnClickListener {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "*/*"
        intent.putExtra(
            Intent.EXTRA_MIME_TYPES,
            arrayOf(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-excel"
            )
        )
        startActivityForResult(intent, 100)
    }

    reportButton.setOnClickListener {
        statusText.text =
            "گزارش پیامک‌ها در مرحله پیاده‌سازی است."
    }

    setContentView(layout)
}

@Deprecated("Use Activity Result API in a later update")
override fun onActivityResult(
    requestCode: Int,
    resultCode: Int,
    data: Intent?
) {
    super.onActivityResult(requestCode, resultCode, data)

    if (requestCode == 100 && resultCode == RESULT_OK) {
        val uri: Uri? = data?.data

        if (uri != null) {
            statusText.text =
                "فایل انتخاب شد.\nآماده بررسی اطلاعات اکسل هستیم."
        }
    }
}

private fun registerForActivityResultCompatPlaceholder(): Nothing? {
    return null
}

}
