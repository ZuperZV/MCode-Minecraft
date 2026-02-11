package com.github.zuperzv.mcodeminecraft.recipe

import com.github.zuperzv.mcodeminecraft.assets.AssetCatalog
import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class RecipeFluidPickerDialog(
    private val catalog: AssetCatalog,
    private val iconProvider: RecipeIconProvider
) : DialogWrapper(true) {

    data class FluidEntry(
        val id: String,
        val displayName: String,
        val searchKey: String,
        val iconId: String?
    )

    private val entries = buildEntries()
    private val listModel = DefaultListModel<FluidEntry>()
    private val list = object : JBList<FluidEntry>(listModel) {
        override fun getToolTipText(event: java.awt.event.MouseEvent): String? {
            val index = locationToIndex(event.point)
            if (index < 0 || index >= listModel.size) return null
            val entry = listModel.get(index)
            return "<html>${entry.displayName}<br>${entry.id}</html>"
        }
    }
    private val nameField = JBTextField()
    private val filterField = JBTextField()
    private var selection: IngredientSelection? = null

    init {
        title = "Fluid selector"
        initList()
        initFilters()
        init()
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
                selection = IngredientSelection.Fluid(entry.id)
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
        actions.add(clearSlot)
        actions.add(cancelAction)
        updateActionState(useSelected)
        list.addListSelectionListener { updateActionState(useSelected) }
        return actions.toTypedArray()
    }

    private fun updateActionState(useSelected: DialogWrapperAction) {
        useSelected.isEnabled = list.selectedValue != null
    }

    private fun initList() {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
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
                selection = IngredientSelection.Fluid(entry.id)
                close(OK_EXIT_CODE)
            }
        })
    }

    private fun initFilters() {
        val listener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = refresh()
            override fun removeUpdate(e: DocumentEvent?) = refresh()
            override fun changedUpdate(e: DocumentEvent?) = refresh()
        }
        filterField.document.addDocumentListener(listener)
    }

    private fun refresh() {
        val query = filterField.text.trim().lowercase()
        val filtered = entries.filter { entry ->
            query.isEmpty() || entry.searchKey.contains(query)
        }
        updateListModel(filtered)
    }

    private fun updateListModel(items: List<FluidEntry>) {
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
        panel.add(JBLabel("Filter:"), gbc)
        gbc.gridx = 1
        gbc.weightx = 1.0
        panel.add(filterField, gbc)
        return panel
    }

    private fun createListPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.minimumSize = Dimension(520, 360)
        panel.preferredSize = Dimension(760, 480)
        panel.add(JBScrollPane(list), BorderLayout.CENTER)
        return panel
    }

    private fun buildEntries(): List<FluidEntry> {
        val result = ArrayList<FluidEntry>()
        val allFluids = catalog.allFluidIds().sorted()
        for (id in allFluids) {
            val name = catalog.resolveDisplayName(id) ?: id
            val iconId = resolveFluidIconId(id)
            result.add(
                FluidEntry(
                    id = id,
                    displayName = name,
                    searchKey = "$name $id".lowercase(),
                    iconId = iconId
                )
            )
        }
        return result
    }

    private fun resolveFluidIconId(id: String): String? {
        return when {
            catalog.hasItem(id) -> id
            catalog.hasItem("${id}_bucket") -> "${id}_bucket"
            else -> null
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
            val entry = value as? FluidEntry
            val component = super.getListCellRendererComponent(list, "", index, isSelected, cellHasFocus)
            val icon = entry?.iconId?.let { iconProvider.getIcon(it) { list.repaint() } }
                ?: AllIcons.FileTypes.Unknown
            this.icon = icon
            horizontalAlignment = CENTER
            verticalAlignment = CENTER
            return component
        }
    }

    companion object {
        private const val CELL_SIZE = 48
    }
}
