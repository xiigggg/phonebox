package top.xingzhenxi.phonebox.push

import android.app.Activity
import android.content.Context
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * UnifiedPush：开源推送协议，推荐配自建 ntfy 当 distributor（手机上装 ntfy App，服务端用自己的 ntfy）。
 * 不依赖任何厂商账号，目前唯一的推送通道。
 */
object UnifiedPushChannel : PushChannel {
    override val id = "unifiedpush"
    override val label = "UnifiedPush"
    private const val INSTANCE = "default"

    override fun available(context: Context): Boolean = UnifiedPush.getDistributors(context).isNotEmpty()

    override fun register(activity: Activity) {
        // 已选过就沿用；没选过有多个 distributor 时由库弹系统选择框
        UnifiedPush.tryUseCurrentOrDefaultDistributor(activity) { ok ->
            if (ok) UnifiedPush.register(activity, INSTANCE, "电话信箱：来电和短信唤醒", null)
            else PushRegistrar.get(activity).onError(id, "没有可用的 distributor（请先装 ntfy 等）")
        }
    }

    override fun unregister(context: Context) = UnifiedPush.unregister(context, INSTANCE)
}

class UnifiedPushService : PushService() {
    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        val keys = endpoint.pubKeySet
        PushRegistrar.get(this).onToken(UnifiedPushChannel.id, endpoint.url, keys?.pubKey, keys?.auth)
    }

    override fun onMessage(message: PushMessage, instance: String) = PushWake.onWake(this, UnifiedPushChannel.id)

    override fun onRegistrationFailed(reason: FailedReason, instance: String) =
        PushRegistrar.get(this).onError(UnifiedPushChannel.id, reason.name)

    override fun onUnregistered(instance: String) = PushRegistrar.get(this).onUnregistered(UnifiedPushChannel.id)
}
