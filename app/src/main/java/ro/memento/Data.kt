package ro.memento

import android.content.Context
import androidx.room.*
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.Flow
import java.time.*
import java.time.format.DateTimeFormatter

enum class Cat(val label: String, val groupLabel: String) {
    AUTO("Documente auto", "Nr. înmatriculare"), DOC("Documente persoane", "Persoană"),
    BILL("Facturi", "Furnizor"), INDEX("Indexuri", "Casă (stradă, nr.)"),
    PAY("Plăți / rate", "Beneficiar"), CONTRACT("Contracte", "Furnizor"),
    BIRTH("Zile de naștere", "Detalii (ex: fiul meu, coleg)"), GENERAL("General", "")
}

@Entity
data class Item(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cat: String, val title: String, val grp: String = "", val note: String = "",
    val due: Long,              // zi (epochDay): expirare / scadență / ultima zi de transmis
    val start: Long = 0,        // început interval (indexuri)
    val minute: Int = 540,      // ora (minute de la miezul nopții) pentru General
    val offsets: String = "",   // ex: "30,15,1"
    val daily: Boolean = false, // notificare zilnică până la bifare (indexuri)
    val repeatMonths: Int = 0,  // 0 = o singură dată
    val done: Boolean = false, val doneAt: Long = 0
)

@Entity
data class Reading(@PrimaryKey(autoGenerate = true) val id: Long = 0, val itemId: Long, val value: String, val day: Long)

@Dao
interface Dao {
    @Query("SELECT * FROM Item ORDER BY due") fun all(): Flow<List<Item>>
    @Query("SELECT * FROM Item") suspend fun list(): List<Item>
    @Insert suspend fun add(i: Item): Long
    @Update suspend fun upd(i: Item)
    @Delete suspend fun del(i: Item)
    @Insert suspend fun addR(r: Reading)
    @Query("SELECT * FROM Reading") suspend fun listR(): List<Reading>
    @Query("DELETE FROM Item") suspend fun clearI()
    @Query("DELETE FROM Reading") suspend fun clearR()
    @Insert suspend fun addAllI(l: List<Item>)
    @Insert suspend fun addAllR(l: List<Reading>)
    @Query("SELECT * FROM Reading WHERE itemId=:id ORDER BY day DESC") fun readings(id: Long): Flow<List<Reading>>
}

@Database([Item::class, Reading::class], version = 1, exportSchema = false)
abstract class DB : RoomDatabase() {
    abstract fun dao(): Dao
    companion object {
        @Volatile private var I: DB? = null
        fun get(c: Context): DB = I ?: synchronized(this) {
            I ?: Room.databaseBuilder(c.applicationContext, DB::class.java, "m.db").build().also { I = it }
        }
    }
}

fun fmt(e: Long): String = LocalDate.ofEpochDay(e).format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
fun LocalDateTime.ms(): Long = atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

object Repo {
    // Bifează ca făcut/plătit. Dacă se repetă, păstrează o copie în istoric și mută termenul înainte.
    suspend fun complete(d: Dao, i: Item, value: String? = null) {
        if (i.cat == Cat.BIRTH.name) return
        val today = LocalDate.now().toEpochDay()
        if (value != null) d.addR(Reading(itemId = i.id, value = value, day = today))
        if (i.repeatMonths > 0) {
            if (value == null) d.add(i.copy(id = 0, done = true, doneAt = today, repeatMonths = 0, daily = false))
            fun sh(e: Long) = LocalDate.ofEpochDay(e).plusMonths(i.repeatMonths.toLong()).toEpochDay()
            d.upd(i.copy(due = sh(i.due), start = if (i.start > 0) sh(i.start) else 0))
        } else d.upd(i.copy(done = true, doneAt = today))
    }
}

