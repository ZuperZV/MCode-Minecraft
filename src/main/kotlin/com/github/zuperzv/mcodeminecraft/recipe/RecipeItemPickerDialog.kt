package com.github.zuperzv.mcodeminecraft.recipe

import com.github.zuperzv.mcodeminecraft.assets.AssetCatalog
import com.github.zuperzv.mcodeminecraft.registry.RegistryIndexService
import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.ButtonGroup
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class RecipeItemPickerDialog(
    private val project: Project,
    private val catalog: AssetCatalog,
    private val iconProvider: RecipeIconProvider,
    private val mode: SelectionMode
) : DialogWrapper(true) {

    enum class SelectionMode {
        ITEM_ONLY,
        ITEM_OR_TAG
    }

    enum class EntryKind {
        ITEM,
        BLOCK,
        TAG
    }

    data class ItemEntry(
        val id: String,
        val displayName: String?,
        val kind: EntryKind,
        val searchKey: String
    )

    private var entries = buildEntries().toMutableList()
    private val listModel = DefaultListModel<ItemEntry>()
    private val list = object : JBList<ItemEntry>(listModel) {
        override fun getToolTipText(event: java.awt.event.MouseEvent): String? {
            val index = locationToIndex(event.point)
            if (index < 0 || index >= listModel.size) return null
            val entry = listModel.get(index)
            val name = entry.displayName ?: entry.id
            return "<html>$name<br>${entry.id}</html>"
        }
    }
    private val nameField = JBTextField()
    private val filterField = JBTextField()
    private val filterAll = JToggleButton("All")
    private val filterBlocks = JToggleButton("Blocks")
    private val filterItems = JToggleButton("Items")
    private val filterTags = JToggleButton("Tags")
    private var selection: IngredientSelection? = null

    init {
        title = "Block/item selector"
        list.emptyText.text = "Loading registry..."
        initList()
        initFilters()
        init()
        loadRegistryEntriesAsync()
    }

    fun getSelection(): IngredientSelection? = selection

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 8))
        panel.add(createHeaderPanel(), BorderLayout.NORTH)
        panel.add(createListPanel(), BorderLayout.CENTER)
        return panel
    }

    override fun createActions(): Array<javax.swing.Action> {
        val useSelected = object : DialogWrapperAction("Use selected") {
            override fun doAction(e: java.awt.event.ActionEvent?) {
                val entry = list.selectedValue ?: return
                selection = if (entry.kind == EntryKind.TAG) {
                    IngredientSelection.Tag(entry.id)
                } else {
                    IngredientSelection.Item(entry.id)
                }
                close(OK_EXIT_CODE)
            }
        }
        val useTag = object : DialogWrapperAction("Use tag") {
            override fun doAction(e: java.awt.event.ActionEvent?) {
                val entry = list.selectedValue ?: return
                if (entry.kind != EntryKind.TAG) return
                selection = IngredientSelection.Tag(entry.id)
                close(OK_EXIT_CODE)
            }
        }
        val clearSlot = object : DialogWrapperAction("Clear slot") {
            override fun doAction(e: java.awt.event.ActionEvent?) {
                selection = IngredientSelection.Clear
                close(OK_EXIT_CODE)
            }
        }
        val actions = ArrayList<javax.swing.Action>()
        actions.add(useSelected)
        if (mode == SelectionMode.ITEM_OR_TAG) {
            actions.add(useTag)
        }
        if (mode == SelectionMode.ITEM_OR_TAG) {
            actions.add(clearSlot)
        }
        actions.add(cancelAction)
        updateActionState(useSelected, useTag)
        list.addListSelectionListener {
            updateActionState(useSelected, useTag)
        }
        return actions.toTypedArray()
    }

    private fun updateActionState(useSelected: DialogWrapperAction, useTag: DialogWrapperAction) {
        val entry = list.selectedValue
        useSelected.isEnabled = entry != null && (mode == SelectionMode.ITEM_OR_TAG || entry.kind != EntryKind.TAG)
        useTag.isEnabled = mode == SelectionMode.ITEM_OR_TAG && entry?.kind == EntryKind.TAG
    }

    private fun initList() {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.layoutOrientation = JList.HORIZONTAL_WRAP
        list.visibleRowCount = -1
        list.fixedCellWidth = CELL_SIZE
        list.fixedCellHeight = CELL_SIZE
        list.cellRenderer = EntryRenderer(iconProvider)
        updateListModel(entries)
        list.addListSelectionListener {
            val entry = list.selectedValue
            nameField.text = entry?.displayName ?: entry?.id ?: ""
        }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2 || e.button != MouseEvent.BUTTON1) return
                val entry = list.selectedValue ?: return
                selection = if (entry.kind == EntryKind.TAG) {
                    IngredientSelection.Tag(entry.id)
                } else {
                    IngredientSelection.Item(entry.id)
                }
                close(OK_EXIT_CODE)
            }
        })
    }

    private fun initFilters() {
        val group = ButtonGroup()
        group.add(filterAll)
        group.add(filterBlocks)
        group.add(filterItems)
        group.add(filterTags)
        filterAll.isSelected = true

        val listener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = refresh()
            override fun removeUpdate(e: DocumentEvent?) = refresh()
            override fun changedUpdate(e: DocumentEvent?) = refresh()
        }
        filterField.document.addDocumentListener(listener)
        filterAll.addActionListener { refresh() }
        filterBlocks.addActionListener { refresh() }
        filterItems.addActionListener { refresh() }
        filterTags.addActionListener { refresh() }
    }

    private fun refresh() {
        val query = filterField.text.trim().lowercase()
        val filter = selectedFilter()
        val filtered = entries.filter { entry ->
            val kindMatches = when (filter) {
                EntryKind.BLOCK -> entry.kind == EntryKind.BLOCK
                EntryKind.ITEM -> entry.kind == EntryKind.ITEM
                EntryKind.TAG -> entry.kind == EntryKind.TAG
                else -> true
            }
            kindMatches && (query.isEmpty() || entry.searchKey.contains(query))
        }
        updateListModel(filtered)
        if (listModel.size == 0) {
            list.emptyText.text = "No entries found. Check RegistryIndexService logs."
        }
    }

    private fun selectedFilter(): EntryKind? {
        return when {
            filterBlocks.isSelected -> EntryKind.BLOCK
            filterItems.isSelected -> EntryKind.ITEM
            filterTags.isSelected -> EntryKind.TAG
            else -> null
        }
    }

    private fun updateListModel(items: List<ItemEntry>) {
        listModel.removeAllElements()
        items.forEach { listModel.addElement(it) }
    }

    private fun createHeaderPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(4, 4, 4, 4)
        }
        gbc.gridx = 0
        gbc.gridy = 0
        panel.add(JBLabel("Name:"), gbc)
        gbc.gridx = 1
        gbc.weightx = 1.0
        nameField.isEditable = false
        panel.add(nameField, gbc)

        gbc.gridx = 0
        gbc.gridy = 1
        gbc.weightx = 0.0
        panel.add(JBLabel("Display filter:"), gbc)
        gbc.gridx = 1
        gbc.weightx = 1.0
        panel.add(filterField, gbc)

        gbc.gridx = 2
        gbc.weightx = 0.0
        panel.add(createFilterButtons(), gbc)
        return panel
    }

    private fun createFilterButtons(): JComponent {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        panel.add(filterAll)
        panel.add(filterBlocks)
        panel.add(filterItems)
        if (mode == SelectionMode.ITEM_OR_TAG) {
            panel.add(filterTags)
        }
        return panel
    }

    private fun createListPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.minimumSize = Dimension(600, 400)
        panel.preferredSize = Dimension(860, 520)
        panel.add(JBScrollPane(list), BorderLayout.CENTER)
        return panel
    }

    private fun buildEntries(): List<ItemEntry> {
        val result = ArrayList<ItemEntry>()
        val registry = project.getService(RegistryIndexService::class.java).getIndexAsync()
            .takeIf { it.isDone && !it.isCompletedExceptionally }
            ?.getNow(null)
        if (registry == null) {
            result.addAll(buildEntriesFromCatalog())
            return result
        }
        val allItems = registry.items.sorted()
        val itemSet = allItems.toSet()
        val allBlocks = registry.blocks.sorted()
        if (allItems.isEmpty() && allBlocks.isEmpty()) {
            return buildEntriesFromCatalog()
        }
        for (id in allItems) {
            result.add(createEntry(id, EntryKind.ITEM))
        }
        for (id in allBlocks) {
            if (itemSet.contains(id)) continue
            result.add(createEntry(id, EntryKind.BLOCK))
        }
        if (mode == SelectionMode.ITEM_OR_TAG) {
            for (tag in catalog.allTags().sorted()) {
                val display = "#$tag"
                result.add(
                    ItemEntry(
                        id = tag,
                        displayName = display,
                        kind = EntryKind.TAG,
                        searchKey = "$display $tag".lowercase()
                    )
                )
            }
        }
        return result
    }

    private fun buildEntriesFromCatalog(): List<ItemEntry> {
        val result = ArrayList<ItemEntry>()
        val allItems = catalog.allItemIds().sorted()
        val itemSet = allItems.toSet()
        val allBlocks = catalog.allBlockIds().sorted()
        for (id in allItems) {
            result.add(createEntry(id, EntryKind.ITEM))
        }
        for (id in allBlocks) {
            if (itemSet.contains(id)) continue
            result.add(createEntry(id, EntryKind.BLOCK))
        }
        return result
    }

    private fun createEntry(id: String, kind: EntryKind): ItemEntry {
        val name = catalog.resolveDisplayName(id) ?: id
        return ItemEntry(
            id = id,
            displayName = name,
            kind = kind,
            searchKey = "$name $id".lowercase()
        )
    }

    private fun loadRegistryEntriesAsync() {
        val future = project.getService(RegistryIndexService::class.java).getIndexAsync()
        future.thenAccept { index ->
            if (index == null) return@thenAccept
            val registryEntries = buildEntries()
            SwingUtilities.invokeLater {
                entries = registryEntries.toMutableList()
                refresh()
                if (registryEntries.isNotEmpty()) {
                    list.emptyText.text = ""
                }
            }
        }
    }

    private class EntryRenderer(
        private val iconProvider: RecipeIconProvider
    ) : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            val entry = value as? ItemEntry
            val component = super.getListCellRendererComponent(list, "", index, isSelected, cellHasFocus)
            val icon = when (entry?.kind) {
                EntryKind.TAG -> AllIcons.Nodes.Tag
                EntryKind.ITEM, EntryKind.BLOCK ->
                    entry?.id?.let { iconProvider.getIcon(it) { list.repaint() } }
                else -> null
            }
            icon?.let { this.icon = it } ?: run { this.icon = null }
            horizontalAlignment = CENTER
            verticalAlignment = CENTER
            return component
        }
    }

    companion object {
        private const val CELL_SIZE = 48
    }
}
