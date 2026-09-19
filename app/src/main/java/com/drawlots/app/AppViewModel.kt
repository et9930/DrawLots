package com.drawlots.app

import android.app.Application
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.drawlots.app.data.PoolStore
import com.drawlots.app.data.RoomPrefs
import com.drawlots.app.data.WifiAddress
import com.drawlots.app.domain.DrawMode
import com.drawlots.app.domain.DrawRecord
import com.drawlots.app.domain.DrawSession
import com.drawlots.app.domain.Lot
import com.drawlots.app.domain.LotKind
import com.drawlots.app.domain.StartupChoice
import com.drawlots.app.domain.chooseStartupSnapshot
import com.drawlots.app.domain.formatResultsText
import com.drawlots.app.media.ImageStore
import com.drawlots.app.media.LotImageSource
import com.drawlots.app.media.RoomImageCache
import com.drawlots.app.room.BluetoothTransport
import com.drawlots.app.room.LanBeaconSender
import com.drawlots.app.room.LanTransport
import com.drawlots.app.room.RequestResult
import com.drawlots.app.room.RoomAction
import com.drawlots.app.room.RoomActionResult
import com.drawlots.app.room.RoomController
import com.drawlots.app.room.RoomHostRequest
import com.drawlots.app.room.RoomImageKeys
import com.drawlots.app.room.RoomInfo
import com.drawlots.app.room.RoomInvite
import com.drawlots.app.room.RoomRole
import com.drawlots.app.room.RoomStatus
import com.drawlots.app.room.RoomTransportKind
import com.drawlots.app.room.applyRoomAction
import com.drawlots.app.room.DiscoveredRoom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** 签池里的一行（签 + 已抽/剩余支数）。 */
data class LotUi(
    val lot: Lot,
    val remaining: Int,
    val drawnCount: Int,
)

data class UiState(
    val lots: List<LotUi> = emptyList(),
    val results: List<DrawRecord> = emptyList(),
    val mode: DrawMode = DrawMode.WITHOUT_REPLACEMENT,
    val totalQuantity: Int = 0,
    val totalRemaining: Int = 0,
    val canDraw: Boolean = false,
) {
    val isEmpty: Boolean get() = lots.isEmpty()

    val summary: String
        get() = buildString {
            append("共 ${lots.size} 种签 · 共 $totalQuantity 支")
            if (mode == DrawMode.WITHOUT_REPLACEMENT) {
                append(" · 剩余 $totalRemaining 支")
            }
        }
}

/** 抽签结果：离线/房主是「已经生效」，成员是「已发给房主，等广播」。 */
sealed interface DrawOutcome {
    data class Drawn(val records: List<DrawRecord>) : DrawOutcome
    data object SentToHost : DrawOutcome
    data class Failed(val reason: String) : DrawOutcome
}

