package top.xingzhenxi.phonebox.push

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.xingzhenxi.phonebox.appGraph
import top.xingzhenxi.phonebox.diag.ArrivalVia
import top.xingzhenxi.phonebox.diag.DiagType
import top.xingzhenxi.phonebox.service.KeepAliveService

/**
 * 收到任何推送都走这里。推送内容不可信（可能被伪造、也可能解不开），所以只做固定动作：
 * 拉起常驻服务（重新注册 SIP、重连事件流），再全量拉一次 feed 补出通知。不读消息里的任何字段。
 */
object PushWake {
    fun onWake(context: Context, provider: String) {
        val graph = context.appGraph
        graph.diagLog.log(DiagType.Push, "收到推送唤醒（$provider）")
        PushRegistrar.get(context).recordWake()
        KeepAliveService.ensureRunning(context, "推送唤醒")
        graph.scope.launch {
            // 等服务把事件流的本地缓存（去重基线）读进来再拉，否则这批新短信会被当成基线、不出通知
            delay(1_500)
            graph.phonebox.value?.feedSync?.pollNow(ArrivalVia.Push)
        }
    }
}
