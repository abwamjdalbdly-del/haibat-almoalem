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
    private val pickExcelRequest = 4101
    private data class SheetInfo(val name: String, val path: String)
    private data class ExcelBook(val entries: Map<String, ByteArray>, val sheets: List<SheetInfo>, val sharedStrings: List<String>)

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); showHome() }

    private fun setup(title: String) {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(20,20,20,20); setBackgroundColor(Color.rgb(246,248,247)) }
        root.addView(TextView(this).apply { text=title; textSize=24f; setTextColor(green); gravity=Gravity.CENTER; setPadding(8,18,8,22) })
        setContentView(root)
    }

    private fun addButton(label: String, action: () -> Unit) {
        val b=Button(this).apply { text=label; textSize=17f; isAllCaps=false; setTextColor(Color.WHITE); setBackgroundColor(green); setPadding(12,16,12,16) }
        root.addView(b,LinearLayout.LayoutParams(-1,-2).apply { setMargins(0,7,0,7) })
        b.setOnClickListener { action() }
    }

    private fun showHome() {
        setup("حقيبة المعلم الشاملة")
        root.addView(TextView(this).apply { text="إدارة مدرسية تعمل دون إنترنت"; textSize=16f; gravity=Gravity.CENTER; setTextColor(Color.DKGRAY); setPadding(4,0,4,20) })
        addButton("قاعدة بيانات الطلاب") { showStudents() }
        addButton("كشف الحضور والغياب") { showAttendance() }
        addButton("كشوف التحصيل والنتائج") { showResults() }
        addButton("نسخة احتياطية للبيانات") { backupInfo() }
        root.addView(TextView(this).apply { text="نسخة أولية قيد التطوير"; textSize=13f; gravity=Gravity.CENTER; setTextColor(Color.GRAY); setPadding(8,22,8,8) })
    }

    // قاعدة البيانات العامة القديمة محفوظة للتوافق مع بيانات الإصدارات السابقة.
    private fun studentNames(): MutableSet<String> {
        val saved=prefs.getString("students_list_v2",null)
        val old=prefs.getStringSet("students",emptySet())?.toSet() ?: emptySet()
        return (if(saved!=null) saved.split("\n").filter{it.isNotBlank()} else old).toMutableSet()
    }

    private fun saveStudentNames(names:Set<String>) {
        val clean=names.map{it.trim()}.filter{it.isNotEmpty()}.distinct()
        prefs.edit().putString("students_list_v2",clean.joinToString("\n")).putStringSet("students",clean.toSet()).apply()
    }

    // إدارة الصفوف واستعادة أسمائها من مفاتيح القوائم المحفوظة.
    private fun classNames(): MutableList<String> {
        val saved = prefs.getString("class_names_v1", "") ?: ""
        val names = mutableListOf<String>()
        saved.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.forEach { name ->
            if (names.none { it.equals(name, true) }) names.add(name)
        }
        prefs.all.keys.filter { it.startsWith("class_students_") }.forEach { key ->
            val name = Uri.decode(key.removePrefix("class_students_")).trim()
            if (name.isNotEmpty() && names.none { it.equals(name, true) }) names.add(name)
        }
        prefs.edit().putString("class_names_v1", names.joinToString("\n")).apply()
        return names
    }

    private fun saveClassNames(names: List<String>) {
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
        val classes = classNames()
        if (classes.none { it.equals(className.trim(), true) }) classes.add(className.trim())
        prefs.edit()
            .putString(classKey(className), clean.joinToString("\n"))
            .putString("class_names_v1", classes.joinToString("\n"))
            .commit()
    }

    private fun showStudents() {
        setup("قاعدة بيانات الطلاب")
        val input=EditText(this).apply { hint="اكتب اسم الطالب"; textSize=18f }
        root.addView(input,LinearLayout.LayoutParams(-1,-2))
        addButton("حفظ اسم الطالب") {
            val name=input.text.toString().trim()
            if(name.isEmpty()) toast("اكتب اسم الطالب أولاً") else {
                val names=studentNames()
                if(names.any{it.equals(name,true)}) toast("الاسم موجود مسبقًا") else {
                    names.add(name); saveStudentNames(names); input.setText(""); toast("تم حفظ الاسم على الهاتف"); showStudents()
                }
            }
        }
        addButton("استيراد أسماء الطلاب من Excel") { chooseExcelFile() }
        addButton("عرض أسماء الطلاب") {
            val n=studentNames().toList()
            AlertDialog.Builder(this).setTitle("أسماء الطلاب")
                .setMessage(if(n.isEmpty())"لا توجد أسماء محفوظة بعد" else n.joinToString("\n"))
                .setPositiveButton("إغلاق",null).show()
        }
        addButton("عرض الصفوف وأعداد طلابها") {
            val cs=classNames()
            val msg=if(cs.isEmpty())"لم يتم استيراد صفوف بعد" else cs.joinToString("\n"){ "$it : ${classStudentNames(it).size} طالبًا" }
            AlertDialog.Builder(this).setTitle("الصفوف الدراسية").setMessage(msg).setPositiveButton("إغلاق",null).show()
        }
        addButton("العودة للرئيسية") { showHome() }
    }

    private fun toast(s:String) { Toast.makeText(this,s,Toast.LENGTH_SHORT).show() }

    private fun chooseExcelFile() {
        val intent=Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        }
        try { startActivityForResult(intent,pickExcelRequest) } catch(e:Exception) { showError("تعذر فتح نافذة اختيار الملفات.") }
    }

    @Deprecated("Using startActivityForResult for compatibility")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==pickExcelRequest && resultCode==RESULT_OK) {
            val uri=data?.data ?: return
            try { val book=readWorkbook(uri); if(book.sheets.isEmpty()) showError("لم أجد أوراق عمل في الملف.") else chooseSheet(book) }
            catch(e:Exception) { showError("تعذر قراءة الملف. اختر ملف Excel بصيغة .xlsx غير محمي بكلمة مرور.") }
        }
    }

    private fun chooseSheet(book:ExcelBook) {
        AlertDialog.Builder(this).setTitle("اختر الصف / ورقة العمل")
            .setItems(book.sheets.map{it.name}.toTypedArray()) { _,which -> showImportOptions(book,book.sheets[which]) }
            .setNegativeButton("إلغاء",null).show()
    }

    private fun showImportOptions(book:ExcelBook,sheet:SheetInfo) {
        val panel=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(40,12,40,4) }
        val column=EditText(this).apply { hint="عمود الأسماء (B)"; setText("B"); textSize=17f; inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS }
        val first=EditText(this).apply { hint="أول صف"; setText("5"); inputType=android.text.InputType.TYPE_CLASS_NUMBER }
        val last=EditText(this).apply { hint="آخر صف"; setText("36"); inputType=android.text.InputType.TYPE_CLASS_NUMBER }
        panel.addView(TextView(this).apply{text="سيُسجل اسم الورقة صفًا دراسيًا: ${sheet.name}";textSize=16f})
        panel.addView(TextView(this).apply{text="حرف عمود الأسماء:"}); panel.addView(column)
        panel.addView(TextView(this).apply{text="أول صف:"}); panel.addView(first)
        panel.addView(TextView(this).apply{text="آخر صف:"}); panel.addView(last)
        val dialog=AlertDialog.Builder(this).setTitle("إعدادات استيراد الأسماء").setView(panel)
            .setPositiveButton("معاينة الأسماء",null).setNegativeButton("إلغاء",null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val col=column.text.toString().trim().uppercase()
            val f=first.text.toString().toIntOrNull(); val l=last.text.toString().toIntOrNull()
            if(!col.matches(Regex("[A-Z]{1,3}")) || f==null || l==null || f<1 || l<f || l-f>5000) {
                toast("تحقق من حرف العمود وأرقام الصفوف."); return@setOnClickListener
            }
            try {
                val extracted=readNamesFromSheet(book,sheet,columnNumber(col),f,l)
                dialog.dismiss(); previewNames(extracted,sheet.name)
            } catch(e:Exception) { showError("تعذر قراءة الأسماء من ورقة العمل المختارة.") }
        } }
        dialog.show()
    }

    private fun normalizeName(name:String)=name.trim().replace(Regex("\\s+")," ").lowercase()

    private fun previewNames(rawNames:List<String>,className:String) {
        val clean=mutableListOf<String>(); val seen=mutableSetOf<String>(); var duplicates=0
        for(raw in rawNames) {
            val name=raw.trim().replace(Regex("\\s+")," ")
            if(name.isEmpty()) continue
            if(!seen.add(normalizeName(name))) duplicates++ else clean.add(name)
        }
        val oldCount=classStudentNames(className).size
        if(clean.isEmpty()) {
            showError("لم يتم العثور على أسماء في ورقة $className. تحقق من عمود الأسماء وأرقام الصفوف.")
            return
        }
        val preview="عدد الأسماء المقروءة للصف $className: ${clean.size}\n"+
            "الأسماء المكررة داخل ملف Excel: $duplicates\n"+
            "عدد الأسماء المرتبطة بالصف حاليًا: $oldCount\n\n"+
            clean.take(100).joinToString("\n")+
            if(clean.size>100) "\n... وبقية الأسماء (${clean.size-100})" else ""
        AlertDialog.Builder(this).setTitle("معاينة أسماء الصف").setMessage(preview)
            .setPositiveButton("اعتماد القائمة وتحديث الصف") { _,_ ->
                saveClassStudentNames(className,clean)
                val classes=classNames()
                if(classes.none{it.equals(className,true)}) { classes.add(className); saveClassNames(classes) }
                val all=studentNames(); all.addAll(clean); saveStudentNames(all)
                AlertDialog.Builder(this).setTitle("تم تحديث الصف")
                    .setMessage("تم ربط ${clean.size} طالبًا بالصف $className.\nلم تُحذف أسماء القاعدة العامة أو بيانات الصفوف الأخرى.\nالمكرر داخل الملف: $duplicates")
                    .setPositiveButton("حسنًا") { _,_->showStudents() }.show()
            }.setNegativeButton("إلغاء",null).show()
    }

    private fun showAttendance() {
        setup("الحضور والغياب")
        val classes = classNames()
        if (classes.isEmpty()) {
            root.addView(TextView(this).apply { text = "لا توجد صفوف مستوردة. استورد أسماء الطلاب من قاعدة البيانات أولاً."; textSize = 17f })
            addButton("العودة للرئيسية") { showHome() }; return
        }
        val classSpinner = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, classes) }
        val months = listOf("الشهر الأول", "الشهر الثاني", "الشهر الثالث")
        val monthSpinner = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, months) }
        val weekSpinner = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, (1..4).map { "الأسبوع: $it" }) }
        fun label(t: String) { root.addView(TextView(this).apply { text=t; textSize=16f; setTextColor(green); setPadding(0,8,0,3) }) }
        label("الصف الدراسي"); root.addView(classSpinner)
        label("الشهر"); root.addView(monthSpinner)
        label("الأسبوع"); root.addView(weekSpinner)
        val days = listOf("السبت", "الأحد", "الاثنين", "الثلاثاء", "الأربعاء", "الخميس")
        val area = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        val scroller = HorizontalScrollView(this).apply { addView(area) }
        root.addView(scroller, LinearLayout.LayoutParams(-1,0,1f))
        fun key(cls:String, month:String, week:String) = "attendance_matrix_v1_${Uri.encode(cls)}_${Uri.encode(month)}_${Uri.encode(week)}"
        fun render() {
            area.removeAllViews()
            val cls=classSpinner.selectedItem?.toString() ?: classes.first()
            val month=monthSpinner.selectedItem?.toString() ?: months.first()
            val week=weekSpinner.selectedItem?.toString() ?: "الأسبوع: 1"
            val names=classStudentNames(cls)
            val saved=(prefs.getString(key(cls,month,week),"") ?: "").split("\n").filter{it.contains("|")}.associate { val a=it.split("|"); a[0] to a.getOrElse(1){""} }
            val table=TableLayout(this).apply { isStretchAllColumns=true }
            val header=TableRow(this)
            listOf("اسم الطالب") .plus(days).forEach { h -> header.addView(TextView(this).apply { text=h; textSize=13f; gravity=Gravity.CENTER; setTextColor(Color.WHITE); setBackgroundColor(green); setPadding(7,12,7,12) }) }
            table.addView(header)
            val values=linkedMapOf<String,MutableList<String>>()
            names.forEachIndexed { index,name ->
                val row=TableRow(this)
                row.addView(TextView(this).apply { text="${index+1}. $name"; textSize=14f; setPadding(8,10,8,10); setTextColor(Color.BLACK) })
                val old=saved[name]?.split(",") ?: emptyList()
                val dayValues=MutableList(6){i->old.getOrElse(i){""}}
                values[name]=dayValues
                days.indices.forEach { d ->
                    val sp=Spinner(this)
                    sp.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,listOf("—","✓","غ","م"))
                    sp.setSelection(when(dayValues[d]){"✓"->1;"غ"->2;"م"->3;else->0})
                    sp.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
                        override fun onNothingSelected(parent:AdapterView<*>?) {}
                        override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long) { dayValues[d]=listOf("","✓","غ","م")[position] }
                    }
                    row.addView(sp,TableRow.LayoutParams(100,-2))
                }
                table.addView(row)
                table.addView(TableRow(this).apply { addView(View(this@MainActivity).apply { setBackgroundColor(Color.LTGRAY) },TableRow.LayoutParams(-1,1)) })
            }
            area.addView(table)
            val save=Button(this).apply { text="حفظ سجل الأسبوع"; isAllCaps=false; setTextColor(Color.WHITE); setBackgroundColor(green) }
            area.addView(save,LinearLayout.LayoutParams(-1,-2))
            save.setOnClickListener {
                prefs.edit().putString(key(cls,month,week),values.entries.joinToString("\n"){it.key+"|"+it.value.joinToString(",")}).apply()
                val all=values.values.flatten()
                toast("تم الحفظ — حاضر: ${all.count{it=="✓"}}، غائب: ${all.count{it=="غ"}}، بعذر: ${all.count{it=="م"}}")
            }
            val summary=Button(this).apply { text="ملخص الحضور لهذا الأسبوع"; isAllCaps=false }
            area.addView(summary,LinearLayout.LayoutParams(-1,-2))
            summary.setOnClickListener {
                val all=values.values.flatten()
                AlertDialog.Builder(this).setTitle("ملخص $month — $week")
                    .setMessage("الصف: $cls\nأيام الحضور: ${all.count{it=="✓"}}\nالغياب: ${all.count{it=="غ"}}\nالغياب بعذر: ${all.count{it=="م"}}\nغير محدد: ${all.count{it.isEmpty()}}")
                    .setPositiveButton("إغلاق",null).show()
            }
        }
        val listener=object:AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent:AdapterView<*>?) {}
            override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long) { render() }
        }
        classSpinner.onItemSelectedListener=listener; monthSpinner.onItemSelectedListener=listener; weekSpinner.onItemSelectedListener=listener
        render()
        addButton("العودة للرئيسية") { showHome() }
    }

    private data class SubjectInfo(val name: String)
    private data class SubjectMarks(val homework: Double, val attendance: Double, val oral: Double, val written: Double) {
        val total: Double get() = homework + attendance + oral + written
        val average20: Double get() = total / 5.0
        val complete: Boolean get() = true
    }
    private data class StudentResult(val name: String, val total: Double, val average20: Double, val enteredSubjects: Int, val subjectCount: Int)

    private fun subjectsKey(className: String) = "result_subjects_v2_" + Uri.encode(className)
    private fun resultGradeKey(className: String, term: String, student: String, subject: String, part: String) =
        "result_marks_v2_${Uri.encode(className)}_${Uri.encode(term)}_${Uri.encode(student)}_${Uri.encode(subject)}_$part"

    private fun subjectsFor(className: String): MutableList<SubjectInfo> {
        val raw = prefs.getString(subjectsKey(className), "") ?: ""
        return raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }.map { SubjectInfo(it) }.toMutableList()
    }

    private fun saveSubjects(className: String, subjects: List<SubjectInfo>) {
        prefs.edit().putString(subjectsKey(className), subjects.map { it.name.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.joinToString("\n")).commit()
    }

    private fun showResults() {
        setup("كشوف التحصيل والنتائج")
        val classes = classNames()
        if (classes.isEmpty()) {
            root.addView(TextView(this).apply { text = "لا توجد صفوف دراسية بعد. استورد أسماء الطلاب من Excel أولاً من قاعدة بيانات الطلاب."; textSize = 17f; setPadding(4, 8, 4, 20) })
            addButton("العودة للرئيسية") { showHome() }
            return
        }
        root.addView(TextView(this).apply { text = "اختر الصف الدراسي:"; textSize = 16f })
        val classSpinner = Spinner(this)
        classSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, classes)
        root.addView(classSpinner)
        root.addView(TextView(this).apply { text = "الفصل الدراسي:"; textSize = 16f; setPadding(0, 12, 0, 2) })
        val termInput = EditText(this).apply { hint = "مثال: الفصل الأول 2026"; textSize = 17f }
        root.addView(termInput, LinearLayout.LayoutParams(-1, -2))
        root.addView(TextView(this).apply { text = "الشهر المراد إدخال درجاته:"; textSize = 16f; setPadding(0, 10, 0, 2) })
        val monthSpinner = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("الشهر الأول", "الشهر الثاني", "الشهر الثالث")) }
        root.addView(monthSpinner)
        addButton("إدارة المواد الدراسية") { manageSubjects(classSpinner.selectedItem?.toString() ?: classes.first()) }
        addButton("إدخال درجات الطلاب") {
            val term = termInput.text.toString().trim()
            if (term.isEmpty()) toast("اكتب اسم الفصل الدراسي أولاً")
            else enterStudentGrades(classSpinner.selectedItem?.toString() ?: classes.first(), "$term | ${monthSpinner.selectedItem}")
        }
        addButton("عرض كشف النتائج والترتيب") {
            val term = termInput.text.toString().trim()
            if (term.isEmpty()) toast("اكتب اسم الفصل الدراسي أولاً")
            else showResultsSummary(classSpinner.selectedItem?.toString() ?: classes.first(), "$term | ${monthSpinner.selectedItem}")
        }
        addButton("العودة للرئيسية") { showHome() }
    }

    private fun manageSubjects(className: String) {
        val subjects = subjectsFor(className)
        val options = subjects.map { it.name }.toMutableList().apply { add("＋ إضافة مادة جديدة") }
        AlertDialog.Builder(this).setTitle("مواد الصف: $className")
            .setItems(options.toTypedArray()) { _, which ->
                if (which == subjects.size) editSubject(className, null) else showSubjectActions(className, subjects[which])
            }.setNegativeButton("إغلاق", null).setNeutralButton("إضافة مادة") { _, _ -> editSubject(className, null) }.show()
    }

    private fun showSubjectActions(className: String, subject: SubjectInfo) {
        AlertDialog.Builder(this).setTitle(subject.name)
            .setItems(arrayOf("تعديل اسم المادة", "حذف المادة")) { _, which ->
                if (which == 0) editSubject(className, subject)
                else AlertDialog.Builder(this).setTitle("تأكيد حذف المادة")
                    .setMessage("سيتم حذف المادة ودرجاتها المحفوظة لهذا الصف والفترة. هل تريد المتابعة؟")
                    .setNegativeButton("إلغاء", null).setPositiveButton("حذف") { _, _ ->
                        val prefix = "result_marks_v2_${Uri.encode(className)}_"
                        val suffix = "_${Uri.encode(subject.name)}_"
                        val editor = prefs.edit()
                        prefs.all.keys.filter { it.startsWith(prefix) && it.contains(suffix) }.forEach { editor.remove(it) }
                        editor.commit()
                        saveSubjects(className, subjectsFor(className).filterNot { it.name == subject.name })
                        toast("تم حذف المادة ودرجاتها")
                        manageSubjects(className)
                    }.show()
            }.setNegativeButton("رجوع") { _, _ -> manageSubjects(className) }.show()
    }

    private fun editSubject(className: String, existing: SubjectInfo?) {
        val name = EditText(this).apply { hint = "اسم المادة"; setText(existing?.name ?: ""); textSize = 17f }
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 8, 36, 4); addView(TextView(this@MainActivity).apply { text = "اسم المادة" }); addView(name) }
        val dialog = AlertDialog.Builder(this).setTitle(if (existing == null) "إضافة مادة" else "تعديل المادة")
            .setView(panel).setPositiveButton("حفظ", null).setNegativeButton("إلغاء", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newName = name.text.toString().trim()
                val current = subjectsFor(className)
                if (newName.isEmpty()) { toast("اكتب اسم المادة"); return@setOnClickListener }
                if (current.any { it.name.equals(newName, true) && it.name != existing?.name }) { toast("هذه المادة موجودة بالفعل"); return@setOnClickListener }
                if (existing == null) current.add(SubjectInfo(newName))
                else {
                    val index = current.indexOfFirst { it.name == existing.name }
                    if (index >= 0) current[index] = SubjectInfo(newName)
                    if (newName != existing.name) {
                        val oldPrefix = "result_marks_v2_${Uri.encode(className)}_"
                        val oldPart = "_${Uri.encode(existing.name)}_"
                        val editor = prefs.edit()
                        prefs.all.keys.filter { it.startsWith(oldPrefix) && it.contains(oldPart) }.forEach { oldKey ->
                            val newKey = oldKey.replace(oldPart, "_${Uri.encode(newName)}_")
                            prefs.getString(oldKey, null)?.let { editor.putString(newKey, it) }
                            editor.remove(oldKey)
                        }
                        editor.commit()
                    }
                }
                saveSubjects(className, current); dialog.dismiss(); toast("تم حفظ المادة"); manageSubjects(className)
            }
        }
        dialog.show()
    }

    private fun enterStudentGrades(className: String, term: String) {
        val students = classStudentNames(className)
        val subjects = subjectsFor(className)
        if (students.isEmpty()) { toast("لا توجد أسماء طلاب مرتبطة بهذا الصف"); return }
        if (subjects.isEmpty()) { toast("أضف المواد الدراسية أولاً"); manageSubjects(className); return }
        AlertDialog.Builder(this).setTitle("اختر الطالب - $className")
            .setItems(students.toTypedArray()) { _, which -> showStudentGradeEditor(className, term, students[which], subjects) }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun showStudentGradeEditor(className: String, term: String, student: String, subjects: List<SubjectInfo>) {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 8, 28, 8) }
        panel.addView(TextView(this).apply { text = "الطالب: $student\nالصف: $className\nالفترة: $term"; textSize = 17f; setTextColor(green) })
        val fields = linkedMapOf<Pair<SubjectInfo, String>, EditText>()
        val components = listOf("الواجبات" to (20.0 to "homework"), "المواظبة" to (20.0 to "attendance"), "الشفهي" to (20.0 to "oral"), "التحريري" to (40.0 to "written"))
        subjects.forEach { subject ->
            panel.addView(TextView(this).apply { text = "مادة: ${subject.name} — المجموع 100"; textSize = 17f; setTextColor(green); setPadding(0, 14, 0, 3) })
            components.forEach { (label, spec) ->
                val maximum = spec.first; val keyPart = spec.second
                panel.addView(TextView(this).apply { text = "$label (من ${maximum.toInt()})"; textSize = 15f })
                val field = EditText(this).apply {
                    hint = "أدخل الدرجة"; inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                    textSize = 16f
                    setText(prefs.getString(resultGradeKey(className, term, student, subject.name, keyPart), "") ?: "")
                }
                fields[subject to keyPart] = field; panel.addView(field)
            }
        }
        val scroll = ScrollView(this).apply { addView(panel) }
        val dialog = AlertDialog.Builder(this).setTitle("درجات الطالب")
            .setView(scroll).setPositiveButton("حفظ الدرجات", null).setNegativeButton("إلغاء", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val invalid = fields.entries.firstOrNull { (key, field) ->
                    val raw = field.text.toString().trim(); val max = if (key.second == "written") 40.0 else 20.0
                    raw.isNotEmpty() && (raw.toDoubleOrNull() == null || raw.toDouble() < 0 || raw.toDouble() > max)
                }
                if (invalid != null) { toast("الواجبات والمواظبة والشفهي من 20، والتحريري من 40"); return@setOnClickListener }
                val editor = prefs.edit()
                fields.forEach { (key, field) ->
                    val storage = resultGradeKey(className, term, student, key.first.name, key.second)
                    val raw = field.text.toString().trim()
                    if (raw.isEmpty()) editor.remove(storage) else editor.putString(storage, raw)
                }
                editor.commit(); dialog.dismiss(); toast("تم حفظ درجات $student")
            }
        }
        dialog.show()
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
            val average = if (entered == subjects.size) total / subjects.size / 5.0 else 0.0
            StudentResult(student, total, average, entered, subjects.size)
        }.sortedWith(compareByDescending<StudentResult> { it.enteredSubjects == it.subjectCount }.thenByDescending { it.total }.thenBy { it.name })
        val completed = rows.filter { it.enteredSubjects == it.subjectCount }
        val lines = rows.map { result ->
            val rank = if (result.enteredSubjects == result.subjectCount) completed.takeWhile { it.total > result.total }.size + 1 else 0
            val status = if (result.enteredSubjects == result.subjectCount) "مكتمل" else "غير مكتمل (${result.enteredSubjects}/${result.subjectCount} مادة)"
            "${if (rank == 0) "—" else rank}. ${result.name}\n   مجموع المواد: ${formatGrade(result.total)} / ${100 * subjects.size}\n   المتوسط العام: ${if (result.enteredSubjects == result.subjectCount) formatGrade(result.average20) else "—"} من 20\n   $status"
        }
        val subjectLines = subjects.joinToString("\n") { "${it.name}: واجبات 20 + مواظبة 20 + شفهي 20 + تحريري 40 = 100 (المتوسط من 20)" }
        val message = "الصف: $className\nالفترة: $term\n\nتوزيع درجات كل مادة:\n$subjectLines\n\nنتائج الطلاب:\n\n" + lines.joinToString("\n\n")
        val text = TextView(this).apply { this.text = message; textSize = 15f; setPadding(28, 12, 28, 12) }
        val scroll = ScrollView(this).apply { addView(text) }
        AlertDialog.Builder(this).setTitle("كشف النتائج والترتيب").setView(scroll).setPositiveButton("إغلاق", null).show()
    }

    private fun formatGrade(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else String.format(java.util.Locale.US, "%.2f", value)

    private fun backupInfo() {
        AlertDialog.Builder(this).setTitle("النسخ الاحتياطي")
            .setMessage("البيانات محفوظة محليًا على الهاتف في هذه النسخة الأولية. سنضيف تصدير واستعادة نسخة احتياطية لاحقًا.")
            .setPositiveButton("حسنًا",null).show()
    }

    private fun showError(message:String) {
        AlertDialog.Builder(this).setTitle("تنبيه").setMessage(message).setPositiveButton("حسنًا",null).show()
    }

    private fun columnNumber(letters:String):Int {
        var value=0
        for(c in letters) value=value*26+(c-'A'+1)
        return value
    }

    private fun readWorkbook(uri:Uri):ExcelBook {
        val entries=linkedMapOf<String,ByteArray>()
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input)
            ZipInputStream(input).use { zip ->
                while(true) {
                    val e=zip.nextEntry?:break
                    if(!e.isDirectory) {
                        val out=java.io.ByteArrayOutputStream()
                        zip.copyTo(out)
                        entries[e.name]=out.toByteArray()
                    }
                    zip.closeEntry()
                }
            }
        }
        val wb=entries["xl/workbook.xml"]?:error("Not xlsx")
        val rel=entries["xl/_rels/workbook.xml.rels"]?:error("No rels")
        val rels=parseRelationships(rel)
        val sheets=parseSheetNames(wb).mapNotNull { (name,id) ->
            val target=rels[id]?:return@mapNotNull null
            val path=normalizeSheetPath(target)
            if(entries.containsKey(path)) SheetInfo(name,path) else null
        }
        val strings=entries["xl/sharedStrings.xml"]?.let{parseSharedStrings(it)}?:emptyList()
        return ExcelBook(entries,sheets,strings)
    }

    private fun parseRelationships(bytes:ByteArray):Map<String,String> {
        val p=Xml.newPullParser(); p.setInput(ByteArrayInputStream(bytes),"UTF-8")
        val r=mutableMapOf<String,String>(); var e=p.eventType
        while(e!=XmlPullParser.END_DOCUMENT) {
            if(e==XmlPullParser.START_TAG&&p.name=="Relationship") {
                val id=p.getAttributeValue(null,"Id"); val t=p.getAttributeValue(null,"Target")
                if(id!=null&&t!=null) r[id]=t
            }
            e=p.next()
        }
        return r
    }

    private fun normalizeSheetPath(target:String):String {
        val c=target.replace("\\","/")
        return when { c.startsWith("/") -> c.removePrefix("/"); c.startsWith("xl/") -> c; else -> "xl/$c" }
    }

    private fun parseSheetNames(bytes:ByteArray):List<Pair<String,String>> {
        val p=Xml.newPullParser(); p.setInput(ByteArrayInputStream(bytes),"UTF-8")
        val r=mutableListOf<Pair<String,String>>(); var e=p.eventType
        while(e!=XmlPullParser.END_DOCUMENT) {
            if(e==XmlPullParser.START_TAG&&p.name=="sheet") {
                val n=p.getAttributeValue(null,"name")
                val id=p.getAttributeValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships","id")?:findAttributeBySuffix(p,"id")
                if(n!=null&&id!=null) r.add(n to id)
            }
            e=p.next()
        }
        return r
    }

    private fun findAttributeBySuffix(p:XmlPullParser,suffix:String):String? {
        for(i in 0 until p.attributeCount) if(p.getAttributeName(i).endsWith(":$suffix")||p.getAttributeName(i)==suffix) return p.getAttributeValue(i)
        return null
    }

    private fun parseSharedStrings(bytes:ByteArray):List<String> {
        val p=Xml.newPullParser(); p.setInput(ByteArrayInputStream(bytes),"UTF-8")
        val r=mutableListOf<String>(); var inItem=false; val current=StringBuilder(); var e=p.eventType
        while(e!=XmlPullParser.END_DOCUMENT) {
            when(e) {
                XmlPullParser.START_TAG -> if(p.name=="si") { inItem=true; current.setLength(0) } else if(inItem&&p.name=="t") current.append(readText(p))
                XmlPullParser.END_TAG -> if(p.name=="si"&&inItem) { r.add(current.toString()); inItem=false }
            }
            e=p.next()
        }
        return r
    }

    private fun readText(p:XmlPullParser):String {
        val out=StringBuilder(); var e=p.next()
        while(e!=XmlPullParser.END_DOCUMENT) {
            if(e==XmlPullParser.TEXT||e==XmlPullParser.CDSECT) out.append(p.text)
            if(e==XmlPullParser.END_TAG&&p.name=="t") break
            e=p.next()
        }
        return out.toString()
    }

    private fun columnFromCellReference(ref:String):Int {
        var v=0
        for(c in ref.takeWhile{it.isLetter()}.uppercase()) v=v*26+(c-'A'+1)
        return v
    }

    private fun readNamesFromSheet(book:ExcelBook,sheet:SheetInfo,column:Int,firstRow:Int,lastRow:Int):List<String> {
        val bytes=book.entries[sheet.path]?:error("Missing sheet")
        val p=Xml.newPullParser(); p.setInput(ByteArrayInputStream(bytes),"UTF-8")
        val result=mutableMapOf<Int,String>()
        var row=-1; var col=-1; var type:String?=null; val value=StringBuilder()
        var target=false; var inValue=false; var inline=false; var e=p.eventType
        while(e!=XmlPullParser.END_DOCUMENT) {
            when(e) {
                XmlPullParser.START_TAG -> when(p.name) {
                    "row" -> row=p.getAttributeValue(null,"r")?.toIntOrNull()?:-1
                    "c" -> {
                        col=columnFromCellReference(p.getAttributeValue(null,"r")?:"")
                        type=p.getAttributeValue(null,"t"); value.setLength(0)
                        target=row in firstRow..lastRow&&col==column
                    }
                    "v" -> inValue=target
                    "t" -> inline=target&&type=="inlineStr"
                }
                XmlPullParser.TEXT,XmlPullParser.CDSECT -> if(target&&(inValue||inline)) value.append(p.text)
                XmlPullParser.END_TAG -> when(p.name) {
                    "v" -> inValue=false
                    "t" -> inline=false
                    "c" -> {
                        if(target) {
                            val raw=value.toString().trim()
                            val text=if(type=="s") raw.toIntOrNull()?.let{book.sharedStrings.getOrNull(it)}?:"" else raw
                            if(text.isNotBlank()) result[row]=text.trim()
                        }
                        target=false; inValue=false; inline=false
                    }
                }
            }
            e=p.next()
        }
        return (firstRow..lastRow).mapNotNull{result[it]}
    }
}
