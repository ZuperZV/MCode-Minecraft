package com.github.zuperzv.mcodeminecraft.recipe

import com.github.zuperzv.mcodeminecraft.assets.AssetCatalog
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.util.Locale
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.SwingUtilities
import javax.swing.plaf.basic.BasicSplitPaneDivider
import javax.swing.plaf.basic.BasicSplitPaneUI

class RecipeViewerView(private val project: Project) : JBPanel<RecipeViewerView>(BorderLayout()) {
    private var catalog: AssetCatalog? = null
    private var itemPreviewProvider: RecipeItemPreviewProvider? = null
    private var iconProvider: RecipeIconProvider? = null
    private var document: RecipeDocument? = null

    private val statusLabel = JBLabel("Indexing assets...")
    private val typeLabel = JBLabel("")
    private val previewPanel = JPanel(BorderLayout())
    private val detailsPanel = JPanel(BorderLayout())
    private val detailsList = JPanel()
    private val missingLabel = JBLabel("")

    private val viewerContainer = object : JPanel(BorderLayout()) {
        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val arc = JBUI.scale(52)
                val inset = JBUI.scale(20)
                val rectWidth = width - inset * 2
                val rectHeight = height - inset * 2
                val clipShape = RoundRectangle2D.Float(
                    inset.toFloat(),
                    inset.toFloat(),
                    rectWidth.toFloat(),
                    rectHeight.toFloat(),
                    arc.toFloat(),
                    arc.toFloat()
                )
                g2.color = SELECTED_BACKGROUND_COLOR
                g2.fill(clipShape)
                g2.clip = clipShape
                super.paintComponent(g)
                g2.clip = null
                g2.color = JBColor.border()
                g2.draw(clipShape)
            } finally {
                g2.dispose()
            }
        }
    }

    init {
        previewPanel.isOpaque = false
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(6, 10, 4, 10)
            add(typeLabel, BorderLayout.WEST)
        }
        typeLabel.foreground = JBColor.GRAY

        val viewerContent = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(header, BorderLayout.NORTH)
            add(previewPanel, BorderLayout.CENTER)
        }

        viewerContainer.isOpaque = false
        viewerContainer.border = JBUI.Borders.empty(20 + 1, 20 + 1, 20 + 14, 20 + 1)
        viewerContainer.add(viewerContent, BorderLayout.CENTER)

        detailsList.layout = BoxLayout(detailsList, BoxLayout.Y_AXIS)
        detailsList.isOpaque = false
        detailsPanel.isOpaque = true
        detailsPanel.background = BACKGROUND_COLOR
        detailsPanel.border = JBUI.Borders.empty(10)
        detailsPanel.add(detailsList, BorderLayout.NORTH)
        detailsPanel.add(missingLabel, BorderLayout.SOUTH)

        val splitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT, viewerContainer, detailsPanel)
        splitPane.resizeWeight = 0.72
        splitPane.isOneTouchExpandable = false
        splitPane.isOpaque = false
        splitPane.dividerSize = JBUI.scale(8)
        splitPane.border = null
        splitPane.ui = object : BasicSplitPaneUI() {
            override fun createDefaultDivider(): BasicSplitPaneDivider {
                return object : BasicSplitPaneDivider(this) {
                    override fun paint(g: Graphics) {
                        g.color = BORDER_COLOR
                        g.fillRect(0, 0, width, height + 10)
                    }
                }
            }
        }
        add(splitPane, BorderLayout.CENTER)

        previewPanel.isOpaque = false

        updateStatus()
    }

    fun setCatalog(catalog: AssetCatalog?) {
        this.catalog = catalog
        this.itemPreviewProvider = catalog?.let { RecipeItemPreviewProvider(it) }
        this.iconProvider = catalog?.let { RecipeIconProvider(it) }
        updateStatus()
        render()
    }

    fun updateRecipe(document: RecipeDocument?) {
        this.document = document
        render()
    }

    private fun updateStatus() {
        statusLabel.text = if (catalog == null) {
            "Indexing assets..."
        } else {
            "Open a recipe JSON file to preview."
        }
    }

    private fun render() {
        previewPanel.removeAll()
        detailsList.removeAll()
        val catalog = this.catalog
        val doc = document
        if (catalog == null || doc == null) {
            previewPanel.add(statusLabel, BorderLayout.CENTER)
            typeLabel.text = ""
            missingLabel.text = ""
            refresh()
            return
        }
        val itemPreviewProvider = itemPreviewProvider ?: RecipeItemPreviewProvider(catalog)
        val iconProvider = iconProvider ?: RecipeIconProvider(catalog)
        val layout = doc.layout
        val typeField = layout.extraFields.firstOrNull { it.key == "type" }
        typeLabel.text = typeField?.let { formatExtraField(it) } ?: ""
        val layoutPanel = buildLayoutPanel(layout, catalog, itemPreviewProvider, iconProvider)
        previewPanel.add(layoutPanel, BorderLayout.CENTER)

        val missingInfo = computeMissing(layout, catalog)
        missingLabel.text = missingInfo
        missingLabel.foreground = if (missingInfo.isNotEmpty()) JBColor.RED else null

        val extraFields = layout.extraFields.filterNot { it.key == "type" }
        extraFields.forEachIndexed { index, field ->
            detailsList.add(JBLabel(formatExtraField(field)).apply { foreground = JBColor.GRAY })
            if (index < extraFields.lastIndex) {
                detailsList.add(Box.createVerticalStrut(4))
            }
        }
        refresh()
    }

    private fun buildLayoutPanel(
        layout: RecipeLayout,
        catalog: AssetCatalog,
        itemPreviewProvider: RecipeItemPreviewProvider,
        iconProvider: RecipeIconProvider
    ): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.isOpaque = false
        panel.add(buildSlotsPanel(layout, catalog, itemPreviewProvider, iconProvider))
        if (layout.fluidSlots.isNotEmpty()) {
            panel.add(Box.createVerticalStrut(10))
            panel.add(buildFluidPanel(layout, catalog, iconProvider))
        }
        return panel
    }

    private fun buildSlotsPanel(
        layout: RecipeLayout,
        catalog: AssetCatalog,
        itemPreviewProvider: RecipeItemPreviewProvider,
        iconProvider: RecipeIconProvider
    ): JComponent {
        val panel = JPanel(GridBagLayout())
        panel.isOpaque = false
        val gbc = GridBagConstraints().apply {
            insets = Insets(4, 4, 4, 4)
        }
        val inputPanel = createInputGrid(layout, catalog, itemPreviewProvider, iconProvider)
        gbc.gridx = 0
        gbc.gridy = 0
        panel.add(inputPanel, gbc)

        val arrow = JBLabel("->")
        gbc.gridx = 1
        panel.add(arrow, gbc)

        val outputSlot = layout.outputSlot?.let {
            createSlotComponent(it, catalog, itemPreviewProvider, iconProvider, true)
        } ?: RecipeSlotComponent()
        gbc.gridx = 2
        panel.add(outputSlot, gbc)
        return panel
    }

    private fun buildFluidPanel(
        layout: RecipeLayout,
        catalog: AssetCatalog,
        iconProvider: RecipeIconProvider
    ): JComponent {
        val panel = JPanel(GridBagLayout())
        panel.isOpaque = false
        val gbc = GridBagConstraints().apply {
            anchor = GridBagConstraints.WEST
            insets = Insets(2, 2, 2, 8)
        }
        layout.fluidSlots.forEachIndexed { index, slot ->
            gbc.gridx = 0
            gbc.gridy = index
            panel.add(JBLabel(slot.binding.field), gbc)
            gbc.gridx = 1
            val component = createFluidSlotComponent(slot, catalog, iconProvider)
            panel.add(component, gbc)
        }
        return panel
    }

    private fun createInputGrid(
        layout: RecipeLayout,
        catalog: AssetCatalog,
        itemPreviewProvider: RecipeItemPreviewProvider,
        iconProvider: RecipeIconProvider
    ): JComponent {
        val grid = JPanel(GridBagLayout())
        grid.isOpaque = false
        val slotMap = layout.inputSlots.associateBy { it.row * layout.gridWidth + it.col }
        for (row in 0 until layout.gridHeight) {
            for (col in 0 until layout.gridWidth) {
                val idx = row * layout.gridWidth + col
                val positioned = slotMap[idx]
                val slot = positioned?.view
                val component = if (slot != null) {
                    createSlotComponent(slot, catalog, itemPreviewProvider, iconProvider, false)
                } else {
                    RecipeSlotComponent()
                }
                val gbc = GridBagConstraints().apply {
                    gridx = col
                    gridy = row
                    insets = Insets(2, 2, 2, 2)
                }
                grid.add(component, gbc)
            }
        }
        return grid
    }

    private fun createSlotComponent(
        slot: RecipeSlotView,
        catalog: AssetCatalog,
        itemPreviewProvider: RecipeItemPreviewProvider,
        iconProvider: RecipeIconProvider,
        isOutput: Boolean
    ): RecipeSlotComponent {
        val component = RecipeSlotComponent()
        val display = buildDisplay(slot, catalog, itemPreviewProvider, isOutput) { icon ->
            component.icon = icon
        }
        component.icon = display.icon
        component.count = display.count
        component.missing = display.missing
        component.toolTipText = display.tooltip
        if (!isOutput || slot.output != null) {
            component.addMouseListener(
                SlotClickHandler(project, slot.binding, catalog, itemPreviewProvider, isOutput)
            )
        }
        return component
    }

    private fun createFluidSlotComponent(
        slot: FluidSlotView,
        catalog: AssetCatalog,
        iconProvider: RecipeIconProvider
    ): RecipeSlotComponent {
        val component = RecipeSlotComponent()
        val display = buildFluidDisplay(slot, catalog, iconProvider) { icon ->
            component.icon = icon
        }
        component.icon = display.icon
        component.count = display.count
        component.missing = display.missing
        component.toolTipText = display.tooltip
        component.addMouseListener(
            FluidSlotClickHandler(project, slot.binding, catalog, iconProvider)
        )
        return component
    }

    private fun buildDisplay(
        slot: RecipeSlotView,
        catalog: AssetCatalog,
        itemPreviewProvider: RecipeItemPreviewProvider,
        isOutput: Boolean,
        onIconReady: (javax.swing.Icon?) -> Unit
    ): SlotDisplay {
        return if (isOutput && slot.output != null) {
            val output = slot.output
            when (output) {
                is RecipeParser.RecipeOutput.Item -> {
                    val id = output.id
                    val icon = itemPreviewProvider.getIcon(id) { loaded -> onIconReady(loaded) }
                    val name = catalog.resolveDisplayName(id) ?: id
                    val missing = !catalog.hasItem(id)
                    SlotDisplay(
                        icon = icon,
                        count = output.count,
                        missing = missing,
                        tooltip = toTooltip(listOf(name, id))
                    )
                }
                is RecipeParser.RecipeOutput.Fluid -> {
                    val fluidId = output.id
                    val iconId = resolveFluidIconId(fluidId, catalog)
                    val icon = iconId?.let { iconProvider!!.getIcon(it) { loaded -> onIconReady(loaded) } }
                        ?: AllIcons.FileTypes.Unknown
                    val name = catalog.resolveDisplayName(fluidId) ?: fluidId
                    val missing = !catalog.allFluidIds().contains(fluidId.lowercase())
                    SlotDisplay(
                        icon = icon,
                        count = output.amount,
                        missing = missing,
                        tooltip = toTooltip(listOf(name, fluidId, "Amount: ${output.amount}"))
                    )
                }
                null -> SlotDisplay(null, null, false, "Empty slot")
            }
        } else {
            val ingredient = slot.ingredient
            if (ingredient == null || ingredient.choices.isEmpty()) {
                return SlotDisplay(null, null, false, "Empty slot")
            }
            val missing = ingredient.choices.none { choice ->
                when {
                    choice.itemId != null -> catalog.hasItem(choice.itemId)
                    choice.tagId != null -> catalog.hasTag(choice.tagId)
                    else -> false
                }
            }
            val displayChoice = ingredient.choices.firstOrNull { choice ->
                when {
                    choice.itemId != null -> catalog.hasItem(choice.itemId)
                    choice.tagId != null -> catalog.hasTag(choice.tagId)
                    else -> false
                }
            } ?: ingredient.choices.first()
            val icon = when {
                displayChoice.itemId != null ->
                    itemPreviewProvider.getIcon(displayChoice.itemId) { loaded -> onIconReady(loaded) }
                displayChoice.tagId != null -> AllIcons.Nodes.Tag
                else -> null
            }
            val tooltip = buildIngredientTooltip(ingredient, catalog, missing)
            SlotDisplay(icon = icon, count = null, missing = missing, tooltip = tooltip)
        }
    }

    private fun buildFluidDisplay(
        slot: FluidSlotView,
        catalog: AssetCatalog,
        iconProvider: RecipeIconProvider,
        onIconReady: (javax.swing.Icon?) -> Unit
    ): SlotDisplay {
        val fluid = slot.fluid ?: return SlotDisplay(null, null, false, "Empty fluid slot")
        val iconId = resolveFluidIconId(fluid.id, catalog)
        val icon = iconId?.let { id ->
            iconProvider.getIcon(id) { loaded -> onIconReady(loaded) }
        } ?: AllIcons.FileTypes.Unknown
        val name = catalog.resolveDisplayName(fluid.id) ?: fluid.id
        val missing = !catalog.allFluidIds().contains(fluid.id.lowercase(Locale.US))
        val amountLabel = if (fluid.amount > 0) "Amount: ${fluid.amount}" else "Amount: ?"
        return SlotDisplay(
            icon = icon,
            count = if (fluid.amount > 0) fluid.amount else null,
            missing = missing,
            tooltip = toTooltip(listOf(name, fluid.id, amountLabel))
        )
    }

    private fun resolveFluidIconId(id: String, catalog: AssetCatalog): String? {
        return when {
            catalog.hasItem(id) -> id
            catalog.hasItem("${id}_bucket") -> "${id}_bucket"
            else -> null
        }
    }

    private fun buildIngredientTooltip(
        ingredient: IngredientSlot,
        catalog: AssetCatalog,
        missing: Boolean
    ): String {
        val lines = ArrayList<String>()
        if (missing) {
            lines.add("Missing ingredient")
        }
        ingredient.choices.forEach { choice ->
            when {
                choice.itemId != null -> {
                    val name = catalog.resolveDisplayName(choice.itemId) ?: choice.itemId
                    lines.add("$name (${choice.itemId})")
                }
                choice.tagId != null -> {
                    lines.add("#${choice.tagId}")
                }
            }
        }
        return toTooltip(lines)
    }

    private fun formatExtraField(field: RecipeExtraField): String {
        val value = field.value
        val renderedValue = if (value.matches(NUMBER_REGEX)) value else "\"$value\""
        return "\"${field.key}\": $renderedValue"
    }

    private fun toTooltip(lines: List<String>): String {
        return "<html>${lines.joinToString("<br>")}</html>"
    }

    private fun computeMissing(layout: RecipeLayout, catalog: AssetCatalog): String {
        val missingItems = LinkedHashSet<String>()
        val missingTags = LinkedHashSet<String>()
        val missingFluids = LinkedHashSet<String>()
        val fluidIds = catalog.allFluidIds()
        layout.inputSlots.forEach { positioned ->
            val ingredient = positioned.view.ingredient ?: return@forEach
            ingredient.choices.forEach { choice ->
                when {
                    choice.itemId != null && !catalog.hasItem(choice.itemId) -> missingItems.add(choice.itemId)
                    choice.tagId != null && !catalog.hasTag(choice.tagId) -> missingTags.add(choice.tagId)
                }
            }
        }
        layout.fluidSlots.forEach { slot ->
            val fluid = slot.fluid ?: return@forEach
            if (!fluidIds.contains(fluid.id.lowercase(Locale.US))) {
                missingFluids.add(fluid.id)
            }
        }
        val parts = ArrayList<String>()
        if (missingItems.isNotEmpty()) {
            parts.add("Missing items: ${missingItems.joinToString(", ")}")
        }
        if (missingTags.isNotEmpty()) {
            parts.add("Missing tags: ${missingTags.joinToString(", ")}")
        }
        if (missingFluids.isNotEmpty()) {
            parts.add("Missing fluids: ${missingFluids.joinToString(", ")}")
        }
        return parts.joinToString(" | ")
    }

    private fun refresh() {
        SwingUtilities.invokeLater {
            revalidate()
            repaint()
        }
    }

    private data class SlotDisplay(
        val icon: javax.swing.Icon?,
        val count: Int?,
        val missing: Boolean,
        val tooltip: String
    )

    companion object {
        private val SELECTED_BACKGROUND_COLOR: Color =
            JBColor.namedColor(
                "ActionButton.hoverBorderColor",
                JBColor(0xc5dffc, 0x113a5c)
            )

        private val BACKGROUND_COLOR: Color =
            JBColor.namedColor(
                "Panel.background",
                JBColor(0x2B2B2B, 0x2B2B2B)
            )

        private val BORDER_COLOR: Color =
            JBColor.namedColor(
                "Borders.color",
                JBColor(0x26282b, 0x26282b)
            )

        private val NUMBER_REGEX = Regex("^-?\\d+(\\.\\d+)?$")
    }
}
