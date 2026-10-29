package ye.teacherbag.app

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class MainActivity : Activity() {
    private val green = Color.rgb(23, 107, 85)
    private val prefs by lazy { getSharedPreferences("teacher_bag_local", Context.MODE_PRIVATE) }
    private lateinit var root: LinearLayout

    private data class SheetInfo(val name: String, val path: String)
    private data class ExcelBook(
        val entries: Map<String, ByteArray>,
        val sheets: List<SheetInfo>,
        val sharedStrings: List<String>
    )

    private val pickExcelRequest = 4101
    private var pendingImportUri: Uri? = null

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
        val lp = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 7, 0, 7) }
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

    private fun saveStudentNames(names: Set<String>) {
        prefs.edit().putStringSet("students", names.toSet()).apply()
    }

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
                val exists = names.any { it.trim().equals(name, ignoreCase = true) }
                if (exists) {
                    Toast.makeText(this, "الاسم موجود مسبقًا", Toast.LENGTH_SHORT).show()
                } else {
                    names.add(name)
                    saveStudentNames(names)
                    input.setText("")
                    Toast.makeText(this, "تم حفظ الاسم على الهاتف", Toast.LENGTH_SHORT).show()
                    showStudents()
                }
            }
        }
        addButton("استيراد أسماء الطلاب من Excel") { chooseExcelFile() }
        addButton("عرض أسماء الطلاب") {
            val names = studentNames().sorted()
            AlertDialog.Builder(this).setTitle("أسماء الطلاب")
                .setMessage(if (names.isEmpty()) "لا توجد أسماء محفوظة بعد" else names.joinToString("\n"))
                .setPositiveButton("إغلاق", null).show()
        }
        addButton("العودة للرئيسية") { showHome() }
    }

    private fun chooseExcelFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        }
        try {
            startActivityForResult(intent, pickExcelRequest)
        } catch (e: Exception) {
            showError("تعذر فتح نافذة اختيار الملفات.")
        }
    }

    @Deprecated("Using startActivityForResult for compatibility with the current plain Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickExcelRequest && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            pendingImportUri = uri
            try {
                val book = readWorkbook(uri)
                if (book.sheets.isEmpty()) {
                    showError("لم أجد أوراق عمل في الملف. تأكد أنه ملف Excel بصيغة .xlsx.")
                    return
                }
                chooseSheet(book)
            } catch (e: Exception) {
                showError("تعذر قراءة الملف. اختر ملف Excel حديثًا بصيغة .xlsx غير محمي بكلمة مرور.")
            }
        }
    }

    private fun chooseSheet(book: ExcelBook) {
        val labels = book.sheets.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("اختر ورقة العمل")
            .setItems(labels) { _, which ->
                showImportOptions(book, book.sheets[which])
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showImportOptions(book: ExcelBook, sheet: SheetInfo) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 12, 40, 4)
        }
        val column = EditText(this).apply {
            hint = "عمود الأسماء (B)"
            setText("B")
            textSize = 17f
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        val firstRow = EditText(this).apply {
            hint = "أول صف"
            setText("5")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val lastRow = EditText(this).apply {
            hint = "آخر صف"
            setText("36")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        panel.addView(TextView(this).apply { text = "ورقة العمل: ${sheet.name}"; textSize = 16f })
        panel.addView(TextView(this).apply { text = "حرف العمود الذي يحتوي على الأسماء:"; textSize = 15f })
        panel.addView(column)
        panel.addView(TextView(this).apply { text = "رقم أول صف:"; textSize = 15f })
        panel.addView(firstRow)
        panel.addView(TextView(this).apply { text = "رقم آخر صف:"; textSize = 15f })
        panel.addView(lastRow)
        AlertDialog.Builder(this)
            .setTitle("إعدادات استيراد الأسماء")
            .setView(panel)
            .setPositiveButton("معاينة الأسماء", null)
            .setNegativeButton("إلغاء", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val colText = column.text.toString().trim().uppercase()
                        val first = firstRow.text.toString().toIntOrNull()
                        val last = lastRow.text.toString().toIntOrNull()
                        if (!colText.matches(Regex("[A-Z]{1,3}")) || first == null || last == null ||
                            first < 1 || last < first || last - first > 5000) {
                            Toast.makeText(this, "تحقق من حرف العمود وأرقام الصفوف.", Toast.LENGTH_LONG).show()
                            return@setOnClickListener
                        }
                        val colIndex = columnNumber(colText)
                        try {
                            val extracted = readNamesFromSheet(book, sheet, colIndex, first, last)
                            dialog.dismiss()
                            previewNames(extracted)
                        } catch (e: Exception) {
                            showError("تعذر قراءة الأسماء من ورقة العمل المختارة.")
                        }
                    }
                }
                dialog.show()
            }
    }

    private fun previewNames(rawNames: List<String>) {
        val seen = linkedSetOf<String>()
        val existing = studentNames().map { normalizeName(it) }.toSet()
        val newNames = mutableListOf<String>()
        var duplicates = 0
        for (raw in rawNames) {
            val name = raw.trim()
            if (name.isEmpty()) continue
            val normalized = normalizeName(name)
            if (normalized in existing || !seen.add(normalized)) {
                duplicates++
            } else {
                newNames.add(name)
            }
        }
        val preview = if (newNames.isEmpty()) {
            "لا توجد أسماء جديدة للحفظ.\nالأسماء المكررة أو الموجودة مسبقًا: $duplicates"
        } else {
            "الأسماء الجديدة التي ستُحفظ (${newNames.size}):\n\n" +
                newNames.take(100).joinToString("\n") +
                if (newNames.size > 100) "\n... وبقية الأسماء (${newNames.size - 100})" else ""
        }
        AlertDialog.Builder(this)
            .setTitle("معاينة قبل الحفظ")
            .setMessage("$preview\n\nسيتم تجاهل المكرر: $duplicates")
            .setPositiveButton(if (newNames.isEmpty()) "إغلاق" else "حفظ الأسماء") { _, _ ->
                if (newNames.isNotEmpty()) {
                    val combined = studentNames()
                    combined.addAll(newNames)
                    saveStudentNames(combined)
                    AlertDialog.Builder(this)
                        .setTitle("اكتمل الاستيراد")
                        .setMessage("تمت إضافة ${newNames.size} اسمًا جديدًا.\nتم تجاهل $duplicates اسمًا مكررًا أو موجودًا مسبقًا.")
                        .setPositiveButton("حسنًا") { _, _ -> showStudents() }
                        .show()
                }
            }
            .setNegativeButton("رجوع", null)
            .show()
    }

    private fun normalizeName(name: String): String =
        name.trim().replace(Regex("\\s+"), " ").lowercase()

    private fun columnNumber(letters: String): Int {
        var value = 0
        for (c in letters) value = value * 26 + (c - 'A' + 1)
        return value
    }

    private fun readWorkbook(uri: Uri): ExcelBook {
        val entries = linkedMapOf<String, ByteArray>()
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open file" }
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory) {
                        val out = java.io.ByteArrayOutputStream()
                        zip.copyTo(out)
                        entries[entry.name] = out.toByteArray()
                    }
                    zip.closeEntry()
                }
            }
        }
        val workbookBytes = entries["xl/workbook.xml"] ?: error("Not an xlsx workbook")
        val relBytes = entries["xl/_rels/workbook.xml.rels"] ?: error("Missing workbook relationships")
        val rels = parseRelationships(relBytes)
        val sheets = parseSheetNames(workbookBytes).mapNotNull { (name, relId) ->
            val target = rels[relId] ?: return@mapNotNull null
            val normalized = normalizeSheetPath(target)
            if (entries.containsKey(normalized)) SheetInfo(name, normalized) else null
        }
        val strings = entries["xl/sharedStrings.xml"]?.let { parseSharedStrings(it) } ?: emptyList()
        return ExcelBook(entries, sheets, strings)
    }

    private fun parseRelationships(bytes: ByteArray): Map<String, String> {
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(bytes), "UTF-8")
        val result = mutableMapOf<String, String>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "Relationship") {
                val id = parser.getAttributeValue(null, "Id")
                val target = parser.getAttributeValue(null, "Target")
                if (id != null && target != null) result[id] = target
            }
            event = parser.next()
        }
        return result
    }

    private fun normalizeSheetPath(target: String): String {
        val clean = target.replace("\\", "/")
        if (clean.startsWith("/")) return clean.removePrefix("/")
        if (clean.startsWith("xl/")) return clean
        return "xl/$clean"
    }

    private fun parseSheetNames(bytes: ByteArray): List<Pair<String, String>> {
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(bytes), "UTF-8")
        val result = mutableListOf<Pair<String, String>>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "sheet") {
                val name = parser.getAttributeValue(null, "name")
                val relId = parser.getAttributeValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")
                    ?: parser.getAttributeValue("r", "id")
                    ?: findAttributeBySuffix(parser, "id")
                if (name != null && relId != null) result.add(name to relId)
            }
            event = parser.next()
        }
        return result
    }

    private fun findAttributeBySuffix(parser: XmlPullParser, suffix: String): String? {
        for (i in 0 until parser.attributeCount) {
            if (parser.getAttributeName(i).endsWith(":$suffix") || parser.getAttributeName(i) == suffix) {
                return parser.getAttributeValue(i)
            }
        }
        return null
    }

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(bytes), "UTF-8")
        val result = mutableListOf<String>()
        var inItem = false
        val current = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "si") {
                        inItem = true
                        current.setLength(0)
                    } else if (inItem && parser.name == "t") {
                        current.append(readText(parser))
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "si" && inItem) {
                    result.add(current.toString())
                    inItem = false
                }
            }
            event = parser.next()
        }
        return result
    }

    private fun readText(parser: XmlPullParser): String {
        val out = StringBuilder()
        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.TEXT || event == XmlPullParser.CDSECT) out.append(parser.text)
            if (event == XmlPullParser.END_TAG && parser.name == "t") break
            event = parser.next()
        }
        return out.toString()
    }

    
