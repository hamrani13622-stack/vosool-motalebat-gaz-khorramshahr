import java.io.InputStream
import java.util.zip.ZipInputStream
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
private fun normalizeHeader(value: String): String =
    value.trim()
        .replace("ي", "ی")
        .replace("ك", "ک")
        .replace("‌", "")
        .replace(" ", "")
        .replace("_", "")
        .replace("-", "")
        .replace("ـ", "")
        .lowercase()

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
                XmlPullParser.START_TAG -> when (parser.name) {
                    "si" -> {
                        insideSi = true
                        text.setLength(0)
                    }
                    "t" -> if (insideSi) {
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

                "v" -> if (insideCell) {
                    cellValue = parser.nextText()
                }

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
                        strings.getOrNull(
                            cellValue.toIntOrNull() ?: -1
                        ) ?: ""
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

    val headers = rows.first().mapValues {
        normalizeHeader(it.value)
    }

    fun findColumn(vararg aliases: String): Int? {
        val normalizedAliases = aliases.map { normalizeHeader(it) }

        // ابتدا عنوان دقیق را بررسی می‌کنیم.
        headers.entries.firstOrNull { entry ->
            normalizedAliases.any { it == entry.value }
        }?.let { return it.key }

        // سپس عنوان‌هایی را که عبارت موردنظر را دارند بررسی می‌کنیم.
        return headers.entries.firstOrNull { entry ->
            normalizedAliases.any { alias ->
                entry.value.contains(alias)
            }
        }?.key
    }

    val subscriptionCol = findColumn(
        "شماره اشتراک", "اشتراک", "کد اشتراک", "شماره مشترک"
    )

    val nameCol = findColumn(
        "نام مشترک",
        "نام و نام خانوادگی",
        "نام و نام‌خانوادگی",
        "نام خانوادگی",
        "نام مشتری",
        "نام"
    )

    val phoneCol = findColumn(
        "شماره همراه",
        "شماره موبایل",
        "موبایل",
        "تلفن همراه",
        "شماره تلفن",
        "شماره تماس",
        "تلفن",
        "phone"
    )

    val debtCol = findColumn(
        "مبلغ بدهی",
        "بدهی",
        "مبلغ قابل پرداخت",
        "مبلغ"
    )

    val approvalCol = findColumn(
        "تأیید ارسال پیامک",
        "تایید ارسال پیامک",
        "وضعیت تأیید ارسال",
        "وضعیت ارسال",
        "تأیید ارسال",
        "تایید ارسال",
        "تأیید",
        "تایید",
        "approval"
    )

    // وجود عنوان اشتراک به همراه سایر عناوین نشان‌دهنده
    // وجود ردیف سرستون است؛ حتی اگر عنوان تلفن متفاوت باشد.
    val hasHeader = subscriptionCol != null &&
        (
            nameCol != null ||
            phoneCol != null ||
            debtCol != null ||
            approvalCol != null
        )

    val dataRows = if (hasHeader) rows.drop(1) else rows

    val subIndex = if (hasHeader) subscriptionCol!! else 0
    val nameIndex = if (hasHeader) nameCol else null
    val phoneIndex = if (hasHeader) phoneCol ?: 1 else 1
    val debtIndex = if (hasHeader) debtCol ?: 2 else 2
    val approvalIndex = if (hasHeader) approvalCol ?: 3 else 3

    return dataRows.mapNotNull { cells ->
        fun value(index: Int?): String =
            if (index == null) "" else
                cells[index].orEmpty().trim()

        val subscription = value(subIndex)

        if (subscription.isBlank()) {
            return@mapNotNull null
        }

        Subscriber(
            subscription = subscription,
            name = value(nameIndex),
            phone = normalizeDigits(value(phoneIndex)),
            debt = normalizedDebt(value(debtIndex)),
            approval = value(approvalIndex)
        )
    }
}
