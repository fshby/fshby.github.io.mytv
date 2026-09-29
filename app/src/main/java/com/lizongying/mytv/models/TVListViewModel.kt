package com.lizongying.mytv.models

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.lizongying.mytv.SP

class TVListViewModel : ViewModel() {

    var maxNum = mutableListOf<Int>()

    private val _tvListViewModel = MutableLiveData<MutableList<TVViewModel>>()
    val tvListViewModel: LiveData<MutableList<TVViewModel>>
        get() = _tvListViewModel

    private val _itemPosition = MutableLiveData<Int>()
    val itemPosition: LiveData<Int>
        get() = _itemPosition

    fun addTVViewModel(tvViewModel: TVViewModel) {
        if (_tvListViewModel.value == null) {
            _tvListViewModel.value = mutableListOf(tvViewModel)
        } else {
            _tvListViewModel.value?.add(tvViewModel)
        }
    }

    /**
     * 按下标取频道。列表重建（远程列表变短）或下标越界时返回 null，
     * 不要直接 list[id]——那会抛 IndexOutOfBoundsException 导致换台闪退。
     */
    fun getTVViewModel(id: Int): TVViewModel? {
        val list = _tvListViewModel.value ?: return null
        if (id < 0 || id >= list.size) {
            return null
        }
        return list[id]
    }

    fun setItemPosition(position: Int) {
        _itemPosition.value = position
        SP.itemPosition = position
    }

    fun size(): Int {
        if (_tvListViewModel.value == null) {
            return 0
        }

        return _tvListViewModel.value!!.size
    }
}