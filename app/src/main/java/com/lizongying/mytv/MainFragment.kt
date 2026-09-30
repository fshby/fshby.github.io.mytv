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

        activity?.let { YSP.init(it) }

        itemPosition = SP.itemPosition

        // 同步加载外部列表（本地文件 / 缓存，毫秒级），保证首屏就是外部频道表
        TVList.load(requireContext())

        view?.post {
            buildRows()
            registerObservers()

            (activity as MainActivity).fragmentReady(TAG)

            // 后台拉取远程列表，有变化再重建
            refreshRemoteList()
            // 之后每 30 分钟静默重探：IPTV 源可用性随时变化，
            // 周期刷新让死源及时剔除、恢复的源及时回来
            startPeriodicRefresh()
        }
    }

    /** 周期性重拉远程列表 + 重探可用性；lifecycleScope 随 Fragment 销毁自动取消 */
    private fun startPeriodicRefresh() {
        lifecycleScope.launch {
            while (isActive) {
                delay(30 * 60 * 1000L)
                Log.i(TAG, "periodic refresh")
                refreshRemoteList()
            }
        }
    }

    /** 按当前 TVList 重建全部分组行（可重复调用） */
    private fun buildRows() {
        val context = context ?: return
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
                    (activity as MainActivity).mainActive()
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

    /** 后台拉取远程频道列表；成功且内容有变化时重建界面并续播当前频道 */
    private fun refreshRemoteList() {
        val url = TVSource.remoteUrl(requireContext())
        if (url.isEmpty()) {
            return
        }
        Log.i(TAG, "remote list $url")
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { TVSource.fetch(url) }
                ?: return@launch
            val parsed = TVSource.parse(text, url) ?: return@launch
            // 先按名去重后立即上屏（不等待探测，保证秒响应）
            if (TVList.applyRemote(parsed)) {
                rebuildRows()
            }
            // 后台探测各源可用性：剔除异常频道，同名频道保留探测通过的那个
            val alive = withContext(Dispatchers.IO) { TVSource.filterAlive(parsed) }
            if (TVList.applyRemote(alive)) {
                Log.i(TAG, "unreachable channels filtered")
                rebuildRows()
            }
            // 缓存保存探测过滤后的列表（而非远程原文）：
            // 下次启动秒开的列表即已剔除死源，不会先看到大量播放错误的频道
            context?.let { TVSource.saveCache(it, TVSource.toM3U(alive)) }
        }
    }

    private fun rebuildRows() {
        if (_binding == null) {
            return
        }
        // 列表被过滤/重建后下标会漂移，先记住当前频道名，重建后按名字找回
        val currentTitle = tvListViewModel.getTVViewModel(itemPosition)?.getTV()?.title
        buildRows()
        registerObservers()
        if (!currentTitle.isNullOrEmpty()) {
            val index = TVList.list.values.flatten().indexOfFirst { it.title == currentTitle }
            if (index >= 0) {
                itemPosition = index
                tvListViewModel.setItemPosition(itemPosition)
            }
        }
        Log.i(TAG, "rows rebuilt, ${tvListViewModel.size()} channels")
        // fragmentReady 的启动计数早已用完，重建后需主动触发一次换台续播
        tvListViewModel.getTVViewModel(itemPosition)?.changed("remote")
        setPosition()
    }

    fun changeMenu() {
        if (SP.grid) {
            for (i in rowList) {
                if (i is RecyclerView) {
                    i.layoutManager = GridLayoutManager(context, 6)
                    i.layoutParams.height =
                        application.dp2Px(110 * (((i.adapter as CardAdapter).getItemCount() + 6 - 1) / 6) + 5)
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

    override fun onKey(keyCode: Int): Boolean {
        if (this.isHidden) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    (activity as MainActivity).onKey(keyCode)
                    return true
                }

                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    (activity as MainActivity).onKey(keyCode)
                    return true
                }
            }
        }
        return false
    }

    override fun onItemHasFocus(tvViewModel: TVViewModel) {
        val row = tvViewModel.getRowPosition()

        for (i in rowList) {
            if (i.tag as Int != row) {
                ((i as RecyclerView).adapter as CardAdapter).focusable = false
                (i.adapter as CardAdapter).clear()
            } else {
                ((i as RecyclerView).adapter as CardAdapter).focusable = true
            }
        }

        (activity as MainActivity).mainActive()
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
        rowList[rowPosition].post {
            when (val layoutManager = (rowList[rowPosition] as RecyclerView).layoutManager) {
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
        val videoUrl = tvViewModel.videoIndex.value?.let { tvViewModel.videoUrl.value?.get(it) }
        if (videoUrl == null || videoUrl == "") {
            Log.e(TAG, "$title videoUrl is empty")
            return false
        }

        if (videoUrl == lastVideoUrl) {
            Log.e(TAG, "$title videoUrl is duplication")
            return false
        }

        return true
    }

    fun fragmentReady() {
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
                    ((i as RecyclerView).adapter as CardAdapter).updateEPG()
                    (i.adapter as CardAdapter).focusable = true
                    (i.adapter as CardAdapter).toPosition(itemPosition)
                    break
                }
            }
        } else {
            view?.post {
                for (i in rowList) {
                    ((i as RecyclerView).adapter as CardAdapter).focusable = false
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
    }
}
