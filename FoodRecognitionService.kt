package com.lifetrack.banking

import android.app.*
import android.content.*
import android.os.Build
import android.service.notification.*
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lifetrack.MainActivity
import com.lifetrack.R
import com.lifetrack.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

object BankConsent {
    private fun prefs(context: Context) =
        context.getSharedPreferences("bank_notification_consent", Context.MODE_PRIVATE)

    fun selected(context: Context): Set<String> =
        prefs(context).getStringSet("packages", emptySet())?.toSet().orEmpty()

    fun set(context: Context, apps: List<BankApp>) {
        prefs(context)
            .edit()
            .putStringSet("packages", apps.filter { it.enabled }.map { it.packageName }.toSet())
            .apply()
    }

    fun connected(context: Context) =
        context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
}

class BankNotificationListenerService : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val allowed = BankConsent.selected(this)
        // Privacy boundary: do not read extras of any unselected package.
        if (
            sbn.packageName !in allowed ||
                sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
        )
            return
        val title =
            sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.take(512)
        val text =
            (sbn.notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                    ?: sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT))
                ?.toString()
                ?.take(4096)
        val eventTime =
            sbn.notification.`when`.takeIf { it > 0 && it <= System.currentTimeMillis() }
                ?: sbn.postTime
        val parsed =
            BankParserRegistry().parse(allowed, sbn.packageName, title, text, eventTime) ?: return
        val identity =
            BankFingerprint.hash(
                "${sbn.packageName}|${sbn.key}|$eventTime|${parsed.type}|${parsed.amount}|${parsed.currency}|${parsed.normalizedMerchant}"
            )
        scope.launch {
            try {
                val db = LifeStore.get(this@BankNotificationListenerService)
                val repo = BankRepository(db)
                val row = repo.ingest(sbn.packageName, parsed, identity) ?: return@launch
                val prefs = db.productDao().preferences().first().associate { it.key to it.value }
                val app =
                    repo.dao.appsNow().firstOrNull {
                        it.packageName == sbn.packageName && it.enabled
                    }
                val categories = db.dao().categoriesNow()
                val category =
                    MerchantCategorizer.suggest(parsed, categories, repo.dao.rules().first())
                if (
                    prefs["bank_mode"] == "AUTO" &&
                        parsed.confidence == Confidence.HIGH &&
                        !row.duplicate &&
                        app?.accountId != null &&
                        parsed.type in setOf(BankKind.PURCHASE, BankKind.INCOME, BankKind.FEE)
                ) {
                    repo.confirm(row, app.accountId, category)
                    TodayWidget.render(
                        this@BankNotificationListenerService,
                        LocalLifeRepository(db).snapshots.first(),
                    )
                } else if (prefs["bank_quiet_notifications"] == "true")
                    BankReviewNotification.show(
                        this@BankNotificationListenerService,
                        row,
                        app?.accountId,
                    )
            } catch (e: Exception) {
                /* Never log bank notification text or payload. Pending rows remain reviewable. */
            }
        }
    }
}

object BankReviewNotification {
    fun show(context: Context, row: BankCandidate, accountId: Long?) {
        if (
            Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        )
            return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                "bank_review",
                "Проверка банковских операций",
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        val edit =
            PendingIntent.getActivity(
                context,
                row.id.hashCode(),
                Intent(context, MainActivity::class.java)
                    .putExtra("lifetrack_action", "finance-inbox"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val b =
            NotificationCompat.Builder(context, "bank_review")
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(row.rawMerchant.ifBlank { "Банковская операция" })
                .setContentText(
                    "${com.lifetrack.domain.MoneyFormatter.format(row.amount,row.currency)} · требуется проверка"
                )
                .setContentIntent(edit)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .addAction(0, "Изменить", edit)
        if (
            accountId != null &&
                !row.duplicate &&
                row.kind in listOf("PURCHASE", "INCOME", "FEE", "REFUND")
        ) {
            val confirm =
                PendingIntent.getBroadcast(
                    context,
                    row.id.hashCode(),
                    Intent(context, BankConfirmReceiver::class.java).putExtra("candidate", row.id),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            b.addAction(0, "Подтвердить", confirm)
        }
        nm.notify(
            61000,
            b.build(),
        ) // One quiet review notification, never a stream of notifications.
    }
}

class BankConfirmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = BankRepository(LifeStore.get(context))
                val row =
                    repo.dao.candidatesNow().firstOrNull {
                        it.id == intent.getStringExtra("candidate") && it.state == "PENDING"
                    } ?: return@launch
                val app =
                    repo.dao.appsNow().firstOrNull {
                        it.packageName == row.packageName && it.enabled
                    } ?: return@launch
                val account = app.accountId ?: return@launch
                val category =
                    MerchantCategorizer.suggest(
                        BankRepository.parsed(row),
                        LifeStore.get(context).dao().categoriesNow(),
                        repo.dao.rules().first(),
                    )
                repo.confirm(row, account, category)
                context.getSystemService(NotificationManager::class.java).cancel(61000)
            } catch (e: Exception) {
                /* User can resolve the pending operation in Finance Inbox. */
            } finally {
                pending.finish()
            }
        }
    }
}
