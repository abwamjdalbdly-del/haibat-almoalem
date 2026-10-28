
package ye.teacherbag.app

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.*
import android.content.Context

class MainActivity : Activity() {
    private val green = Color.rgb(23, 107, 85)
    private val prefs by lazy { getSharedPreferences("teacher_bag_local", Context.MODE_PRIVATE) }
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showHome()
    }

    private fun setup(title: String) {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.rgb(246, 248, 247))
        }
        val heading = TextView(this).apply {
            text = title
            textSize = 24f
            setTextColor(green)
            gravity = Gravity.CENTER
            setPadding(8, 18, 8, 22)
        }
        root.addView(heading)
        setContentView(root)
    }

    private fun addButton(label: String, action: () -> Unit) {
        val b = Button(this).apply {
            text = label
            textSize = 17f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(green)
            setPadding(12, 16, 12, 16)
        }
        val lp = LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, 7, 0, 7)
        }
        root.addView(b, lp)
        b.setOnClickListener { action() }
    }

    private fun showHome() {
        setup("حقيبة المعلم الشاملة")
        val subtitle = TextView(this).apply {
            text = "إدارة مدرسية تعمل دون إنترنت"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.DKGRAY)
            setPadding(4, 0, 4, 20)
        }
        root.addView(subtitle)
        addButton("قاعدة بيانات الطلاب") { showStudents() }
        addButton("كشف الحضور والغياب") { showAttendance() }
        addButton("كشوف التحصيل والنتائج") { showResults() }
        addButton("نسخة احتياطية للبيانات") { backupInfo() }
        val note = TextView(this).apply {
            text = "نسخة أولية قيد التطوير"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.GRAY)
            setPadding(8, 22, 8, 8)
        }
        root.addView(note)
    }

    private fun studentNames(): MutableSet<String> =
        prefs.getStringSet("students", emptySet())!!.toMutableSet()

    private fun showStudents() {
        setup("قاعدة بيانات الطلاب")
        val input = EditText(this).apply {
            hint = "اكتب اسم الطالب"
            textSize = 18f
        }
        root.addView(input, LinearLayout.LayoutParams(-1, -2))
        addButton("حفظ اسم الطالب") {
            val name = input.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "اكتب اسم الطالب أولاً", Toast.LENGTH_SHORT).show()
            } else {
                val names = studentNames()
                names.add(name)
                prefs.edit().putStringSet("students", names).apply()
                input.setText("")
                Toast.makeText(this, "تم حفظ الاسم على الهاتف", Toast.LENGTH_SHORT).show()
                showStudents()
            }
        }
        addButton("عرض أسماء الطلاب") {
            val names = studentNames().sorted()
            AlertDialog.Builder(this).setTitle("أسماء الطلاب")
                .setMessage(
                    if (names.isEmpty()) "لا توجد أسماء محفوظة بعد"
                    else names.joinToString("\n")
                )
                .setPositiveButton("إغلاق", null).show()
        }
        addButton("العودة للرئيسية") { showHome() }
    }

    private fun showAttendance() {
        setup("كشف الحضور والغياب")
        val names = studentNames().sorted()
        if (names.isEmpty()) {
            root.addView(TextView(this).apply {
                text = "أضف أسماء الطلاب أولاً من قاعدة البيانات."
                textSize = 17f
                setPadding(4, 8, 4, 18)
            })
        } else {
            val month = EditText(this).apply {
                hint = "الشهر / الفترة"
                textSize = 17f
            }
            root.addView(month)
            val choices = mutableMapOf<String, String>()
            names.forEach { name ->
                val line = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                line.addView(TextView(this).apply {
                    text = name
                    textSize = 16f
                }, LinearLayout.LayoutParams(0, -2, 1f))
                val spinner = Spinner(this)
                spinner.adapter = ArrayAdapter(
                    this,
                    android.R.layout.simple_spinner_dropdown_item,
                    listOf("√ حاضر", "غ غائب", "م بعذر")
                )
                line.addView(spinner, LinearLayout.LayoutParams(130, -2))
                spinner.onItemSelectedListener =
                    object : AdapterView.OnItemSelectedListener {
                        override fun onNothingSelected(parent: AdapterView<*>?) {}
                        override fun onItemSelected(
                            parent: AdapterView<*>?,
                            view: View?,
                            position: Int,
                            id: Long
                        ) {
                            choices[name] = listOf("√", "غ", "م")[position]
                        }
                    }
                root.addView(line)
            }
            addButton("حفظ كشف الحضور") {
                val key = "attendance_" + month.text.toString().trim()
                val packed = choices.entries.joinToString("\n") {
                    "${it.key}|${it.value}"
                }
                prefs.edit().putString(key, packed).apply()
                val present = choices.values.count { it == "√" }
                val absent = choices.values.count { it == "غ" }
                val excused = choices.values.count { it == "م" }
                AlertDialog.Builder(this).setTitle("تم حفظ الكشف")
                    .setMessage("حاضر: $present\nغائب: $absent\nبعذر: $excused")
                    .setPositiveButton("حسنًا", null).show()
            }
        }
        addButton("العودة للرئيسية") { showHome() }
    }

    private fun showResults() {
        setup("كشوف التحصيل والنتائج")
        root.addView(TextView(this).apply {
            text = "سيضاف في المرحلة التالية إدخال درجات المواد، وحساب المجموع والنسبة والتقدير والنجاح والترتيب."
            textSize = 17f
            setTextColor(Color.DKGRAY)
            setPadding(4, 8, 4, 20)
        })
        addButton("العودة للرئيسية") { showHome() }
    }

    private fun backupInfo() {
        AlertDialog.Builder(this).setTitle("النسخ الاحتياطي")
            .setMessage("البيانات محفوظة محليًا على الهاتف في هذه النسخة الأولية. سنضيف تصدير واستعادة نسخة احتياطية في مرحلة لاحقة.")
            .setPositiveButton("حسنًا", null).show()
    }
}
