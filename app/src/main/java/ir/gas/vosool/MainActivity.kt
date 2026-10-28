
package ir.gas.vosool

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.telephony.SmsManager
import android.text.InputType
import android.util.Xml
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class Subscriber(
    val subscription: String,
    val phone: String,
    val debt: String,
    val approval: String,
    val name: String = "",
    val collectionStatus: String = "وصول نشده",
    val notes: String = ""
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

        private val COLLECTION_STATUSES = arrayOf(
            "وصول نشده",
            "وصول جزئی",
            "وصول کامل"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!ActivationManager.isActivated(this)) {
            startActivity(Intent(this, ActivationActivity::class.java))
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

        addButton("جست‌وجوی مشترک با نام، اشتراک یا موبایل") {
            searchSubscriber()
        }

        addButton("ویرایش اطلاعات مشترک انتخاب‌شده") {
            val s = currentSubscriber
            if (s == null) {
                toast("ابتدا یک مشترک را جست‌وجو یا انتخاب کنید.")
            } else {
                editSubscriber(s)
            }
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

    private fun defaultMessage(): String {
        return "مشترک گرامی،\n\n" +
            "شماره اشتراک: {شماره اشتراک}\n" +
            "مبلغ بدهی گاز شما {مبلغ بدهی} ریال است. " +
            "لطفاً جهت پرداخت ظرف 48 ساعت آینده اقدام فرمایید " +
            "و رسید پرداختی را به این شماره ارسال فرمایید.\n\n" +
            "لینک سابقه قبوض:\n" +
            "https://portal.nigc-khgc.ir/index.php/site/loginmoshtarak"
    }

    private fun historyKey(s: Subscriber): String {
        return "sent_${s.subscription}_${s.phone}"
    }

    private fun recordKey(s: Subscriber): String {
        return "record_${s.subscription}_${s.phone}"
    }

    private fun isSent(s: Subscriber): Boolean {
        return prefs.getBoolean(historyKey(s), false)
    }

    private fun saveSubscribers() {
        try {
            val array = JSONArray()

            subscribers.forEach { s ->
                val item = JSONObject()
                item.put("subscription", s.subscription)
                item.put("phone", s.phone)
                item.put("debt", s.debt)
                item.put("approval", s.approval)
                item.put("name", s.name)
                item.put("collectionStatus", s.collectionStatus)
                item.put("notes", s.notes)
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
                        approval = item.optString("approval"),
                        name = item.optString("name", ""),
                        collectionStatus = item.optString(
                            "collectionStatus",
                            "وصول نشده"
                        ).ifBlank { "وصول نشده" },
                        notes = item.optString("notes", "")
                    )
                )
            }

            currentSubscriber = null

            statusView.text =
                "اطلاعات ذخیره‌شده بازیابی شد.\n" +
                "تعداد مشترکان: ${subscribers.size}"

            subscriberView.text =
                "برای شروع، جست‌وجو یا نمایش مشترک بعدی را انتخاب کنید."

        } catch (e: Exception) {
            statusView.text =
                "بازیابی اطلاعات ناموفق بود. فایل اکسل را دوباره انتخاب کنید."
            toast("خطا در بازیابی اطلاعات: ${e.message}")
        }
    }

    private fun normalizeDigits(value: String): String {
        return value.map { ch ->
            when (ch) {
                in '۰'..'۹' -> '0' + (ch - '۰')
                in '٠'..'٩' -> '0' + (ch - '٠')
                else -> ch
            }
        }.joinToString("")
    }

    private fun normalizeSearchText(value: String): String {
        return normalizeDigits(value)
            .replace('ي', 'ی')
            .replace('ك', 'ک')
            .trim()
            .lowercase()
            .filterNot { it.isWhitespace() }
    }

    private fun searchSubscriber() {
        if (subscribers.isEmpty()) {
            toast("ابتدا فایل اکسل مشترکان را انتخاب کنید.")
            return
        }

        val input = EditText(this).apply {
            hint = "نام، شماره اشتراک یا شماره موبایل"
            textSize = 18f
            gravity = Gravity.END
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("جست‌وجوی مشترک")
            .setMessage("بخشی از نام، شماره اشتراک یا شماره تماس را وارد کنید:")
            .setView(input)
            .setNegativeButton("انصراف", null)
            .setPositiveButton("جست‌وجو", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    val query = normalizeSearchText(
                        input.text.toString()
                    )

                    if (query.isBlank()) {
                        toast("عبارت جست‌وجو را وارد کنید.")
                        return@setOnClickListener
                    }

                    val matches = subscribers.filter { s ->
                        normalizeSearchText(s.subscription).contains(query) ||
                        normalizeSearchText(s.phone).contains(query) ||
                        normalizeSearchText(s.name).contains(query)
                    }

                    if (matches.isEmpty()) {
                        toast("مشترکی با این مشخصات یافت نشد.")
                        return@setOnClickListener
                    }

                    dialog.dismiss()
                    showSearchResults(matches)
                }
        }

        dialog.show()
    }

    private fun subscriberDetails(s: Subscriber): String {
        val smsState = when {
            s.approval.equals("بله", true) ->
                "طبق ستون تأیید اکسل، از ارسال مستثنا است"
            isSent(s) ->
                "درخواست ارسال قبلاً ثبت شده؛ تحویل تأیید نشده"
            s.approval.equals("خیر", true) ->
                "ارسال ثبت نشده؛ تأیید اکسل: خیر"
            s.approval.isBlank() ->
                "ستون تأیید خالی است؛ نیازمند بررسی"
            else ->
                "مقدار تأیید اکسل نیازمند بررسی است"
        }

        return "نام مشترک: ${s.name.ifBlank { "ثبت نشده" }}\n" +
            "شماره اشتراک: ${s.subscription}\n" +
            "شماره همراه: ${s.phone}\n" +
            "مبلغ بدهی: ${s.debt} ریال\n" +
            "تأیید ارسال در اکسل: ${s.approval.ifBlank { "خالی" }}\n" +
            "وضعیت وصول: ${s.collectionStatus}\n" +
            "توضیحات: ${s.notes.ifBlank { "ندارد" }}\n" +
            "وضعیت پیامک: $smsState"
    }

    private fun selectSubscriber(s: Subscriber) {
        currentSubscriber = s
        subscriberView.text = subscriberDetails(s)

        messageInput.setText(
            defaultMessage()
                .replace("{شماره اشتراک}", s.subscription)
                .replace("{مبلغ بدهی}", s.debt)
        )

        statusView.text = "مشترک ${s.subscription} انتخاب شد."
    }

    private fun showSearchResults(matches: List<Subscriber>) {
        val labels = matches.map { s ->
            "نام: ${s.name.ifBlank { "ثبت نشده" }}\n" +
                "اشتراک: ${s.subscription} | موبایل: ${s.phone}\n" +
                "بدهی: ${s.debt} ریال\n" +
                "وصول: ${s.collectionStatus}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("نتایج جست‌وجو (${matches.size})")
            .setItems(labels) { _, which ->
                val s = matches[which]
                selectSubscriber(s)

                AlertDialog.Builder(this)
                    .setTitle("اطلاعات مشترک")
                    .setMessage(subscriberDetails(s))
                    .setPositiveButton("ویرایش اطلاعات") { _, _ ->
                        editSubscriber(s)
                    }
                    .setNeutralButton("انتخاب") { _, _ ->
                        toast("مشترک انتخاب شد.")
                    }
                    .setNegativeButton("بستن", null)
                    .show()
            }
            .setNegativeButton("بستن", null)
            .show()
    }

    private fun editSubscriber(old: Subscriber) {
        val index = subscribers.indexOf(old)

        if (index < 0) {
            toast("مشترک در فهرست فعلی پیدا نشد.")
            return
        }

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 10, 30, 10)
        }

        val scroll = ScrollView(this)
        scroll.addView(form)

        fun addField(label: String, value: String, multiline: Boolean = false): EditText {
            form.addView(
                TextView(this).apply {
                    text = label
                    textSize = 15f
                    gravity = Gravity.END
                    setPadding(0, 8, 0, 4)
                }
            )

            return EditText(this).apply {
                setText(value)
                textSize = 16f
                gravity = Gravity.END
                if (multiline) {
                    minLines = 3
                    maxLines = 6
                    inputType = InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE
                } else {
                    setSingleLine(true)
                }
                form.addView(
                    this,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
        }

        val nameInput = addField("نام مشترک - ستون B", old.name)
        val subscriptionInput =
            addField("شماره اشتراک - ستون A", old.subscription)
        val phoneInput =
            addField("شماره همراه - ستون C", old.phone)
        val debtInput =
            addField("مبلغ بدهی به ریال - ستون D", old.debt)
        val approvalInput =
            addField("تأیید ارسال پیامک - ستون E", old.approval)

        form.addView(
            TextView(this).apply {
                text = "وضعیت وصول - ستون F"
                textSize = 15f
                gravity = Gravity.END
                setPadding(0, 8, 0, 4)
            }
        )

        val statusSpinner = Spinner(this)
        statusSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            COLLECTION_STATUSES
        )

        val statusIndex = COLLECTION_STATUSES.indexOf(old.collectionStatus)
        statusSpinner.setSelection(if (statusIndex >= 0) statusIndex else 0)
        form.addView(statusSpinner)

        val notesInput = addField(
            "توضیحات - ستون G",
            old.notes,
            multiline = true
        )

        val dialog = AlertDialog.Builder(this)
            .setTitle("ویرایش اطلاعات مشترک")
            .setView(scroll)
            .setNegativeButton("انصراف", null)
            .setPositiveButton("ذخیره تغییرات", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    val subscription =
                        subscriptionInput.text.toString().trim()
                    val phone = phoneInput.text.toString().trim()
                    val debt = debtInput.text.toString()
                        .trim()
                        .replace(",", "")
                        .replace("٬", "")

                    if (subscription.isBlank()) {
                        toast("شماره اشتراک نمی‌تواند خالی باشد.")
                        return@setOnClickListener
                    }

                    if (phone.isNotBlank()) {
                        val normalizedPhone = normalizeDigits(phone)
                        if (!normalizedPhone.matches(Regex("^09[0-9]{9}$"))) {
                            toast("شماره همراه باید ۱۱ رقم و با ۰۹ شروع شود.")
                            return@setOnClickListener
                        }
                    }

                    if (debt.isNotBlank() && debt.toLongOrNull() == null) {
                        toast("مبلغ بدهی باید عددی باشد.")
                        return@setOnClickListener
                    }

                    val updated = Subscriber(
                        subscription = subscription,
                        phone = phone,
                        debt = debt,
                        approval = approvalInput.text.toString().trim(),
                        name = nameInput.text.toString().trim(),
                        collectionStatus =
                            statusSpinner.selectedItem.toString(),
                        notes = notesInput.text.toString().trim()
                    )

                    val duplicate = subscribers.withIndex().any { (i, s) ->
                        i != index &&
                            s.subscription == updated.subscription
                    }

                    if (duplicate) {
                        toast("این شماره اشتراک در فهرست وجود دارد.")
                        return@setOnClickListener
                    }

                    // سوابق ارسال در صورت تغییر شماره اشتراک یا تلفن منتقل می‌شوند.
                    migrateHistory(old, updated)

                    subscribers[index] = updated
                    currentSubscriber = updated
                    saveSubscribers()
                    selectSubscriber(updated)

                    statusView.text =
                        "اطلاعات مشترک ${updated.subscription} ذخیره شد."

                    dialog.dismiss()
                    toast("تغییرات ذخیره شد.")
                }
        }

        dialog.show()
    }

    private fun migrateHistory(old: Subscriber, updated: Subscriber) {
        val oldSentKey = historyKey(old)
        val oldRecordKey = recordKey(old)
        val newSentKey = historyKey(updated)
        val newRecordKey = recordKey(updated)

        if (oldSentKey == newSentKey && oldRecordKey == newRecordKey) {
            return
        }

        val editor = prefs.edit()

        if (prefs.getBoolean(oldSentKey, false)) {
            editor.putBoolean(newSentKey, true)
        }

        val oldRecord = prefs.getString(oldRecordKey, null)
        if (oldRecord != null) {
            editor.putString(
                newRecordKey,
                oldRecord
                    .replace("اشتراک: ${old.subscription}", "اشتراک: ${updated.subscription}")
                    .replace("تلفن: ${old.phone}", "تلفن: ${updated.phone}")
            )
        }

        editor.remove(oldSentKey)
        editor.remove(oldRecordKey)
        editor.apply()
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
                        "برای شروع، جست‌وجو یا نمایش مشترک بعدی را بزنید."

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

    private fun normalizeHeader(value: String): String {
        return normalizeSearchText(value)
            .replace("‌", "")
            .replace("ـ", "")
            .replace("ء", "")
            .replace("أ", "ا")
            .replace("إ", "ا")
            .replace("ؤ", "و")
            .replace("ئ", "ی")
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
                                if (maxColumn < 0) emptyList()
                                else (0..maxColumn).map { row[it] ?: "" }
                            )
                        }
                    }
                }
            }

            event = parser.next()
        }

        if (result.isEmpty()) return emptyList()

        val firstRow = result.first()
        val headers = firstRow.map { normalizeHeader(it) }

        fun findHeader(vararg names: String): Int {
            val candidates = names.map { normalizeHeader(it) }
            return headers.indexOfFirst { header ->
                header.isNotBlank() &&
                    candidates.any { candidate ->
                        header == candidate || header.contains(candidate)
                    }
            }
        }

        val subscriptionColumn = findHeader(
            "شماره اشتراک", "شماره اشتراک گاز", "اشتراک", "subscription"
        )
        val nameColumn = findHeader(
            "نام مشترک", "نام و نام خانوادگی", "نام خانوادگی", "نام", "name"
        )
        val phoneColumn = findHeader(
            "شماره همراه", "شماره موبایل", "موبایل", "تلفن همراه",
            "تلفن", "phone"
        )
        val debtColumn = findHeader(
            "مبلغ بدهی به ریال", "مبلغ بدهی", "بدهی", "مبلغ", "debt"
        )
        val approvalColumn = findHeader(
            "تأیید ارسال پیامک", "تایید ارسال پیامک",
            "تأیید ارسال", "تایید ارسال", "تأیید", "تایید", "approval"
        )
        val collectionColumn = findHeader(
            "وضعیت وصول", "وضعیت پرداخت", "وصول", "collectionstatus"
        )
        val notesColumn = findHeader(
            "توضیحات", "شرح", "یادداشت", "notes"
        )

        val hasHeader = subscriptionColumn >= 0 ||
            nameColumn >= 0 ||
            phoneColumn >= 0 ||
            debtColumn >= 0 ||
            approvalColumn >= 0 ||
            collectionColumn >= 0 ||
            notesColumn >= 0

        // فایل جدید با سرستون: A اشتراک، B نام، C تلفن، D بدهی،
        // E تأیید، F وضعیت وصول، G توضیحات.
        // فایل قدیمی بدون سرستون: A اشتراک، B تلفن، C بدهی، D تأیید.
        val startIndex = if (hasHeader) 1 else 0

        val subCol = if (hasHeader) {
            if (subscriptionColumn >= 0) subscriptionColumn else 0
        } else 0

        val namCol = if (hasHeader) {
            if (nameColumn >= 0) nameColumn else 1
        } else -1

        val telCol = if (hasHeader) {
            if (phoneColumn >= 0) phoneColumn else 2
        } else 1

        val debtCol = if (hasHeader) {
            if (debtColumn >= 0) debtColumn else 3
        } else 2

        val approvalCol = if (hasHeader) {
            if (approvalColumn >= 0) approvalColumn else 4
        } else 3

        val collectionCol = if (hasHeader) {
            if (collectionColumn >= 0) collectionColumn else 5
        } else -1

        val notesCol = if (hasHeader) {
            if (notesColumn >= 0) notesColumn else 6
        } else -1

        fun valueAt(cells: List<String>, index: Int): String {
            return if (index >= 0) cells.getOrElse(index) { "" }.trim()
            else ""
        }

        return result.drop(startIndex).mapNotNull { cells ->
            val subscription = valueAt(cells, subCol)

            if (subscription.isBlank()) {
                null
            } else {
                val debt = valueAt(cells, debtCol)
                    .replace(",", "")
                    .replace("٬", "")
                    .replace(" ", "")

                Subscriber(
                    subscription = subscription,
                    phone = valueAt(cells, telCol),
                    debt = debt,
                    approval = valueAt(cells, approvalCol),
                    name = valueAt(cells, namCol),
                    collectionStatus = valueAt(cells, collectionCol)
                        .ifBlank { "وصول نشده" },
                    notes = valueAt(cells, notesCol)
                )
            }
        }
    }

    private fun isEligible(s: Subscriber): Boolean {
        return !s.approval.equals("بله", true) && !isSent(s)
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
                "گزارش وضعیت را بررسی کنید."

            return
        }

        val s = subscribers[next]
        selectSubscriber(s)
        statusView.text = "مشترک بعدی برای بررسی نمایش داده شد."
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

        val normalizedPhone = normalizeDigits(s.phone)
            .filterNot { it.isWhitespace() }

        if (!normalizedPhone.matches(Regex("^09[0-9]{9}$"))) {
            toast("شماره همراه معتبر نیست.")
            return
        }

        val normalizedDebt = s.debt
            .replace(",", "")
            .replace("٬", "")
            .trim()

        if (normalizedDebt.toLongOrNull() == null) {
            toast("مبلغ بدهی معتبر نیست.")
            return
        }

        val message = messageInput.text.toString()
            .replace("{شماره اشتراک}", s.subscription)
            .replace("{مبلغ بدهی}", normalizedDebt)

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
                "نام مشترک: ${s.name.ifBlank { "ثبت نشده" }}\n" +
                "شماره اشتراک: ${s.subscription}\n" +
                "شماره همراه: ${s.phone}\n" +
                "مبلغ بدهی: ${s.debt} ریال\n\n" +
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
                    s.phone,
                    null,
                    parts,
                    null,
                    null
                )
            } else {
                manager.sendTextMessage(
                    s.phone,
                    null,
                    message,
                    null,
                    null
                )
            }

            val record =
                "نام مشترک: ${s.name}\n" +
                "اشتراک: ${s.subscription}\n" +
                "تلفن: ${s.phone}\n" +
                "مبلغ: ${s.debt}\n" +
                "وضعیت وصول: ${s.collectionStatus}\n" +
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

        val waiting = subscribers.count { isEligible(it) }
        val sent = subscribers.count { isSent(it) }

        val notCollected = subscribers.count {
            it.collectionStatus == "وصول نشده"
        }
        val partial = subscribers.count {
            it.collectionStatus == "وصول جزئی"
        }
        val collected = subscribers.count {
            it.collectionStatus == "وصول کامل"
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
            "وضعیت وصول:\n" +
            "وصول نشده: $notCollected\n" +
            "وصول جزئی: $partial\n" +
            "وصول کامل: $collected\n\n" +
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

    private fun xmlEscape(value: String): String {
        return value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
            .filter { it == '\n' || it == '\r' || it == '\t' || it.code >= 32 }
    }

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
                "نام مشترک",
                "شماره همراه",
                "مبلغ بدهی به ریال",
                "تأیید ارسال پیامک",
                "وضعیت وصول",
                "توضیحات"
            )
        )

        subscribers.forEach { s ->
            rows.add(
                listOf(
                    s.subscription,
                    s.name,
                    s.phone,
                    s.debt,
                    s.approval,
                    s.collectionStatus,
                    s.notes
                )
            )
        }

        rows.add(emptyList())
        rows.add(listOf("گزارش وضعیت ارسال پیامک"))
        rows.add(
            listOf(
                "نام مشترک",
                "شماره اشتراک",
                "شماره همراه",
                "مبلغ بدهی",
                "شرح سابقه"
            )
        )

        prefs.all
            .filterKeys { it.startsWith("record_") }
            .values
            .filterIsInstance<String>()
            .sorted()
            .forEach { record ->
                val lines = record.split("\n")
                val name = lines.firstOrNull {
                    it.startsWith("نام مشترک:")
                }?.substringAfter(":")?.trim().orEmpty()
                val subscription = lines.firstOrNull {
                    it.startsWith("اشتراک:")
                }?.substringAfter(":")?.trim().orEmpty()
                val phone = lines.firstOrNull {
                    it.startsWith("تلفن:")
                }?.substringAfter(":")?.trim().orEmpty()
                val debt = lines.firstOrNull {
                    it.startsWith("مبلغ:")
                }?.substringAfter(":")?.trim().orEmpty()

                rows.add(
                    listOf(
                        name,
                        subscription,
                        phone,
                        debt,
                        record.replace("\n", " | ")
                    )
                )
            }

        ZipOutputStream(output).use { zip ->

            fun entry(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
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
