package ye.teacherbag.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextUtils
import android.util.Xml
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class MainActivity : Activity() {

    private val green = Color.rgb(23, 107, 85)
    private val prefs by lazy { getSharedPreferences("teacher_bag_local", Context.MODE_PRIVATE) }
    private lateinit var root: LinearLayout
    private val pickExcelRequest = 4101
    private val saveExcelRequest = 4102
    private val savePdfRequest = 4103
    private var pendingPdfData: ByteArray? = null
    private var pendingExcelData: ByteArray? = null

    private data class SheetInfo(val name: String, val path: String)
    private data class ExcelBook(val entries: Map<String, ByteArray>, val sheets: List<SheetInfo>, val sharedStrings: List<String>)
    private data class SubjectInfo(val name: String)
    private data class SubjectMarks(val homework: Double, val attendance: Double, val oral: Double, val written: Double) {
        val total: Double get() = homework + attendance + oral + written
        val average20: Double get() = total / 5.0
    }
    private data class StudentResult(val name: String, val total: Double, val average20: Double, val enteredSubjects: Int, val subjectCount: Int)

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
        root.addView(TextView(this).apply {
            text = title
            textSize = 24f
            setTextColor(green)
            gravity = Gravity.CENTER
            setPadding(8, 18, 8, 22)
        })
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
        root.addView(b, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 7, 0, 7) })
        b.setOnClickListener { action() }
    }

    private fun toast(s: String) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun showError(message: String) {
        AlertDialog.Builder(this).setTitle("تنبيه").setMessage(message).setPositiveButton("حسنًا", null).show()
    }

    private fun showHome() {
        setup("حقيبة المعلم الشاملة")
        root.addView(TextView(this).apply {
            text = "إدارة مدرسية تعمل دون إنترنت"
            textSize = 16f; gravity = Gravity.CENTER; setTextColor(Color.DKGRAY); setPadding(4, 0, 4, 20)
        })
        addButton("قاعدة بيانات الطلاب") { showStudents() }
        addButton("كشف الحضور والغياب") { showAttendance() }
        addButton("كشوف التحصيل والنتائج") { showResults() }
        addButton("نسخة احتياطية للبيانات") { backupInfo() }
        root.addView(TextView(this).apply {
            text = "نسخة أولية قيد التطوير"
            textSize = 13f; gravity = Gravity.CENTER; setTextColor(Color.GRAY); setPadding(8, 22, 8, 8)
        })
    }

    private fun studentNames(): MutableSet<String> {
        val saved = prefs.getString("students_list_v2", null)
        val old = prefs.getStringSet("students", emptySet())?.toSet() ?: emptySet()
        return (if (saved != null) saved.split("\n").filter { it.isNotBlank() } else old).toMutableSet()
    }

    private fun saveStudentNames(names: Set<String>) {
        val clean = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        prefs.edit().putString("students_list_v2", clean.joinToString("\n"))
            .putStringSet("students", clean.toSet()).apply()
    }

    private fun readClassNames(): MutableList<String> {
        val saved = prefs.getString("class_names_v1", "") ?: ""
        val names = mutableListOf<String>()
        saved.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.forEach { name ->
            if (names.none { it.equals(name, true) }) names.add(name)
        }
        prefs.all.keys.filter { it.startsWith("class_students_") }.forEach { key ->
            val name = Uri.decode(key.removePrefix("class_students_")).trim()
            if (name.isNotEmpty() && names.none { it.equals(name, true) }) names.add(name)
        }
        return names
    }

    private fun writeClassNames(names: List<String>) {
        val clean = names.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        prefs.edit().putString("class_names_v1", clean.joinToString("\n")).commit()
    }

    private fun classKey(className: String) = "class_students_" + Uri.encode(className.trim())

    private fun classStudentNames(className: String): MutableList<String> {
        val saved = prefs.getString(classKey(className), "") ?: ""
        return saved.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }.toMutableList()
    }

    private fun saveClassStudentNames(className: String, names: List<String>) {
        val clean = names.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        val classes = readClassNames()
        if (classes.none { it.equals(className.trim(), true) }) classes.add(className.trim())
        prefs.edit()
            .putString(classKey(className), clean.joinToString("\n"))
            .putString("class_names_v1", classes.joinToString("\n"))
            .commit()
    }

    private fun showStudents() {
        setup("قاعدة بيانات الطلاب")
        val input = EditText(this).apply { hint = "اكتب اسم الطالب"; textSize = 18f }
        root.addView(input, LinearLayout.LayoutParams(-1, -2))
        addButton("حفظ اسم الطالب") {
            val name = input.text.toString().trim()
            if (name.isEmpty()) toast("اكتب اسم الطالب أولاً") else {
                val names = studentNames()
                if (names.any { it.equals(name, true) }) toast("الاسم موجود مسبقًا") else {
                    names.add(name); saveStudentNames(names); input.setText(""); toast("تم حفظ الاسم على الهاتف"); showStudents()
                }
            }
        }
        addButton("استيراد أسماء الطلاب من Excel") { chooseExcelFile() }
        addButton("عرض أسماء الطلاب") {
            val n = studentNames().toList()
            AlertDialog.Builder(this).setTitle("أسماء الطلاب")
                .setMessage(if (n.isEmpty()) "لا توجد أسماء محفوظة بعد" else n.joinToString("\n"))
                .setPositiveButton("إغلاق", null).show()
        }
        addButton("عرض الصفوف وأعداد طلابها") {
            val cs = readClassNames()
            val msg = if (cs.isEmpty()) "لم يتم استيراد صفوف بعد"
                else cs.joinToString("\n") { "$it : ${classStudentNames(it).size} طالبًا" }
            AlertDialog.Builder(this).setTitle("الصفوف الدراسية").setMessage(msg)
                .setPositiveButton("إغلاق", null).show()
        }
        addButton("العودة للرئيسية") { showHome() }
    }

    private fun chooseExcelFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        }
        try { startActivityForResult(intent, pickExcelRequest) }
        catch (e: Exception) { showError("تعذر فتح نافذة اختيار الملفات.") }
    }

    @Deprecated("Using startActivityForResult for compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == saveExcelRequest) {
            if (resultCode == RESULT_OK) {
                val uri = data?.data
                val bytes = pendingExcelData
                if (uri != null && bytes != null) {
                    try {
                        contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        toast("تم تصدير ملف Excel بنجاح")
                    } catch (e: Exception) { showError("تعذر حفظ ملف Excel") }
                }
            }
            pendingExcelData = null
            return
        }

        if (requestCode == savePdfRequest) {
            if (resultCode == RESULT_OK) {
                val uri = data?.data
                val bytes = pendingPdfData
                if (uri != null && bytes != null) {
                    try {
                        contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        toast("تم تصدير كشف الحضور إلى PDF بنجاح")
                    } catch (e: Exception) { showError("تعذر حفظ ملف PDF") }
                }
            }
            pendingPdfData = null
            return
        }

        if (requestCode == pickExcelRequest && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                val book = readWorkbook(uri)
                if (book.sheets.isEmpty()) showError("لم أجد أوراق عمل في الملف.")
                else chooseSheet(book)
            } catch (e: Exception) {
                showError("تعذر قراءة الملف. اختر ملف Excel بصيغة .xlsx غير محمي بكلمة مرور.")
            }
        }
    }

    private fun chooseSheet(book: ExcelBook) {
        AlertDialog.Builder(this).setTitle("اختر الصف / ورقة العمل")
            .setItems(book.sheets.map { it.name }.toTypedArray()) { _, which -> showImportOptions(book, book.sheets[which]) }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun showImportOptions(book: ExcelBook, sheet: SheetInfo) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(40, 12, 40, 4)
        }
        val column = EditText(this).apply {
            hint = "عمود الأسماء (B)"; setText("B"); textSize = 17f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        val first = EditText(this).apply { hint = "أول صف"; setText("5"); inputType = InputType.TYPE_CLASS_NUMBER }
        val last = EditText(this).apply { hint = "آخر صف"; setText("36"); inputType = InputType.TYPE_CLASS_NUMBER }
        panel.addView(TextView(this).apply { text = "سيُسجل اسم الورقة صفًا دراسيًا: ${sheet.name}"; textSize = 16f })
        panel.addView(TextView(this).apply { text = "حرف عمود الأسماء:" }); panel.addView(column)
        panel.addView(TextView(this).apply { text = "أول صف:" }); panel.addView(first)
        panel.addView(TextView(this).apply { text = "آخر صف:" }); panel.addView(last)
        val dialog = AlertDialog.Builder(this).setTitle("إعدادات استيراد الأسماء").setView(panel)
            .setPositiveButton("معاينة الأسماء", null).setNegativeButton("إلغاء", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val col = column.text.toString().trim().uppercase()
                val f = first.text.toString().toIntOrNull()
                val l = last.text.toString().toIntOrNull()
                if (!col.matches(Regex("[A-Z]{1,3}")) || f == null || l == null || f < 1 || l < f || l - f > 5000) {
                    toast("تحقق من حرف العمود وأرقام الصفوف."); return@setOnClickListener
                }
                try {
                    val extracted = readNamesFromSheet(book, sheet, columnNumber(col), f, l)
                    dialog.dismiss(); previewNames(extracted, sheet.name)
                } catch (e: Exception) { showError("تعذر قراءة الأسماء من ورقة العمل المختارة.") }
            }
        }
        dialog.show()
    }

    private fun normalizeName(name: String) = name.trim().replace(Regex("\\s+"), " ").lowercase()

    private fun previewNames(rawNames: List<String>, className: String) {
        val clean = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        var duplicates = 0
        for (raw in rawNames) {
            val name = raw.trim().replace(Regex("\\s+"), " ")
            if (name.isEmpty()) continue
            if (!seen.add(normalizeName(name))) duplicates++ else clean.add(name)
        }
        val oldCount = classStudentNames(className).size
        if (clean.isEmpty()) {
            showError("لم يتم العثور على أسماء في ورقة $className. تحقق من عمود الأسماء وأرقام الصفوف.")
            return
        }
        val preview = "عدد الأسماء المقروءة للصف $className: ${clean.size}\n" +
                "الأسماء المكررة داخل ملف Excel: $duplicates\n" +
                "عدد الأسماء المرتبطة بالصف حاليًا: $oldCount\n\n" +
                clean.take(100).joinToString("\n") +
                if (clean.size > 100) "\n... وبقية الأسماء (${clean.size - 100})" else ""
        AlertDialog.Builder(this).setTitle("معاينة أسماء الصف").setMessage(preview)
            .setPositiveButton("اعتماد القائمة وتحديث الصف") { _, _ ->
                saveClassStudentNames(className, clean)
                val classes = readClassNames()
                if (classes.none { it.equals(className, true) }) { classes.add(className); writeClassNames(classes) }
                val all = studentNames(); all.addAll(clean); saveStudentNames(all)
                AlertDialog.Builder(this).setTitle("تم تحديث الصف")
                    .setMessage("تم ربط ${clean.size} طالبًا بالصف $className.\nلم تُحذف أسماء القاعدة العامة أو بيانات الصفوف الأخرى.\nالمكرر داخل الملف: $duplicates")
                    .setPositiveButton("حسنًا") { _, _ -> showStudents() }.show()
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun attendanceKey(cls: String, month: String, week: String) =
        "attendance_matrix_v1_${Uri.encode(cls)}_${Uri.encode(month)}_${Uri.encode(week)}"

    private fun showAttendance() {
        var currentMonth = "الشهر الاول"
        var currentWeek = "21-28"
        var currentClass = "الخامس"
        
        val classes = readClassNames()
        if (classes.isNotEmpty()) currentClass = classes.first()

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(Color.parseColor("#6A5ACD")) 
        }
        setContentView(root)

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(15), dp(10), dp(15))
        }
        
        val backBtn = TextView(this).apply {
            text = "←"
            textSize = 24f
            setTextColor(Color.WHITE)
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener { showHome() }
        }
        
        val titleText = TextView(this).apply {
            text = "الصف : $currentClass |\n2026-2027"
            textSize = 16f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(dp(15), dp(8), dp(15), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(20).toFloat()
            }
        }
        
        val helpBtn = TextView(this).apply {
            text = "؟"
            textSize = 20f
            setTextColor(Color.parseColor("#6A5ACD"))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                shape = GradientDrawable.OVAL
            }
            val size = dp(35)
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginStart = dp(10) }
        }

        topBar.addView(backBtn)
        topBar.addView(titleText, LinearLayout.LayoutParams(0, -2, 1f))
        topBar.addView(helpBtn)
        root.addView(topBar)

        val mainWhiteContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F0F0F0"))
            setPadding(dp(5), dp(5), dp(5), dp(5))
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        root.addView(mainWhiteContainer)

        val weekBar = TextView(this).apply {
            text = "الاسبوع : $currentWeek"
            textSize = 16f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.WHITE)
            setPadding(dp(10), dp(15), dp(10), dp(15))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(dp(1), Color.LTGRAY)
                cornerRadius = dp(10).toFloat()
            }
        }
        mainWhiteContainer.addView(weekBar, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(5)) })

        val tableHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL 
            setBackgroundColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(dp(1), Color.LTGRAY)
                cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }

        val headerHeight = dp(85)
        val daysTextList = listOf("السبت", "الأحد", "الاثنين", "الثلاثاء", "الأربعاء", "الخميس")
        
        val numHeader = TextView(this).apply {
            text = "م"
            textSize = 16f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F8F9FA"))
                setStroke(dp(1), Color.LTGRAY)
                cornerRadius = dp(8).toFloat()
            }
        }
        tableHeader.addView(numHeader, LinearLayout.LayoutParams(dp(40), headerHeight).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })

        val nameHeader = TextView(this).apply {
            text = "اسم\nالطالب\n..."
            textSize = 14f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F8F9FA"))
                setStroke(dp(1), Color.LTGRAY)
                cornerRadius = dp(8).toFloat()
            }
        }
        tableHeader.addView(nameHeader, LinearLayout.LayoutParams(0, headerHeight, 2.5f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })

        daysTextList.forEach { day ->
            val dayCell = FrameLayout(this).apply {
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#F8F9FA"))
                    setStroke(dp(1), Color.LTGRAY)
                    cornerRadius = dp(8).toFloat()
                }
            }
            val dayText = TextView(this).apply {
                text = day
                textSize = 13f 
                setTextColor(Color.BLACK)
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
                rotation = 270f 
            }
            dayCell.addView(dayText, FrameLayout.LayoutParams(dp(60), dp(50), Gravity.CENTER))
            tableHeader.addView(dayCell, LinearLayout.LayoutParams(0, headerHeight, 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
        }

        mainWhiteContainer.addView(tableHeader, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(5)) })

        val scrollView = ScrollView(this)
        val studentsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(studentsContainer)
        mainWhiteContainer.addView(scrollView, LinearLayout.LayoutParams(-1, 0, 1f))

        fun updateTable() {
            studentsContainer.removeAllViews()
            weekBar.text = "الاسبوع : $currentWeek"
            
            var studentNames = classStudentNames(currentClass)
            if (studentNames.isEmpty()) {
                studentNames = mutableListOf("احمد ...", "امير ...", "انس ...", "جلال ...", "خليل ...", "شوقي ...", "عبدالرحمن...", "عبدالله...", "محمد...", "مشتاق...", "معين...")
            }

            val savedDataStr = prefs.getString(attendanceKey(currentClass, currentMonth, currentWeek), "") ?: ""
            val savedRecords = savedDataStr.split("\n").filter { it.contains("|") }.associate { line ->
                val parts = line.split("|")
                parts[0] to parts.getOrElse(1) { "" }.split(",")
            }

            studentNames.forEachIndexed { index, studentName ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutDirection = View.LAYOUT_DIRECTION_RTL
                    setPadding(dp(5), dp(2), dp(5), dp(2))
                }

                val numCell = TextView(this).apply {
                    text = (index + 1).toString()
                    textSize = 15f
                    setTextColor(Color.BLACK)
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#E0F7FA")) 
                        setStroke(dp(1), Color.LTGRAY)
                        cornerRadius = dp(8).toFloat()
                    }
                }
                row.addView(numCell, LinearLayout.LayoutParams(dp(40), dp(40)).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })

                val nameCell = TextView(this).apply {
                    text = studentName
                    textSize = 14f
                    setTextColor(Color.BLACK)
                    gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
                    maxLines = 1 
                    ellipsize = TextUtils.TruncateAt.END 
                    background = GradientDrawable().apply {
                        setColor(if (index % 2 == 0) Color.WHITE else Color.parseColor("#F8F9FA"))
                        setStroke(dp(1), Color.LTGRAY)
                        cornerRadius = dp(8).toFloat()
                    }
                    setPadding(dp(10), 0, dp(10), 0)
                }
                row.addView(nameCell, LinearLayout.LayoutParams(0, dp(40), 2.5f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })

                val studentMarks = savedRecords[studentName] ?: List(6) { "" }
                for (dayIndex in 0 until 6) {
                    val mark = studentMarks.getOrElse(dayIndex) { "" }
                    val dayCell = TextView(this).apply {
                        text = mark
                        textSize = 16f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(if (mark == "غ") Color.RED else Color.BLACK)
                        gravity = Gravity.CENTER
                        background = GradientDrawable().apply {
                            setColor(Color.WHITE)
                            setStroke(dp(1), Color.LTGRAY)
                            cornerRadius = dp(8).toFloat()
                        }
                        setOnClickListener {
                            val currentMark = this.text.toString()
                            val newMark = when (currentMark) {
                                "" -> "✓"
                                "✓" -> "غ"
                                "غ" -> "م"
                                else -> ""
                            }
                            this.text = newMark
                            this.setTextColor(if (newMark == "غ") Color.RED else Color.BLACK)
                            val updatedMarks = studentMarks.toMutableList()
                            updatedMarks[dayIndex] = newMark
                            saveAttendanceForStudent(currentClass, currentMonth, currentWeek, studentName, updatedMarks)
                        }
                    }
                    row.addView(dayCell, LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
                }

                studentsContainer.addView(row, LinearLayout.LayoutParams(-1, -2))
            }
        }

        val bottomContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        root.addView(bottomContainer)

        val monthsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER
        }
        val monthsButtons = mutableListOf<TextView>()
        
        listOf("الشهر\nالاول", "الشهر\nالثاني", "الشهر\nالثالث").forEach { monthText ->
            val month = monthText.replace("\n", " ")
            val btn = TextView(this).apply {
                text = monthText
                textSize = 14f
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setOnClickListener {
                    currentMonth = month
                    monthsButtons.forEach { b -> 
                        val active = b.text.replace("\n", " ") == month
                        b.setTextColor(if (active) Color.WHITE else Color.BLACK)
                        b.background = GradientDrawable().apply {
                            setColor(if (active) Color.RED else Color.WHITE)
                            cornerRadius = dp(15).toFloat()
                        }
                    }
                    updateTable()
                }
            }
            monthsButtons.add(btn)
            monthsLayout.addView(btn, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(5), 0, dp(5), 0) })
        }
        bottomContainer.addView(monthsLayout)

        val weeksLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        val weeksButtons = mutableListOf<TextView>()

        listOf("7-1", "14-\n8", "15-\n22", "28-\n22", "21-\n28", "خلا\nصة").forEach { weekText ->
            val week = weekText.replace("\n", "")
            val btn = TextView(this).apply {
                text = weekText
                textSize = 13f
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
                setPadding(dp(5), dp(10), dp(5), dp(10))
                setOnClickListener {
                    currentWeek = week
                    weeksButtons.forEach { b -> 
                        val active = b.text.replace("\n", "") == week
                        b.setTextColor(if (active) Color.WHITE else Color.BLACK)
                        b.background = GradientDrawable().apply {
                            setColor(if (active) Color.RED else Color.WHITE)
                            cornerRadius = dp(20).toFloat()
                        }
                    }
                    updateTable()
                }
            }
            weeksButtons.add(btn)
            weeksLayout.addView(btn, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        bottomContainer.addView(weeksLayout)

        monthsButtons[0].performClick()
        weeksButtons[4].performClick() 
    }

    private fun saveAttendanceForStudent(cls: String, month: String, week: String, student: String, marks: List<String>) {
        val key = attendanceKey(cls, month, week)
        val currentStr = prefs.getString(key, "") ?: ""
        val lines = currentStr.split("\n").filter { it.isNotBlank() }.toMutableList()
        
        val newLine = "$student|${marks.joinToString(",")}"
        var found = false
        for (i in lines.indices) {
            if (lines[i].startsWith("$student|")) {
                lines[i] = newLine
                found = true
                break
            }
        }
        if (!found) lines.add(newLine)
        
        prefs.edit().putString(key, lines.joinToString("\n")).apply()
    }

    private fun showMonthlyAttendance() {
        setup("كشف الحضور الشهري")
        val months = listOf("الشهر جماداول", "الشهر جمادثاني", "الشهر رجب")
        val classes = readClassNames().ifEmpty { listOf("لا توجد صفوف") }

        val topLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val classSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, classes)
        }
        val monthSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, months)
        }
        topLayout.addView(classSpinner)
        topLayout.addView(monthSpinner)
        root.addView(topLayout, LinearLayout.LayoutParams(-1, -2))

        val table = TableLayout(this).apply {
            isStretchAllColumns = false
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val horizontalScroll = HorizontalScrollView(this).apply {
            isFillViewport = true
            addView(table, ViewGroup.LayoutParams(-2, -2))
        }
        val verticalScroll = ScrollView(this).apply {
            addView(horizontalScroll, ViewGroup.LayoutParams(-1, -2))
        }
        root.addView(verticalScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        fun cell(value: String, width: Int, background: Int = Color.WHITE, textColor: Int = Color.BLACK): TextView =
            TextView(this).apply {
                text = value; textSize = 11f; gravity = Gravity.CENTER
                setTextColor(textColor); setBackgroundColor(background)
                setPadding(dp(2), dp(7), dp(2), dp(7))
                layoutParams = TableRow.LayoutParams(dp(width), -2).apply {
                    setMargins(dp(1), dp(1), dp(1), dp(1))
                }
            }

        fun renderMonthly() {
            table.removeAllViews()
            val cls = classSpinner.selectedItem?.toString() ?: return
            val month = monthSpinner.selectedItem?.toString() ?: return

            if (cls == "لا توجد صفوف") {
                table.addView(TableRow(this).apply { addView(cell("لا توجد صفوف مسجلة", 220)) })
                return
            }

            val weeklyData = Array(5) { linkedMapOf<String, List<String>>() }
            val allNames = linkedSetOf<String>()

            for (weekNo in 1..5) {
                val weekLabel = "الأسبوع: $weekNo"
                val savedText = prefs.getString(attendanceKey(cls, month, weekLabel), "") ?: ""
                savedText.lines().forEach { line ->
                    val parts = line.split("|", limit = 2)
                    if (parts.size == 2) {
                        val studentName = parts[0].trim()
                        if (studentName.isNotEmpty()) {
                            val marks = parts[1].split(",").take(6).toMutableList()
                            while (marks.size < 6) marks.add("")
                            weeklyData[weekNo - 1][studentName] = marks
                            allNames.add(studentName)
                        }
                    }
                }
            }

            if (allNames.isEmpty()) {
                table.addView(TableRow(this).apply {
                    addView(cell("لا توجد سجلات محفوظة لهذا الشهر", 260, Color.WHITE))
                })
                return
            }

            val header = TableRow(this)
            header.addView(cell("اسم الطالب", 145, Color.rgb(23, 107, 85), Color.WHITE))

            for (day in 1..30) {
                val dayNames = listOf("سبت", "أحد", "اثن", "ثلا", "أربع", "خميس")
                val dayName = dayNames[(day - 1) % 6]
                header.addView(cell("$day\n$dayName", 48, Color.rgb(23, 107, 85), Color.WHITE))
            }

            header.addView(cell("حاضر", 55, Color.rgb(40, 130, 100), Color.WHITE))
            header.addView(cell("غائب", 55, Color.rgb(180, 70, 70), Color.WHITE))
            header.addView(cell("بعذر", 55, Color.rgb(70, 110, 170), Color.WHITE))
            table.addView(header)

            allNames.forEachIndexed { index, studentName ->
                val row = TableRow(this)
                val background = if (index % 2 == 0) Color.rgb(245, 248, 246) else Color.WHITE
                row.addView(cell(studentName, 145, Color.rgb(230, 238, 234)))
                val allMarks = mutableListOf<String>()
                for (weekIndex in 0 until 5) {
                    val marks = weeklyData[weekIndex][studentName] ?: List(6) { "" }
                    for (mark in marks.take(6)) {
                        allMarks.add(mark)
                        val markColor = when (mark) {
                            "✓" -> Color.rgb(40, 130, 80)
                            "غ" -> Color.rgb(190, 60, 60)
                            "م" -> Color.rgb(50, 100, 180)
                            else -> Color.BLACK
                        }
                        row.addView(cell(mark, 48, background, markColor))
                    }
                }
                val present = allMarks.count { it == "✓" }
                val absent = allMarks.count { it == "غ" }
                val excused = allMarks.count { it == "م" }
                row.addView(cell(present.toString(), 55, background))
                row.addView(cell(absent.toString(), 55, background))
                row.addView(cell(excused.toString(), 55, background))
                table.addView(row)
            }

            val totalRow = TableRow(this)
            val totalPresent = weeklyData.sumOf { week -> week.values.sumOf { marks -> marks.count { it == "✓" } } }
            val totalAbsent = weeklyData.sumOf { week -> week.values.sumOf { marks -> marks.count { it == "غ" } } }
            val totalExcused = weeklyData.sumOf { week -> week.values.sumOf { marks -> marks.count { it == "م" } } }

            totalRow.addView(cell("الإجمالي", 145, Color.rgb(220, 230, 225)))
            repeat(30) { totalRow.addView(cell("", 48, Color.rgb(220, 230, 225))) }
            totalRow.addView(cell(totalPresent.toString(), 55, Color.rgb(220, 230, 225)))
            totalRow.addView(cell(totalAbsent.toString(), 55, Color.rgb(220, 230, 225)))
            totalRow.addView(cell(totalExcused.toString(), 55, Color.rgb(220, 230, 225)))
            table.addView(totalRow)
        }

        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { renderMonthly() }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        classSpinner.onItemSelectedListener = listener
        monthSpinner.onItemSelectedListener = listener
        renderMonthly()

        addButton("العودة للحضور الأسبوعي") { showAttendance() }
        addButton("العودة للرئيسية") { showHome() }
    }

    private fun subjectsKey(className: String) = "result_subjects_v2_" + Uri.encode(className)
    private fun resultGradeKey(className: String, term: String, student: String, subject: String, part: String) =
        "result_marks_v2_${Uri.encode(className)}_${Uri.encode(term)}_${Uri.encode(student)}_${Uri.encode(subject)}_$part"

    private fun subjectsFor(className: String): MutableList<SubjectInfo> {
        val raw = prefs.getString(subjectsKey(className), "") ?: ""
        return raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }.map { SubjectInfo(it) }.toMutableList()
    }

    private fun saveSubjects(className: String, subjects: List<SubjectInfo>) {
        prefs.edit().putString(
            subjectsKey(className),
            subjects.map { it.name.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.joinToString("\n")
        ).commit()
    }

    private fun showResults() {
        setup("كشوف التحصيل والنتائج")
        val classes = readClassNames()
        if (classes.isEmpty()) {
            root.addView(TextView(this).apply {
                text = "لا توجد صفوف دراسية بعد. استورد أسماء الطلاب من Excel أولاً من قاعدة بيانات الطلاب."
                textSize = 17f; setPadding(4, 8, 4, 20)
            })
            addButton("العودة للرئيسية") { showHome() }
            return
        }
        root.addView(TextView(this).apply { text = "اختر الصف الدراسي:"; textSize = 16f })
        val classSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, classes)
        }
        root.addView(classSpinner)
        root.addView(TextView(this).apply { text = "الفصل الدراسي:"; textSize = 16f; setPadding(0, 12, 0, 2) })
        val termInput = EditText(this).apply { hint = "مثال: الفصل الأول 2026"; textSize = 17f }
        root.addView(termInput, LinearLayout.LayoutParams(-1, -2))
        root.addView(TextView(this).apply { text = "الشهر المراد إدخال درجاته:"; textSize = 16f; setPadding(0, 10, 0, 2) })
        val monthSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("الشهر جماداول", "الشهر جمادثاني", "الشهر رجب"))
        }
        root.addView(monthSpinner)

        addButton("إدارة المواد الدراسية") {
            manageSubjects(classSpinner.selectedItem?.toString() ?: classes.first())
        }
        addButton("إدخال درجات الطلاب") {
            val term = termInput.text.toString().trim()
            if (term.isEmpty()) toast("اكتب اسم الفصل الدراسي أولاً")
            else enterStudentGrades(
                classSpinner.selectedItem?.toString() ?: classes.first(),
                "$term|${monthSpinner.selectedItem}"
            )
        }
        addButton("عرض كشف النتائج والترتيب") {
            val term = termInput.text.toString().trim()
            if (term.isEmpty()) toast("اكتب اسم الفصل الدراسي أولاً")
            else showResultsSummary(
                classSpinner.selectedItem?.toString() ?: classes.first(),
                "$term|${monthSpinner.selectedItem}"
            )
        }
        addButton("العودة للرئيسية") { showHome() }
    }

    private fun manageSubjects(className: String) {
        val subjects = subjectsFor(className)
        val options = subjects.map { it.name }.toMutableList().apply { add("＋ إضافة مادة جديدة") }
        AlertDialog.Builder(this).setTitle("مواد الصف: $className")
            .setItems(options.toTypedArray()) { _, which ->
                if (which == subjects.size) editSubject(className, null) else showSubjectActions(className, subjects[which])
            }
            .setNegativeButton("إغلاق", null)
            .setNeutralButton("إضافة مادة") { _, _ -> editSubject(className, null) }
            .show()
    }

    private fun showSubjectActions(className: String, subject: SubjectInfo) {
        AlertDialog.Builder(this).setTitle(subject.name)
            .setItems(arrayOf("تعديل اسم المادة", "حذف المادة")) { _, which ->
                if (which == 0) editSubject(className, subject)
                else AlertDialog.Builder(this).setTitle("تأكيد حذف المادة")
                    .setMessage("سيتم حذف المادة ودرجاتها المحفوظة لهذا الصف والفترة. هل تريد المتابعة؟")
                    .setNegativeButton("إلغاء", null)
                    .setPositiveButton("حذف") { _, _ ->
                        val prefix = "result_marks_v2_${Uri.encode(className)}_"
                        val suffix = "_${Uri.encode(subject.name)}_"
                        val editor = prefs.edit()
                        prefs.all.keys.filter { it.startsWith(prefix) && it.contains(suffix) }
                            .forEach { editor.remove(it) }
                        editor.commit()
                        saveSubjects(className, subjectsFor(className).filterNot { it.name == subject.name })
                        toast("تم حذف المادة ودرجاتها")
                        manageSubjects(className)
                    }.show()
            }
            .setNegativeButton("رجوع") { _, _ -> manageSubjects(className) }
            .show()
    }

    private fun editSubject(className: String, existing: SubjectInfo?) {
        val name = EditText(this).apply { hint = "اسم المادة"; setText(existing?.name ?: ""); textSize = 17f }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(36, 8, 36, 4)
            addView(TextView(this@MainActivity).apply { text = "اسم المادة" })
            addView(name)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "إضافة مادة" else "تعديل المادة")
            .setView(panel).setPositiveButton("حفظ", null).setNegativeButton("إلغاء", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newName = name.text.toString().trim()
                val current = subjectsFor(className)
                if (newName.isEmpty()) { toast("اكتب اسم المادة"); return@setOnClickListener }
                if (current.any { it.name.equals(newName, true) && it.name != existing?.name }) {
                    toast("هذه المادة موجودة بالفعل"); return@setOnClickListener
                }
                if (existing == null) current.add(SubjectInfo(newName))
                else {
                    val index = current.indexOfFirst { it.name == existing.name }
                    if (index >= 0) current[index] = SubjectInfo(newName)
                    if (newName != existing.name) {
                        val oldPrefix = "result_marks_v2_${Uri.encode(className)}_"
                        val oldPart = "_${Uri.encode(existing.name)}_"
                        val newPart = "_${Uri.encode(newName)}_"
                        val editor = prefs.edit()
                        prefs.all.keys.filter { it.startsWith(oldPrefix) && it.contains(oldPart) }.forEach { oldKey ->
                            val keyParts = oldKey.split(oldPart)
                            val newKey = keyParts.joinToString(newPart)
                            prefs.getString(oldKey, null)?.let { editor.putString(newKey, it) }
                            editor.remove(oldKey)
                        }
                        editor.commit()
                    }
                }
                saveSubjects(className, current)
                dialog.dismiss(); toast("تم حفظ المادة"); manageSubjects(className)
            }
        }
        dialog.show()
    }

    private fun enterStudentGrades(className: String, term: String) {
        val students = classStudentNames(className)
        val subjects = subjectsFor(className)
        if (students.isEmpty()) { toast("لا توجد أسماء طلاب مرتبطة بهذا الصف"); return }
        if (subjects.isEmpty()) { toast("أضف المواد الدراسية أولاً"); manageSubjects(className); return }
        AlertDialog.Builder(this).setTitle("اختر المادة الدراسية")
            .setItems(subjects.map { it.name }.toTypedArray()) { _, which ->
                showMonthlySubjectEntry(className, term, subjects[which], students)
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun showMonthlySubjectEntry(className: String, term: String, subject: SubjectInfo, students: List<String>) {
        setup("إدخال درجات الطلاب")
        root.addView(TextView(this).apply {
            text = "الصف: $className\nالمادة: ${subject.name}\nالفترة: $term\nأدخل درجات جميع الطلاب في الجدول ثم اضغط حفظ الدرجات"
            textSize = 16f; setTextColor(green); setPadding(0, 0, 0, dp(10))
        })

        val outerScroll = HorizontalScrollView(this).apply {
            isFillViewport = true
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        val table = TableLayout(this).apply {
            isStretchAllColumns = false
            isShrinkAllColumns = false
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        outerScroll.addView(table, ViewGroup.LayoutParams(-2, -2))
        val vertical = ScrollView(this).apply { isFillViewport = true }
        vertical.addView(outerScroll, ViewGroup.LayoutParams(-1, -2))
        root.addView(vertical, LinearLayout.LayoutParams(-1, 0, 1f))

        fun cellText(value: String, header: Boolean = false): TextView = TextView(this).apply {
            text = value; textSize = if (header) 14f else 13f
            gravity = Gravity.CENTER
            setTextColor(if (header) Color.WHITE else Color.DKGRAY)
            setPadding(dp(5), dp(10), dp(5), dp(10))
            background = GradientDrawable().apply {
                if (header) { setColor(green); setStroke(dp(1), Color.WHITE) }
                else { setColor(Color.WHITE); setStroke(dp(1), Color.LTGRAY) }
                cornerRadius = dp(if (header) 5 else 3).toFloat()
            }
        }

        val headerHeight = dp(100)
        val header = TableRow(this).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR }

        fun addVerticalHeader(row: TableRow, label: String, width: Int) {
            val frame = FrameLayout(this).apply {
                background = GradientDrawable().apply {
                    setColor(Color.rgb(40, 100, 200))
                    setStroke(dp(1), Color.WHITE)
                    cornerRadius = dp(5).toFloat()
                }
            }
            val word = TextView(this).apply {
                text = label; textSize = 13f; gravity = Gravity.CENTER
                setTextColor(Color.WHITE); rotation = 270f; includeFontPadding = false
            }
            frame.addView(word, FrameLayout.LayoutParams(headerHeight, dp(width), Gravity.CENTER))
            row.addView(frame, TableRow.LayoutParams(dp(width), headerHeight))
        }
        fun addNormalHeader(row: TableRow, label: String, width: Int) {
            val view = TextView(this).apply {
                text = label; textSize = 14f; gravity = Gravity.CENTER
                setTextColor(Color.WHITE); setPadding(dp(4), dp(5), dp(4), dp(5))
                background = GradientDrawable().apply {
                    setColor(Color.rgb(178, 235, 242))
                    setStroke(dp(1), Color.WHITE); cornerRadius = dp(5).toFloat()
                }
            }
            row.addView(view, TableRow.LayoutParams(dp(width), headerHeight))
        }

        addNormalHeader(header, "م", 42)
        addNormalHeader(header, "اسم الطالب", 100)
        listOf("النوع", "الواجبات 20", "المواظبة 20", "الشفهي 20", "التحريري 40", "المجموع 100", "المتوسط 20")
            .forEach { addVerticalHeader(header, it, 35) }
        table.addView(header)

        data class GradeRow(
            val student: String,
            val gender: Spinner,
            val fields: List<EditText>,
            val total: TextView,
            val average: TextView
        )
        val gradeRows = mutableListOf<GradeRow>()
        val parts = listOf("homework", "attendance", "oral", "written")
        val maxima = listOf(20.0, 20.0, 20.0, 40.0)

        students.forEachIndexed { index, student ->
            val row = TableRow(this).apply {
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                minimumHeight = dp(58)
            }

            fun addSimple(view: View, width: Int) {
                row.addView(view, TableRow.LayoutParams(dp(width), dp(58)).apply {
                    setMargins(dp(1), dp(1), dp(1), dp(1))
                })
            }

            addSimple(cellText((index + 1).toString()), 35)
            addSimple(cellText(student), 100)

            val gender = Spinner(this).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("ذكر", "أنثى"))
                val gKey = "result_gender_v1_${Uri.encode(className)}_${Uri.encode(student)}"
                val savedGender = prefs.getString(gKey, "ذكر") ?: "ذكر"
                setSelection(if (savedGender == "أنثى") 1 else 0)
            }
            addSimple(gender, 35)

            val fields = parts.mapIndexed { i, part ->
                val edit = EditText(this).apply {
                    textSize = 14f; gravity = Gravity.CENTER
                    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                    hint = "—"; setPadding(dp(2), dp(4), dp(2), dp(4)); setBackgroundColor(Color.WHITE)
                    setText(prefs.getString(resultGradeKey(className, term, student, subject.name, part), "") ?: "")
                }
                addSimple(edit, 35)
                edit
            }

            val totalView = cellText("—")
            val averageView = cellText("—")
            addSimple(totalView, 35)
            addSimple(averageView, 35)

            fun updateTotals() {
                val values = fields.map { it.text.toString().trim().toDoubleOrNull() }
                if (values.all { it != null }) {
                    val total = values.filterNotNull().sum()
                    totalView.text = formatGrade(total)
                    averageView.text = formatGrade(total / 5.0)
                } else {
                    totalView.text = "—"; averageView.text = "—"
                }
            }
            fields.forEach {
                it.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updateTotals() }
                    override fun afterTextChanged(s: android.text.Editable?) {}
                })
            }
            updateTotals()

            gradeRows.add(GradeRow(student, gender, fields, totalView, averageView))
            table.addView(row)
        }

        addButton("حفظ درجات جميع الطلاب") {
            val invalid = gradeRows.firstOrNull { r ->
                r.fields.withIndex().any { (i, f) ->
                    val raw = f.text.toString().trim()
                    val value = raw.toDoubleOrNull()
                    raw.isNotEmpty() && (value == null || value < 0 || value > maxima[i])
                }
            }
            if (invalid != null) {
                toast("تحقق من درجات الطالب: ${invalid.student} — الحدود 20، 20، 20، 40")
                return@addButton
            }
            val editor = prefs.edit()
            gradeRows.forEach { r ->
                val genderKey = "result_gender_v1_${Uri.encode(className)}_${Uri.encode(r.student)}"
                editor.putString(genderKey, r.gender.selectedItem?.toString() ?: "ذكر")
                parts.forEachIndexed { i, part ->
                    val key = resultGradeKey(className, term, r.student, subject.name, part)
                    val raw = r.fields[i].text.toString().trim()
                    if (raw.isEmpty()) editor.remove(key) else editor.putString(key, raw)
                }
            }
            editor.commit()
            toast("تم حفظ درجات ${gradeRows.size} طالبًا في ${subject.name}")
        }
        addButton("العودة إلى المحصلات") { showResults() }
    }

    private fun getSubjectMarks(className: String, term: String, student: String, subject: String): SubjectMarks? {
        val parts = listOf("homework", "attendance", "oral", "written")
        val values = parts.map { prefs.getString(resultGradeKey(className, term, student, subject, it), null)?.toDoubleOrNull() }
        if (values.any { it == null }) return null
        return SubjectMarks(values[0]!!, values[1]!!, values[2]!!, values[3]!!)
    }

    private fun showResultsSummary(className: String, term: String) {
        val students = classStudentNames(className)
        val subjects = subjectsFor(className)
        if (students.isEmpty()) { toast("لا توجد أسماء طلاب لهذا الصف"); return }
        if (subjects.isEmpty()) { toast("أضف المواد الدراسية أولاً"); manageSubjects(className); return }
        val rows = students.map { student ->
            val marks = subjects.map { getSubjectMarks(className, term, student, it.name) }
            val entered = marks.count { it != null }
            val total = marks.filterNotNull().sumOf { it.total }
            val average = if (entered == subjects.size) (total / subjects.size / 5.0) else 0.0
            StudentResult(student, total, average, entered, subjects.size)
        }.sortedWith(compareByDescending<StudentResult> { it.enteredSubjects == it.subjectCount }
            .thenByDescending { it.total }.thenBy { it.name })
        val completed = rows.filter { it.enteredSubjects == it.subjectCount }
        val lines = rows.map { result ->
            val rank = if (result.enteredSubjects == result.subjectCount)
                completed.takeWhile { it.total > result.total }.size + 1 else 0
            val status = if (result.enteredSubjects == result.subjectCount) "مكتمل"
                else "غير مكتمل (${result.enteredSubjects}/${result.subjectCount} مادة)"
            "${if (rank == 0) "—" else rank}. ${result.name}\n   مجموع المواد: ${formatGrade(result.total)} / ${100 * subjects.size}\n   المتوسط العام: ${if (result.enteredSubjects == result.subjectCount) formatGrade(result.average20) else "—"} من 20\n   $status"
        }
        val subjectLines = subjects.joinToString("\n") {
            "${it.name}: واجبات 20 + مواظبة 20 + شفهي 20 + تحريري 40 = 100 (المتوسط من 20)"
        }
        val message = "الصف: $className\nالفترة: $term\n\nتوزيع درجات كل مادة:\n$subjectLines\n\nنتائج الطلاب:\n\n" +
                lines.joinToString("\n\n")
        val text = TextView(this).apply { this.text = message; textSize = 15f; setPadding(28, 12, 28, 12) }
        val scroll = ScrollView(this).apply { addView(text) }
        AlertDialog.Builder(this).setTitle("كشف النتائج والترتيب").setView(scroll)
            .setPositiveButton("إغلاق", null).show()
    }

    private fun formatGrade(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString()
        else String.format(Locale.US, "%.2f", value)

    private fun backupInfo() {
        AlertDialog.Builder(this).setTitle("النسخ الاحتياطي")
            .setMessage("البيانات محفوظة محليًا على الهاتف في هذه النسخة الأولية. سنضيف تصدير واستعادة نسخة احتياطية لاحقًا.")
            .setPositiveButton("حسنًا", null).show()
    }

    private fun xmlSafe(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")

    private fun excelColumn(number: Int): String {
        var n = number; var result = ""
        while (n > 0) {
            val r = (n - 1) % 26
            result = ('A' + r) + result
            n = (n - 1) / 26
        }
        return result
    }

    private fun createExcelFile(rows: List<List<String>>): ByteArray {
        val sheet = StringBuilder()
        sheet.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sheet.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
        rows.forEachIndexed { rowIndex, row ->
            val rowNumber = rowIndex + 1
            sheet.append("""<row r="$rowNumber">""")
            row.forEachIndexed { colIndex, value ->
                val cell = "${excelColumn(colIndex + 1)}$rowNumber"
                sheet.append("""<c r="$cell" t="inlineStr"><is><t xml:space="preserve">${xmlSafe(value)}</t></is></c>""")
            }
            sheet.append("</row>")
        }
        sheet.append("</sheetData></worksheet>")

        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            fun addFile(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            addFile(
                "[Content_Types].xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                <Default Extension="xml" ContentType="application/xml"/>
                <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                </Types>"""
            )
            addFile(
                "_rels/.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                </Relationships>"""
            )
            addFile(
                "xl/workbook.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                <sheets><sheet name="كشف البيانات" sheetId="1" r:id="rId1"/></sheets>
                </workbook>"""
            )
            addFile(
                "xl/_rels/workbook.xml.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                </Relationships>"""
            )
            addFile("xl/worksheets/sheet1.xml", sheet.toString())
        }
        return output.toByteArray()
    }

    private fun columnNumber(letters: String): Int {
        var value = 0
        for (c in letters) value = value * 26 + (c - 'A' + 1)
        return value
    }

    private fun readWorkbook(uri: Uri): ExcelBook {
        val entries = linkedMapOf<String, ByteArray>()
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input)
            ZipInputStream(input).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (!e.isDirectory) {
                        val out = ByteArrayOutputStream()
                        zip.copyTo(out)
                        entries[e.name] = out.toByteArray()
                    }
                    zip.closeEntry()
                }
            }
        }
        val wb = entries["xl/workbook.xml"] ?: error("Not xlsx")
        val rel = entries["xl/_rels/workbook.xml.rels"] ?: error("No rels")
        val rels = parseRelationships(rel)
        val sheets = parseSheetNames(wb).mapNotNull { (name, id) ->
            val target = rels[id] ?: return@mapNotNull null
            val path = normalizeSheetPath(target)
            if (entries.containsKey(path)) SheetInfo(name, path) else null
        }
        val strings = entries["xl/sharedStrings.xml"]?.let { parseSharedStrings(it) } ?: emptyList()
        return ExcelBook(entries, sheets, strings)
    }

    private fun parseRelationships(bytes: ByteArray): Map<String, String> {
        val p = Xml.newPullParser(); p.setInput(ByteArrayInputStream(bytes), "UTF-8")
        val r = mutableMapOf<String, String>(); var e = p.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG && p.name == "Relationship") {
                val id = p.getAttributeValue(null, "Id")
                val t = p.getAttributeValue(null, "Target")
                if (id != null && t != null) r[id] = t
            }
            e = p.next()
        }
        return r
    }

    private fun normalizeSheetPath(target: String): String {
        val c = target.replace("\\", "/")
        return when {
            c.startsWith("/") -> c.removePrefix("/")
            c.startsWith("xl/") -> c
            else -> "xl/$c"
        }
    }

    private fun parseSheetNames(bytes: ByteArray): List<Pair<String, String>> {
        val p = Xml.newPullParser(); p.setInput(ByteArrayInputStream(bytes), "UTF-8")
        val r = mutableListOf<Pair<String, String>>(); var e = p.eventType
        val relNs = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG && p.name == "sheet") {
                val n = p.getAttributeValue(null, "name")
                val id = p.getAttributeValue(relNs, "id")
                if (n != null && id != null) r.add(n to id)
            }
            e = p.next()
        }
        return r
    }

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val p = Xml.newPullParser(); p.setInput(ByteArrayInputStream(bytes), "UTF-8")
        val r = mutableListOf<String>()
        var inItem = false
        val current = StringBuilder()
        var e = p.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            when (e) {
                XmlPullParser.START_TAG -> if (p.name == "si") { inItem = true; current.setLength(0) }
                else if (inItem && p.name == "t") current.append(readText(p))
                XmlPullParser.END_TAG -> if (p.name == "si" && inItem) { r.add(current.toString()); inItem = false }
            }
            e = p.next()
        }
        return r
    }

    private fun readText(p: XmlPullParser): String {
        val out = StringBuilder(); var e = p.next()
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.TEXT || e == XmlPullParser.CDSECT) out.append(p.text)
            if (e == XmlPullParser.END_TAG && p.name == "t") break
            e = p.next()
        }
        return out.toString()
    }

    private fun columnFromCellReference(ref: String): Int {
        var v = 0
        for (c in ref.takeWhile { it.isLetter() }.uppercase()) v = v * 26 + (c - 'A' + 1)
        return v
    }

    private fun readNamesFromSheet(book: ExcelBook, sheet: SheetInfo, column: Int, firstRow: Int, lastRow: Int): List<String> {
        val bytes = book.entries[sheet.path] ?: error("Missing sheet")
        val p = Xml.newPullParser(); p.setInput(ByteArrayInputStream(bytes), "UTF-8")
        val result = mutableMapOf<Int, String>()
        var row = -1; var col = -1; var type: String? = null
        val value = StringBuilder()
        var target = false; var inValue = false; var inline = false
        var e = p.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            when (e) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> row = p.getAttributeValue(null, "r")?.toIntOrNull() ?: -1
                    "c" -> {
                        col = columnFromCellReference(p.getAttributeValue(null, "r") ?: "")
                        type = p.getAttributeValue(null, "t")
                        value.setLength(0)
                        target = row in firstRow..lastRow && col == column
                    }
                    "v" -> inValue = target
                    "t" -> inline = target && type == "inlineStr"
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (target && (inValue || inline)) value.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "v" -> inValue = false
                    "t" -> inline = false
                    "c" -> {
                        if (target) {
                            val raw = value.toString().trim()
                            val text = if (type == "s") raw.toIntOrNull()?.let { book.sharedStrings.getOrNull(it) } ?: ""
                            else raw
                            if (text.isNotBlank()) result[row] = text.trim()
                        }
                        target = false; inValue = false; inline = false
                    }
                }
            }
            e = p.next()
        }
        return (firstRow..lastRow).mapNotNull { result[it] }
    }

    private fun drawArabicCentered(
        canvas: Canvas,
        text: String,
        left: Float,
        top: Float,
        width: Int,
        height: Int,
        paint: TextPaint
    ) {
        val content = text.ifBlank { " " }
        val layout = StaticLayout.Builder
            .obtain(content, 0, content.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setTextDirection(TextDirectionHeuristics.RTL)
            .setIncludePad(false)
            .build()
        canvas.save()
        canvas.translate(left, top + (height - layout.height) / 2f)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun createAttendancePdf(
        className: String,
        month: String,
        week: String,
        names: List<String>,
        values: Map<String, List<String>>
    ): ByteArray {
        val document = PdfDocument()
        val output = ByteArrayOutputStream()

        val pageWidth = 842
        val pageHeight = 595
        val margin = 24
        val rowsPerPage = 25

        val numberWidth = 30f
        val nameWidth = 205f
        val dayWidth = 90f
        val headerHeight = 30f
        val rowHeight = 17f

        val borderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 0.7f
        }
        val fillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(20, 100, 200)
            style = android.graphics.Paint.Style.FILL
        }
        val whitePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = android.graphics.Paint.Style.FILL
        }
        val textPaint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 10f
            typeface = Typeface.DEFAULT
        }
        val titlePaint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
        }
        val headerPaint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
        }

        val totalPages = maxOf(1, (names.size + rowsPerPage - 1) / rowsPerPage)

        for (pageIndex in 0 until totalPages) {
            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageIndex + 1).create()
            val page = document.startPage(pageInfo)
            val canvas = page.canvas

            drawArabicCentered(canvas, "كشف الحضور والغياب", 0f, 10f, pageWidth, 34, titlePaint)
            drawArabicCentered(
                canvas,
                "الصف: $className    الشهر: $month    $week",
                40f, 46f, pageWidth - 80, 24, headerPaint
            )

            val tableTop = 82f
            val rightEdge = pageWidth - margin.toFloat()

            var x = rightEdge - nameWidth

            fun drawCell(text: String, left: Float, top: Float, width: Float, height: Float, header: Boolean = false) {
                canvas.drawRect(left, top, left + width, top + height, if (header) fillPaint else whitePaint)
                canvas.drawRect(left, top, left + width, top + height, borderPaint)
                drawArabicCentered(
                    canvas, text, left + 2f, top + 1f,
                    (width - 4f).toInt(), (height - 2f).toInt(),
                    if (header) headerPaint else textPaint
                )
            }

            drawCell("اسم الطالب", x, tableTop, nameWidth, headerHeight, true)
            x -= numberWidth
            drawCell("م", x, tableTop, numberWidth, headerHeight, true)

            val days = listOf("السبت", "الأحد", "الاثنين", "الثلاثاء", "الأربعاء", "الخميس")
            days.forEach { day ->
                x -= dayWidth
                drawCell(day, x, tableTop, dayWidth, headerHeight, true)
            }

            val start = pageIndex * rowsPerPage
            val end = minOf(start + rowsPerPage, names.size)

            for (index in start until end) {
                val name = names[index]
                val rowTop = tableTop + headerHeight + (index - start) * rowHeight
                var cellX = rightEdge - nameWidth
                drawCell(name, cellX, rowTop, nameWidth, rowHeight)
                cellX -= numberWidth
                drawCell((index + 1).toString(), cellX, rowTop, numberWidth, rowHeight)
                val studentValues = values[name] ?: emptyList()
                days.indices.forEach { day ->
                    cellX -= dayWidth
                    val mark = when (studentValues.getOrNull(day) ?: "") {
                        "✓" -> "حاضر"; "غ" -> "غائب"; "م" -> "بعذر"; else -> ""
                    }
                    drawCell(mark, cellX, rowTop, dayWidth, rowHeight)
                }
            }

            val footerY = pageHeight - 40f
            drawArabicCentered(
                canvas,
                "توقيع المعلم: ............................       توقيع الإدارة: ............................",
                40f, footerY, pageWidth - 80, 16, textPaint
            )
            drawArabicCentered(
                canvas,
                "صفحة ${pageIndex + 1} من $totalPages",
                10f, pageHeight - 18f, pageWidth - 20, 14, textPaint
            )

            document.finishPage(page)
        }

        document.writeTo(output)
        document.close()
        return output.toByteArray()
    }
{
// هذا القوس الأخير يجب أن يكون إغلاقاً } وليس فتحاً {