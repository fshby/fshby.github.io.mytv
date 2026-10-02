package com.lizongying.mytv

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
import android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
import android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
import android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
import android.view.WindowManager
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.lizongying.mytv.models.TVViewModel
import com.lizongying.mytv.requests.Request
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch


class MainActivity : FragmentActivity(), Request.RequestListener, OnSharedPreferenceChangeListener {

    /** 已创建完成的 Fragment 视图数量 */
    private var fragmentReadyCount = 0

    /** MainFragment 的频道列表是否已构建完毕（只有此时才能安全起播、拉 EPG） */
    private var channelListReady = false

    /** 启动闸门是否已放行（保证只放行一次） */
    private var startupStarted = false

    /** 参与启动闸门的 Fragment：Player / Error / Time / Info / Channel / Main */
    private val startupFragmentCount = 6

    /** 时间校准等远端初始化延后到起播之后再跑，避免占用启动关键路径 */
    private val syncTimeDelay: Long = 8000

    private val playerFragment = PlayerFragment()
    private val errorFragment = ErrorFragment()

    private val loadingFragment = LoadingFragment()
    private val mainFragment = MainFragment()
    private val infoFragment = InfoFragment()
    private val channelFragment = ChannelFragment()
    private var timeFragment = TimeFragment()
    private val settingFragment = SettingFragment()

    private var doubleBackToExitPressedOnce = false

    private lateinit var gestureDetector: GestureDetector

    private val handler = Handler()
    private val delayHideMain: Long = 10000
    private val delayHideSetting: Long = 15000

