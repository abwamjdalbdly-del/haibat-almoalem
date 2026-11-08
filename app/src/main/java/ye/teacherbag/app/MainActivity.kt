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
                            val marks