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
        root.addView(b,LinearLayout.LayoutParams(-1,-2).apply { setMargins(0,7,0,7) }); b.setOnClickListener { action() }
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
    private fun classNames(): MutableList<String> = prefs.getString("class_names_v1","")!!.split("\n").map{it.trim()}.filter{it.isNotEmpty()}.distinct().toMutableList()
    private fun saveClassNames(names:List<String>) { prefs.edit().putString("class_names_v1",names.map{it.trim()}.filter{it.isNotEmpty()}.distinct().joinToString("\n")).apply() }
    private fun classKey(className:String) = "class_students_" + Uri.encode(className)
    private fun classStudentNames(className:String): MutableList<String> = prefs.getString(classKey(className),"")!!.split("\n").map{it.trim()}.filter{it.isNotEmpty()}.distinct().toMutableList()
    private fun saveClassStudentNames(className:String,names:List<String>) { prefs.edit().putString(classKey(className),names.map{it.trim()}.filter{it.isNotEmpty()}.distinct().joinToString("\n")).apply() }

    private fun showStudents() {
        setup("قاعدة بيانات الطلاب")
        val input=EditText(this).apply { hint="اكتب اسم الطالب"; textSize=18f }; root.addView(input,LinearLayout.LayoutParams(-1,-2))
        addButton("حفظ اسم الطالب") {
            val name=input.text.toString().trim()
            if(name.isEmpty()) toast("اكتب اسم الطالب أولاً") else {
                val names=studentNames()
                if(names.any{it.equals(name,true)}) toast("الاسم موجود مسبقًا") else { names.add(name); saveStudentNames(names); input.setText(""); toast("تم حفظ الاسم على الهاتف"); showStudents() }
            }
        }
        addButton("استيراد أسماء الطلاب من Excel") { chooseExcelFile() }
        addButton("عرض أسماء الطلاب") { val n=studentNames().toList(); AlertDialog.Builder(this).setTitle("أسماء الطلاب").setMessage(if(n.isEmpty())"لا توجد أسماء محفوظة بعد" else n.joinToString("\n")).setPositiveButton("إغلاق",null).show() }
        addButton("عرض الصفوف وأعداد طلابها") {
            val cs=classNames(); val msg=if(cs.isEmpty())"لم يتم استيراد صفوف بعد" else cs.joinToString("\n"){ "$it : ${classStudentNames(it).size} طالبًا" }
            AlertDialog.Builder(this).setTitle("الصفوف الدراسية").setMessage(msg).setPositiveButton("إغلاق",null).show()
        }
        addButton("العودة للرئيسية") { showHome() }
    }
    private fun toast(s:String) { Toast.makeText(this,s,Toast.LENGTH_SHORT).show() }

    private fun chooseExcelFile() {
        val intent=Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" }
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
        AlertDialog.Builder(this).setTitle("اختر الصف / ورقة العمل").setItems(book.sheets.map{it.name}.toTypedArray()) { _,which -> showImportOptions(book,book.sheets[which]) }.setNegativeButton("إلغاء",null).show()
    }
    private fun showImportOptions(book:ExcelBook,sheet:SheetInfo) {
        val panel=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(40,12,40,4) }
        val column=EditText(this).apply { hint="عمود الأسماء (B)"; setText("B"); textSize=17f; inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS }
        val first=EditText(this).apply { hint="أول صف"; setText("5"); inputType=android.text.InputType.TYPE_CLASS_NUMBER }
        val last=EditText(this).apply { hint="آخر صف"; setText("36"); inputType=android.text.InputType.TYPE_CLASS_NUMBER }
        panel.addView(TextView(this).apply{text="سيُسجل اسم الورقة صفًا دراسيًا: ${sheet.name}";textSize=16f}); panel.addView(TextView(this).apply{text="حرف عمود الأسماء:"}); panel.addView(column); panel.addView(TextView(this).apply{text="أول صف:"}); panel.addView(first); panel.addView(TextView(this).apply{text="آخر صف:"}); panel.addView(last)
        val dialog=AlertDialog.Builder(this).setTitle("إعدادات استيراد الأسماء").setView(panel).setPositiveButton("معاينة الأسماء",null).setNegativeButton("إلغاء",null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val col=column.text.toString().trim().uppercase(); val f=first.text.toString().toIntOrNull(); val l=last.text.toString().toIntOrNull()
            if(!col.matches(Regex("[A-Z]{1,3}")) || f==null || l==null || f<1 || l<f || l-f>5000) { toast("تحقق من حرف العمود وأرقام الصفوف."); return@setOnClickListener }
            try { val extracted=readNamesFromSheet(book,sheet,columnNumber(col),f,l); dialog.dismiss(); previewNames(extracted,sheet.name) } catch(e:Exception) { showError("تعذر قراءة الأسماء من ورقة العمل المختارة.") }
        } }; dialog.show()
    }
    private fun normalizeName(name:String)=name.trim().replace(Regex("\\s+")," ").lowercase()
    private fun previewNames(rawNames:List<String>,className:String) {
        val existing=classStudentNames(className).map{normalizeName(it)}.toSet()
        val seen=mutableSetOf<String>(); val newNames=mutableListOf<String>(); var duplicates=0
        for(raw in rawNames) { val name=raw.trim(); if(name.isEmpty()) continue; val key=normalizeName(name); if(key in existing || !seen.add(key)) duplicates++ else newNames.add(name) }
        val message=if(newNames.isEmpty()) "لا توجد أسماء جديدة للحفظ." else "الأسماء الجديدة للصف $className (${newNames.size}):\n\n"+newNames.take(100).joinToString("\n")+(if(newNames.size>100)"\n... وبقية الأسماء" else "")
        AlertDialog.Builder(this).setTitle("معاينة قبل الحفظ").setMessage("$message\n\nالمكرر أو الموجود مسبقًا: $duplicates").setPositiveButton(if(newNames.isEmpty())"إغلاق" else "حفظ الأسماء") { _,_ ->
            if(newNames.isNotEmpty()) {
                val combined=(classStudentNames(className)+newNames).distinctBy{normalizeName(it)}
                saveClassStudentNames(className,combined)
                val classes=classNames(); if(className !in classes) { classes.add(className); saveClassNames(classes) }
                val all=studentNames(); all.addAll(newNames); saveStudentNames(all)
                AlertDialog.Builder(this).setTitle("اكتمل الاستيراد").setMessage("تم حفظ ${newNames.size} اسمًا في صف $className.\nتم تجاهل $duplicates اسمًا مكررًا.").setPositiveButton("حسنًا"){_,_->showStudents()}.show()
            }
        }.setNegativeButton("رجوع",null).show()
    }

    private fun showAttendance() {
        setup("كشف الحضور والغياب")
        val classes=classNames()
        if(classes.isEmpty()) {
            root.addView(TextView(this).apply { text="لا توجد صفوف مستوردة بعد. انتقل إلى قاعدة بيانات الطلاب واستورد ورقة Excel لكل صف."; textSize=17f; setPadding(4,8,4,18) })
        } else {
            root.addView(TextView(this).apply{text="اختر الصف الدراسي:";textSize=16f})
            val classSpinner=Spinner(this); classSpinner.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,classes); root.addView(classSpinner)
            root.addView(TextView(this).apply{text="الفترة أو الشهر:";textSize=16f})
            val period=EditText(this).apply{hint="مثال: أكتوبر أو الأسبوع الأول";textSize=17f}; root.addView(period)
            val studentArea=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}; root.addView(studentArea,LinearLayout.LayoutParams(-1,-2))
            var currentClass=classes.first(); var marks=linkedMapOf<String,String>()
            fun storageKey()="attendance_v2_"+Uri.encode(currentClass)+"_"+Uri.encode(period.text.toString().trim())
            fun render() {
                studentArea.removeAllViews(); currentClass=classSpinner.selectedItem?.toString() ?: classes.first()
                val names=classStudentNames(currentClass)
                if(names.isEmpty()) { studentArea.addView(TextView(this).apply{text="لا توجد أسماء محفوظة لهذا الصف.";textSize=16f}); return }
                val key=storageKey(); val saved=prefs.getString(key,"")!!; marks=linkedMapOf()
                saved.split("\n").filter{it.contains("|")}.forEach { val p=it.split("|",limit=2); if(p.size==2) marks[p[0]]=p[1] }
                names.forEach { name ->
                    val line=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(0,4,0,4)}
                    line.addView(TextView(this).apply{text=name;textSize=16f},LinearLayout.LayoutParams(0,-2,1f))
                    val spinner=Spinner(this); spinner.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,listOf("√ حاضر","غ غائب","م بعذر"))
                    val selected=when(marks[name]){"غ"->1;"م"->2;else->0}; spinner.setSelection(selected)
                    spinner.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
                        override fun onNothingSelected(parent:AdapterView<*>?) {}
                        override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long) { marks[name]=listOf("√","غ","م")[position] }
                    }
                    line.addView(spinner,LinearLayout.LayoutParams(145,-2)); studentArea.addView(line)
                }
                val save=Button(this).apply{text="حفظ كشف هذا الصف والفترة";isAllCaps=false;setTextColor(Color.WHITE);setBackgroundColor(green)}
                studentArea.addView(save,LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,8,0,5)})
                save.setOnClickListener {
                    val p=period.text.toString().trim(); if(p.isEmpty()){toast("اكتب الشهر أو الفترة أولاً");return@setOnClickListener}
                    val packed=names.joinToString("\n"){ "$it|${marks[it] ?: "√"}" }; prefs.edit().putString(storageKey(),packed).apply()
                    val values=names.map{marks[it]?:"√"}; val present=values.count{it=="√"}; val absent=values.count{it=="غ"}; val excused=values.count{it=="م"}
                    AlertDialog.Builder(this).setTitle("تم حفظ الكشف").setMessage("الصف: $currentClass\nالفترة: $p\nحاضر: $present\nغائب: $absent\nبعذر: $excused").setPositiveButton("حسنًا",null).show()
                }
                val clear=Button(this).apply{text="مسح علامات هذا الصف والفترة";isAllCaps=false}
                studentArea.addView(clear,LinearLayout.LayoutParams(-1,-2)); clear.setOnClickListener {
                    val p=period.text.toString().trim(); if(p.isEmpty()){toast("اكتب الفترة أولاً");return@setOnClickListener}
                    AlertDialog.Builder(this).setTitle("تأكيد المسح").setMessage("سيتم مسح علامات الحضور للصف $currentClass والفترة $p فقط، ولن تُحذف أسماء الطلاب أو كشوف الصفوف الأخرى.").setNegativeButton("إلغاء",null).setPositiveButton("مسح العلامات"){_,_->prefs.edit().remove(storageKey()).apply();toast("تم مسح علامات هذه الفترة فقط");render()}.show()
                }
            }
            classSpinner.onItemSelectedListener=object:AdapterView.OnItemSelectedListener { override fun onNothingSelected(parent:AdapterView<*>?) {}; override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long){render()} }
            addButton("تحديث الكشف حسب الصف والفترة") { render() }
        }
        addButton("العودة للرئيسية") { showHome() }
    }

    private fun showResults() { setup("كشوف التحصيل والنتائج"); root.addView(TextView(this).apply{text="سيضاف في المرحلة التالية إدخال درجات المواد، وحساب المجموع والنسبة والتقدير والنجاح والترتيب.";textSize=17f;setTextColor(Color.DKGRAY);setPadding(4,8,4,20)}); addButton("العودة للرئيسية"){showHome()} }
    private fun backupInfo() { AlertDialog.Builder(this).setTitle("النسخ الاحتياطي").setMessage("البيانات محفوظة محليًا على الهاتف في هذه النسخة الأولية. سنضيف تصدير واستعادة نسخة احتياطية لاحقًا.").setPositiveButton("حسنًا",null).show() }
    private fun showError(message:String) { AlertDialog.Builder(this).setTitle("تنبيه").setMessage(message).setPositiveButton("حسنًا",null).show() }

    private fun columnNumber(letters:String):Int { var value=0; for(c in letters) value=value*26+(c-'A'+1); return value }
    private fun readWorkbook(uri:Uri):ExcelBook {
        val entries=linkedMapOf<String,ByteArray>(); contentResolver.openInputStream(uri).use { input -> requireNotNull(input); ZipInputStream(input).use { zip -> while(true){val e=zip.nextEntry?:break; if(!e.isDirectory){val out=java.io.ByteArrayOutputStream();zip.copyTo(out);entries[e.name]=out.toByteArray()};zip.closeEntry()} } }
        val wb=entries["xl/workbook.xml"]?:error("Not xlsx"); val rel=entries["xl/_rels/workbook.xml.rels"]?:error("No rels"); val rels=parseRelationships(rel)
        val sheets=parseSheetNames(wb).mapNotNull{(name,id)-> val target=rels[id]?:return@mapNotNull null; val path=normalizeSheetPath(target); if(entries.containsKey(path)) SheetInfo(name,path) else null }
        val strings=entries["xl/sharedStrings.xml"]?.let{parseSharedStrings(it)}?:emptyList(); return ExcelBook(entries,sheets,strings)
    }
    private fun parseRelationships(bytes:ByteArray):Map<String,String> { val p=Xml.newPullParser();p.setInput(ByteArrayInputStream(bytes),"UTF-8");val r=mutableMapOf<String,String>();var e=p.eventType;while(e!=XmlPullParser.END_DOCUMENT){if(e==XmlPullParser.START_TAG&&p.name=="Relationship"){val id=p.getAttributeValue(null,"Id");val t=p.getAttributeValue(null,"Target");if(id!=null&&t!=null)r[id]=t};e=p.next()};return r }
    private fun normalizeSheetPath(target:String):String { val c=target.replace("\\","/");return when{c.startsWith("/")->c.removePrefix("/");c.startsWith("xl/")->c;else->"xl/$c"} }
    private fun parseSheetNames(bytes:ByteArray):List<Pair<String,String>> { val p=Xml.newPullParser();p.setInput(ByteArrayInputStream(bytes),"UTF-8");val r=mutableListOf<Pair<String,String>>();var e=p.eventType;while(e!=XmlPullParser.END_DOCUMENT){if(e==XmlPullParser.START_TAG&&p.name=="sheet"){val n=p.getAttributeValue(null,"name");val id=p.getAttributeValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships","id")?:findAttributeBySuffix(p,"id");if(n!=null&&id!=null)r.add(n to id)};e=p.next()};return r }
    private fun findAttributeBySuffix(p:XmlPullParser,suffix:String):String? { for(i in 0 until p.attributeCount) if(p.getAttributeName(i).endsWith(":$suffix")||p.getAttributeName(i)==suffix)return p.getAttributeValue(i);return null }
    private fun parseSharedStrings(bytes:ByteArray):List<String> { val p=Xml.newPullParser();p.setInput(ByteArrayInputStream(bytes),"UTF-8");val r=mutableListOf<String>();var inItem=false;val current=StringBuilder();var e=p.eventType;while(e!=XmlPullParser.END_DOCUMENT){when(e){XmlPullParser.START_TAG->if(p.name=="si"){inItem=true;current.setLength(0)}else if(inItem&&p.name=="t")current.append(readText(p));XmlPullParser.END_TAG->if(p.name=="si"&&inItem){r.add(current.toString());inItem=false}};e=p.next()};return r }
    private fun readText(p:XmlPullParser):String { val out=StringBuilder();var e=p.next();while(e!=XmlPullParser.END_DOCUMENT){if(e==XmlPullParser.TEXT||e==XmlPullParser.CDSECT)out.append(p.text);if(e==XmlPullParser.END_TAG&&p.name=="t")break;e=p.next()};return out.toString() }
    private fun columnFromCellReference(ref:String):Int { var v=0;for(c in ref.takeWhile{it.isLetter()}.uppercase())v=v*26+(c-'A'+1);return v }
    private fun readNamesFromSheet(book:ExcelBook,sheet:SheetInfo,column:Int,firstRow:Int,lastRow:Int):List<String> {
        val bytes=book.entries[sheet.path]?:error("Missing sheet");val p=Xml.newPullParser();p.setInput(ByteArrayInputStream(bytes),"UTF-8");val result=mutableMapOf<Int,String>();var row=-1;var col=-1;var type:String?=null;val value=StringBuilder();var target=false;var inValue=false;var inline=false;var e=p.eventType
        while(e!=XmlPullParser.END_DOCUMENT){when(e){XmlPullParser.START_TAG->when(p.name){"row"->row=p.getAttributeValue(null,"r")?.toIntOrNull()?:-1;"c"->{col=columnFromCellReference(p.getAttributeValue(null,"r")?:"");type=p.getAttributeValue(null,"t");value.setLength(0);target=row in firstRow..lastRow&&col==column};"v"->inValue=target;"t"->inline=target&&type=="inlineStr"};XmlPullParser.TEXT,XmlPullParser.CDSECT->if(target&&(inValue||inline))value.append(p.text);XmlPullParser.END_TAG->when(p.name){"v"->inValue=false;"t"->inline=false;"c"->{if(target){val raw=value.toString().trim();val text=if(type=="s")raw.toIntOrNull()?.let{book.sharedStrings.getOrNull(it)}?:"" else raw;if(text.isNotBlank())result[row]=text.trim()};target=false;inValue=false;inline=false}}};e=p.next()}
        return (firstRow..lastRow).mapNotNull{result[it]}
    }
}
