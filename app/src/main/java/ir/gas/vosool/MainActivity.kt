
package ir.gas.vosool

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
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
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

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
        private const val SAVE_EXCEL = 102
        private const val SMS_PERMISSION = 200
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // کنترل فعال‌سازی قبل از نمایش برنامه اصلی
        if (!ActivationManager.isActivated(this)) {
            startActivity(
                Intent(this, ActivationActivity::class.java)
            )
            finish()
            return
        }

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
            content.addView(
                Button(this).apply {
                    this.text = text
                    setOnClickListener { action() }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        addText("وصول مطالبات گاز قادر خرمشهر", 23f)

        statusView = addText("در حال بارگذاری اطلاعات...")
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
            setText(defaultMessage())
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

        addButton("ذخیره گزارش Excel") {
            saveExcel()
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

        loadSavedSubscribers()
    }

    private fun defaultMessage() =
        "مشترک گرامی،\n\n" +
        "شماره اشتراک: {شماره اشتراک}\n" +
        "مبلغ بدهی گاز شما {مبلغ بدهی} ریال است. " +
        "لطفاً جهت پرداخت ظرف 48 ساعت آینده اقدام فرمایید " +
        "و رسید پرداختی را به این شماره ارسال فرمایید.\n\n" +
        "لینک سابقه قبوض:\n" +
        "https://portal.nigc-khgc.ir/index.php/site/loginmoshtarak"

    private fun historyKey(s: Subscriber) =
        "sent_${s.subscription}_${s.phone}"

    private fun recordKey(s: Subscriber) =
        "record_${s.subscription}_${s.phone}"

    private fun isSent(s: Subscriber) =
        prefs.getBoolean(historyKey(s), false)

    private fun saveSubscribers() {
        try {
            val array = JSONArray()

            subscribers.forEach { s ->
                val item = JSONObject()
                item.put("subscription", s.subscription)
                item.put("phone", s.phone)
                item.put("debt", s.debt)
                item.put("approval", s.approval)
                array.put(item)
            }

            prefs.edit()
                .putString("subscribers_json", array.toString())
                .apply()

        } catch (e: Exception) {
            toast("خطا در ذخیره اطلاعات: ${e.message}")
        }
    }

    private fun loadSavedSubscribers() {
        try {
            val saved = prefs.getString("subscribers_json", null)

            if (saved.isNullOrBlank()) {
                statusView.text =
                    "ابتدا فایل اکسل مشترکان را انتخاب کنید."
                subscriberView.text = "مشترکی انتخاب نشده است."
                return
            }

            val array = JSONArray(saved)
            subscribers.clear()

            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)

                subscribers.add(
                    Subscriber(
                        subscription = item.optString("subscription"),
                        phone = item.optString("phone"),
                        debt = item.optString("debt"),
                        approval = item.optString("approval")
                    )
                )
            }

            currentSubscriber = null

            statusView.text =
                "اطلاعات ذخیره‌شده بازیابی شد.\n" +
                "تعداد مشترکان: ${subscribers.size}"

            subscriberView.text =
                "برای شروع، نمایش مشترک بعدی را بزنید."

        } catch (e: Exception) {
            statusView.text =
                "بازیابی اطلاعات ناموفق بود. فایل اکسل را دوباره انتخاب کنید."
            toast("خطا در بازیابی اطلاعات: ${e.message}")
        }
    }

    private fun chooseExcel() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type =
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
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
                    val input = contentResolver.openInputStream(uri)
                        ?: throw IllegalArgumentException(
                            "فایل قابل خواندن نیست."
                        )

                    val rows = input.use { readXlsx(it) }

                    if (rows.isEmpty()) {
                        toast("ردیف مشترکی در برگه اول پیدا نشد.")
                        return
                    }

                    subscribers.clear()
                    subscribers.addAll(rows)
                    currentSubscriber = null

                    saveSubscribers()

                    statusView.text =
                        "تعداد مشترکان خوانده‌شده: ${subscribers.size}\n" +
                        "اطلاعات در حافظه برنامه ذخیره شد."

                    subscriberView.text =
                        "برای شروع، نمایش مشترک بعدی را بزنید."

                    toast("اکسل خوانده و اطلاعات ذخیره شد.")

                } catch (e: Exception) {
                    toast("خطا در خواندن اکسل: ${e.message}")
                }
            }

            SAVE_EXCEL -> {
                try {
                    val output = contentResolver.openOutputStream(uri)
                        ?: throw IllegalArgumentException(
                            "امکان ذخیره فایل وجود ندارد."
                        )

                    output.use { writeXlsx(it) }
                    toast("گزارش Excel ذخیره شد.")

                } catch (e: Exception) {
                    toast("خطا در ذخیره اکسل: ${e.message}")
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
                        when (parser.name) {
                            "si" -> {
                                insideSi = true
                                text.setLength(0)
                            }

                            "t" -> if (insideSi) {
                                text.append(parser.nextText())
                            }
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
                "برگه اول اکسل پیدا نشد."
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
                            cellRef =
                                parser.getAttributeValue(null, "r") ?: ""
                            cellType =
                                parser.getAttributeValue(null, "t") ?: ""
                            cellValue = ""
                            insideCell = true
                        }

                        "v" -> if (insideCell) {
                            cellValue = parser.nextText()
                        }

                        "t" -> if (
                            insideCell && cellType == "inlineStr"
                        ) {
                            cellValue += parser.nextText()
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "c" -> {
                            val letters =
                                cellRef.takeWhile { it.isLetter() }

                            var column = 0

                            letters.forEach { ch ->
                                column = column * 26 +
                                    (ch.uppercaseChar() - 'A' + 1)
                            }

                            column--

                            val value = if (cellType == "s") {
                                strings.getOrNull(
                                    cellValue.toIntOrNull() ?: -1
                                ) ?: ""
                            } else {
                                cellValue
                            }

                            if (column >= 0) {
                                row[column] = value
                            }

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

        return result.drop(1).mapNotNull { cells ->
            val subscription =
                cells.getOrElse(0) { "" }.trim()

            val phone =
                cells.getOrElse(1) { "" }.trim()

            val debt =
                cells.getOrElse(2) { "" }
                    .trim()
                    .replace(",", "")
                    .replace("٬", "")

            val approval =
                cells.getOrElse(3) { "" }.trim()

            if (subscription.isBlank()) {
                null
            } else {
                Subscriber(
                    subscription,
                    phone,
                    debt,
                    approval
                )
            }
        }
    }

    // مقدار «بله» مستثنا است؛ مقدار خالی و «خیر» قابل بررسی هستند.
    private fun isEligible(s: Subscriber): Boolean =
        !s.approval.equals("بله", true) && !isSent(s)

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
                "گزارش وضعیت را بررسی کنید."

            return
        }

        val s = subscribers[next]
        currentSubscriber = s

        subscriberView.text =
            "شماره اشتراک: ${s.subscription}\n" +
            "شماره همراه: ${s.phone}\n" +
            "مبلغ بدهی: ${s.debt} ریال\n" +
            "تأیید ارسال در اکسل: " +
            "${s.approval.ifBlank { "خالی؛ نیازمند بررسی" }}\n" +
            "وضعیت: آماده بررسی"

        messageInput.setText(defaultMessage())
    }

    private fun prepareMessage() {
        val s = currentSubscriber

        if (s == null) {
            toast("ابتدا یک مشترک را نمایش دهید.")
            return
        }

        if (s.approval.equals("بله", true) || isSent(s)) {
            toast("این مشترک واجد شرایط ارسال نیست.")
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
            toast(
                "این مشترک واجد شرایط ارسال نیست یا قبلاً ثبت شده است."
            )
            return
        }

        val message = messageInput.text.toString()

        if (
            message.contains("{شماره اشتراک}") ||
            message.contains("{مبلغ بدهی}")
        ) {
            toast("ابتدا پیامک را آماده کنید.")
            return
        }

        AlertDialog.Builder(this)
            .setTitle("تأیید نهایی ارسال")
            .setMessage(
                "شماره اشتراک: ${s.subscription}\n" +
                "شماره همراه: ${s.phone}\n\n" +
                "$message\n\n" +
                "ارسال این پیامک را تأیید می‌کنید؟"
            )
            .setNegativeButton("انصراف", null)
            .setPositiveButton("تأیید و ارسال") { _, _ ->
                sendSms(s, message)
            }
            .show()
    }

    private fun sendSms(s: Subscriber, message: String) {
        if (
            checkSelfPermission(Manifest.permission.SEND_SMS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.SEND_SMS),
                SMS_PERMISSION
            )

            toast(
                "مجوز پیامک را تأیید کنید و سپس دوباره ارسال را بزنید."
            )
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
                .putBoolean(historyKey(s), true)
                .putString(recordKey(s), record)
                .apply()

            saveSubscribers()

            statusView.text =
                "درخواست ارسال برای اشتراک ${s.subscription} ثبت شد.\n" +
                "تحویل پیامک تأیید نشده است."

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
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == SMS_PERMISSION) {
            if (
                grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                toast("مجوز داده شد. دوباره ارسال را تأیید کنید.")
            } else {
                toast("مجوز پیامک داده نشد.")
            }
        }
    }

    private fun showReport() {
        val excluded = subscribers.count {
            it.approval.equals("بله", true)
        }

        val needsReview = subscribers.count {
            it.approval.isNotBlank() &&
                !it.approval.equals("بله", true) &&
                !it.approval.equals("خیر", true)
        }

        val blankApproval = subscribers.count {
            it.approval.isBlank()
        }

        val waiting = subscribers.count {
            isEligible(it)
        }

        val sent = subscribers.count {
            isSent(it)
        }

        val history = prefs.all
            .filterKeys { it.startsWith("record_") }
            .values
            .filterIsInstance<String>()
            .sorted()

        val report =
            "تعداد رکوردهای فایل فعلی: ${subscribers.size}\n" +
            "تأیید بله، مستثنا: $excluded\n" +
            "ستون تأیید خالی: $blankApproval\n" +
            "مقدار تأیید نامشخص: $needsReview\n" +
            "واجد شرایط از نظر فیلتر برنامه: $waiting\n" +
            "درخواست‌های ثبت‌شده: $sent\n\n" +
            history.joinToString("\n\n").ifBlank {
                "سابقه‌ای ثبت نشده است."
            }

        AlertDialog.Builder(this)
            .setTitle("گزارش وضعیت")
            .setMessage(report)
            .setPositiveButton("بستن", null)
            .show()
    }

    private fun saveExcel() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type =
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            putExtra(Intent.EXTRA_TITLE, "vosool_report.xlsx")
        }

        startActivityForResult(intent, SAVE_EXCEL)
    }

    private fun xmlEscape(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    private fun columnName(index: Int): String {
        var n = index + 1
        var result = ""

        while (n > 0) {
            val r = (n - 1) % 26
            result = ('A'.code + r).toChar() + result
            n = (n - 1) / 26
        }

        return result
    }

    private fun writeXlsx(output: OutputStream) {
        val rows = mutableListOf(
            listOf(
                "شماره اشتراک",
                "شماره همراه",
                "مبلغ بدهی به ریال",
                "تأیید ارسال پیامک",
                "وضعیت"
            )
        )

        subscribers.forEach { s ->
            val state = when {
                s.approval.equals("بله", true) ->
                    "مستثنا از ارسال"

                isSent(s) ->
                    "درخواست ارسال ثبت شده؛ تحویل تأیید نشده"

                s.approval.isBlank() ->
                    "ستون تأیید خالی؛ قابل بررسی"

                !s.approval.equals("خیر", true) ->
                    "مقدار تأیید نامشخص"

                else ->
                    "ارسال نشده"
            }

            rows.add(
                listOf(
                    s.subscription,
                    s.phone,
                    s.debt,
                    s.approval,
                    state
                )
            )
        }

        rows.add(listOf("سابقه درخواست‌های ارسال"))

        prefs.all
            .filterKeys { it.startsWith("record_") }
            .values
            .filterIsInstance<String>()
            .sorted()
            .forEach { record ->
                rows.add(listOf(record.replace("\n", " | ")))
            }

        ZipOutputStream(output).use { zip ->

            fun entry(name: String, content: String) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            entry(
                "[Content_Types].xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
</Types>"""
            )

            entry(
                "_rels/.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""
            )

            entry(
                "xl/workbook.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets><sheet name="گزارش مطالبات" sheetId="1" r:id="rId1"/></sheets>
</workbook>"""
            )

            entry(
                "xl/_rels/workbook.xml.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
</Relationships>"""
            )

            val sheet = StringBuilder()

            sheet.append(
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>"""
            )

            rows.forEachIndexed { rowIndex, cells ->
                val excelRow = rowIndex + 1

                sheet.append("""<row r="$excelRow">""")

                cells.forEachIndexed { colIndex, value ->
                    val ref = columnName(colIndex) + excelRow

                    sheet.append(
                        """<c r="$ref" t="inlineStr"><is><t xml:space="preserve">${xmlEscape(value)}</t></is></c>"""
                    )
                }

                sheet.append("</row>")
            }

            sheet.append("</sheetData></worksheet>")

            entry(
                "xl/worksheets/sheet1.xml",
                sheet.toString()
            )
        }
    }

    private fun toast(message: String) {
        Toast.makeText(
            this,
            message,
            Toast.LENGTH_LONG
        ).show()
    }
}
