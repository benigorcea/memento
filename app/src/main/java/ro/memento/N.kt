package ro.memento

import android.app.*
import android.content.*
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.*
import java.time.*
import java.util.concurrent.TimeUnit

object N {
    private const val CH = "m"
    fun channel(c: Context) = c.getSystemService(NotificationManager::class.java)
        .createNotificationChannel(NotificationChannel(CH, "Memento", NotificationManager.IMPORTANCE_HIGH))

    private fun pi(c: Context, i: Item, a: String, code: Int) = PendingIntent.getBroadcast(
        c, i.id.toInt() * 10 + code, Intent(c, Act::class.java).setAction(a).putExtra("id", i.id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun show(c: Context, i: Item, text: String) {
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(c, CH).setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(if (i.cat == Cat.BIRTH.name) "🎂 Ziua lui ${i.title}" else i.title + if (i.grp.isBlank()) "" else " · " + i.grp).setContentText(text)
            .setContentIntent(open).setAutoCancel(true)
            .addAction(0, "Amână 1 zi", pi(c, i, "snooze", 1)).addAction(0, "Gata", pi(c, i, "done", 2)).build()
        c.getSystemService(NotificationManager::class.java).notify(i.id.toInt(), n)
    }

    // Alarmă unică (memento-uri generale și amânări)
    fun schedule(c: Context, i: Item, at: Long) {
        if (at < System.currentTimeMillis()) return
        c.getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(c, i, "fire", 3))
    }

    // Verificare zilnică la 09:00
    fun daily(c: Context) {
        val now = LocalDateTime.now()
        var t = now.toLocalDate().atTime(9, 0); if (t.isBefore(now)) t = t.plusDays(1)
        WorkManager.getInstance(c).enqueueUniquePeriodicWork("d", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DailyWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(Duration.between(now, t).toMillis(), TimeUnit.MILLISECONDS).build())
    }
}

class DailyWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result {
        val today = LocalDate.now().toEpochDay()
        DB.get(applicationContext).dao().list().filter { !it.done && it.cat != Cat.GENERAL.name }.forEach { i ->
            if (i.cat == Cat.BIRTH.name) {
                val nb = nextBirthday(i.due, today)
                if (nb == today)
                    N.show(applicationContext, i, "Astăzi este ziua lui ${i.title}, împlinește ${ageAt(i.due, today)} ani. Urează-i La mulți ani! 🎉")
                else if (nb == today + 7)
                    N.show(applicationContext, i, "Peste o săptămână, pe ${fmt(nb)}, este ziua lui ${i.title} și împlinește ${ageAt(i.due, nb)} ani. Pregătește un cadou sau un mesaj! 🎁")
                return@forEach
            }
            val left = i.due - today
            val offs = i.offsets.split(",").mapNotNull { it.trim().toIntOrNull() }
            if ((i.daily && today >= i.start) || left == 0L || left in offs.map { it.toLong() }) {
                val txt = when { left < 0 -> "Termen depășit cu ${-left} zile (${fmt(i.due)})"
                    left == 0L -> "Scadent AZI"; else -> "Mai sunt $left zile (${fmt(i.due)})" }
                N.show(applicationContext, i, txt)
            }
        }
        return Result.success()
    }
}

class Act : BroadcastReceiver() {
    override fun onReceive(c: Context, t: Intent) {
        val pr = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val d = DB.get(c).dao(); val nm = c.getSystemService(NotificationManager::class.java)
            if (t.action == Intent.ACTION_BOOT_COMPLETED) {
                d.list().filter { !it.done && it.cat == Cat.GENERAL.name }.forEach {
                    N.schedule(c, it, LocalDateTime.of(LocalDate.ofEpochDay(it.due), LocalTime.of(it.minute / 60, it.minute % 60)).ms())
                }
            } else {
                val id = t.getLongExtra("id", 0)
                d.list().firstOrNull { it.id == id }?.let { i ->
                    when (t.action) {
                        "fire" -> N.show(c, i, i.note.ifBlank { "Memento" })
                        "snooze" -> { nm.cancel(id.toInt()); N.schedule(c, i, LocalDate.now().plusDays(1).atTime(9, 0).ms()) }
                        "done" -> { nm.cancel(id.toInt()); Repo.complete(d, i) }
                    }
                }
            }
            pr.finish()
        }
    }
}