// Calendar .ics cu toate evenimentele active + alarme la zilele din "offsets"
fun ics(items: List<Item>): String {
    val f = DateTimeFormatter.BASIC_ISO_DATE
    val stamp = LocalDate.now().format(f) + "T000000Z"
    val sb = StringBuilder("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Memento//RO\r\n")
    items.filter { !it.done }.forEach { x ->
        val birth = x.cat == Cat.BIRTH.name
        val title = (if (birth) "Ziua lui " + x.title else x.title + if (x.grp.isBlank()) "" else " - " + x.grp).replace(",", "\\,")
        sb.append("BEGIN:VEVENT\r\nUID:m${x.id}@memento\r\nDTSTAMP:$stamp\r\nSUMMARY:$title\r\n")
        sb.append("DTSTART;VALUE=DATE:${LocalDate.ofEpochDay(x.due).format(f)}\r\n")
        if (birth) sb.append("RRULE:FREQ=YEARLY\r\n")
        if (x.note.isNotBlank()) sb.append("DESCRIPTION:${x.note.replace("\n", " ").replace(",", "\\,")}\r\n")
        x.offsets.split(",").mapNotNull { s -> s.trim().toIntOrNull() }.forEach { d ->
            sb.append("BEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:$title\r\nTRIGGER:-P${d}D\r\nEND:VALARM\r\n")
        }
        sb.append("END:VEVENT\r\n")
    }
    return sb.append("END:VCALENDAR\r\n").toString()
}

// Următoarea aniversare (29 feb -> 28 feb în anii nebisecți)
fun nextBirthday(birth: Long, today: Long): Long {
    val b = LocalDate.ofEpochDay(birth); val t = LocalDate.ofEpochDay(today)
    fun at(y: Int) = if (b.monthValue == 2 && b.dayOfMonth == 29 && !Year.isLeap(y.toLong())) LocalDate.of(y, 2, 28)
                     else LocalDate.of(y, b.monthValue, b.dayOfMonth)
    var n = at(t.year); if (n.isBefore(t)) n = at(t.year + 1)
    return n.toEpochDay()
}
fun ageAt(birth: Long, day: Long): Int = LocalDate.ofEpochDay(day).year - LocalDate.ofEpochDay(birth).year

// Copie de siguranță în fișier JSON (mutare pe alt telefon)
object Backup {
    fun toJson(items: List<Item>, rs: List<Reading>): String {
        val o = JSONObject().put("v", 1)
        o.put("items", JSONArray().also { a -> items.forEach { i -> a.put(JSONObject()
            .put("id", i.id).put("cat", i.cat).put("title", i.title).put("grp", i.grp).put("note", i.note)
            .put("due", i.due).put("start", i.start).put("minute", i.minute).put("offsets", i.offsets)
            .put("daily", i.daily).put("repeatMonths", i.repeatMonths).put("done", i.done).put("doneAt", i.doneAt)) } })
        o.put("readings", JSONArray().also { a -> rs.forEach { r -> a.put(JSONObject()
            .put("id", r.id).put("itemId", r.itemId).put("value", r.value).put("day", r.day)) } })
        return o.toString(2)
    }

    // Citește și validează totul ÎNAINTE de a șterge datele existente
    suspend fun restore(c: Context, json: String) {
        val o = JSONObject(json); val ia = o.getJSONArray("items"); val ra = o.getJSONArray("readings")
        val items = (0 until ia.length()).map { n -> ia.getJSONObject(n).let { j -> Item(
            j.getLong("id"), j.getString("cat"), j.getString("title"), j.getString("grp"), j.getString("note"),
            j.getLong("due"), j.getLong("start"), j.getInt("minute"), j.getString("offsets"),
            j.getBoolean("daily"), j.getInt("repeatMonths"), j.getBoolean("done"), j.getLong("doneAt")) } }
        val rs = (0 until ra.length()).map { n -> ra.getJSONObject(n).let { j ->
            Reading(j.getLong("id"), j.getLong("itemId"), j.getString("value"), j.getLong("day")) } }
        items.forEach { Cat.valueOf(it.cat) }
        val db = DB.get(c); val d = db.dao()
        db.withTransaction { d.clearR(); d.clearI(); d.addAllI(items); d.addAllR(rs) }
        items.filter { !it.done && it.cat == Cat.GENERAL.name }.forEach {
            N.schedule(c, it, LocalDateTime.of(LocalDate.ofEpochDay(it.due), LocalTime.of(it.minute / 60, it.minute % 60)).ms())
        }
    }
}
