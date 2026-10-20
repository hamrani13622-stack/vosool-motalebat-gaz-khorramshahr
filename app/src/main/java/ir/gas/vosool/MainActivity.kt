
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 24)
        }

        val scroll = ScrollView(this)
        scroll.addView(content)

        val title = TextView(this).apply {
            text = "وصول مطالبات گاز قادر خرمشهر"
            textSize = 23f
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 24)
        }
        content.addView(title)

        subscriptionInput = addField(
            content,
            "شماره اشتراک",
            "مثلاً 12345678"
        )

        phoneInput = addField(
            content,
            "شماره تلفن همراه",
            "مثلاً 09123456789"
        )

        debtInput = addField(
            content,
            "مبلغ بدهی به ریال",
            "مثلاً 12500000"
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
                toast(
                    "شماره اشتراک، شماره همراه و مبلغ بدهی را بررسی کنید."
                )
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

        setContentView(scroll)
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
            setSingleLine(true)
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
        val subscription =
            subscriptionInput.text.toString().trim()

        val phone =
            phoneInput.text.toString().trim()

        val debtText =
            debtInput.text.toString().trim()
                .replace(",", "")
                .replace("٬", "")
                .replace(" ", "")

        if (subscription.isBlank() ||
            phone.isBlank() ||
            debtText.isBlank()
        ) {
            return null
        }

        if (!phone.matches(Regex("^09[0-9]{9}$"))) {
            return null
        }

        val debt = debtText.toLongOrNull()
            ?: return null

        if (debt <= 0L) {
            return null
        }

        val formattedDebt = String.format(
            Locale.US,
            "%,d",
            debt
        )

        return "مشترک گرامی،\n" +
            "شماره اشتراک: $subscription\n" +
            "مبلغ بدهی گاز شما $formattedDebt ریال است. " +
            "لطفاً جهت پرداخت ظرف 48 ساعت آینده اقدام فرمایید " +
            "و رسید پرداختی را به این شماره ارسال فرمایید."
    }

    private fun confirmAndSend() {
        val subscription =
            subscriptionInput.text.toString().trim()

        val phone =
            phoneInput.text.toString().trim()

        val message = buildMessage()

        if (message == null) {
            toast(
                "اطلاعات را بررسی کنید. شماره همراه باید با 09 شروع شود و 11 رقم باشد."
            )
            return
        }

        if (prefs.getBoolean("sent_$subscription", false)) {
            toast(
                "برای این شماره اشتراک قبلاً درخواست ارسال ثبت شده است."
            )
            return
        }

        AlertDialog.Builder(this)
            .setTitle("تأیید نهایی ارسال")
            .setMessage(
                "شماره اشتراک: $subscription\n" +
                    "شماره همراه: $phone\n\n" +
                    "$message\n\n" +
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
                REQUEST_SMS_PERMISSION
            )
            toast(
                "مجوز پیامک را تأیید کنید؛ سپس دوباره ارسال را بزنید."
            )
            return
        }

        try {
            @Suppress("DEPRECATION")
            val smsManager = SmsManager.getDefault()

            val parts = smsManager.divideMessage(message)

            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(
                    phone,
                    null,
                    parts,
                    null,
                    null
                )
            } else {
                smsManager.sendTextMessage(
                    phone,
                    null,
                    message,
                    null,
                    null
                )
            }

            val timestamp = System.currentTimeMillis()
            val date = SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss",
                Locale.getDefault()
            ).format(Date(timestamp))

            val record =
                "اشتراک: $subscription\n" +
                "تلفن: $phone\n" +
                "زمان درخواست ارسال: $date\n" +
                "وضعیت: درخواست ارسال به سیستم گوشی سپرده شد؛ تحویل تأیید نشده است."

            prefs.edit()
                .putBoolean("sent_$subscription", true)
                .putString("record_$subscription", record)
                .apply()

            statusText.text =
                "درخواست ارسال برای اشتراک $subscription ثبت شد.\n" +
                "تحویل پیامک هنوز تأیید نشده است."

            toast("درخواست ارسال ثبت شد.")

        } catch (e: Exception) {
            toast(
                "ارسال انجام نشد: " +
                    (e.message ?: "خطای نامشخص")
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == REQUEST_SMS_PERMISSION) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                toast(
                    "مجوز داده شد. اطلاعات را بررسی و دوباره ارسال را انتخاب کنید."
                )
            } else {
                toast(
                    "بدون مجوز پیامک، امکان ارسال وجود ندارد."
                )
            }
        }
    }

    private fun showHistory() {
        val records = prefs.all
            .filterKeys { it.startsWith("record_") }
            .values
            .filterIsInstance<String>()
            .sorted()

        val text = if (records.isEmpty()) {
            "هنوز درخواست ارسالی ثبت نشده است."
        } else {
            records.joinToString("\n\n--------------------\n\n")
        }

        AlertDialog.Builder(this)
            .setTitle("گزارش ارسال‌های ثبت‌شده")
            .setMessage(text)
            .setPositiveButton("بستن", null)
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(
            this,
            message,
            Toast.LENGTH_LONG
        ).show()
    }

    companion object {
        private const val REQUEST_SMS_PERMISSION = 200
    }
}