private fun readNamesFromSheet(
    book: ExcelBook,
    sheet: SheetInfo,
    column: Int,
    firstRow: Int,
    lastRow: Int
): List<String> {
    val bytes = book.entries[sheet.path] ?: error("Missing sheet data")
    val parser = Xml.newPullParser()
    parser.setInput(ByteArrayInputStream(bytes), "UTF-8")

    val result = mutableMapOf<Int, String>()
    var currentRow = -1
    var currentCellColumn = -1
    var cellType: String? = null
    val cellValue = StringBuilder()
    var inTargetCell = false
    var inValue = false
    var inInlineText = false

    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        when (event) {
            XmlPullParser.START_TAG -> when (parser.name) {
                "row" -> {
                    currentRow = parser.getAttributeValue(null, "r")
                        ?.toIntOrNull() ?: -1
                }
                "c" -> {
                    val ref = parser.getAttributeValue(null, "r") ?: ""
                    currentCellColumn = columnFromCellReference(ref)
                    cellType = parser.getAttributeValue(null, "t")
                    cellValue.setLength(0)
                    inTargetCell =
                        currentRow in firstRow..lastRow &&
                        currentCellColumn == column
                }
                "v" -> inValue = inTargetCell
                "t" -> inInlineText =
                    inTargetCell && cellType == "inlineStr"
            }

            XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                if (inTargetCell && (inValue || inInlineText)) {
                    cellValue.append(parser.text)
                }
            }

            XmlPullParser.END_TAG -> when (parser.name) {
                "v" -> inValue = false
                "t" -> inInlineText = false
                "c" -> {
                    if (inTargetCell) {
                        val raw = cellValue.toString().trim()
                        val value = if (cellType == "s") {
                            raw.toIntOrNull()
                                ?.let { book.sharedStrings.getOrNull(it) }
                                ?: ""
                        } else {
                            raw
                        }.trim()

                        if (value.isNotEmpty()) {
                            result[currentRow] = value
                        }
                    }
                    inTargetCell = false
                    inValue = false
                    inInlineText = false
                }
            }
        }
        event = parser.next()
    }

    return (firstRow..lastRow).mapNotNull { result[it] }
}

    private fun columnFromCellReference(ref: String): Int {
        var value = 0
        for (c in ref.takeWhile { it.isLetter() }.uppercase()) {
            value = value * 26 + (c - 'A' + 1)
        }
        return value
    }

    private fun showError(message: String) {
        AlertDialog.Builder(this).setTitle("تنبيه")
            .setMessage(message)
            .setPositiveButton("حسنًا", null).show()
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
            val month = EditText(this).apply { hint = "الشهر / الفترة"; textSize = 17f }
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
                    this, android.R.layout.simple_spinner_dropdown_item,
                    listOf("√ حاضر", "غ غائب", "م بعذر")
                )
                line.addView(spinner, LinearLayout.LayoutParams(130, -2))
                spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        choices[name] = listOf("√", "غ", "م")[position]
                    }
                }
                root.addView(line)
            }
            addButton("حفظ كشف الحضور") {
                val key = "attendance_" + month.text.toString().trim()
                val packed = choices.entries.joinToString("\n") { "${it.key}|${it.value}" }
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