    /** 断网恢复自愈：监听系统网络可用事件，通知播放器重连 */
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        Utils.setRequestListener(this)
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "network available")
                PlaybackRecovery.onNetworkRestored()
            }
        }
        try {
            cm.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
            networkCallback = callback
        } catch (e: Exception) {
            Log.e(TAG, "registerNetworkCallback failed", e)
        }
    }

    fun syncTime() {
        lifecycleScope.launch(Dispatchers.IO) {
            val utilsJob = async(start = CoroutineStart.LAZY) { Utils.init() }
            utilsJob.start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.setAttributes(lp)
        }


        window.decorView.apply {
            systemUiVisibility =
                SYSTEM_UI_FLAG_FULLSCREEN or
                        SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility =
            SYSTEM_UI_FLAG_FULLSCREEN or
                    SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
//            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
//            window.statusBarColor = Color.TRANSPARENT
//            window.navigationBarColor = Color.TRANSPARENT
//        }

        setContentView(R.layout.activity_main)

        Request.setRequestListener(this)

        if (savedInstanceState == null) {
            val transaction = supportFragmentManager.beginTransaction()
                .add(R.id.main_browse_fragment, playerFragment)
                .add(R.id.main_browse_fragment, errorFragment)
//                .add(R.id.main_browse_fragment, loadingFragment)
                .add(R.id.main_browse_fragment, timeFragment)
                .add(R.id.main_browse_fragment, infoFragment)
                .add(R.id.main_browse_fragment, channelFragment)
                .add(R.id.main_browse_fragment, mainFragment)
                .hide(mainFragment)
                .hide(errorFragment)

            if (!SP.time) {
                transaction.hide(timeFragment)
            } else {
                transaction.show(timeFragment)
            }

            transaction.commitNow()
        }
        gestureDetector = GestureDetector(this, GestureListener())

//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
//            val connectivityManager =
//                getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
//            connectivityManager.registerDefaultNetworkCallback(object :
//                ConnectivityManager.NetworkCallback() {
//                override fun onAvailable(network: Network) {
//                    super.onAvailable(network)
//                    Log.i(TAG, "net ${Build.VERSION.SDK_INT}")
//                    if (this@MainActivity.isNetworkConnected) {
//                        Log.i(TAG, "net isNetworkConnected")
//                        ready++
//                    }
//                }
//            })
//        } else {
//            Log.i(TAG, "net ${Build.VERSION.SDK_INT}")
//            ready++
//        }

        SP.setOnSharedPreferenceChangeListener(this)
    }

    fun showInfoFragment(tvViewModel: TVViewModel) {
        infoFragment.show(tvViewModel)
        if (SP.channelNum) {
            channelFragment.show(tvViewModel)
        }
    }

    fun showErrorFragment(msg: String) {
        errorFragment.show(msg)
        if (errorFragment.isVisible) {
            return
        }
        supportFragmentManager.beginTransaction()
            .show(errorFragment)
            .commit()
    }

    fun hideErrorFragment() {
        errorFragment.show("")
        if (!errorFragment.isVisible) {
            return
        }
        supportFragmentManager.beginTransaction()
            .hide(errorFragment)
            .commit()
    }

    fun showLoadingFragment() {
        showFragment(loadingFragment)
    }

    fun hideLoadingFragment() {
        hideFragment(loadingFragment)
    }

    fun showPlayerFragment() {
        if (playerFragment.isVisible) {
            return
        }
        supportFragmentManager.beginTransaction()
            .show(playerFragment)
            .commit()
    }

    fun hidePlayerFragment() {
        if (!playerFragment.isVisible) {
            return
        }
        supportFragmentManager.beginTransaction()
            .hide(playerFragment)
            .commit()
    }

    private fun showChannel(channel: String) {
        if (!mainFragment.isHidden) {
            return
        }

        if (settingFragment.isVisible) {
            return
        }

        if (SP.channelNum) {
            channelFragment.show(channel)
        }
    }

    fun play(tvViewModel: TVViewModel) {
        playerFragment.play(tvViewModel)
    }

    fun play(itemPosition: Int) {
        mainFragment.play(itemPosition)
    }

    fun prev() {
        mainFragment.prev()
    }

    fun next() {
        mainFragment.next()
    }

    fun switchMainFragment() {
        val transaction = supportFragmentManager.beginTransaction()

        if (mainFragment.isHidden) {
            mainFragment.setPosition()
            transaction.show(mainFragment)
            mainActive()
        } else {
            transaction.hide(mainFragment)
        }

        transaction.commit()
    }

    fun mainActive() {
        handler.removeCallbacks(hideMain)
        handler.postDelayed(hideMain, delayHideMain)
    }

    fun settingDelayHide() {
        handler.removeCallbacks(hideSetting)
        handler.postDelayed(hideSetting, delayHideSetting)
    }

    fun settingNeverHide() {
        handler.removeCallbacks(hideSetting)
    }

    private val hideMain = Runnable {
        if (!mainFragment.isHidden) {
            supportFragmentManager.beginTransaction().hide(mainFragment).commit()
        }
    }

    private fun mainFragmentIsHidden(): Boolean {
        return mainFragment.isHidden
    }

    private fun hideMainFragment() {
        if (!mainFragment.isHidden) {
            supportFragmentManager.beginTransaction()
                .hide(mainFragment)
                .commit()
        }
    }

    /**
     * Fragment 视图就绪（由各 Fragment 的 onCreateView 调用）。
     * 就绪个数只用于「凑齐后放行首屏」，不再绑定任何网络请求。
     */
    fun fragmentReady(tag: String) {
        fragmentReadyCount++
        Log.i(TAG, "ready $tag $fragmentReadyCount")
        tryStartPlayback()
    }

    /**
     * MainFragment 的频道列表已构建完毕。
     *
     * 启动闸门 = 所有 Fragment 视图就绪 + 频道列表就绪，**不含任何网络请求**。
     * 原实现把 Utils.init()（时间校准 + 央视频 JS 抓取，三个串行远端请求，
     * 真机实测阻塞 7.4s）也算作第 7 个就绪项，结果是「网络慢/断网 → 永不启播」，
     * 屏幕上只有时间组件和黑屏。现在远端初始化只做后台校准，不再决定能否起播。
     */
    fun onChannelListReady() {
        channelListReady = true
        tryStartPlayback()
    }

    private fun tryStartPlayback() {
        if (startupStarted) return
        if (!channelListReady) return
        if (fragmentReadyCount < startupFragmentCount) return
        startupStarted = true
        Log.i(TAG, "startup gate open (fragments=$fragmentReadyCount)")
        mainFragment.startup()
    }

    private fun showTime() {
        if (SP.time) {
            showFragment(timeFragment)
        } else {
            hideFragment(timeFragment)
        }
    }

    private fun showFragment(fragment: Fragment) {
        if (!fragment.isHidden) {
            return
        }

        supportFragmentManager.beginTransaction()
            .show(fragment)
            .commit()
    }

    private fun hideFragment(fragment: Fragment) {
        if (fragment.isHidden) {
            return
        }

        supportFragmentManager.beginTransaction()
            .hide(fragment)
            .commit()
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event != null) {
            gestureDetector.onTouchEvent(event)
        }
        return super.onTouchEvent(event)
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            Log.i(TAG, "onSingleTapConfirmed")
            switchMainFragment()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            showSetting()
            return true
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            if (velocityY > 0) {
                if (mainFragment.isHidden) {
                    prev()
                } else {
//                    if (mainFragment.selectedPosition == 0) {
//                        mainFragment.setSelectedPosition(
//                            mainFragment.tvListViewModel.maxNum.size - 1,
//                            false
//                        )
//                    }
                }
            }
            if (velocityY < 0) {
                if (mainFragment.isHidden) {
                    next()
                } else {
//                    if (mainFragment.selectedPosition == mainFragment.tvListViewModel.maxNum.size - 1) {
////                        mainFragment.setSelectedPosition(0, false)
//                        hideMainFragment()
//                        return false
//                    }
                }
            }
            return super.onFling(e1, e2, velocityX, velocityY)
        }
    }

    private fun showSetting() {
        if (!mainFragment.isHidden) {
            return
        }

        if (settingFragment.isVisible) {
            return
        }

        settingFragment.show(supportFragmentManager, TAG)
        settingDelayHide()
    }

    private val hideSetting = Runnable {
        if (settingFragment.isVisible) {
            settingFragment.dismiss()
        }
    }

    private fun hideSettingFragment() {
        handler.postDelayed(hideSetting, 0)
    }

    private fun channelUp() {
        if (mainFragment.isHidden) {
            if (SP.channelReversal) {
                next()
                return
            }
            prev()
        } else {
//                    if (mainFragment.selectedPosition == 0) {
//                        mainFragment.setSelectedPosition(
//                            mainFragment.tvListViewModel.maxNum.size - 1,
//                            false
//                        )
//                    }
        }
    }

    private fun channelDown() {
        if (mainFragment.isHidden) {
            if (SP.channelReversal) {
                prev()
                return
            }
            next()
        } else {
//                    if (mainFragment.selectedPosition == mainFragment.tvListViewModel.maxNum.size - 1) {
////                        mainFragment.setSelectedPosition(0, false)
//                        hideMainFragment()
//                        return false
//                    }
        }
    }

    private fun back() {
        if (!mainFragmentIsHidden()) {
            hideMainFragment()
            return
        }

        if (settingFragment.isVisible) {
            hideSettingFragment()
            return
        }

        if (doubleBackToExitPressedOnce) {
            super.onBackPressed()
            return
        }

        doubleBackToExitPressedOnce = true
        Toast.makeText(this, "再按一次退出", Toast.LENGTH_SHORT).show()

        Handler(Looper.getMainLooper()).postDelayed({
            doubleBackToExitPressedOnce = false
        }, 2000)
    }

    private fun active() {
        if (settingFragment.isVisible) {
            settingDelayHide()
        }
    }

    fun onKey(keyCode: Int): Boolean {
        Log.i(TAG, "keyCode $keyCode")
        when (keyCode) {
            KeyEvent.KEYCODE_0 -> {
                showChannel("0")
                return true
            }

            KeyEvent.KEYCODE_1 -> {
                showChannel("1")
                return true
            }

            KeyEvent.KEYCODE_2 -> {
                showChannel("2")
                return true
            }

            KeyEvent.KEYCODE_3 -> {
                showChannel("3")
                return true
            }

            KeyEvent.KEYCODE_4 -> {
                showChannel("4")
                return true
            }

            KeyEvent.KEYCODE_5 -> {
                showChannel("5")
                return true
            }

            KeyEvent.KEYCODE_6 -> {
                showChannel("6")
                return true
            }

            KeyEvent.KEYCODE_7 -> {
                showChannel("7")
                return true
            }

            KeyEvent.KEYCODE_8 -> {
                showChannel("8")
                return true
            }

            KeyEvent.KEYCODE_9 -> {
                showChannel("9")
                return true
            }

            KeyEvent.KEYCODE_ESCAPE -> {
                back()
                return true
            }

            KeyEvent.KEYCODE_BACK -> {
                back()
                return true
            }

            KeyEvent.KEYCODE_BOOKMARK -> {
                showSetting()
                return true
            }

            KeyEvent.KEYCODE_UNKNOWN -> {
//                showSetting()
                return true
            }

            KeyEvent.KEYCODE_HELP -> {
                showSetting()
                return true
            }

            KeyEvent.KEYCODE_SETTINGS -> {
                showSetting()
                return true
            }

            KeyEvent.KEYCODE_MENU -> {
                showSetting()
                return true
            }

            KeyEvent.KEYCODE_ENTER -> {
                active()
                switchMainFragment()
            }

            KeyEvent.KEYCODE_DPAD_CENTER -> {
                switchMainFragment()
            }

            KeyEvent.KEYCODE_DPAD_UP -> {
                active()
                channelUp()
            }

            KeyEvent.KEYCODE_CHANNEL_UP -> {
                channelUp()
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                active()
                channelDown()
            }

            KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                channelDown()
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
                active()
                if (mainFragment.isHidden && !settingFragment.isVisible) {
                    switchMainFragment()
                    return true
                }
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                active()
                if (mainFragment.isHidden && !settingFragment.isVisible) {
                    showSetting()
                    return true
                }
            }
        }
        return false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (onKey(keyCode)) {
            return true
        }

        return super.onKeyDown(keyCode, event)
    }

    private fun getAppSignature() = this.appSignature

    override fun onStart() {
        Log.i(TAG, "onStart MainActivity")
        super.onStart()
        // 时间校准 + 央视频初始化要串行访问 3 个远端接口，与播放无关。
        // 原来在 onStart 立即执行，实测占掉 7.4s 启动时间且断网时会拖到超时；
        // 改为起播之后在后台跑，失败也不影响播放。
        handler.removeCallbacks(syncTimeRunnable)
        handler.postDelayed(syncTimeRunnable, syncTimeDelay)
    }

    private val syncTimeRunnable = Runnable {
        Log.i(TAG, "deferred sync time")
        syncTime()
    }

    override fun onResume() {
        Log.i(TAG, "onResume")
        super.onResume()
    }

    override fun onResumeFragments() {
        super.onResumeFragments()
        mainFragment.changeMenu()

        if (!mainFragment.isHidden) {
            handler.postDelayed(hideMain, delayHideMain)
        }
    }

    override fun onPause() {
        Log.i(TAG, "onPause")
        super.onPause()
        handler.removeCallbacks(hideMain)
    }

    override fun onDestroy() {
        super.onDestroy()
        Request.onDestroy()
        networkCallback?.let {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
                ?.unregisterNetworkCallback(it)
        }
        networkCallback = null
    }

    override fun onRequestFinished(message: String?) {
        // 远端初始化结果不再计入启动闸门（见 onChannelListReady 注释），仅记录即可
        Log.i(TAG, "request finished: $message")
    }

    private companion object {
        const val TAG = "MainActivity"
    }

    override fun onSharedPreferenceChanged(key: String) {
        Log.i(TAG, "$key changed")
        when (key) {
            SP.KEY_GRID -> mainFragment.changeMenu()
            SP.KEY_TIME -> showTime()
        }
    }
}