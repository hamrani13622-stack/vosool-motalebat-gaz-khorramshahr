
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

        addButton("انتخاب فایل اکسل") { chooseExcel() }
        addButton("جست‌وجو بر اساس اشتراک یا شماره تماس") {
            searchSubscriber()
        }
        addButton("نمایش مشترک بعدی") { showNextSubscriber() }
        addButton("ویرایش اطلاعات مشترک انتخاب‌شده") {
            currentSubscriber?.let { editSubscriber(it) }
                ?: toast("ابتدا یک مشترک انتخاب کنید.")
        }
        addButton("نمایش وضعیت و ویرایش مشترکان") {
            showSubscriberList()
        }

        addText("متن پیامک:")
        messageInput = EditText(this).apply {
            gravity = Gravity.END
            minLines = 5
            setText(defaultMessage())
        }
        content.addView(messageInput)

        addButton("آماده‌سازی پیامک") { prepareMessage() }
        addButton("تأیید و ارسال پیامک") { confirmSend() }
        addButton("نمایش گزارش وضعیت") { showReport() }
        addButton("ذخیره گزارش Excel") { saveExcel() }

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
        "نام مشترک: {نام مشترک}\n" +
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

    private fun normalizeDigits(value: String): String =
        value.trim().map { ch ->
            when (ch) {
                in '۰'..'۹' -> '0' + (ch - '۰')
                in '٠'..'٩' -> '0' + (ch - '٠')
                else -> ch
            }
        }.joinToString("")

    private fun normalizeSearchNumber(value: String): String =
        normalizeDigits(value).filter { !it.isWhitespace() }

    private fun normalizedDebt(value: String): String =
        normalizeDigits(value)
            .replace(",", "")
            .replace("٬", "")
            .replace("٫", "")
            .replace(" ", "")

    private fun saveSubscribers() {
        try {
            val array = JSONArray()

            subscribers.forEach { s ->
                val item = JSONObject()
                item.put("subscription", s.subscription)
                item.put("name", s.name)
                item.put("phone", s.phone)
                item.put("debt", s.debt)
                item.put("approval", s.approval)
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
                statusView.text = "ابتدا فایل اکسل مشترکان را انتخاب کنید."
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
                        name = item.optString("name"),
                        collectionStatus = item.optString(
                            "collectionStatus",
                            "وصول نشده"
                        ),
                        notes = item.optString("notes", "")
                    )
                )
            }

            currentSubscriber = null
            statusView.text =
                "اطلاعات ذخیره‌شده بازیابی شد.\nتعداد مشترکان: ${subscribers.size}"
            subscriberView.text = "برای شروع، نمایش مشترک بعدی را بزنید."
        } catch (e: Exception) {
            statusView.text =
                "بازیابی اطلاعات ناموفق بود. فایل اکسل را دوباره انتخاب کنید."
            toast("خطا در بازیابی اطلاعات: ${e.message}")
        }
    }

    private fun statusText(s: Subscriber): String = when {
        s.approval.equals("بله", true) ->
            "تأیید شده در اکسل؛ مستثنا از ارسال"
        isSent(s) ->
            "درخواست ارسال قبلاً ثبت شده؛ تحویل تأیید نشده"
        s.approval.isBlank() ->
            "ارسال نشده؛ ستون تأیید خالی است"
        s.approval.equals("خیر", true) ->
            "ارسال نشده؛ تأیید اکسل: خیر"
        else ->
            "ارسال نشده؛ مقدار تأیید نیازمند بررسی است"
    }

    private fun subscriberDetails(s: Subscriber): String =
        "نام مشترک: ${s.name.ifBlank { "ثبت نشده" }}\n" +
        "شماره اشتراک: ${s.subscription}\n" +
        "شماره همراه: ${s.phone}\n" +
        "مبلغ بدهی: ${s.debt} ریال\n" +
        "تأیید ارسال در اکسل: ${s.approval.ifBlank { "خالی" }}\n" +
        "وضعیت وصول: ${s.collectionStatus}\n" +
        "توضیحات: ${s.notes.ifBlank { "بدون توضیحات" }}\n" +
        "وضعیت پیامک: ${statusText(s)}"

    private fun selectSubscriber(s: Subscriber) {
        currentSubscriber = s
        subscriberView.text = subscriberDetails(s)
        messageInput.setText(
            defaultMessage()
                .replace("{نام مشترک}", s.name)
                .replace("{شماره اشتراک}", s.subscription)
                .replace("{مبلغ بدهی}", normalizedDebt(s.debt))
        )
        statusView.text = "مشترک ${s.subscription} انتخاب شد."
    }

    private fun searchSubscriber() {
        val input = EditText(this).apply {
            hint = "شماره اشتراک یا شماره موبایل"
            textSize = 18f
            gravity = Gravity.END
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_PHONE
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("جست‌وجوی مشترک")
            .setMessage("شماره اشتراک یا شماره تماس را وارد کنید:")
            .setView(input)
            .setNegativeButton("انصراف", null)
            .setPositiveButton("جست‌وجو", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    val query = normalizeSearchNumber(input.text.toString())

                    if (query.isBlank()) {
                        toast("شماره اشتراک یا تماس را وارد کنید.")
                        return@setOnClickListener
                    }

                    val matches = subscribers.filter { s ->
                        normalizeSearchNumber(s.subscription).contains(query) ||
                        normalizeSearchNumber(s.phone).contains(query)
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

    private fun showSearchResults(matches: List<Subscriber>) {
        if (matches.isEmpty()) {
            toast("مشترکی یافت نشد.")
            return
        }

        val labels = matches.map { s ->
            "${s.name.ifBlank { "نام ثبت نشده" }}\n" +
            "اشتراک: ${s.subscription} | موبایل: ${s.phone}\n" +
            "بدهی: ${s.debt} ریال\n" +
            "وضعیت وصول: ${s.collectionStatus}\n" +
            "توضیحات: ${s.notes.ifBlank { "ندارد" }}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("نتایج جست‌وجو (${matches.size})")
            .setItems(labels) { _, which ->
                val selected = matches[which]
                selectSubscriber(selected)
                showSelectedSubscriberDialog(selected)
            }
            .setNegativeButton("بستن", null)
            .show()
    }

    private fun showSelectedSubscriberDialog(s: Subscriber) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("اطلاعات مشترک")
            .setMessage(subscriberDetails(s))
            .setPositiveButton("ارسال پیامک", null)
            .setNeutralButton("ویرایش اطلاعات", null)
            .setNegativeButton("بستن", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    dialog.dismiss()
                    prepareAndConfirmSms(s)
                }

            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener {
                    dialog.dismiss()
                    editSubscriber(s)
                }
        }
        dialog.show()
    }

    private fun showSubscriberList() {
        if (subscribers.isEmpty()) {
            toast("ابتدا فایل اکسل را وارد کنید.")
            return
        }

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 8, 16, 8)
        }
        scroll.addView(list)

        subscribers.forEach { s ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(8, 12, 8, 12)
            }

            card.addView(TextView(this).apply {
                text = subscriberDetails(s)
                textSize = 15f
                gravity = Gravity.END
                setPadding(0, 4, 0, 8)
            })

            card.addView(Button(this).apply {
                text = "انتخاب مشترک"
                setOnClickListener {
                    selectSubscriber(s)
                    toast("مشترک انتخاب شد.")
                }
            })

            card.addView(Button(this).apply {
                text = "ویرایش اطلاعات این ردیف"
                setOnClickListener { editSubscriber(s) }
            })

            card.addView(Button(this).apply {
                text = "آماده‌سازی و ارسال پیامک"
                setOnClickListener { prepareAndConfirmSms(s) }
            })

            list.addView(card)
            list.addView(TextView(this).apply {
                text = "ــــــــــــــــــــــــــــــــــــــــ"
                gravity = Gravity.CENTER
            })
        }

        AlertDialog.Builder(this)
            .setTitle("وضعیت و ویرایش مشترکان (${subscribers.size})")
            .setView(scroll)
            .setPositiveButton("بستن", null)
            .show()
    }

    private fun editSubscriber(old: Subscriber) {
        val index = subscribers.indexOf(old)
        if (index < 0) {
            toast("این مشترک در فهرست فعلی پیدا نشد.")
            return
        }

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 8, 28, 8)
        }

        fun field(
            label: String,
            value: String,
            singleLine: Boolean = true
        ): EditText {
            form.addView(TextView(this).apply {
                text = label
                textSize = 15f
                gravity = Gravity.END
            })

            return EditText(this).apply {
                setText(value)
                textSize = 16f
                gravity = Gravity.END
                setSingleLine(singleLine)
                if (!singleLine) {
                    minLines = 3
                    inputType = InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                }
                form.addView(this)
            }
        }

        val nameInput = field("نام مشترک", old.name)
        val subscriptionInput = field("شماره اشتراک", old.subscription)
        val phoneInput = field("شماره موبایل", old.phone)
        val debtInput = field("مبلغ بدهی به ریال", old.debt)
        val approvalInput = field("تأیید ارسال در اکسل (بله یا خیر)", old.approval)

        form.addView(TextView(this).apply {
            text = "وضعیت وصول"
            textSize = 15f
            gravity = Gravity.END
        })

        val collectionStatusInput = EditText(this).apply {
            setText(old.collectionStatus)
            textSize = 16f
            gravity = Gravity.END
            isFocusable = false
            isClickable = true
            hint = "برای انتخاب وضعیت لمس کنید"
            form.addView(this)
        }

        val collectionStatuses = arrayOf(
            "وصول نشده",
            "وصول ناقص",
            "وصول کامل"
        )

        collectionStatusInput.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("انتخاب وضعیت وصول")
                .setItems(collectionStatuses) { _, which ->
                    collectionStatusInput.setText(collectionStatuses[which])
                }
                .setNegativeButton("انصراف", null)
                .show()
        }

        val notesInput = field("توضیحات", old.notes, singleLine = false)
        val scroll = ScrollView(this)
        scroll.addView(form)

        val dialog = AlertDialog.Builder(this)
            .setTitle("ویرایش اطلاعات مشترک")
            .setView(scroll)
            .setNegativeButton("انصراف", null)
            .setPositiveButton("ذخیره تغییرات", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    val subscription = subscriptionInput.text.toString().trim()
                    val phone = normalizeDigits(phoneInput.text.toString()).trim()
                    val debt = normalizedDebt(debtInput.text.toString())
                    val approval = approvalInput.text.toString().trim()
                    val name = nameInput.text.toString().trim()
                    val collectionStatus =
                        collectionStatusInput.text.toString().trim()
                    val notes = notesInput.text.toString().trim()

                    if (subscription.isBlank()) {
                        toast("شماره اشتراک نمی‌تواند خالی باشد.")
                        return@setOnClickListener
                    }

                    if (subscribers.indices.any {
                            it != index &&
                            subscribers[it].subscription == subscription
                        }) {
                        toast("این شماره اشتراک برای ردیف دیگری ثبت شده است.")
                        return@setOnClickListener
                    }

                    if (debt.isNotBlank() && debt.toLongOrNull() == null) {
                        toast("مبلغ بدهی باید عدد صحیح باشد.")
                        return@setOnClickListener
                    }

                    val updated = Subscriber(
                        subscription = subscription,
                        phone = phone,
                        debt = debt,
                        approval = approval,
                        name = name,
                        collectionStatus = collectionStatus.ifBlank {
                            "وصول نشده"
                        },
                        notes = notes
                    )

                    subscribers[index] = updated

                    if (currentSubscriber == old) {
                        currentSubscriber = updated
                    }

                    saveSubscribers()

                    if (currentSubscriber == updated) {
                        subscriberView.text = subscriberDetails(updated)
                        messageInput.setText(
                            defaultMessage()
                                .replace("{نام مشترک}", updated.name)
                                .replace("{شماره اشتراک}", updated.subscription)
                                .replace("{مبلغ بدهی}", normalizedDebt(updated.debt))
                        )
                    }

                    statusView.text =
                        "اطلاعات مشترک ${updated.subscription} ذخیره شد."
                    dialog.dismiss()
                    toast("اطلاعات و وضعیت وصول ذخیره شد.")
                }
        }
        dialog.show()
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
                        ?: throw IllegalArgumentException("فایل قابل خواندن نیست.")

                    val rows = input.use { readXlsx(it) }
                    if (rows.isEmpty()) {
                        toast("ردیف مشترکی در اکسل پیدا نشد.")
                        return
                    }

                    subscribers.clear()
                    subscribers.addAll(rows)
                    currentSubscriber = null
                    saveSubscribers()

                    statusView.text =
                        "تعداد مشترکان خوانده‌شده: ${subscribers.size}\n" +
                        "اطلاعات در حافظه برنامه ذخیره شد."
                    subscriberView.text = "برای شروع، نمایش مشترک بعدی را بزنید."
                    toast("اکسل خوانده و اطلاعات ذخیره شد.")
                } catch (e: Exception) {
                    toast("خطا در خواندن اکسل: ${e.message}")
                }
            }

            SAVE_EXCEL -> {
                try {
                    val output = contentResolver.openOutputStream(uri)
                        ?: throw IllegalArgumentException("امکان ذخیره فایل وجود ندارد.")
                    output.use { writeXlsx(it) }
                    toast("گزارش Excel ذخیره شد.")
                } catch (e: Exception) {
                    toast("خطا در ذخیره اکسل: ${e.message}")
                }
            }
        }
    }

    private fun normalizeHeader(value: String): String =
        value.trim()
            .replace("ي", "ی")
            .replace("ك", "ک")
            .replace("‌", "")
            .replace(" ", "")
            .lowercase()

    private fun readXlsx(input: InputStream): List<Subscriber> {
        val entries = mutableMapOf<String, ByteArray>()

        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) entries[entry.name] = zip.readBytes()
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
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "si" -> {
                            insideSi = true
                            text.setLength(0)
                        }
                        "t" -> if (insideSi) text.append(parser.nextText())
                    }
                    XmlPullParser.END_TAG -> if (parser.name == "si") {
                        strings.add(text.toString())
                        insideSi = false
                    }
                }
                event = parser.next()
            }
        }

        val sheetBytes = entries["xl/worksheets/sheet1.xml"]
            ?: throw IllegalArgumentException("برگه اول اکسل پیدا نشد.")

        val parser = Xml.newPullParser()
        parser.setInput(sheetBytes.inputStream(), "UTF-8")

        val rows = mutableListOf<Map<Int, String>>()
        var row = mutableMapOf<Int, String>()
        var cellRef = ""
        var cellType = ""
        var cellValue = ""
        var insideCell = false
        var event = parser.eventType

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> row = mutableMapOf()
                    "c" -> {
                        cellRef = parser.getAttributeValue(null, "r") ?: ""
                        cellType = parser.getAttributeValue(null, "t") ?: ""
                        cellValue = ""
                        insideCell = true
                    }
                    "v" -> if (insideCell) cellValue = parser.nextText()
                    "t" -> if (insideCell && cellType == "inlineStr") {
                        cellValue += parser.nextText()
                    }
                }

                XmlPullParser.END_TAG -> when (parser.name) {
                    "c" -> {
                        val letters = cellRef.takeWhile { it.isLetter() }
                        var column = 0
                        letters.forEach { ch ->
                            column = column * 26 +
                                (ch.uppercaseChar() - 'A' + 1)
                        }
                        column--

                        val value = if (cellType == "s") {
                            strings.getOrNull(cellValue.toIntOrNull() ?: -1) ?: ""
                        } else {
                            cellValue
                        }

                        if (column >= 0) row[column] = value
                        insideCell = false
                    }
                    "row" -> rows.add(row.toMap())
                }
            }
            event = parser.next()
        }

        if (rows.isEmpty()) return emptyList()

        val headers = rows.first().mapValues { normalizeHeader(it.value) }

        fun findColumn(vararg terms: String): Int? =
            headers.entries.firstOrNull { entry ->
                terms.any { term ->
                    entry.value.contains(normalizeHeader(term))
                }
            }?.key

        val subscriptionCol = findColumn("شماره اشتراک", "اشتراک")
        val nameCol = findColumn("نام مشترک", "نام و نام خانوادگی", "نام")
        val phoneCol = findColumn("شماره همراه", "شماره موبایل", "موبایل", "تلفن")
        val debtCol = findColumn("مبلغ بدهی", "بدهی", "مبلغ")
        val approvalCol = findColumn("تأیید ارسال", "تایید ارسال", "تأیید", "تایید")

        val hasHeader = subscriptionCol != null &&
            (phoneCol != null || debtCol != null)

        val dataRows = if (hasHeader) rows.drop(1) else rows
        val subIndex = if (hasHeader) subscriptionCol!! else 0
        val nameIndex = if (hasHeader) nameCol else null
        val phoneIndex = if (hasHeader) phoneCol ?: 1 else 1
        val debtIndex = if (hasHeader) debtCol ?: 2 else 2
        val approvalIndex = if (hasHeader) approvalCol ?: 3 else 3

        return dataRows.mapNotNull { cells ->
            fun value(index: Int?): String =
                if (index == null) "" else cells[index].orEmpty().trim()

            val subscription = value(subIndex)
            if (subscription.isBlank()) return@mapNotNull null

            Subscriber(
                subscription = subscription,
                name = value(nameIndex),
                phone = normalizeDigits(value(phoneIndex)),
                debt = normalizedDebt(value(debtIndex)),
                approval = value(approvalIndex)
            )
        }
    }

    private fun isEligible(s: Subscriber): Boolean =
        !s.approval.equals("بله", true) && !isSent(s)

    private fun showNextSubscriber() {
        val currentIndex = if (currentSubscriber == null) -1
        else subscribers.indexOf(currentSubscriber)

        val next = subscribers.indices
            .drop((currentIndex + 1).coerceAtLeast(0))
            .firstOrNull { isEligible(subscribers[it]) }

        if (next == null) {
            currentSubscriber = null
            subscriberView.text =
                "مشترک واجد شرایط دیگری برای نمایش وجود ندارد.\nگزارش وضعیت را بررسی کنید."
            return
        }

        selectSubscriber(subscribers[next])
        statusView.text = "مشترک بعدی نمایش داده شد."
    }

    private fun prepareMessage() {
        val s = currentSubscriber
        if (s == null) {
            toast("ابتدا یک مشترک را نمایش دهید.")
            return
        }

        if (!isEligible(s)) {
            toast("این مشترک واجد شرایط ارسال نیست.")
            return
        }

        val phone = normalizeDigits(s.phone)
        if (!phone.matches(Regex("^09[0-9]{9}$"))) {
            toast("شماره همراه معتبر نیست.")
            return
        }

        val debt = normalizedDebt(s.debt)
        if (debt.toLongOrNull() == null) {
            toast("مبلغ بدهی معتبر نیست.")
            return
        }

        messageInput.setText(
            messageInput.text.toString()
                .replace("{نام مشترک}", s.name)
                .replace("{شماره اشتراک}", s.subscription)
                .replace("{مبلغ بدهی}", debt)
        )
        statusView.text = "متن پیامک آماده بررسی است."
    }

    private fun prepareAndConfirmSms(s: Subscriber) {
        selectSubscriber(s)

        if (!isEligible(s)) {
            toast("این مشترک تأیید شده یا درخواست پیامک قبلاً ثبت شده است.")
            return
        }

        val phone = normalizeDigits(s.phone)
        if (!phone.matches(Regex("^09[0-9]{9}$"))) {
            toast("شماره موبایل مشترک معتبر نیست.")
            return
        }

        val debt = normalizedDebt(s.debt)
        if (debt.toLongOrNull() == null) {
            toast("مبلغ بدهی معتبر نیست؛ ابتدا اطلاعات را ویرایش کنید.")
            return
        }

        messageInput.setText(
            defaultMessage()
                .replace("{نام مشترک}", s.name)
                .replace("{شماره اشتراک}", s.subscription)
                .replace("{مبلغ بدهی}", debt)
        )

        confirmSend()
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
        if (
            message.contains("{نام مشترک}") ||
            message.contains("{شماره اشتراک}") ||
            message.contains("{مبلغ بدهی}")
        ) {
            toast("ابتدا پیامک را آماده کنید.")
            return
        }

        AlertDialog.Builder(this)
            .setTitle("تأیید نهایی ارسال")
            .setMessage(
                "نام مشترک: ${s.name}\n" +
                "شماره اشتراک: ${s.subscription}\n" +
                "شماره همراه: ${s.phone}\n\n" +
                "$message\n\nارسال این پیامک را تأیید می‌کنید؟"
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
            toast("مجوز پیامک را تأیید کنید و سپس دوباره ارسال را بزنید.")
            return
        }

        try {
            @Suppress("DEPRECATION")
            val manager = SmsManager.getDefault()
            val phone = normalizeDigits(s.phone)
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

            val record =
                "نام: ${s.name}\n" +
                "اشتراک: ${s.subscription}\n" +
                "تلفن: $phone\n" +
                "مبلغ: ${s.debt}\n" +
                "وضعیت: درخواست ارسال ثبت شد؛ تحویل تأیید نشده است."

            prefs.edit()
                .putBoolean(historyKey(s), true)
                .putString(recordKey(s), record)
                .apply()

            statusView.text =
                "درخواست ارسال برای اشتراک ${s.subscription} ثبت شد.\n" +
                "تحویل پیامک تأیید نشده است."
            subscriberView.text = subscriberDetails(s)
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
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

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
        val excluded = subscribers.count { it.approval.equals("بله", true) }
        val needsReview = subscribers.count {
            it.approval.isNotBlank() &&
                !it.approval.equals("بله", true) &&
                !it.approval.equals("خیر", true)
        }
        val blankApproval = subscribers.count { it.approval.isBlank() }
        val waiting = subscribers.count { isEligible(it) }
        val sent = subscribers.count { isSent(it) }

        val collected = subscribers.count { it.collectionStatus == "وصول کامل" }
        val partial = subscribers.count { it.collectionStatus == "وصول ناقص" }
        val notCollected = subscribers.count { it.collectionStatus == "وصول نشده" }

        val history = prefs.all
            .filterKeys { it.startsWith("record_") }
            .values.filterIsInstance<String>().sorted()

        val report =
            "تعداد رکوردهای فایل فعلی: ${subscribers.size}\n" +
            "وصول کامل: $collected\n" +
            "وصول ناقص: $partial\n" +
            "وصول نشده: $notCollected\n\n" +
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
                "نام مشترک",
                "شماره همراه",
                "مبلغ بدهی به ریال",
                "تأیید ارسال پیامک",
                "وضعیت وصول",
                "توضیحات",
                "وضعیت پیامک"
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
                    s.notes,
                    statusText(s)
                )
            )
        }

        rows.add(listOf("سابقه درخواست‌های ارسال"))
        prefs.all.filterKeys { it.startsWith("record_") }
            .values.filterIsInstance<String>().sorted()
            .forEach { rows.add(listOf(it.replace("\n", " | "))) }

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
            entry("xl/worksheets/sheet1.xml", sheet.toString())
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
