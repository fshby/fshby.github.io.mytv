package com.lizongying.mytv

import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.marginBottom
import androidx.core.view.marginStart
import androidx.core.view.marginTop
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lizongying.mytv.api.SourceProfiles
import com.lizongying.mytv.api.YSP
import com.lizongying.mytv.databinding.MenuBinding
import com.lizongying.mytv.databinding.RowBinding
import com.lizongying.mytv.models.ProgramType
import com.lizongying.mytv.models.TVList
import com.lizongying.mytv.models.TVListViewModel
import com.lizongying.mytv.models.TVViewModel
import com.lizongying.mytv.requests.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class MainFragment : Fragment(), CardAdapter.ItemListener {

    private var itemPosition = 0

    private var rowList: MutableList<View> = mutableListOf()

    private var _binding: MenuBinding? = null
    private val binding get() = _binding!!

    var tvListViewModel = TVListViewModel()

    private var lastVideoUrl = ""

    private lateinit var application: MyTVApplication

    private lateinit var gestureDetector: GestureDetector

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        _binding = MenuBinding.inflate(inflater, container, false)

        application = requireActivity().applicationContext as MyTVApplication

        binding.menu.layoutParams.width = application.shouldWidthPx()
        binding.menu.layoutParams.height = application.shouldHeightPx()

        binding.container.setOnClickListener {
            hideSelf()
        }

        gestureDetector = GestureDetector(context, GestureListener())

        return binding.root
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            Log.i(TAG, "onSingleTapConfirmed")
            hideSelf()
            return true
        }
    }

    private fun hideSelf() {
        requireActivity().supportFragmentManager.beginTransaction()
            .hide(this)
            .commit()
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        val tYsp = System.currentTimeMillis()
        activity?.let { YSP.init(it) }
        Log.i(TAG, "YSP.init ${System.currentTimeMillis() - tYsp}ms")

        itemPosition = SP.itemPosition

        // 列表解析已在 Application.onCreate 里预加载（见 TVList.preload），
        // 这里只等它完成：1000+ 条缓存 M3U 的解析 + 同名归一化实测 2s 以上，
        // 与界面创建并行后不再顶在首屏路径上
        lifecycleScope.launch {
            val ctx = context ?: return@launch
            val tLoad = System.currentTimeMillis()
            withContext(Dispatchers.IO) { TVList.ensureLoaded(ctx) }
            Log.i(TAG, "await channel list ${System.currentTimeMillis() - tLoad}ms")
            if (_binding == null) return@launch
            view?.post {
                if (_binding == null) return@post
                val mainActivity = activity as? MainActivity ?: return@post

                val tBuild = System.currentTimeMillis()
                buildRows()
                Log.i(TAG, "buildRows ${System.currentTimeMillis() - tBuild}ms")
                registerObservers()

                // 视图就绪计数 + 频道列表就绪：两者凑齐即放行首屏起播（不依赖任何网络请求）
                mainActivity.fragmentReady(TAG)
                mainActivity.onChannelListReady()

                // 后台拉取远程列表，有变化再重建
                refreshRemoteList()
                // 之后每 30 分钟静默重探：IPTV 源可用性随时变化，
                // 周期刷新让死源及时剔除、恢复的源及时回来
                startPeriodicRefresh()
            }
        }
    }

    /** 每 30 分钟完整重拉 + 重探一次（force 路径），让死源剔除、恢复的源回来 */
    private fun startPeriodicRefresh() {
        lifecycleScope.launch {
            while (isActive) {
                delay(30 * 60 * 1000L)
                Log.i(TAG, "periodic refresh")
                refreshRemoteList(force = true)
            }
        }
    }

    /** 按当前 TVList 重建全部分组行（可重复调用） */
    private fun buildRows() {
        val context = context ?: return
        if (_binding == null) return
        val content = binding.content
        content.removeAllViews()
        rowList.clear()
        tvListViewModel = TVListViewModel()

        var idx: Long = 0
        for ((k, v) in TVList.list) {
            val itemBinding: RowBinding =
                RowBinding.inflate(layoutInflater, content, false)

            val tvListViewModelCurrent = TVListViewModel()
            for ((idx2, v1) in v.withIndex()) {
                val tvViewModel = TVViewModel(v1)
                tvViewModel.setRowPosition(idx.toInt())
                tvViewModel.setItemPosition(idx2)
                tvListViewModelCurrent.addTVViewModel(tvViewModel)
                tvListViewModel.addTVViewModel(tvViewModel)
            }
            tvListViewModel.maxNum.add(v.size)

            val adapter =
                CardAdapter(
                    itemBinding.items,
                    this,
                    tvListViewModelCurrent,
                )
            rowList.add(itemBinding.items)

            adapter.setItemListener(this)

            itemBinding.header.text = k
            itemBinding.items.tag = idx.toInt()
            itemBinding.items.adapter = adapter

            itemBinding.items.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    (activity as? MainActivity)?.mainActive()
                }
            })

            val itemDecoration = ItemDecoration(context)
            itemBinding.items.addItemDecoration(itemDecoration)

            if (SP.grid) {
                itemBinding.items.layoutManager =
                    GridLayoutManager(context, 6)
                itemBinding.items.layoutParams.height =
                    application.dp2Px(110 * ((tvListViewModelCurrent.size() + 6 - 1) / 6) + 5)
            } else {
                itemBinding.items.layoutManager =
                    LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            }

            val layoutParams = itemBinding.row.layoutParams as ViewGroup.MarginLayoutParams
            layoutParams.topMargin = application.dp2Px(11)
            itemBinding.row.layoutParams = layoutParams
            itemBinding.row.setOnClickListener {
                hideSelf()
            }

            itemBinding.items.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
                override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                    gestureDetector.onTouchEvent(e)
                    return false
                }

                override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                    // 触摸遥控器/鼠标拖动列表时会走到这里，保持空实现即可。
                    // 原先这里是 TODO()，一旦被调用就会抛 NotImplementedError 导致闪退。
                }

                override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
                    // 同上：子 View 在滚动/拖拽时会触发，空实现避免崩溃
                }
            })

            val layoutParamsHeader =
                itemBinding.header.layoutParams as ViewGroup.MarginLayoutParams
            layoutParamsHeader.topMargin = application.px2Px(itemBinding.header.marginTop)
            layoutParamsHeader.bottomMargin = application.px2Px(itemBinding.header.marginBottom)
            layoutParamsHeader.marginStart = application.px2Px(itemBinding.header.marginStart)
            itemBinding.header.layoutParams = layoutParamsHeader
            itemBinding.header.textSize = application.px2PxFont(itemBinding.header.textSize)

            content.addView(itemBinding.row)

            idx++
        }

        if (itemPosition >= tvListViewModel.size()) {
            itemPosition = 0
        }

        tvListViewModel.setItemPosition(itemPosition)
    }

    /** 为当前列表的全部频道注册 errInfo / ready / change 观察者（重建后需重新调用） */
    private fun registerObservers() {
        tvListViewModel.tvListViewModel.value?.forEach { tvViewModel ->
            tvViewModel.errInfo.observe(viewLifecycleOwner) { _ ->
                if (tvViewModel.errInfo.value != null
                    && tvViewModel.getTV().id == itemPosition
                ) {
                    if (tvViewModel.errInfo.value == "") {
                        (activity as? MainActivity)?.showPlayerFragment()
                        (activity as? MainActivity)?.hideErrorFragment()
                        (activity as? MainActivity)?.hideLoadingFragment()
                    } else {
                        (activity as? MainActivity)?.hidePlayerFragment()
                        (activity as? MainActivity)?.hideLoadingFragment()
                        (activity as? MainActivity)?.showErrorFragment(tvViewModel.errInfo.value.toString())
                    }
                }
            }
            tvViewModel.ready.observe(viewLifecycleOwner) { _ ->

                // not first time && channel not change
                if (tvViewModel.ready.value != null
                    && tvViewModel.getTV().id == itemPosition
                    && check(tvViewModel)
                ) {
                    Log.i(TAG, "ready ${tvViewModel.getTV().title}")
                    (activity as? MainActivity)?.play(tvViewModel)
                }
            }
            tvViewModel.change.observe(viewLifecycleOwner) { _ ->
                if (tvViewModel.change.value != null) {
                    val title = tvViewModel.getTV().title
                    Log.i(TAG, "switch $title")
                    if (tvViewModel.getTV().pid != "") {
                        Log.i(TAG, "request $title")
                        lifecycleScope.launch(Dispatchers.IO) {
                            tvViewModel.let { Request.fetchData(it) }
                        }
                        (activity as? MainActivity)?.showInfoFragment(tvViewModel)
                        setPosition(
                            tvViewModel.getRowPosition(), tvViewModel.getItemPosition()
                        )
                    } else {
                        if (check(tvViewModel)) {
                            // TODO lastVideoUrl
                            (activity as? MainActivity)?.play(tvViewModel)
                            (activity as? MainActivity)?.showInfoFragment(tvViewModel)
                            setPosition(
                                tvViewModel.getRowPosition(), tvViewModel.getItemPosition()
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * 后台拉取远程频道列表，必要时重建界面并重探可用性。
     *
     * 两条路径：
     *  - 启动路径（force = false）：**先判断能不能直接复用上次结果**，能就整段跳过。
     *    判据是「内容没变（304 或原文签名一致）**且**上次探测结论还新鲜（< CACHE_MAX_AGE）」。
     *    远程列表很少变、探测却要跑几分钟，原来每次重启都无条件重跑，纯属白烧。
     *  - 周期路径（force = true，每 30 分钟）：完整下载 + 解析 + 重探，
     *    保证死源会被剔除、恢复的源能回来。
     */
    private fun refreshRemoteList(force: Boolean = false) {
        val context = context ?: return
        val url = TVSource.remoteUrl(context)
        if (url.isEmpty()) {
            return
        }
        lifecycleScope.launch {
            val meta = withContext(Dispatchers.IO) { TVSource.readCacheMeta(context) }
            // 探测结论新鲜时才发送条件请求：服务器回 304 就没法拿到原文，
            // 而结论过期是必须重探的，必须拿到原文才能重新过滤
            val canReuse = !force && TVSource.isProbeFresh(context, CACHE_MAX_AGE_MS)
            Log.i(TAG, "remote list $url (force=$force, reuse=$canReuse)")
            val res = withContext(Dispatchers.IO) {
                TVSource.fetch(
                    url,
                    if (canReuse) meta.etag else "",
                    if (canReuse) meta.lastModified else "",
                )
            }
            if (res.text == null && !res.notModified) return@launch

            val sig = res.text?.let { TVSource.contentSig(it) }.orEmpty()
            val unchanged = res.notModified ||
                    (sig.isNotEmpty() && sig == meta.sig && TVList.list.isNotEmpty())
            if (unchanged && canReuse) {
                // 列表没变 + 结论新鲜：解析、重建、探测全部跳过
                Log.i(TAG, "list unchanged & probe fresh, reuse cache (skip parse & probe)")
                withContext(Dispatchers.IO) {
                    TVSource.writeCacheMeta(
                        context,
                        meta.copy(
                            ts = System.currentTimeMillis(),
                            etag = res.etag.ifEmpty { meta.etag },
                            lastModified = res.lastModified.ifEmpty { meta.lastModified },
                        )
                    )
                }
                return@launch
            }

            val text = res.text ?: return@launch
            // 解析 226KB M3U + 两千多条频道名的同名归一化（逐条正则）必须离开主线程，
            // lifecycleScope 默认跑在主线程，放这里会直接造成掉帧
            val parsed = withContext(Dispatchers.IO) { TVSource.parse(text, url) }
                ?: return@launch
            // 先按名去重后立即上屏（不等待探测，保证秒响应）
            if (withContext(Dispatchers.IO) { TVList.applyRemote(parsed) }) {
                rebuildRows()
            }
            // 起播稳定后再开始探测：全量探测要跑几分钟且占满多路连接，
            // 立刻开跑会与首帧/首片抢带宽，弱盒子上直接表现为开机卡顿
            delay(PROBE_START_DELAY_MS)
            // 后台探测各源可用性：剔除异常频道，同名频道保留探测通过的那个
            val alive = withContext(Dispatchers.IO) {
                TVSource.filterAlive(parsed, PROBE_CONCURRENCY)
            }
            // 图标优先用内置官方版（不破坏多源结构）
            withContext(Dispatchers.IO) { TVList.patchBuiltinLogos(alive) }
            if (withContext(Dispatchers.IO) { TVList.applyRemote(alive) }) {
                Log.i(TAG, "unreachable channels filtered")
                rebuildRows()
            }
            // 缓存保存探测过滤后的列表（而非远程原文）：下次启动秒开的列表即已剔除死源。
            // 同时记录原文签名 + 探测时间戳，下次启动据此判断能否整段跳过。
            withContext(Dispatchers.IO) {
                TVSource.saveCache(context, TVSource.toM3U(alive))
                // 源画像（目标时长 / 窗口 / 实测吞吐）与列表一起落盘并裁剪：
                // 下次启动若命中「列表没变 + 结论新鲜」而整段跳过探测，
                // 起播偏移依然能按源计算，不会退化成有时生效有时不生效
                SourceProfiles.save(context, alive.values.flatten().flatMap { it.videoUrl })
                val now = System.currentTimeMillis()
                TVSource.writeCacheMeta(
                    context,
                    TVSource.CacheMeta(
                        ts = now,
                        probeTs = now,
                        url = url,
                        etag = res.etag,
                        lastModified = res.lastModified,
                        sig = sig,
                    )
                )
            }
        }
    }

    private fun rebuildRows() {
        if (_binding == null) {
            return
        }
        // 列表被过滤/重建后下标会漂移，先记住当前频道名，重建后按名字找回
        val currentTitle = tvListViewModel.getTVViewModel(itemPosition)?.getTV()?.title
        val t0 = System.currentTimeMillis()
        buildRows()
        registerObservers()
        if (!currentTitle.isNullOrEmpty()) {
            val index = TVList.list.values.flatten().indexOfFirst { it.title == currentTitle }
            if (index >= 0) {
                itemPosition = index
            } else {
                // 正在看的频道被探测判死并从列表里剔除：旧下标在新列表里会指向「另一个频道」，
                // 直接续播就是无提示换台（播错台）。这里回落到 0 号频道并说明。
                Log.w(TAG, "current '$currentTitle' removed by probe, fallback to 0")
                itemPosition = 0
            }
            tvListViewModel.setItemPosition(itemPosition)
        }
        Log.i(TAG, "rows rebuilt, ${tvListViewModel.size()} channels in ${System.currentTimeMillis() - t0}ms")
        // fragmentReady 的启动计数早已用完，重建后需主动触发一次换台续播
        tvListViewModel.getTVViewModel(itemPosition)?.changed("remote")
        setPosition()
    }

    fun changeMenu() {
        if (SP.grid) {
            for (i in rowList) {
                if (i is RecyclerView) {
                    i.layoutManager = GridLayoutManager(context, 6)
                    val count = adapterOf(i)?.getItemCount() ?: continue
                    i.layoutParams.height = application.dp2Px(110 * ((count + 6 - 1) / 6) + 5)
                }
            }
        } else {
            for (i in rowList) {
                if (i is RecyclerView) {
                    i.layoutManager =
                        LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
                    i.layoutParams.height = application.dp2Px(110 + 5)
                }
            }
        }
    }

    /**
     * 行容器上的卡片适配器。
     *
     * buildRows 里是「先把行加进 rowList（入列）、随后才给 items 赋 adapter」，
     * 中间若被重入（列表重建 / onResumeFragments / 遥控回调）就会拿到 adapter == null。
     * 原实现一律 `adapter as CardAdapter` 强转，null 场合直接 TypeError 闪退。
     */
    private fun adapterOf(v: View): CardAdapter? = (v as? RecyclerView)?.adapter as? CardAdapter

    override fun onKey(keyCode: Int): Boolean {
        if (this.isHidden) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    (activity as? MainActivity)?.onKey(keyCode)
                    return true
                }

                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    (activity as? MainActivity)?.onKey(keyCode)
                    return true
                }
            }
        }
        return false
    }

    override fun onItemHasFocus(tvViewModel: TVViewModel) {
        val row = tvViewModel.getRowPosition()

        for (i in rowList) {
            // tag 在 buildRows 里随后才被赋成 Int，重建重入时可能还是 null
            if (i.tag as? Int != row) {
                adapterOf(i)?.apply {
                    focusable = false
                    clear()
                }
            } else {
                adapterOf(i)?.focusable = true
            }
        }

        (activity as? MainActivity)?.mainActive()
    }

    override fun onItemClicked(tvViewModel: TVViewModel) {
        if (this.isHidden) {
            (activity as? MainActivity)?.switchMainFragment()
            return
        }

        if (itemPosition != tvViewModel.getTV().id) {
            itemPosition = tvViewModel.getTV().id
            tvListViewModel.setItemPosition(itemPosition)
            tvListViewModel.getTVViewModel(itemPosition)?.changed("menu")
            Log.i(TAG, "onItemClicked ${tvViewModel.getTV().id}")
        }
        (activity as? MainActivity)?.switchMainFragment()
    }

    fun setPosition() {
        val tvViewModel = tvListViewModel.getTVViewModel(itemPosition) ?: return
        val rowPosition = tvViewModel.getRowPosition()
        val itemPosition = tvViewModel.getItemPosition()
        setPosition(rowPosition, itemPosition)
    }

    fun setPosition(rowPosition: Int, itemPosition: Int) {
        if (rowPosition < 0 || rowPosition >= rowList.size) {
            return
        }
        val row = rowList[rowPosition]
        row.post {
            when (val layoutManager = (row as? RecyclerView)?.layoutManager) {
                is GridLayoutManager -> {
                    layoutManager.findViewByPosition(
                        itemPosition
                    )?.requestFocus()
                }

                is LinearLayoutManager -> {
                    layoutManager.findViewByPosition(
                        itemPosition
                    )?.requestFocus()
                }
            }
        }
    }

    fun check(tvViewModel: TVViewModel): Boolean {
        val title = tvViewModel.getTV().title
        // 列表重建 / 空源频道（videoUrl 为空，下标为 -1）都可能让取址越界，
        // 原实现直接 get(it) 会抛 IndexOutOfBoundsException
        val urls = tvViewModel.videoUrl.value
        val index = tvViewModel.videoIndex.value ?: 0
        if (urls == null || index < 0 || index >= urls.size) {
            Log.e(TAG, "$title videoUrl is empty")
            return false
        }
        val videoUrl = urls[index]
        if (videoUrl == "") {
            Log.e(TAG, "$title videoUrl is empty")
            return false
        }

        if (videoUrl == lastVideoUrl) {
            Log.e(TAG, "$title videoUrl is duplication")
            return false
        }

        return true
    }

    /**
     * 启动闸门放行后的首屏动作：起播当前频道 + 拉取整表 EPG。
     * 由 MainActivity 在「Fragment 视图就绪 + 频道列表就绪」后调用，且只调用一次。
     */
    fun startup() {
        tvListViewModel.getTVViewModel(itemPosition)?.changed("init")

        tvListViewModel.tvListViewModel.value?.forEach { tvViewModel ->
            updateEPG(tvViewModel)
        }
    }

    fun play(itemPosition: Int) {
        view?.post {
            if (itemPosition > -1 && itemPosition < tvListViewModel.size()) {
                this.itemPosition = itemPosition
                tvListViewModel.setItemPosition(itemPosition)
                tvListViewModel.getTVViewModel(itemPosition)?.changed("num")
            } else {
                Toast.makeText(context, "频道不存在", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun prev() {
        view?.post {
            if (tvListViewModel.size() == 0) {
                Log.e(TAG, "prev ignored: empty channel list")
                return@post
            }
            itemPosition--
            if (itemPosition < 0) {
                itemPosition = tvListViewModel.size() - 1
            }
            tvListViewModel.setItemPosition(itemPosition)
            tvListViewModel.getTVViewModel(itemPosition)?.changed("prev")
        }
    }

    fun next() {
        view?.post {
            if (tvListViewModel.size() == 0) {
                Log.e(TAG, "next ignored: empty channel list")
                return@post
            }
            itemPosition++
            if (itemPosition >= tvListViewModel.size()) {
                itemPosition = 0
            }
            tvListViewModel.setItemPosition(itemPosition)
            tvListViewModel.getTVViewModel(itemPosition)?.changed("next")
        }
    }

    private fun updateEPG(tvViewModel: TVViewModel) {
        // 直连频道没有央视频 pid，跳过节目单请求，避免无效网络调用
        if (tvViewModel.getTV().pid == "") {
            return
        }
        when (tvViewModel.getTV().programType) {
            ProgramType.Y_PROTO -> {
                Request.fetchYProtoEPG(tvViewModel)
            }

            ProgramType.Y_JCE -> {
                Request.fetchYJceEPG(tvViewModel)
            }

            ProgramType.F -> {
                Request.fetchFEPG(tvViewModel)
            }
        }
    }

    fun shouldHasFocus(tvModel: TVViewModel): Boolean {
        return tvModel == tvListViewModel.getTVViewModel(itemPosition)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        Log.i(TAG, "onHiddenChanged $hidden")
        super.onHiddenChanged(hidden)
        if (!hidden) {
            // 列表可能刚被远程列表重建（长度变化），这里必须容忍取不到当前项
            val tvModel = tvListViewModel.getTVViewModel(itemPosition) ?: return
            val rowPosition = tvModel.getRowPosition()
            val itemPosition = tvModel.getItemPosition()
            Log.i(TAG, "toPosition $rowPosition $itemPosition")
            for (i in rowList) {
                if (i.tag as? Int == rowPosition) {
                    adapterOf(i)?.apply {
                        updateEPG()
                        focusable = true
                        toPosition(itemPosition)
                    }
                    break
                }
            }
        } else {
            view?.post {
                for (i in rowList) {
                    adapterOf(i)?.focusable = false
                }
            }
        }
    }

    override fun onResume() {
        Log.i(TAG, "onResume")
        super.onResume()
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        super.onDestroy()
    }

    override fun onDestroyView() {
        Log.i(TAG, "onDestroyView")
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "MainFragment"
        private const val POSITION = "position"

        /** 探测并发：偏高会与播放抢带宽，弱盒子上直接表现为开机后播放卡顿 */
        private const val PROBE_CONCURRENCY = 6

        /** 起播后延迟多久再开始全量探测，避开首帧与首片下载 */
        private const val PROBE_START_DELAY_MS = 20_000L

        /**
         * 探测结论的最长复用时间。启动时若上次探测在这个时间内完成，
         * 且远程列表没变，就连解析带探测整段跳过（这是「重启不再白烧资源」的关键）。
         * 超过这个时间说明隔了很久没开机，必须重探以保证死源被剔除。
         */
        private const val CACHE_MAX_AGE_MS = 6 * 60 * 60 * 1000L
    }
}
