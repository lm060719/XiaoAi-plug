package io.mo.xiaoaiplug.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.mo.xiaoaiplug.config.MemoryEntry
import io.mo.xiaoaiplug.config.MemoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MemoryViewModel(app: Application) : AndroidViewModel(app) {

    private val _entries = MutableStateFlow<List<MemoryEntry>>(emptyList())
    val entries: StateFlow<List<MemoryEntry>> = _entries.asStateFlow()

    /** 上一次写入的失败原因(记忆满了之类),null = 没有。 */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val store get() = MemoryStore.get(getApplication())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _entries.value = withContext(Dispatchers.IO) { store.all() }
        }
    }

    /** 手动加一条。加完要 refresh —— 新行的 id 是数据库给的,本地编不出来。 */
    fun add(content: String) {
        if (content.isBlank()) return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                store.append(content, MemoryEntry.SOURCE_MANUAL)
            }
            // append 的失败约定是 "error:" 前缀(给模型看的),界面上把前缀去掉再显示
            _error.value = if (result.startsWith("error:")) result.removePrefix("error:").trim() else null
            refresh()
        }
    }

    /** 改一条。先动内存再落库,免得输入框失焦到列表刷新之间闪一下旧文字。 */
    fun edit(id: Long, content: String) {
        if (content.isBlank()) return
        _entries.value = _entries.value.map {
            if (it.id == id) it.copy(content = content.trim(), source = MemoryEntry.SOURCE_MANUAL) else it
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.update(id, content) }
        }
    }

    /** 理由同 LogsViewModel.delete:先摘掉再落库,且删完故意不 refresh,免得打断移除动画。 */
    fun delete(id: Long) {
        _entries.value = _entries.value.filterNot { it.id == id }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.delete(id) }
        }
    }

    fun clear() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.clear() }
            refresh()
        }
    }

    fun dismissError() {
        _error.value = null
    }
}
