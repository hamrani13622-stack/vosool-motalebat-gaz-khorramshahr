
package ir.gas.vosool

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telephony.SmsManager
import android.util.Xml
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.zip.ZipInputStream

data class Subscriber(
    val subscription: String,
    val phone: String,
    val debt: String,
    val approval: String
)

class MainActivity : Activity() {

    private val prefs by lazy {
        getSharedPreferences("vosool_history", MODE_PRIVATE)
    }

    private val subscribers = mutableListOf<Subscriber>()
    private var currentSubscriber: Subscriber? = null

    private lateinit var statusView: TextView
    private lateinit var subscriberView: TextView
    private lateinit var messageInput: EditText

    companion object {
        private const val PICK_EXCEL = 100
        private const val SAVE_CSV = 101
        private const val SMS_PERMISSION = 200
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 20, 22, 20)
        }

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(content)

        fun addText(text: String, size: Float = 17f): TextView {
            return TextView(this).apply {
                this.text = text
                textSize = size
                gravity = Gravity.END
                setPadding(0, 10, 0, 10)
                content.addView(this)
            }
        }

        fun addButton(text: String, action: () -> Unit) {
            val button = Button(this).apply {
                this.text = text
                setOnClickListener { action() }
            }
            content.addView(
                button,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        addText("وصول مطالبات گاز قادر خرمشهر", 23f)

        statusView = addText("ابتدا فایل اکسل مشترکان را انتخاب کنید.")
        subscriberView = addText("مشترکی انتخاب نشده است.")

        addButton("انتخاب فایل اکسل") {
            chooseExcel()
        }

        addButton("نمایش مشترک بعدی") {
            showNextSubscriber()
        }

        addText("متن پیامک:")

        messageInput = EditText(this).apply {
            gravity = Gravity.END
            minLines = 5
            setText(
                "مشترک گرامی،\n" +
                "شماره اشتراک: {شماره اشتراک}\n" +
                "مبلغ بدهی گاز شما {مبلغ بدهی} ریال است. " +
                "لطفاً جهت پرداخت ظرف 48 ساعت آینده اقدام فرمایید " +
                "و رسید پرداختی را به این شماره ارسال فرمایید."
            )
        }
        content.addView(messageInput)

        addButton("آماده‌سازی پیامک") {
            prepareMessage()
        }

        addButton("تأیید و ارسال پیامک") {
            confirmSend()
        }

        addButton("نمایش گزارش وضعیت") {
            showReport()
        }

        addButton("ذخیره گزارش CSV") {
            saveCsv()
        }

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)
    }

    private fun chooseExcel() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        }
        startActivityForResult(intent, PICK_EXCEL)
    }

    @Deprecated("Uses activity result compatibility")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        when (requestCode) {
            PICK_EXCEL -> {
                try {
                    contentResolver.openInputStream(uri).use { input ->
                        if (input == null) {
                            toast("فایل قابل خواندن نیست.")
                            return
                        }

                        val rows = readXlsx(input)
                        if (rows.isEmpty()) {
                            toast("هیچ ردیف مشترکی در برگه اول پیدا نشد.")
                            return
                        }

                        subscribers.clear()
                        subscribers.addAll(rows)
                        currentSubscriber = null

                        statusView.text =
                            "تعداد مشترکان خوانده‌شده: ${subscribers.size}\n" +
                            "آماده بررسی فهرست هستید."

                        subscriberView.text =
                            "برای نمایش اولین مشترک، دکمه نمایش مشترک بعدی را بزنید."

                        toast("فایل اکسل خوانده شد.")
                    }
                } catch (e: Exception) {
                    toast("خطا در خواندن اکسل: ${e.message}")
                }
            }

            SAVE_CSV -> {
                try {
                    contentResolver.openOutputStream(uri).use { output ->
                        if (output == null) {
                            toast("امکان ذخیره فایل وجود ندارد.")
                            return
                        }
                        output.bufferedWriter(Charsets.UTF_8).use { writer ->
                            writer.write("\uFEFFشماره اشتراک,شماره همراه,مبلغ بدهی,تأیید ارسال,وضعیت\n")
                            subscribers.forEach { s ->
                                val state = when {
                                    s.approval.equals("بله", true) ->
                                        "مستثنا از ارسال"
                                    !s.approval.equals("خیر", true) ->
                                        "نیازمند بررسی"
                                    prefs.getBoolean("sent_${s.subscription}", false) ->
                                        "درخواست ارسال ثبت شده"
                                    else -> "ارسال نشده"
                                }

                                val fields = listOf(
                                    s.subscription,
                                    s.phone,
                                    s.debt,
                                    s.approval,
                                    state
                                ).map {
                                    "\"" + it.replace("\"", "\"\"") + "\""
                                }

                                writer.write(fields.joinToString(",") + "\n")
                            }
                        }
                    }
                    toast("گزارش CSV ذخیره شد.")
                } catch (e: Exception) {
                    toast("خطا در ذخیره گزارش: ${e.message}")
                }
            }
        }
    }

    private fun readXlsx(input: InputStream): List<Subscriber> {
        val entries = mutableMapOf<String, ByteArray>()

        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    entries[entry.name] = zip.readBytes()
                }
                zip.closeEntry()
            }
        }

        val strings = mutableListOf<String>()

        entries["xl/sharedStrings.xml"]?.let { bytes ->
            val parser = Xml.newPullParser()
            parser.setInput(bytes.inputStream(), "UTF-8")

            var insideSi = false
            val text = StringBuilder()
            var event = parser.eventType

            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name == "si") {
                            insideSi = true
                            text.setLength(0)
                        } else if (insideSi && parser.name == "t") {
                            text.append(parser.nextText())
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        if (parser.name == "si") {
                            strings.add(text.toString())
                            insideSi = false
                        }
                    }
                }
                event = parser.next()
            }
        }

        val sheetBytes = entries["xl/worksheets/sheet1.xml"]
            ?: throw IllegalArgumentException(
                "برگه اول فایل اکسل پیدا نشد."
            )

        val parser = Xml.newPullParser()
        parser.setInput(sheetBytes.inputStream(), "UTF-8")

        val result = mutableListOf<List<String>>()
        var row = mutableMapOf<Int, String>()
        var cellRef = ""
        var cellType = ""
        var cellValue = ""
        var insideCell = false
        var event = parser.eventType

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "row" -> row = mutableMapOf()

                        "c" -> {
                            cellRef = parser.getAttributeValue(null, "r") ?: ""
                            cellType = parser.getAttributeValue(null, "t") ?: ""
                            cellValue = ""
                            insideCell = true
                        }

                        "v" -> {
                            if (insideCell) {
                                cellValue = parser.nextText()
                            }
                        }

                        "t" -> {
                            if (insideCell && cellType == "inlineStr") {
                                cellValue += parser.nextText()
                            }
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "c" -> {
                            val letters = cellRef.takeWhile { it.isLetter() }
                            var column = 0
                            letters.forEach { ch ->
                                column = column * 26 +
                                    (ch.uppercaseChar() - 'A' + 1)
                            }
                            column -= 1

                            val value = if (cellType == "s") {
                                strings.getOrNull(
                                    cellValue.toIntOrNull() ?: -1
                                ) ?: ""
                            } else {
                                cellValue
                            }

                            if (column >= 0) row[column] = value
                            insideCell = false
                        }

                        "row" -> {
                            val maxColumn = row.keys.maxOrNull() ?: -1
                            result.add(
                                (0..maxColumn).map { row[it] ?: "" }
                            )
                        }
                    }
                }
            }
            event = parser.next()
        }

        // ردیف اول، عنوان ستون‌ها است.
        return result.drop(1).mapNotNull { cells ->
            val subscription = cells.getOrElse(0) { "" }.trim()
            val phone = cells.getOrElse(1) { "" }.trim()
            val debt = cells.getOrElse(2) { "" }
                .trim().replace(",", "").replace("٬", "")
            val approval = cells.getOrElse(3) { "" }.trim()

            if (subscription.isBlank()) {
                null
            } else {
                Subscriber(subscription, phone, debt, approval)
            }
        }
    }

    private fun isEligible(s: Subscriber): Boolean {
        return s.approval.equals("خیر", true) &&
            !prefs.getBoolean("sent_${s.subscription}", false)
    }

    private fun showNextSubscriber() {
        val currentIndex = if (currentSubscriber == null) {
            -1
        } else {
            subscribers.indexOf(currentSubscriber)
        }

        val next = subscribers.indices
            .drop(currentIndex + 1)
            .firstOrNull { isEligible(subscribers[it]) }

        if (next == null) {
            currentSubscriber = null
            subscriberView.text =
                "مشترک واجد شرایط دیگری برای نمایش وجود ندارد.\n" +
                "موارد خالی یا نامشخص را از گزارش بررسی کنید."
            return
        }

        val s = subscribers[next]
        currentSubscriber = s

        subscriberView.text =
            "شماره اشتراک: ${s.subscription}\n" +
            "شماره همراه: ${s.phone}\n" +
            "مبلغ بدهی: ${s.debt} ریال\n" +
            "تأیید ارسال در اکسل: ${s.approval}\n" +
            "وضعیت: آماده بررسی"

        val template =
            "مشترک گرامی،\n" +
            "شماره اشتراک: {شماره اشتراک}\n" +
            "مبلغ بدهی گاز شما {مبلغ بدهی} ریال است. " +
            "لطفاً جهت پرداخت ظرف 48 ساعت آینده اقدام فرمایید " +
            "و رسید پرداختی را به این شماره ارسال فرمایید."

        messageInput.setText(template)
    }

    private fun prepareMessage() {
        val s = currentSubscriber
        if (s == null) {
            toast("ابتدا یک مشترک را نمایش دهید.")
            return
        }

        if (!s.approval.equals("خیر", true)) {
            toast("این مشترک تأیید ارسال «خیر» ندارد.")
            return
        }

        if (!s.phone.matches(Regex("^09[0-9]{9}$"))) {
            toast("شماره همراه معتبر نیست.")
            return
        }

        if (s.debt.toLongOrNull() == null) {
            toast("مبلغ بدهی معتبر نیست.")
            return
        }

        val message = messageInput.text.toString()
            .replace("{شماره اشتراک}", s.subscription)
            .replace("{مبلغ بدهی}", s.debt)

        messageInput.setText(message)
        statusView.text = "متن پیامک آماده بررسی است."
    }

    private fun confirmSend() {
        val s = currentSubscriber
        if (s == null) {
            toast("ابتدا یک مشترک را انتخاب کنید.")
            return
        }

        if (!isEligible(s)) {
            toast("این مشترک واجد شرایط ارسال نیست یا قبلاً ثبت شده است.")
            return
        }

        val message = messageInput.text.toString()
        if (message.contains("{شماره اشتراک}") ||
            message.contains("{مبلغ بدهی}")
        ) {
            toast("ابتدا پیامک را آماده کنید.")
            return
        }

        AlertDialog.Builder(this)
            .setTitle("تأیید نهایی ارسال")
            .setMessage(
                "شماره اشتراک: ${s.subscription}\n" +
                "شماره همراه: ${s.phone}\n\n$message\n\n" +
                "ارسال این پیامک را تأیید می‌کنید؟"
            )
            .setNegativeButton("انصراف", null)
            .setPositiveButton("تأیید و ارسال") { _, _ ->
                sendSms(s, message)
            }
            .show()
    }

    private fun sendSms(s: Subscriber, message: String) {
        if (checkSelfPermission(Manifest.permission.SEND_SMS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.SEND_SMS),
                SMS_PERMISSION
            )
            toast("مجوز پیامک را تأیید کنید و دوباره ارسال را بزنید.")
            return
        }

        try {
            @Suppress("DEPRECATION")
            val manager = SmsManager.getDefault()

            val parts = manager.divideMessage(message)

            if (parts.size > 1) {
                manager.sendMultipartTextMessage(
                    s.phone, null, parts, null, null
                )
            } else {
                manager.sendTextMessage(
                    s.phone, null, message, null, null
                )
            }

            val record =
                "اشتراک: ${s.subscription}\n" +
                "تلفن: ${s.phone}\n" +
                "مبلغ: ${s.debt}\n" +
                "وضعیت: درخواست ارسال ثبت شد؛ تحویل تأیید نشده است."

            prefs.edit()
                .putBoolean("sent_${s.subscription}", true)
                .putString("record_${s.subscription}", record)
                .apply()

            statusView.text =
                "درخواست ارسال برای اشتراک ${s.subscription} ثبت شد.\n" +
                "تحویل پیامک هنوز تأیید نشده است."

            toast("درخواست ارسال ثبت شد.")
        } catch (e: Exception) {
            toast("خطا در ارسال: ${e.message ?: "نامشخص"}")
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

        if (requestCode == SMS_PERMISSION) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                toast("مجوز داده شد. دوباره ارسال را تأیید کنید.")
            } else {
                toast("مجوز پیامک داده نشد.")
            }
        }
    }

    private fun showReport() {
        val sent = subscribers.count {
            prefs.getBoolean("sent_${it.subscription}", false)
        }
        val excluded = subscribers.count {
            it.approval.equals("بله", true)
        }
        val needsReview = subscribers.count {
            !it.approval.equals("بله", true) &&
                !it.approval.equals("خیر", true)
        }
        val waiting = subscribers.count {
            isEligible(it)
        }

        val history = prefs.all
            .filterKeys { it.startsWith("record_") }
            .values.filterIsInstance<String>()

        val report =
            "تعداد رکوردها: ${subscribers.size}\n" +
            "تأیید بله، مستثنا: $excluded\n" +
            "نیازمند بررسی: $needsReview\n" +
            "آماده ارسال: $waiting\n" +
            "درخواست‌های ثبت‌شده در فایل فعلی: $sent\n\n" +
            history.joinToString("\n\n").ifBlank {
                "سابقه‌ای ثبت نشده است."
            }

        AlertDialog.Builder(this)
            .setTitle("گزارش وضعیت")
            .setMessage(report)
            .setPositiveButton("بستن", null)
            .show()
    }

    private fun saveCsv() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/csv"
            putExtra(Intent.EXTRA_TITLE, "vosool_report.csv")
        }
        startActivityForResult(intent, SAVE_CSV)
    }

    private fun toast(message: String) {
        Toast.makeText(
            this,
            message,
            Toast.LENGTH_LONG
        ).show()
    }
}
