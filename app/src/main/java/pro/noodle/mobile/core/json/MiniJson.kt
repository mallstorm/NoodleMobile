package pro.noodle.mobile.core.json

sealed interface JsonValue {
    data class Obj(val values: Map<String, JsonValue>) : JsonValue
    data class Arr(val values: List<JsonValue>) : JsonValue
    data class Str(val value: String) : JsonValue
    data class Num(val value: Double) : JsonValue
    data class Bool(val value: Boolean) : JsonValue
    data object Null : JsonValue
}

class JsonParseException(message: String) : IllegalArgumentException(message)

object MiniJson {
    fun parse(text: String): JsonValue = Parser(text).parse()

    private class Parser(private val s: String) {
        private var p = 0
        fun parse(): JsonValue { skip(); val v = value(); skip(); if (p != s.length) error("Лишние данные после JSON"); return v }
        private fun value(): JsonValue { skip(); if (p >= s.length) error("Неожиданный конец JSON"); return when (s[p]) {
            '{' -> obj(); '[' -> arr(); '"' -> JsonValue.Str(string()); 't' -> literal("true", JsonValue.Bool(true)); 'f' -> literal("false", JsonValue.Bool(false)); 'n' -> literal("null", JsonValue.Null); '-', in '0'..'9' -> number(); else -> error("Недопустимый символ '${s[p]}'") } }
        private fun obj(): JsonValue.Obj { expect('{'); skip(); val map=linkedMapOf<String,JsonValue>(); if (peek('}')) { p++; return JsonValue.Obj(map) }; while(true){ skip(); if(!peek('"')) error("Ключ объекта должен быть строкой"); val k=string(); skip(); expect(':'); val v=value(); map[k]=v; skip(); if(peek('}')){p++;break}; expect(',') }; return JsonValue.Obj(map) }
        private fun arr(): JsonValue.Arr { expect('['); skip(); val out=mutableListOf<JsonValue>(); if(peek(']')){p++;return JsonValue.Arr(out)}; while(true){out+=value();skip();if(peek(']')){p++;break};expect(',')};return JsonValue.Arr(out) }
        private fun string(): String { expect('"'); val out=StringBuilder(); while(p<s.length){ val c=s[p++]; when(c){ '"'->return out.toString(); '\\'->{if(p>=s.length) error("Некорректный escape"); when(val e=s[p++]){'"','\\','/'->out.append(e);'b'->out.append('\b');'f'->out.append('\u000C');'n'->out.append('\n');'r'->out.append('\r');'t'->out.append('\t');'u'->{if(p+4>s.length) error("Некорректный unicode escape"); val hex=s.substring(p,p+4); out.append(hex.toIntOrNull(16)?.toChar()?:error("Некорректный unicode escape"));p+=4};else->error("Некорректный escape")}} else->out.append(c)} }; error("Незакрытая строка") }
        private fun number(): JsonValue.Num { val start=p; if(peek('-'))p++; if(peek('0'))p++ else { if(p>=s.length||!s[p].isDigit())error("Некорректное число"); while(p<s.length&&s[p].isDigit())p++ }; if(peek('.')){p++;if(p>=s.length||!s[p].isDigit())error("Некорректная дробь");while(p<s.length&&s[p].isDigit())p++}; if(p<s.length&&(s[p]=='e'||s[p]=='E')){p++;if(p<s.length&&(s[p]=='+'||s[p]=='-'))p++;if(p>=s.length||!s[p].isDigit())error("Некорректная экспонента");while(p<s.length&&s[p].isDigit())p++}; return JsonValue.Num(s.substring(start,p).toDoubleOrNull()?:error("Некорректное число")) }
        private fun <T:JsonValue> literal(word:String,v:T):T{ if(!s.startsWith(word,p)) error("Ожидалось $word");p+=word.length;return v }
        private fun skip(){while(p<s.length&&s[p].isWhitespace())p++}
        private fun expect(c:Char){skip();if(p>=s.length||s[p]!=c)error("Ожидался '$c'");p++}
        private fun peek(c:Char)=p<s.length&&s[p]==c
        private fun error(m:String):Nothing=throw JsonParseException("$m (позиция $p)")
    }
}

fun JsonValue.Obj.string(name: String): String? = (values[name] as? JsonValue.Str)?.value
fun JsonValue.Obj.int(name: String): Int? = (values[name] as? JsonValue.Num)?.value?.toInt()
fun JsonValue.Obj.bool(name: String): Boolean? = (values[name] as? JsonValue.Bool)?.value
fun JsonValue.Obj.obj(name: String): JsonValue.Obj? = values[name] as? JsonValue.Obj
fun JsonValue.Obj.arr(name: String): JsonValue.Arr? = values[name] as? JsonValue.Arr
fun JsonValue.Arr.strings(): List<String> = values.mapNotNull { (it as? JsonValue.Str)?.value }

fun JsonValue.toJson(pretty: Boolean = false): String {
    fun esc(s: String): String = buildString {
        append('"')
        s.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }
    fun render(v: JsonValue, depth: Int): String = when (v) {
        is JsonValue.Str -> esc(v.value)
        is JsonValue.Num -> if (v.value % 1.0 == 0.0) v.value.toLong().toString() else v.value.toString()
        is JsonValue.Bool -> v.value.toString()
        JsonValue.Null -> "null"
        is JsonValue.Arr -> {
            if (v.values.isEmpty()) "[]" else if (!pretty) v.values.joinToString(prefix = "[", postfix = "]") { render(it, depth + 1) }
            else v.values.joinToString(prefix = "[\n", postfix = "\n${"  ".repeat(depth)}]", separator = ",\n") { "  ".repeat(depth + 1) + render(it, depth + 1) }
        }
        is JsonValue.Obj -> {
            if (v.values.isEmpty()) "{}" else if (!pretty) v.values.entries.joinToString(prefix = "{", postfix = "}") { (k, value) -> "${esc(k)}:${render(value, depth + 1)}" }
            else v.values.entries.joinToString(prefix = "{\n", postfix = "\n${"  ".repeat(depth)}}", separator = ",\n") { (k, value) -> "  ".repeat(depth + 1) + "${esc(k)}: ${render(value, depth + 1)}" }
        }
    }
    return render(this, 0)
}
