package com.tungsten.fcl.ui.download.favorite

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.mio.data.FavoriteManager
import com.mio.data.favorite.DownloadFavoriteEntity
import com.tungsten.fcl.R
import com.tungsten.fcl.activity.MainActivity
import com.tungsten.fcl.databinding.PageDownloadFavoriteBinding
import com.tungsten.fcl.setting.Profiles
import com.tungsten.fcl.ui.download.DownloadUI
import com.tungsten.fcl.ui.download.common.DownloadPage
import com.tungsten.fclcore.download.LibraryAnalyzer
import com.tungsten.fclcore.mod.ModLoaderType
import com.tungsten.fclcore.mod.RemoteModRepository
import com.tungsten.fclcore.mod.curse.CurseForgeRemoteModRepository
import com.tungsten.fclcore.mod.modrinth.ModrinthRemoteModRepository
import com.tungsten.fclcore.task.Schedulers
import com.tungsten.fclcore.task.Task
import com.tungsten.fcllibrary.component.dialog.FCLAlertDialog
import com.tungsten.fcllibrary.component.theme.ThemeEngine
import com.tungsten.fcllibrary.component.ui.FCLPage
import com.tungsten.fcllibrary.component.ui.observeWhileAttached
import com.tungsten.fcllibrary.component.view.FCLTextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.stream.Collectors

