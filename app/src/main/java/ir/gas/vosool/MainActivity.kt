
package ir.gas.vosool

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.telephony.SmsManager
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var subscriptionInput: EditText
    private lateinit var phoneInput: EditText
    private lateinit var debtInput: EditText
    private lateinit var statusText: TextView

    private val prefs by lazy {
        getSharedPreferences("vosool_sms_history", MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 30, 28, 24)
        }

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(content)

        val title = TextView(this).apply {
            text = "وصول مطالبات گاز قادر خرمشهر"
            textSize = 23f
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 24)
        }
        content.addView(title)

        subscriptionInput = addField(
            content, "شماره اشتراک", "مثلاً 12345678"
        )

        phoneInput = addField(
            content, "شماره تلفن همراه", "مثلاً 09xxxxxxxxx"
        )

        debtInput = addField(
            content, "مبلغ بدهی به ریال", "مثلاً 12500000"
        )

        val previewButton = Button(this).apply {
            text = "پیش‌نمایش پیامک"
        }
        content.addView(previewButton)

        statusText = TextView(this).apply {
            text = "اطلاعات مشترک را وارد کنید."
            textSize = 16f
            setPadding(0, 20, 0, 20)
        }
        content.addView(statusText)

        val sendButton = Button(this).apply {
            text = "بررسی و تأیید ارسال پیامک"
        }
        content.addView(sendButton)

        val historyButton = Button(this).apply {
            text = "گزارش ارسال‌های ثبت‌شده"
        }
        content.addView(historyButton)

        previewButton.setOnClickListener {
            val message = buildMessage()
            if (message == null) {
                toast("شماره اشتراک، تلفن همراه و مبلغ بدهی را کامل وارد کنید.")
            } else {
                statusText.text = message
            }
        }

        sendButton.setOnClickListener {
            confirmAndSend()
        }

        historyButton.setOnClickListener {
            showHistory()
        }

        page.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        setContentView(page)
    }

    private fun addField(
        parent: LinearLayout,
        label: String,
        hintText: String
    ): EditText {
        val labelView = TextView(this).apply {
            text = label
            textSize = 16f
            setPadding(0, 10, 0, 4)
        }
        parent.addView(labelView)

        val field = EditText(this).apply {
            hint = hintText
            singleLine = true
            textSize = 16f
        }
        parent.addView(
            field,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        return field
    }

    private fun buildMessage(): String? {
        val subscription = subscriptionInput.text.toString().trim()
        val phone = phoneInput.text.toString().trim()
        val debt = debtInput.text.toString().trim()

        if (subscription.isBlank() || phone.isBlank() || debt.isBlank()) {
            return null
        }

        if (!phone.matches(Regex("^09[0-9]{9}$"))) {
            return null
        }

        if (debt.toLongOrNull() == null || debt.toLong() <= 0L) {
            return null
        }

        return "مشترک گرامی،\n" +
            "شماره اشتراک: $subscription\n" +
            "مبلغ بدهی گاز شما $debt ریال است. " +
            "لطفاً جهت پرداخت ظرف 48 ساعت آینده اقدام فرمایید " +
            "و رسید پرداختی را به این شماره ارسال فرمایید."
    }

    private fun confirmAndSend() {
        val subscription = subscriptionInput.text.toString().trim()
        val phone = phoneInput.text.toString().trim()
        val message = buildMessage()

        if (message == null) {
            toast("اطلاعات را بررسی کنید؛ شماره تلفن باید ۱۱ رقم و با 09 شروع شود.")
            return
        }

        if (prefs.getBoolean("sent_$subscription", false)) {
            toast("برای این شماره اشتراک قبلاً ارسال ثبت شده است.")
            return
        }

        AlertDialog.Builder(this)
            .setTitle("تأیید نهایی ارسال")
            .setMessage(
                "شماره اشتراک: $subscription\n" +
                "شماره همراه: $phone\n\n$message\n\n" +
                "آیا ارسال این پیامک را تأیید می‌کنید؟"
            )
            .setNegativeButton("انصراف", null)
            .setPositiveButton("تأیید و ارسال") { _, _ ->
                sendSms(subscription, phone, message)
            }
            .show()
    }

    private fun sendSms(
        subscription: String,
        phone: String,
        message: String
    ) {
        if (checkSelfPermission(Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.SEND_SMS),
                200
            )
            toast("مجوز پیامک را تأیید کنید؛ سپس دوباره ارسال را بزنید.")
            return
        }

        try {
            @Suppress("DEPRECATION")
            val manager = SmsManager.getDefault()

            val parts = manager.divideMessage(message)
            if (parts.size > 1) {
                manager.sendMultipartTextMessage(
                    phone, null, parts, null, null
                )
            } else {
                manager.sendTextMessage(
                    phone, null, message, null, null
                )
            }

            val timestamp = System.currentTimeMillis()
            prefs.edit()
                .putBoolean("sent_$subscription", true)
                .putString(
                    "record_$subscription",
                    "$subscription | $phone | $timestamp"
                )
                .apply()

            statusText.text =
                "درخواست ارسال برای اشتراک $subscription ثبت شد.\n" +
                "این ثبت به معنی تأیید تحویل پیامک نیست."

            toast("درخواست ارسال پیامک ثبت شد.")
        } catch (e: Exception) {
            toast("ارسال انجام نشد: ${e.message ?: "خطای نامشخص"}")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode, permissions, grantResults
        )

        if (requestCode == 200) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                toast("مجوز داده شد. اطلاعات را بررسی و دوباره ارسال را انتخاب کنید.")
            } else {
                toast("بدون مجوز پیامک، امکان ارسال وجود ندارد.")
            }
        }
    }

    private fun showHistory() {
        val all = prefs.all
        val records = all
            .filterKeys { it.startsWith("record_") }
            .values
            .filterIsInstance<String>()

        val text = if (records.isEmpty()) {
            "هنوز ارسال ثبت‌شده‌ای وجود ندارد."
        } else {
            records.joinToString("\n\n")
        }

        AlertDialog.Builder(this)
            .setTitle("گزارش ارسال‌های ثبت‌شده")
            .setMessage(text)
            .setPositiveButton("بستن", null)
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
