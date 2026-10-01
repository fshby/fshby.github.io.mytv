package com.lizongying.mytv.models

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.lizongying.mytv.api.FEPG
import com.lizongying.mytv.proto.Ysp.cn.yangshipin.omstv.common.proto.programModel.Program
import com.tencent.videolite.android.datamodel.cctvjce.TVProgram
import java.text.SimpleDateFormat
import java.util.TimeZone

class TVViewModel(private var tv: TV) : ViewModel() {

    private var rowPosition: Int = 0
    private var itemPosition: Int = 0

    var retryTimes = 0
    var retryMaxTimes = 8
    var authYSPRetryTimes = 0
    var authYSPRetryMaxTimes = 3
    var tokenYSPRetryTimes = 0
    var tokenYSPRetryMaxTimes = 0
    var tokenFHRetryTimes = 0
    var tokenFHRetryMaxTimes = 8

    var needGetToken = false

    private val _errInfo = MutableLiveData<String>()
    val errInfo: LiveData<String>
        get() = _errInfo

    private var _epg = MutableLiveData<MutableList<EPG>>()
    val epg: LiveData<MutableList<EPG>>
        get() = _epg

    private val _videoUrl = MutableLiveData<List<String>>()
    val videoUrl: LiveData<List<String>>
        get() = _videoUrl

    private val _videoIndex = MutableLiveData<Int>()
    val videoIndex: LiveData<Int>
        get() = _videoIndex

    private val _change = MutableLiveData<String>()
    val change: LiveData<String>
        get() = _change

    private val _ready = MutableLiveData<Boolean>()
    val ready: LiveData<Boolean>
        get() = _ready

    var seq = 0

    fun addVideoUrl(url: String) {
        if (_videoUrl.value?.isNotEmpty() == true) {
            if (_videoUrl.value!!.last().contains("cctv.cn")) {
                tv.videoUrl = tv.videoUrl.subList(0, tv.videoUrl.lastIndex) + listOf(url)
            } else {
                tv.videoUrl += listOf(url)
            }
        } else {
            tv.videoUrl += listOf(url)
        }
        _videoUrl.value = tv.videoUrl
        _videoIndex.value = tv.videoUrl.lastIndex
    }

    fun changed(from: String) {
        retryTimes = 0
        authYSPRetryTimes = 0
        tokenYSPRetryTimes = 0
        tokenFHRetryTimes = 0
        _change.value = from
    }

    fun allReady() {
        _ready.value = true
    }

    init {
        _videoUrl.value = tv.videoUrl
        // 直连多源频道：下标 0 就是探测阶段按「实测分片吞吐 ÷ 实时码率需求」排序后的
        // 首选源（同时兼顾窗口大小，小窗口源降权），换台即用最优源。
        // 央视频 / 凤凰等接口拉流频道（pid 非空）地址是播放时逐个追加的，仍取最新那个。
        _videoIndex.value = if (tv.pid.isEmpty()) 0 else tv.videoUrl.lastIndex
    }

    fun getRowPosition(): Int {
        return rowPosition
    }

    fun getItemPosition(): Int {
        return itemPosition
    }

    fun setRowPosition(position: Int) {
        rowPosition = position
    }

    fun setItemPosition(position: Int) {
        itemPosition = position
    }

    fun setErrInfo(info: String) {
        _errInfo.value = info
    }

    fun getTV(): TV {
        return tv
    }

    fun addYJceEPG(p: MutableList<TVProgram>) {
        _epg.value = p.map { EPG(it.name, it.start_time_stamp.toInt()) }.toMutableList()
    }

    fun addYEPG(p: MutableList<Program>) {
        _epg.value = p.map { EPG(it.name, it.st.toInt()) }.toMutableList()
    }

    private fun formatFTime(s: String): Int {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")
        dateFormat.timeZone = TimeZone.getTimeZone("UTC")
        val date = dateFormat.parse(s.substring(0, 19))
        if (date != null) {
            return (date.time / 1000).toInt()
        }
        return 0
    }

    fun addFEPG(p: List<FEPG>) {
        _epg.value = p.map { EPG(it.title, formatFTime(it.event_time)) }.toMutableList()
    }

    /**
     * 当前播放地址。空源频道（视频地址列表为空或下标越界）返回空串，
     * 由调用方跳过播放——早期实现用 !! 取下标，会抛 IndexOutOfBoundsException 闪退。
     */
    fun getVideoUrlCurrent(): String {
        val urls = _videoUrl.value ?: return ""
        val index = _videoIndex.value ?: return ""
        if (urls.isEmpty() || index < 0 || index >= urls.size) {
            return ""
        }
        return urls[index]
    }

    /**
     * 轮转到频道内下一个备用源（循环）。返回 true 表示已切换，
     * 调用方应换新地址重试而不是继续撞同一个坏源。
     */
    fun rotateToNextSource(): Boolean {
        val urls = _videoUrl.value ?: return false
        if (urls.size < 2) return false
        val index = _videoIndex.value ?: 0
        _videoIndex.value = (index + 1) % urls.size
        return true
    }

    fun getVideoUrls(): List<String> = _videoUrl.value ?: emptyList()

    /** 设置当前源下标（带边界校验），供播放侧按实播重缓冲史换选更优源 */
    fun setVideoIndex(index: Int) {
        val urls = _videoUrl.value ?: return
        if (index in urls.indices) {
            _videoIndex.value = index
        }
    }

    companion object {
        private const val TAG = "TVViewModel"
    }
}