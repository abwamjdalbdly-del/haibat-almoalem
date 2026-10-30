
private fun classNames(): MutableList<String> {
    val saved = prefs.getString("class_names_v1", "") ?: ""
    val names = mutableListOf<String>()

    saved.split("\n")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .forEach { name ->
            if (names.none { it.equals(name, true) }) names.add(name)
        }

    // استعادة أسماء الصفوف من مفاتيح قوائم الطلاب المحفوظة
    prefs.all.keys
        .filter { it.startsWith("class_students_") }
        .forEach { key ->
            val name = Uri.decode(key.removePrefix("class_students_")).trim()
            if (name.isNotEmpty() && names.none { it.equals(name, true) }) {
                names.add(name)
            }
        }

    // إصلاح سجل الصفوف وحفظه
    prefs.edit()
        .putString("class_names_v1", names.joinToString("\n"))
        .apply()

    return names
}

private fun saveClassNames(names: List<String>) {
    val clean = names.map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }

    prefs.edit()
        .putString("class_names_v1", clean.joinToString("\n"))
        .commit()
}

private fun classKey(className: String) =
    "class_students_" + Uri.encode(className.trim())

private fun classStudentNames(className: String): MutableList<String> {
    val saved = prefs.getString(classKey(className), "") ?: ""
    return saved.split("\n")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }
        .toMutableList()
}

private fun saveClassStudentNames(className: String, names: List<String>) {
    val clean = names.map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }

    val classes = classNames()
    if (classes.none { it.equals(className.trim(), true) }) {
        classes.add(className.trim())
    }

    // حفظ قائمة الطلاب واسم الصف معًا
    prefs.edit()
        .putString(classKey(className), clean.joinToString("\n"))
        .putString("class_names_v1", classes.joinToString("\n"))
        .commit()
}
