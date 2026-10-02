package com.lizongying.mytv

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.marginEnd
import androidx.core.view.marginTop
import androidx.fragment.app.DialogFragment
import com.lizongying.mytv.api.YSP
import com.lizongying.mytv.databinding.SettingBinding


class SettingFragment : DialogFragment() {

    private var _binding: SettingBinding? = null
    private val binding get() = _binding!!

    private lateinit var updateManager: UpdateManager

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                setAttributes(attributes)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, 0)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext() // It‘s safe to get context here.
        _binding = SettingBinding.inflate(inflater, container, false)
        binding.versionName.text = "当前版本: v${context.appVersionName}"
        // 展示当前生效的在线直播源配置地址（若外部放置 tvlist.remote 则显示该地址）
        binding.version.text = "直播源: ${TVSource.remoteUrl(context)}"

        binding.switchChannelReversal.run {
            isChecked = SP.channelReversal
            setOnCheckedChangeListener { _, isChecked ->
                SP.channelReversal = isChecked
                (activity as MainActivity).settingDelayHide()
            }
        }

        binding.switchChannelNum.run {
            isChecked = SP.channelNum
            setOnCheckedChangeListener { _, isChecked ->
                SP.channelNum = isChecked
                (activity as MainActivity).settingDelayHide()
            }
        }

        binding.switchTime.run {
            isChecked = SP.time
            setOnCheckedChangeListener { _, isChecked ->
                SP.time = isChecked
                (activity as MainActivity).settingDelayHide()
            }
        }

        binding.switchBootStartup.run {
            isChecked = SP.bootStartup
            setOnCheckedChangeListener { _, isChecked ->
                SP.bootStartup = isChecked
                // 开关与系统无障碍授权双向联动（需 adb 授予 WRITE_SECURE_SETTINGS，
                // 未授予时静默降级，退回 adb 手动 settings put 的老路径）
                if (isChecked) {
                    activity?.let { BootA11y.rearmIfMissing(it) }
                } else {
                    activity?.let { BootA11y.disarm(it) }
                }
                refreshBootStatus()
                (activity as MainActivity).settingDelayHide()
            }
        }

        // 一键修复：自启动开关置开后强制补齐系统侧授权条目 + 无障碍总开关。
        // 把原先「授权缺失但界面无任何提示」的静默失败变成可自助恢复。
        binding.bootFix.setOnClickListener {
            SP.bootStartup = true
            if (!binding.switchBootStartup.isChecked) {
                // 赋值会同步触发上面的 listener（内部即调用 rearmIfMissing）
                binding.switchBootStartup.isChecked = true
            } else {
                activity?.let { BootA11y.arm(it) }
            }
            refreshBootStatus()
            val st = BootA11y.status(context)
            Toast.makeText(
                context,
                if (st.armed) "开机自启授权已就绪" else "修复失败：需 adb 授予 WRITE_SECURE_SETTINGS",
                Toast.LENGTH_LONG
            ).show()
            (activity as MainActivity).settingDelayHide()
        }

        // 开机直达桌面（HOME 直通模式）：把本应用设为系统默认桌面后，STR 待机
        // 唤醒时系统必须启动桌面=本应用，Activity 启动不受 stopped 态广播排除
        // 限制，从结构上绕开 MiTV「睡眠强杀 + 唤醒无广播」死结（报告 8.8 节）。
        // 仅跳转系统「默认桌面」设置由用户显式选择，应用不静默抢占桌面。
        //
        // 2026-10-02 真机修正：小米电视把 android.settings.HOME_SETTINGS 与
        // MANAGE_DEFAULT_APPS_SETTINGS 一并指向空壳 com.android.tv.settings/
        // .EmptyStubActivity —— 跳过去只会把应用切到后台（表现为「播放暂停」）
        // 却什么界面都不显示。因此先探测是否真有设置界面，没有就不跳转、直接
        // 说明本机不支持（绝不为了一个空操作把正在播的直播打断）。
        binding.bootHomeFix.setOnClickListener {
            val ctx = context
            if (ctx == null) {
                (activity as MainActivity).settingDelayHide()
                return@setOnClickListener
            }
            when {
                isDefaultHome(ctx) ->
                    Toast.makeText(ctx, R.string.boot_home_already, Toast.LENGTH_SHORT).show()

                !homeSettingsAvailable(ctx) ->
                    Toast.makeText(ctx, R.string.boot_home_unsupported, Toast.LENGTH_LONG).show()

                else -> runCatching { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
                    .onFailure {
                        Toast.makeText(
                            ctx,
                            "未找到桌面设置，可用 adb：cmd package set-home-activity com.fshby.mytv/com.lizongying.mytv.MainActivity",
                            Toast.LENGTH_LONG
                        ).show()
                    }
            }
            refreshBootStatus()
            (activity as MainActivity).settingDelayHide()
        }

        binding.switchGrid.run {
            isChecked = SP.grid
            setOnCheckedChangeListener { _, isChecked ->
                SP.grid = isChecked
                (activity as MainActivity).settingDelayHide()
            }
        }

        binding.checkVersion.setOnClickListener {
            (activity as MainActivity).settingDelayHide()
            requestInstallPermissions()
        }

        binding.clear.setOnClickListener {
            (requireActivity() as MainActivity).syncTime()
            SP.guid = ""
            YSP.getGuid()
        }

        val application = requireActivity().applicationContext as MyTVApplication
        val textSize = application.px2PxFont(binding.switchChannelReversal.textSize)

        binding.content.layoutParams.width =
            application.px2Px(binding.content.layoutParams.width)
        binding.content.setPadding(
            application.px2Px(binding.content.paddingLeft),
            application.px2Px(binding.content.paddingTop),
            application.px2Px(binding.content.paddingRight),
            application.px2Px(binding.content.paddingBottom)
        )
        binding.name.textSize = application.px2PxFont(binding.name.textSize)
        binding.version.textSize = textSize
        val layoutParamsVersion = binding.version.layoutParams as ViewGroup.MarginLayoutParams
        layoutParamsVersion.topMargin = application.px2Px(binding.version.marginTop)
        binding.version.layoutParams = layoutParamsVersion

        binding.checkVersion.textSize = textSize
        val layoutParamsCheckVersion =
            binding.checkVersion.layoutParams as ViewGroup.MarginLayoutParams
        layoutParamsCheckVersion.marginEnd = application.px2Px(binding.checkVersion.marginEnd)
        binding.checkVersion.layoutParams = layoutParamsCheckVersion

        binding.versionName.textSize = textSize

        binding.clear.textSize = textSize
        binding.exit.textSize = textSize

        val layoutParamsChannelSwitch =
            binding.switchChannelReversal.layoutParams as ViewGroup.MarginLayoutParams
        layoutParamsChannelSwitch.topMargin =
            application.px2Px(binding.switchChannelReversal.marginTop)

        binding.switchChannelReversal.textSize = textSize
        binding.switchChannelReversal.layoutParams = layoutParamsChannelSwitch

        binding.switchChannelNum.textSize = textSize
        binding.switchChannelNum.layoutParams = layoutParamsChannelSwitch

        binding.switchTime.textSize = textSize
        binding.switchTime.layoutParams = layoutParamsChannelSwitch

        binding.switchBootStartup.textSize = textSize
        binding.switchBootStartup.layoutParams = layoutParamsChannelSwitch

        binding.bootStatus.textSize = application.px2PxFont(binding.bootStatus.textSize)
        binding.bootFix.textSize = textSize
        refreshBootStatus()

        binding.bootHomeStatus.textSize =
            application.px2PxFont(binding.bootHomeStatus.textSize)
        binding.bootHomeFix.textSize = textSize
        binding.bootHomeHint.textSize = application.px2PxFont(binding.bootHomeHint.textSize)

        binding.switchGrid.textSize = textSize
        binding.switchGrid.layoutParams = layoutParamsChannelSwitch

        binding.appreciate.layoutParams.width =
            application.px2Px(binding.appreciate.layoutParams.width)

        binding.exit.setOnClickListener {
            requireActivity().finishAffinity()
        }

//        val myViewModel = application.myViewModel
//        application.myViewModel.downloadProgress.observe(viewLifecycleOwner) { _ ->
//            val downloadProgress = myViewModel.downloadProgress.value
//            if (downloadProgress != null) {
//                if (downloadProgress == 100) {
//                    binding.progressBar.visibility = GONE
//                } else {
//                    if (!binding.progressBar.isVisible) {
//                        binding.progressBar.visibility = VISIBLE
//                    }
//                    binding.progressBar.progress = downloadProgress
//                }
//            }
//        }

        updateManager = UpdateManager(context, context.appVersionCode)

        return binding.root
    }

    /**
     * 刷新「开机自启状态」四项自检：应用内开关 / 系统授权条目 / 无障碍总开关 / 写权限。
     * 原先这四项之外的任何一项出问题都表现为「开机没反应且无任何提示」，
     * 这里把它们显式暴露出来，让用户能自查。
     */
    private fun refreshBootStatus() {
        val ctx = context ?: return
        val b = _binding ?: return
        val st = BootA11y.status(ctx)
        b.bootStatus.text = getString(R.string.boot_status_prefix) + buildString {
            append("开关").append(if (st.switchOn) "开" else "关")
            append(" · 系统授权").append(if (st.entryPresent) "有" else "无")
            append(" · 无障碍总开关").append(if (st.masterOn) "开" else "关")
            append(" · ").append(if (st.writable) "可自愈" else "无写权限")
            if (st.switchOn && !st.armed) append("（未就绪，可点下方修复）")
        }
        // HOME 直通模式状态：显示当前默认桌面；本机若无该设置界面则标记不可用
        val isOurs = isDefaultHome(ctx)
        val home = currentHomePackage(ctx) ?: "未知"
        b.bootHomeStatus.text = getString(R.string.boot_home_status_prefix) + when {
            isOurs -> "已启用（本应用即桌面，唤醒必达）"
            !homeSettingsAvailable(ctx) -> "本机不可用（系统固定使用 $home）"
            else -> "未启用（当前桌面 $home）"
        }
        b.bootHomeHint.text = getString(
            if (homeSettingsAvailable(ctx)) R.string.boot_home_hint
            else R.string.boot_home_hint_unsupported
        )
    }

    private fun isDefaultHome(ctx: android.content.Context): Boolean =
        currentHomePackage(ctx) == ctx.packageName

    private fun currentHomePackage(ctx: android.content.Context): String? = try {
        ctx.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo?.packageName
    } catch (e: Exception) {
        null
    }

    /**
     * 系统是否真的提供「默认桌面」设置界面。小米电视把 HOME_SETTINGS /
     * MANAGE_DEFAULT_APPS_SETTINGS 都指向 com.android.tv.settings.EmptyStubActivity
     * （空壳，无 UI），跳转只会让应用切后台、中断播放，因此必须先探测。
     */
    private fun homeSettingsAvailable(ctx: android.content.Context): Boolean {
        val resolved = try {
            ctx.packageManager.resolveActivity(
                Intent(Settings.ACTION_HOME_SETTINGS),
                PackageManager.MATCH_DEFAULT_ONLY
            )
        } catch (e: Exception) {
            null
        }
        val name = resolved?.activityInfo?.name ?: return false
        return !name.contains("Stub", ignoreCase = true)
    }

    private fun requestInstallPermissions() {
        val context = requireContext()
        val permissionsList: MutableList<String> = ArrayList()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            permissionsList.add(Manifest.permission.REQUEST_INSTALL_PACKAGES)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsList.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsList.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        if (permissionsList.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                requireActivity(),
                permissionsList.toTypedArray<String>(),
                PERMISSIONS_REQUEST_CODE
            )
        } else {
            updateManager.checkAndUpdate()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            var allPermissionsGranted = true
            for (result in grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allPermissionsGranted = false
                    break
                }
            }
            if (allPermissionsGranted) {
                updateManager.checkAndUpdate()
            } else {
                Toast.makeText(context, "权限授权失败", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "SettingFragment"
        const val PERMISSIONS_REQUEST_CODE = 1
    }
}

