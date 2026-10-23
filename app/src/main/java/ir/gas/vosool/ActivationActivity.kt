
package ir.gas.vosool

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class ActivationActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ActivationManager.isActivated(this)) {
            openMain()
            return
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 28, 28, 28)
            setBackgroundColor(Color.WHITE)
        }

        val title = TextView(this).apply {
            text = "فعال‌سازی برنامه وصول مطالبات گاز"
            textSize = 22f
            setTextColor(Color.rgb(25, 70, 120))
            gravity = Gravity.CENTER
        }

        val description = TextView(this).apply {
            text = "کد فعال‌سازی را وارد کنید.\nاعتبار برنامه پس از فعال‌سازی ۳۶۵ روز است."
            textSize = 16f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 24)
        }

        val codeInput = EditText(this).apply {
            hint = "کد فعال‌سازی"
            textSize = 18f
            gravity = Gravity.CENTER
            singleLine = true
        }

        val activateButton = Button(this).apply {
            text = "فعال‌سازی"
            textSize = 18f
        }

        layout.addView(
            title,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        layout.addView(
            description,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        layout.addView(
            codeInput,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        layout.addView(
            activateButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 24
            }
        )

        setContentView(layout)

        activateButton.setOnClickListener {
            val enteredCode = codeInput.text.toString().trim()

            if (ActivationManager.activate(this, enteredCode)) {
                Toast.makeText(
                    this,
                    "برنامه با موفقیت فعال شد.",
                    Toast.LENGTH_LONG
                ).show()
                openMain()
            } else {
                Toast.makeText(
                    this,
                    "کد فعال‌سازی اشتباه است.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun openMain() {
        startActivity(
            Intent(this, MainActivity::class.java)
        )
        finish()
    }
}
