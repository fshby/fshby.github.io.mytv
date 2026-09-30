package com.lizongying.mytv

import android.content.res.Resources
import android.os.Bundle
import android.os.Handler
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.marginEnd
import androidx.core.view.marginTop
import androidx.fragment.app.Fragment
import com.lizongying.mytv.databinding.ChannelBinding
import com.lizongying.mytv.models.TVViewModel

class ChannelFragment : Fragment() {
    private var _binding: ChannelBinding? = null
    private val binding get() = _binding!!

    private val handler = Handler()
    private val delay: Long = 5000
    private var channel = 0
    private var channelCount = 0

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ChannelBinding.inflate(inflater, container, false)
        binding.root.visibility = View.GONE

        val application = requireActivity().applicationContext as MyTVApplication

        binding.channel.layoutParams.width = application.px2Px(binding.channel.layoutParams.width)
        binding.channel.layoutParams.height = application.px2Px(binding.channel.layoutParams.height)

        val layoutParams = binding.channel.layoutParams as ViewGroup.MarginLayoutParams
        layoutParams.topMargin = application.px2Px(binding.channel.marginTop)
        layoutParams.marginEnd = application.px2Px(binding.channel.marginEnd)
        binding.channel.layoutParams = layoutParams

        binding.content.textSize = application.px2PxFont(binding.content.textSize)
        binding.time.textSize = application.px2PxFont(binding.time.textSize)

        binding.main.layoutParams.width = application.shouldWidthPx()
        binding.main.layoutParams.height = application.shouldHeightPx()

        (activity as? MainActivity)?.fragmentReady(TAG)
        return binding.root
    }

    fun show(tvViewModel: TVViewModel) {
        val b = _binding ?: return
        handler.removeCallbacks(hideRunnable)
        handler.removeCallbacks(playRunnable)
        b.content.text = (tvViewModel.getTV().id.plus(1)).toString()
        view?.visibility = View.VISIBLE
        handler.postDelayed(hideRunnable, delay)
    }

    fun show(channel: String) {
        if (channelCount > 1) {
            return
        }
        val b = _binding ?: return
        val next = "${this.channel}$channel".toIntOrNull()
        if (next == null) {
            // 非数字输入：丢弃这次缓冲，避免 toInt() 抛 NumberFormatException
            this.channel = 0
            channelCount = 0
            return
        }
        channelCount++
        this.channel = next
        handler.removeCallbacks(hideRunnable)
        handler.removeCallbacks(playRunnable)
        if (channelCount < 2) {
            b.content.text = "${this.channel}"
            view?.visibility = View.VISIBLE
            handler.postDelayed(playRunnable, delay)
        } else {
            handler.postDelayed(playRunnable, 0)
        }
    }

    override fun onResume() {
        super.onResume()
        if (view?.visibility == View.VISIBLE) {
            handler.postDelayed(hideRunnable, delay)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(hideRunnable)
        handler.removeCallbacks(playRunnable)
    }

    private val hideRunnable = Runnable {
        _binding?.let { it.content.text = "" }
        view?.visibility = View.GONE
        channel = 0
        channelCount = 0
    }

    private val playRunnable = Runnable {
        val index = channel - 1
        val act = activity as? MainActivity
        _binding?.let { it.content.text = "" }
        view?.visibility = View.GONE
        channel = 0
        channelCount = 0
        act?.play(index)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // handler 不属于视图生命周期，延迟任务可能在 onDestroyView 之后才到，
        // 那时 binding 已被置空。这里主动摘除回调，避免对已销毁视图取值而闪退。
        handler.removeCallbacks(hideRunnable)
        handler.removeCallbacks(playRunnable)
        _binding = null
    }

    companion object {
        private const val TAG = "ChannelFragment"
    }
}