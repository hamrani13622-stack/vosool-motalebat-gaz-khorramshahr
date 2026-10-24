
private fun showSearchResults(matches: List<Subscriber>) {
    if (matches.isEmpty()) {
        AlertDialog.Builder(this)
            .setTitle("نتیجه جست‌وجو")
            .setMessage("مشترکی با این مشخصات پیدا نشد.")
            .setPositiveButton("باشه", null)
            .show()
        return
    }

    val labels = matches.map { s ->
        "${s.name.ifBlank { "نام ثبت نشده" }}\n" +
        "اشتراک: ${s.subscription}\n" +
        "موبایل: ${s.phone}\n" +
        "بدهی: ${s.debt} ریال\n" +
        "وضعیت وصول: ${s.collectionStatus}\n" +
        "توضیحات: ${s.notes.ifBlank { "ندارد" }}"
    }.toTypedArray()

    AlertDialog.Builder(this)
        .setTitle("نتایج جست‌وجو (${matches.size})")
        .setItems(labels) { _, which ->
            val selected = matches[which]
            selectSubscriber(selected)

            val detailsDialog = AlertDialog.Builder(this)
                .setTitle("اطلاعات مشترک")
                .setMessage(subscriberDetails(selected))
                .setPositiveButton("ارسال پیامک", null)
                .setNeutralButton("ویرایش اطلاعات", null)
                .setNegativeButton("بستن", null)
                .create()

            detailsDialog.setOnShowListener {
                detailsDialog.getButton(
                    AlertDialog.BUTTON_POSITIVE
                ).setOnClickListener {
                    detailsDialog.dismiss()
                    prepareAndConfirmSms(selected)
                }

                detailsDialog.getButton(
                    AlertDialog.BUTTON_NEUTRAL
                ).setOnClickListener {
                    detailsDialog.dismiss()
                    editSubscriber(selected)
                }
            }

            detailsDialog.show()
        }
        .setNegativeButton("بستن", null)
        .show()
}

private fun prepareAndConfirmSms(s: Subscriber) {
    selectSubscriber(s)

    if (!isEligible(s)) {
        Toast.makeText(
            this,
            "این مشترک مجاز به دریافت پیامک نیست یا پیامک قبلاً ثبت شده است.",
            Toast.LENGTH_LONG
        ).show()
        return
    }

    val phone = s.phone.trim()
    if (!phone.matches(Regex("^09[0-9]{9}$"))) {
        Toast.makeText(
            this,
            "شماره موبایل مشترک معتبر نیست.",
            Toast.LENGTH_LONG
        ).show()
        return
    }

    val debt = s.debt.trim()
        .replace(",", "")
        .replace("٬", "")
        .replace(" ", "")

    if (debt.toLongOrNull() == null) {
        Toast.makeText(
            this,
            "مبلغ بدهی معتبر نیست. ابتدا اطلاعات مشترک را ویرایش کنید.",
            Toast.LENGTH_LONG
        ).show()
        return
    }

    // آماده‌سازی متن پیامک برای مشترک انتخاب‌شده
    prepareMessage()
}