/**
 * 界面状态的唯一来源：包住 [DrawSession]，负责持久化、图片导入，以及联机房间的角色分派。
 *
 * 三条路径的语义：
 * - 离线：直接改本地 session（与 1.0 完全一致）
 * - 房主：改动交给 [RoomController] 应用（会增加 revision 并广播给成员）
 * - 成员：本地只发请求，收到房主广播后由副本覆盖本地 session
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val store = PoolStore(application)
    private val roomPrefs = RoomPrefs(application)
    private val roomImageCache = RoomImageCache(application)

    /** 上次加入房间留下的离线备份说明那个房间已经不在了，启动时恢复自己的签池。 */
    private val startup: StartupChoice = chooseStartupSnapshot(
        current = store.load(),
        offlineBackup = store.loadOfflineBackup(),
    )
    private val session = DrawSession(
        initialLots = startup.snapshot.lots,
        initialResults = startup.snapshot.results,
        initialMode = startup.snapshot.mode,
    )

    private val _ui = MutableStateFlow(buildState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _drawCount = MutableStateFlow(1)
    val drawCount: StateFlow<Int> = _drawCount.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    val roomController: RoomController = RoomController(
        scope = viewModelScope,
        backupStore = store,
        session = session,
        imageSource = LotImageSource(session),
        transportFactory = { kind ->
            when (kind) {
                RoomTransportKind.Lan -> LanTransport()
                RoomTransportKind.Bluetooth -> BluetoothTransport(application)
            }
        },
        hostAddressProvider = { WifiAddress.ipv4(application) },
        deviceId = roomPrefs.deviceId(),
        initialName = roomPrefs.myName(defaultDeviceName()),
        onNameChanged = { roomPrefs.setMyName(it) },
        onSessionChanged = { publish() },
        beaconSenderFactory = { port -> LanBeaconSender(viewModelScope, port) },
    )

    val roomRole: StateFlow<RoomRole> = roomController.role
    val roomStatus: StateFlow<RoomStatus> = roomController.status
    val roomInfo: StateFlow<RoomInfo?> = roomController.roomInfo
    val myName: StateFlow<String> = roomController.myName
    val discoveredRooms: StateFlow<List<DiscoveredRoom>> = roomController.discoveredRooms

    init {
        // 启动时若恢复了离线备份，把恢复后的状态写回存档并清掉备份
        if (startup.restoredFromBackup) {
            store.save(session.snapshot())
            store.clearOfflineBackup()
        }
    }

    private fun defaultDeviceName(): String =
        Build.MODEL?.trim()?.takeIf { it.isNotBlank() } ?: "我的手机"

    // ---------- 签池编辑 ----------

    fun addTextLots(entries: List<Pair<String, Int>>) {
        val lots = entries.mapNotNull { (text, quantity) ->
            val trimmed = text.trim()
            if (trimmed.isEmpty()) null
            else Lot(kind = LotKind.TEXT, text = trimmed, quantity = quantity)
        }
        if (lots.isEmpty()) return
        viewModelScope.launch {
            var rejected: String? = null
            for (lot in lots) {
                val result = dispatch(RoomAction.AddLot(lot)) { applyRoomAction(session, RoomAction.AddLot(lot)) }
                if (result is RoomActionResult.Failed) rejected = result.reason
            }
            _message.value = rejected ?: "已添加 ${lots.size} 个文字签"
        }
    }

    fun updateLot(lot: Lot) {
        viewModelScope.launch {
            dispatch(RoomAction.UpdateLot(lot)) { applyRoomAction(session, RoomAction.UpdateLot(lot)) }
        }
    }

    fun setQuantity(id: String, quantity: Int) {
        viewModelScope.launch {
            dispatch(RoomAction.SetQuantity(id, quantity)) {
                applyRoomAction(session, RoomAction.SetQuantity(id, quantity))
            }
        }
    }

    fun removeLot(id: String) {
        viewModelScope.launch {
            val removed = session.lotById(id)
            val result = dispatch(RoomAction.RemoveLot(id)) {
                applyRoomAction(session, RoomAction.RemoveLot(id))
            }
            if (result is RoomActionResult.Ok && removed?.kind == LotKind.IMAGE) {
                ImageStore.delete(removed.imagePath)
            }
        }
    }

    /** 清空签池（同时清空结果，并删除不再使用的图片文件）。 */
    fun clearPool() {
        viewModelScope.launch {
            val orphanImages = (session.lots.mapNotNull { it.imagePath } +
                session.results.mapNotNull { it.imagePath }).distinct()
            val result = dispatch(RoomAction.ClearPool) { applyRoomAction(session, RoomAction.ClearPool) }
            if (result is RoomActionResult.Ok) {
                orphanImages.forEach { ImageStore.delete(it) }
                _message.value = "签池已清空"
            }
        }
    }

    // ---------- 图片签 ----------

    fun addGalleryImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _importing.value = true
            var imported = 0
            for (uri in uris) {
                val path = ImageStore.importImage(getApplication(), uri)
                if (path != null && addImageLot(path)) imported++
            }
            _importing.value = false
            _message.value = if (imported > 0) "已添加 $imported 个图片签" else "图片导入失败"
        }
    }

    fun addCameraImage(file: File) {
        viewModelScope.launch {
            _importing.value = true
            val path = ImageStore.importFile(getApplication(), file)
            val added = path != null && addImageLot(path)
            _importing.value = false
            _message.value = if (added) "已添加照片签" else "照片导入失败"
        }
    }

    private suspend fun addImageLot(path: String): Boolean {
        val lot = Lot(kind = LotKind.IMAGE, imagePath = path, quantity = 1)
        return when (val result = dispatch(RoomAction.AddLot(lot)) { applyRoomAction(session, RoomAction.AddLot(lot)) }) {
            is RoomActionResult.Ok -> true
            is RoomActionResult.Failed -> {
                _message.value = result.reason
                ImageStore.delete(path)
                false
            }
        }
    }

    // ---------- 抽签 ----------

    fun setMode(mode: DrawMode) {
        if (session.mode == mode) return
        viewModelScope.launch {
            dispatch(RoomAction.SetMode(mode)) { applyRoomAction(session, RoomAction.SetMode(mode)) }
        }
    }

    fun setDrawCount(count: Int) {
        _drawCount.value = count.coerceIn(1, 99)
    }

    /** 本次真正能抽出多少支。 */
    fun plannedDrawCount(): Int = session.plannedDrawCount(_drawCount.value)

    suspend fun drawAsync(): DrawOutcome {
        val action = RoomAction.Draw(_drawCount.value)
        return when (roomController.role.value) {
            RoomRole.Offline -> {
                // 单机不写署名
                val records = session.draw(_drawCount.value)
                publish()
                DrawOutcome.Drawn(records)
            }

            RoomRole.Host -> {
                roomController.applyAsHost(action)
                publish()
                DrawOutcome.Drawn(session.results.takeLast(_drawCount.value))
            }

            RoomRole.Member -> {
                val client = roomController.clientForRequests()
                if (client == null) {
                    DrawOutcome.Failed("尚未连接到房主")
                } else {
                    // 关键：**不要在这里等 ack**。请求发出去就立刻返回「已发给房主」，
                    // 让界面马上给出「等待房主」的反馈；结果本身随房主的状态广播到达。
                    // （以前是在这里等 ack 才返回，BLE 上一个来回要几百毫秒到几秒，
                    //   导致反馈被压到 ack 之后才出现——真机体验就是「点了半天才变等待房主」。）
                    viewModelScope.launch {
                        val result = client.request(action)
                        if (result is RequestResult.Failed) _message.value = result.reason
                    }
                    DrawOutcome.SentToHost
                }
            }
        }
    }

    /** 撤销最后一次抽签（把签放回）。 */
    fun undoLast() {
        viewModelScope.launch {
            val result = dispatch(RoomAction.Undo) { applyRoomAction(session, RoomAction.Undo) }
            if (result is RoomActionResult.Ok && roomController.role.value != RoomRole.Member) {
                _message.value = "已放回最后一支"
            }
        }
    }

    /** 放回指定的那一条抽签结果（点结果卡片时用）。 */
    fun putBack(record: DrawRecord) {
        viewModelScope.launch {
            val consumed = record.consumedCopy
            val result = dispatch(RoomAction.PutBack(record.seq)) {
                applyRoomAction(session, RoomAction.PutBack(record.seq))
            }
            if (result is RoomActionResult.Ok) {
                _message.value = if (consumed) "已放回：${record.title}" else "已从结果中移除：${record.title}"
            }
        }
    }

    /** 给某条抽签结果写备注（弹窗里边打字边保存）。 */
    fun setNote(record: DrawRecord, note: String) {
        viewModelScope.launch {
            dispatch(RoomAction.SetNote(record.seq, note)) {
                applyRoomAction(session, RoomAction.SetNote(record.seq, note))
            }
        }
    }

    /** 重新开始：清空结果，所有签放回。 */
    fun resetPool() {
        viewModelScope.launch {
            dispatch(RoomAction.ResetPool) { applyRoomAction(session, RoomAction.ResetPool) }
            _message.value = "已重新开始，所有签已放回"
        }
    }

    fun resultsAsText(): String = formatResultsText(session.results, session.mode)

    // ---------- 联机 ----------

    fun setMyName(name: String) {
        roomController.setMyName(name)
    }

    fun hostRoom(request: RoomHostRequest, onResult: (RoomActionResult) -> Unit = {}) {
        viewModelScope.launch {
            val result = roomController.hostRoom(request)
            if (result is RoomActionResult.Failed) _message.value = result.reason
            onResult(result)
        }
    }

    fun joinRoom(invite: RoomInvite, onResult: (RoomActionResult) -> Unit = {}) {
        viewModelScope.launch {
            val result = roomController.joinRoom(invite)
            if (result is RoomActionResult.Failed) _message.value = result.reason
            onResult(result)
        }
    }

    fun joinByCode(code: String, host: String? = null, port: Int? = null, onResult: (RoomActionResult) -> Unit = {}) {
        viewModelScope.launch {
            val result = roomController.joinByCode(code, host, port)
            if (result is RoomActionResult.Failed) _message.value = result.reason
            onResult(result)
        }
    }

    fun leaveRoom() {
        viewModelScope.launch {
            roomController.leaveRoom()
            _message.value = "已退出房间"
            publish()
        }
    }

    fun startRoomDiscovery() = roomController.startDiscovery()

    fun stopRoomDiscovery() = roomController.stopDiscovery()

    fun probeRoomDiscovery() = roomController.probeDiscovery()

    suspend fun fetchRoomImage(imageKey: String): ByteArray? = roomController.fetchImage(imageKey)

    /**
     * 联机取图：把一个「房主侧的图片路径」解析成本机可读路径。
     * 本地已有就直接返回；成员模式下向房主拉取并缓存到 `room-images/`。
     */
    suspend fun resolveRoomImage(imagePath: String): String? {
        if (File(imagePath).isFile) return imagePath
        if (roomController.role.value != RoomRole.Member) return null
        val key = RoomImageKeys.keyFor(imagePath)
        roomImageCache.cachedPath(key)?.let { return it }
        val bytes = roomController.fetchImage(key) ?: return null
        return roomImageCache.store(key, bytes)
    }

    fun showMessage(text: String) {
        _message.value = text
    }

    fun consumeMessage() {
        _message.value = null
    }

    // ---------- 内部 ----------

    /**
     * 按角色分派一个动作。
     * 离线与房主路径用 [localFallback]（语义与房主完全一致，都走 `applyRoomAction`）。
     */
    private suspend fun dispatch(
        action: RoomAction,
        localFallback: () -> Unit,
    ): RoomActionResult = when (roomController.role.value) {
        RoomRole.Offline -> {
            localFallback()
            publish()
            RoomActionResult.Ok()
        }

        RoomRole.Host -> {
            roomController.applyAsHost(action)
            publish()
            RoomActionResult.Ok()
        }

        RoomRole.Member -> when (val result = roomController.clientForRequests()?.request(action)) {
            null -> RoomActionResult.Failed("尚未连接到房主")
            is RequestResult.Ok -> RoomActionResult.Ok()
            is RequestResult.Failed -> {
                _message.value = result.reason
                RoomActionResult.Failed(result.reason)
            }
        }
    }

    private fun publish() {
        _ui.value = buildState()
        store.save(session.snapshot())
    }

    private fun buildState(): UiState = UiState(
        lots = session.lots.map { lot ->
            LotUi(lot = lot, remaining = session.remainingOf(lot.id), drawnCount = session.drawnCountOf(lot.id))
        },
        results = session.results.toList(),
        mode = session.mode,
        totalQuantity = session.totalQuantity,
        totalRemaining = session.totalRemaining,
        canDraw = session.canDraw(),
    )
}