class FavoritePage(
    context: Context,
    id: Int,
    page: DownloadPage,
) : FCLPage(context, id, R.layout.page_download_favorite) {

    private lateinit var binding: PageDownloadFavoriteBinding
    private val downloadPage: DownloadPage = page
    private lateinit var adapter: FavoriteAdapter
    private lateinit var allFavorites: List<DownloadFavoriteEntity>
    private var filterType: RemoteModRepository.Type? = null

    private var filterGroup: String? = null
    private lateinit var filterOptionRows: List<FilterOptionRow>
    private lateinit var groupOptionRows: List<FilterOptionRow>

    private var loading = false
    private var batchJob: Job? = null

    private data class FilterOptionRow(
        val root: ConstraintLayout,
        val label: FCLTextView,
        val count: FCLTextView,
        val type: RemoteModRepository.Type? = null,
        val groupId: String? = null,
    )

    override fun onCreate() {
        super.onCreate()
        binding = PageDownloadFavoriteBinding.bind(contentView)
        adapter = FavoriteAdapter(context, this)
        adapter.onMultiSelectStateChanged = { active -> updateMultiSelectBar(active) }
        binding.list.layoutManager = LinearLayoutManager(context)
        binding.list.adapter = adapter
        setupFilter(binding)
        binding.btnDownloadAll.setOnClickListener { startBatchDownload() }
        
        binding.btnSelectAll.setOnClickListener { adapter.selectAll() }
        binding.btnInvertSelect.setOnClickListener { adapter.invertSelect() }
        binding.btnExitSelect.setOnClickListener { adapter.exitMultiSelect() }
        binding.btnSelectGroup.setOnClickListener { selectGroupForSelected() }
        binding.btnRemoveSelected.setOnClickListener { confirmRemoveSelected() }

        ThemeEngine.getInstance().registerEvent(binding.emptyIcon) {
            binding.emptyIcon.setColorFilter(ThemeEngine.getInstance().getTheme().getColor2())
        }
        observeWhileAttached(FavoriteManager.favorites) { favorites ->
            allFavorites = favorites
            applyFilter()
        }
    }

    private fun updateMultiSelectBar(active: Boolean) {
        binding.multiSelectBar.visibility = if (active) View.VISIBLE else View.GONE
        binding.btnDownloadAll.visibility = if (active) View.GONE else View.VISIBLE
    }

    private fun selectGroupForSelected() {
        val ids = adapter.getSelectedIds()
        if (ids.isEmpty()) return
        val favorites = FavoriteManager.favorites.value.filter { it.id in ids }
        val common = FavoriteManager.groups.value.map { it.groupId }
            .filter { g -> favorites.all { g in it.groups } }
        GroupSelectionDialog(
            context,
            context.getString(R.string.favorite_group_edit),
            common.toSet()
        ) { groupIds ->
            MainActivity.getInstance().lifecycleScope.launch {
                FavoriteManager.setGroupsBulk(ids, groupIds)
                adapter.exitMultiSelect()
            }
        }.show()
    }

    private fun confirmRemoveSelected() {
        val ids = adapter.getSelectedIds()
        if (ids.isEmpty()) return
        FCLAlertDialog.Builder(context)
            .setAlertLevel(FCLAlertDialog.AlertLevel.INFO)
            .setMessage(context.getString(R.string.favorite_multi_remove_confirm, ids.size))
            .setPositiveButton {
                MainActivity.getInstance().lifecycleScope.launch {
                    FavoriteManager.removeFavorites(ids)
                    adapter.exitMultiSelect()
                }
            }
            .setNegativeButton(null)
            .create()
            .show()
    }

    private fun setupFilter(binding: PageDownloadFavoriteBinding) {
        val options = listOf(
            context.getString(R.string.favorite_filter_all) to null,
            context.getString(R.string.mods) to RemoteModRepository.Type.MOD,
            context.getString(R.string.modpack) to RemoteModRepository.Type.MODPACK,
            context.getString(R.string.resourcepack) to RemoteModRepository.Type.RESOURCE_PACK,
            context.getString(R.string.shaderpack) to RemoteModRepository.Type.SHADER_PACK,
            context.getString(R.string.world) to RemoteModRepository.Type.WORLD,
        )
        val rows = options.map { (label, type) ->
            buildFilterRow(label, type) {
                if (filterType != type) {
                    filterType = type
                    applyFilter()
                }
            }
        }
        filterOptionRows = rows
        rebuildGroupRows(binding)
        binding.btnManageGroups.setOnClickListener { GroupManageDialog(context).show() }
        rows.forEach {
            ThemeEngine.getInstance().registerEvent(it.root) { refreshFilterOptionStates() }
            binding.filterOptions.addView(it.root)
        }
        applyPanelBackground()
        ThemeEngine.getInstance().registerEvent(binding.filterPanel) { applyPanelBackground() }
        observeWhileAttached(FavoriteManager.groups) { groups ->
            if (filterGroup != null && groups.none { it.groupId == filterGroup }) {
                filterGroup = null
            }
            rebuildGroupRows(binding)
            applyFilter()
        }
    }

    private fun rebuildGroupRows(binding: PageDownloadFavoriteBinding) {
        binding.groupOptions.removeAllViews()
        val options: List<Pair<String, String?>> =
            listOf(context.getString(R.string.favorite_filter_all) to null) +
                    FavoriteManager.groups.value.map { it.name to it.groupId }
        val rows = options.map { (label, groupId) ->
            buildFilterRow(label, groupId = groupId) {
                if (filterGroup != groupId) {
                    filterGroup = groupId
                    applyFilter()
                }
            }
        }
        groupOptionRows = rows
        groupOptionRows.forEach {
            ThemeEngine.getInstance().registerEvent(it.root) { refreshFilterOptionStates() }
            binding.groupOptions.addView(it.root)
        }
        refreshFilterOptionStates()
    }

    private fun buildFilterRow(
        label: String,
        type: RemoteModRepository.Type? = null,
        groupId: String? = null,
        onClick: () -> Unit,
    ): FilterOptionRow {
        val density = context.resources.displayMetrics.density
        val row = ConstraintLayout(context).apply {
            setPadding(
                (density * 10).toInt(), (density * 8).toInt(),
                (density * 10).toInt(), (density * 8).toInt()
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (density * 5).toInt() }
            setOnClickListener { onClick() }
        }
        val labelView = FCLTextView(context).apply {
            text = label
            textSize = 14f
            isAutoTint = true
            layoutParams = ConstraintLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            }
        }
        val countView = FCLTextView(context).apply {
            textSize = 12f
            isAutoTint = true
            layoutParams = ConstraintLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                marginStart = (density * 8).toInt()
            }
        }
        row.addView(labelView)
        row.addView(countView)
        return FilterOptionRow(row, labelView, countView, type, groupId)
    }

    private fun refreshFilterOptionStates() {
        val countsReady = ::allFavorites.isInitialized
        filterOptionRows.forEach { row ->
            styleRow(row, row.type == filterType, if (countsReady) countForType(row.type) else 0, countsReady)
        }
        groupOptionRows.forEach { row ->
            styleRow(row, row.groupId == filterGroup, if (countsReady) countForGroup(row.groupId) else 0, countsReady)
        }
    }

    private fun styleRow(row: FilterOptionRow, selected: Boolean, count: Int, countsReady: Boolean) {
        val theme = ThemeEngine.getInstance().getTheme()
        val density = context.resources.displayMetrics.density
        val contentColor = theme.autoTint
        val pill = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = density * 10
            setColor(if (selected) theme.getColor() else Color.TRANSPARENT)
        }
        row.root.background = RippleDrawable(
            ColorStateList.valueOf(ColorUtils.setAlphaComponent(theme.autoTint, 40)),
            pill,
            null
        )
        row.label.setTextColor(contentColor)
        row.label.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        row.count.setTextColor(ColorUtils.setAlphaComponent(contentColor, if (selected) 230 else 170))
        row.count.text = count.toString()
        row.count.visibility = if (countsReady && count > 0) View.VISIBLE else View.GONE
    }

    private fun countForType(type: RemoteModRepository.Type?): Int = when (type) {
        null -> allFavorites.count { matchesGroup(it) }
        else -> allFavorites.count { it.type == type.name && matchesGroup(it) }
    }

    private fun countForGroup(groupId: String?): Int = when (groupId) {
        null -> allFavorites.count { matchesType(it) }
        else -> allFavorites.count { groupId in it.groups && matchesType(it) }
    }

    private fun matchesType(favorite: DownloadFavoriteEntity): Boolean {
        val type = filterType
        return type == null || favorite.type == type.name
    }

    private fun matchesGroup(favorite: DownloadFavoriteEntity): Boolean {
        val group = filterGroup
        return group == null || group in favorite.groups
    }

    private fun currentFiltered(): List<DownloadFavoriteEntity> =
        if (!::allFavorites.isInitialized) emptyList()
        else allFavorites.filter { matchesType(it) && matchesGroup(it) }

    private fun applyPanelBackground() {
        binding.filterPanel.setBackgroundResource(R.drawable.bg_container_white)
        binding.filterPanel.backgroundTintList = ColorStateList.valueOf(ThemeEngine.getInstance().getTheme().ltColor)
    }

    private fun applyFilter() {
        refreshFilterOptionStates()
        val filtered = currentFiltered()
        adapter.submit(filtered)
        binding.emptyView.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    fun openFavorite(favorite: DownloadFavoriteEntity) {
        if (loading) return
        val type = try {
            RemoteModRepository.Type.valueOf(favorite.type)
        } catch (_: IllegalArgumentException) {
            RemoteModRepository.Type.MOD
        }
        val pageId = pageIdForType(type)
        if (!downloadPage.isTypeReady(pageId)) {
            downloadPage.switchType(pageId)
        }
        loading = true
        binding.progress.visibility = View.VISIBLE
        Task.supplyAsync { repositoryFor(favorite).getModById(favorite.modId) }
            .whenComplete(Schedulers.androidUIThread()) { mod, exception ->
                loading = false
                binding.progress.visibility = View.GONE
                if (exception == null && mod != null) {
                    downloadPage.openModDetail(mod)
                } else {
                    Toast.makeText(context, context.getString(R.string.favorite_open_failed), Toast.LENGTH_SHORT).show()
                }
            }.start()
    }

    private fun startBatchDownload() {
        if (loading) {
            Toast.makeText(context, context.getString(R.string.favorite_operation_in_progress), Toast.LENGTH_SHORT).show()
            return
        }
        val profile = Profiles.getSelectedProfile()
        val selected = profile.selectedVersion
        if (selected == null) {
            Toast.makeText(context, context.getString(R.string.favorite_batch_no_version), Toast.LENGTH_SHORT).show()
            return
        }
        val mods = currentFiltered().filter { it.type == RemoteModRepository.Type.MOD.name }
        if (mods.isEmpty()) {
            Toast.makeText(context, context.getString(R.string.favorite_batch_no_mods), Toast.LENGTH_SHORT).show()
            return
        }
        loading = true
        val dialog = FavoriteBatchDialog(context)
        dialog.setOnDismissListener { batchJob?.cancel() }
        dialog.showParsing()
        dialog.show()
        batchJob = MainActivity.getInstance().lifecycleScope.launch {
            try {
                val (entries, skipped) = withContext(Dispatchers.IO) {
                    val analyzer = LibraryAnalyzer.analyze(
                        profile.repository.getResolvedPreservingPatchesVersion(selected),
                        selected
                    )
                    val mcv = analyzer.getVersion(LibraryAnalyzer.LibraryType.MINECRAFT).orElse("")
                    resolveBatchEntries(mods, mcv, analyzer.modLoaders)
                }
                if (entries.isEmpty()) {
                    dialog.dismiss()
                    Toast.makeText(context, context.getString(R.string.favorite_batch_none_matched), Toast.LENGTH_SHORT).show()
                    return@launch
                }
                dialog.showResult(entries, skipped) { selectedVersions ->
                    downloadPage.downloadModsBatch(context, profile, selected, selectedVersions, "mods") { queued, installedSkipped, failed ->
                        when {
                            queued > 0 -> Toast.makeText(context, context.getString(R.string.favorite_batch_queued, queued), Toast.LENGTH_SHORT).show()
                            installedSkipped > 0 && failed == 0 -> Toast.makeText(context, context.getString(R.string.mods_already_installed), Toast.LENGTH_SHORT).show()
                            else -> Toast.makeText(context, context.getString(R.string.favorite_batch_failed), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (_: CancellationException) {
            } catch (_: Exception) {
                if (dialog.isShowing) dialog.dismiss()
                Toast.makeText(context, context.getString(R.string.favorite_batch_failed), Toast.LENGTH_SHORT).show()
            } finally {
                loading = false
            }
        }
    }

    private suspend fun resolveBatchEntries(
        mods: List<DownloadFavoriteEntity>,
        mcv: String,
        loaders: Set<ModLoaderType>,
    ): Pair<List<FavoriteBatchDialog.Entry>, List<String>> {
        val entries = mutableListOf<FavoriteBatchDialog.Entry>()
        val skipped = mutableListOf<String>()
        for (favorite in mods) {
            currentCoroutineContext().ensureActive()
            try {
                val repo = repositoryFor(favorite)
                val mod = repo.getModById(favorite.modId)
                val versions = mod.data.loadVersions(repo).collect(Collectors.toList())
                val match = versions
                    .filter { v -> v.gameVersions.contains(mcv) }
                    .filter { v -> loaders.isEmpty() || v.loaders.any { it in loaders } }
                    .maxByOrNull { v -> v.datePublished?.toEpochMilli() ?: 0L }
                if (match != null) {
                    entries.add(FavoriteBatchDialog.Entry(favorite.title, match))
                } else {
                    skipped.add(favorite.title)
                }
            } catch (_: Exception) {
                skipped.add(favorite.title)
            }
        }
        return entries to skipped
    }

    private fun repositoryFor(favorite: DownloadFavoriteEntity): RemoteModRepository =
        if (favorite.source == FavoriteManager.SOURCE_CURSEFORGE) CurseForgeRemoteModRepository.MODS
        else ModrinthRemoteModRepository.MODS

    private fun pageIdForType(type: RemoteModRepository.Type): Int = when (type) {
        RemoteModRepository.Type.MODPACK -> DownloadUI.PAGE_ID_DOWNLOAD_MODPACK
        RemoteModRepository.Type.RESOURCE_PACK -> DownloadUI.PAGE_ID_DOWNLOAD_RESOURCE_PACK
        RemoteModRepository.Type.SHADER_PACK -> DownloadUI.PAGE_ID_DOWNLOAD_SHADER_PACK
        RemoteModRepository.Type.WORLD -> DownloadUI.PAGE_ID_DOWNLOAD_WORLD
        else -> DownloadUI.PAGE_ID_DOWNLOAD_MOD
    }
}
